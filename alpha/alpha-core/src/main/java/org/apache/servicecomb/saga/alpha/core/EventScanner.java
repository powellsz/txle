/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.saga.alpha.core;

import org.apache.servicecomb.saga.alpha.core.kafka.IKafkaMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.servicecomb.saga.alpha.core.TaskStatus.NEW;
import static org.apache.servicecomb.saga.common.EventType.*;

public class EventScanner implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final byte[] EMPTY_PAYLOAD = new byte[0];

  private final ScheduledExecutorService scheduler;
  private final TxEventRepository eventRepository;
  private final CommandRepository commandRepository;
  private final TxTimeoutRepository timeoutRepository;
  private final OmegaCallback omegaCallback;
  private IKafkaMessageProducer kafkaMessageProducer;
  private UtxMetrics utxMetrics;

  private final int eventPollingInterval;

  private long nextEndedEventId;
  private long nextCompensatedEventId;

  public EventScanner(ScheduledExecutorService scheduler,
      TxEventRepository eventRepository,
      CommandRepository commandRepository,
      TxTimeoutRepository timeoutRepository,
      OmegaCallback omegaCallback,
      IKafkaMessageProducer kafkaMessageProducer,
      UtxMetrics utxMetrics,
      int eventPollingInterval) {
    this.scheduler = scheduler;
    this.eventRepository = eventRepository;
    this.commandRepository = commandRepository;
    this.timeoutRepository = timeoutRepository;
    this.omegaCallback = omegaCallback;
    this.kafkaMessageProducer = kafkaMessageProducer;
    this.utxMetrics = utxMetrics;
    this.eventPollingInterval = eventPollingInterval;
  }

  @Override
  public void run() {
    pollEvents();
  }

  private void pollEvents() {
    scheduler.scheduleWithFixedDelay(
				() -> {
					try {
                        updateTimeoutStatus();
						findTimeoutEvents();
						abortTimeoutEvents();
						saveUncompensatedEventsToCommands();
						compensate();
						updateCompensatedCommands();
						deleteDuplicateSagaEndedEvents();
						updateTransactionStatus();
					} catch (Exception e) {
						// to avoid stopping this scheduler in case of exception By Gannalyo
						LOG.error("[Action Saga Error] EventScanner.pollEvents.scheduleWithFixedDelay run abortively.", e);
					}
				},
        0,
        eventPollingInterval,
        MILLISECONDS);
  }

  private void findTimeoutEvents() {
    eventRepository.findTimeoutEvents()
        .forEach(event -> {
          CurrentThreadContext.put(event.globalTxId(), event);
          LOG.info("Found timeout event {}", event);
          timeoutRepository.save(txTimeoutOf(event));
        });
  }

  private void updateTimeoutStatus() {
    timeoutRepository.markTimeoutAsDone();
  }

  private void saveUncompensatedEventsToCommands() {
    eventRepository.findFirstUncompensatedEventByIdGreaterThan(nextEndedEventId, TxEndedEvent.name())
        .forEach(event -> {
          CurrentThreadContext.put(event.globalTxId(), event);
          LOG.info("Found uncompensated event {}", event);
          nextEndedEventId = event.id();
          commandRepository.saveCompensationCommands(event.globalTxId());
        });
  }

  private void updateCompensatedCommands() {
    // The 'findFirstCompensatedEventByIdGreaterThan' interface did not think about the 'SagaEndedEvent' type so that would do too many thing those were wasted.
      List<TxEvent> unCompensableEventList = eventRepository.findSequentialCompensableEventOfUnended();
//        eventRepository.findFirstCompensatedEventByIdGreaterThan(nextCompensatedEventId)
      unCompensableEventList.forEach(event -> {
          CurrentThreadContext.put(event.globalTxId(), event);
          LOG.info("Found compensated event {}", event);
          nextCompensatedEventId = event.id();
          updateCompensationStatus(event);
        });
  }

  private void deleteDuplicateSagaEndedEvents() {
    try {
      List<Long> maxSurrogateIdList = eventRepository.getMaxSurrogateIdGroupByGlobalTxIdByType(SagaEndedEvent.name());
      if (maxSurrogateIdList != null && !maxSurrogateIdList.isEmpty()) {
        eventRepository.deleteDuplicateEventsByTypeAndSurrogateIds(SagaEndedEvent.name(), maxSurrogateIdList);
      }
//      eventRepository.deleteDuplicateEvents(SagaEndedEvent.name());
    } catch (Exception e) {
      LOG.warn("Failed to delete duplicate event", e);
    }
  }

  private void updateCompensationStatus(TxEvent event) {
    commandRepository.markCommandAsDone(event.globalTxId(), event.localTxId());
    LOG.info("Transaction with globalTxId {} and localTxId {} was compensated",
        event.globalTxId(),
        event.localTxId());

    CurrentThreadContext.put(event.globalTxId(), event);

    markSagaEnded(event);
  }

  private void abortTimeoutEvents() {
    timeoutRepository.findFirstTimeout().forEach(timeout -> {
      LOG.info("Found timeout event {} to abort", timeout);

      TxEvent event = toTxAbortedEvent(timeout);
      CurrentThreadContext.put(event.globalTxId(), event);
      eventRepository.save(event);

      boolean isRetried = eventRepository.checkIsRetiredEvent(event.type());
      utxMetrics.countTxNumber(event, true, isRetried);

      if (timeout.type().equals(TxStartedEvent.name())) {
        eventRepository.findTxStartedEvent(timeout.globalTxId(), timeout.localTxId())
            .ifPresent(omegaCallback::compensate);
      }
    });
  }

  private void updateTransactionStatus() {
    eventRepository.findFirstAbortedGlobalTransaction().ifPresent(this::markGlobalTxEndWithEvents);
  }

  private void markSagaEnded(TxEvent event) {
    CurrentThreadContext.put(event.globalTxId(), event);
    if (commandRepository.findUncompletedCommands(event.globalTxId()).isEmpty()) {
      markGlobalTxEndWithEvent(event);
    }
  }

  private void markGlobalTxEndWithEvent(TxEvent event) {
    CurrentThreadContext.put(event.globalTxId(), event);
    eventRepository.save(toSagaEndedEvent(event));
    // To send message to Kafka.
    kafkaMessageProducer.send(event);
    LOG.info("Marked end of transaction with globalTxId {}", event.globalTxId());
  }

  private void markGlobalTxEndWithEvents(List<TxEvent> events) {
    events.forEach(this::markGlobalTxEndWithEvent);
  }

  private TxEvent toTxAbortedEvent(TxTimeout timeout) {
    return new TxEvent(
        timeout.serviceName(),
        timeout.instanceId(),
        timeout.globalTxId(),
        timeout.localTxId(),
        timeout.parentTxId(),
        TxAbortedEvent.name(),
        "",
        timeout.category(),
        ("Transaction timeout").getBytes());
  }

  private TxEvent toSagaEndedEvent(TxEvent event) {
    return new TxEvent(
        event.serviceName(),
        event.instanceId(),
        event.globalTxId(),
        event.globalTxId(),
        null,
        SagaEndedEvent.name(),
        "",
        event.category(),
        EMPTY_PAYLOAD);
  }

  private void compensate() {
    commandRepository.findFirstCommandToCompensate()
        .forEach(command -> {
          LOG.info("Compensating transaction with globalTxId {} and localTxId {}",
              command.globalTxId(),
              command.localTxId());

          omegaCallback.compensate(txStartedEventOf(command));
        });
  }

  private TxEvent txStartedEventOf(Command command) {
    return new TxEvent(
        command.serviceName(),
        command.instanceId(),
        command.globalTxId(),
        command.localTxId(),
        command.parentTxId(),
        TxStartedEvent.name(),
        command.compensationMethod(),
        command.category(),
        command.payloads()
    );
  }

  private TxTimeout txTimeoutOf(TxEvent event) {
    return new TxTimeout(
        event.id(),
        event.serviceName(),
        event.instanceId(),
        event.globalTxId(),
        event.localTxId(),
        event.parentTxId(),
        event.type(),
        event.expiryTime(),
        NEW.name(),
        event.category()
    );
  }
}
