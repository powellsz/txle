/*
 * Copyright (c) 2018-2019 ActionTech.
 * based on code by ServiceComb Pack CopyrightHolder Copyright (C) 2018,
 * License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package org.apache.servicecomb.saga.alpha.core;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.health.model.Check;
import com.ecwid.consul.v1.session.model.NewSession;
import org.apache.servicecomb.saga.alpha.core.cache.ITxleCache;
import org.apache.servicecomb.saga.alpha.core.kafka.IKafkaMessageProducer;
import org.apache.servicecomb.saga.common.TxleConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.servicecomb.saga.alpha.core.TaskStatus.NEW;
import static org.apache.servicecomb.saga.common.EventType.*;
import static org.apache.servicecomb.saga.common.TxleConstants.CONSUL_LEADER_KEY;
import static org.apache.servicecomb.saga.common.TxleConstants.CONSUL_LEADER_KEY_VALUE;

public class EventScanner implements Runnable {
  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final byte[] emptyPayload = new byte[0];

  private final ScheduledExecutorService scheduler;
  private final TxEventRepository eventRepository;
  private final CommandRepository commandRepository;
  private final TxTimeoutRepository timeoutRepository;
  private final OmegaCallback omegaCallback;
  private IKafkaMessageProducer kafkaMessageProducer;

  private final long eventPollingInterval;

  private long nextEndedEventId;

  // 不能简单地获取已完成的最大id，因为会存在部分id小的还未完成的场景，如id：1、2、3，可能3完成了，但2也许还未完成
  private static volatile long unendedMinEventId;
  // 需要查询未完成最小id的次数，如果值为0则不需要查询，初始值为1，即系统启动后先查询最小id
  public static final AtomicInteger UNENDED_MIN_EVENT_ID_SELECT_COUNT = new AtomicInteger(1);

  public static final String SCANNER_SQL = " /**scanner_sql**/";

  private final ConsulClient consulClient;
  private final String serverName;
  private int serverPort;
  private final String consulInstanceId;
  private String consulSessionId;
  private boolean isMaster;
  private ITxleCache txleCache;

  public EventScanner(ScheduledExecutorService scheduler,
      TxEventRepository eventRepository,
      CommandRepository commandRepository,
      TxTimeoutRepository timeoutRepository,
      OmegaCallback omegaCallback,
      IKafkaMessageProducer kafkaMessageProducer,
      int eventPollingInterval,
      ConsulClient consulClient,
      ITxleCache txleCache,
      Object... params) {
    this.scheduler = scheduler;
    this.eventRepository = eventRepository;
    this.commandRepository = commandRepository;
    this.timeoutRepository = timeoutRepository;
    this.omegaCallback = omegaCallback;
    this.kafkaMessageProducer = kafkaMessageProducer;
    this.eventPollingInterval = eventPollingInterval;
    this.consulClient = consulClient;
    this.txleCache = txleCache;
    this.serverName = params[0] + "";
    try {
      this.serverPort = Integer.parseInt(params[1] + "");
    } catch (Exception e) {
      this.serverPort = 8090;
    }
    this.consulInstanceId = params[2] + "";
  }

  @Override
  public void run() {
    pollEvents();
  }

  private void pollEvents() {
    registerConsulSession();

    /**
     * 补偿业务逻辑大换血：
     *    主要需要补偿的分为两大类，超时导致的异常和其它情况导致的异常(重试属于其它情况)；
     *    原逻辑：将二者混在一起，带来了各种繁杂困扰，出现问题很难定位；
     *    现逻辑：将二者分开；TM主要指的是TxConsistentService#handleSupportTxPause(TxEvent)；
     *            其它异常情况：【客户端】负责异常检测，检测到后，由TM保存Aborted事件，再对当前全局事务下的相关子事务记录补偿并及时下发补偿命令；
     *                           排除发生异常的子事务，因为发生异常的子事务已由本地事务回滚；
     *            超时异常情况：【定时器】负责超时检测，检测到后，由TM保存Aborted事件，再对当前全局事务下的相关子事务记录补偿并及时下发补偿命令；
     *                           需对所有子事务进行补偿；
     *            TM超时检测：TM中也会对即将结束的全局事务检测是否超时，因为定时扫描器中检测超时会存在一定误差，如定时器中任务需3s完成，但某事务超时设置的是2秒，此时还未等对该事物进行检测，该事务就已经结束了
     */
    scheduler.scheduleWithFixedDelay(
            () -> {
              try {
                if (isMaster()) {
                  // 未防止出现部分事务在未检测超时前就已经结束的情况，此处将超时检测单开一个线程，否则其它方法如果执行超过了事务的超时时间，那么下次超时检测将在事务之后检测了，此时事务已经正常结束了
                  updateTimeoutStatus();
                  findTimeoutEvents();
                  abortTimeoutEvents();
                }
              } catch (Exception e) {
                // to avoid stopping this scheduler in case of exception By Gannalyo
                log.error(TxleConstants.LOG_ERROR_PREFIX + "Failed to detect timeout in scheduler.", e);
              }
            },
            0,
            eventPollingInterval,
            MILLISECONDS);

    scheduler.scheduleWithFixedDelay(
				() -> {
					try {
                      if (isMaster()) {
                        compensate();
                        updateCompensatedCommands();
                        getMinUnendedEventId();
                      }
					} catch (Exception e) {
						// to avoid stopping this scheduler in case of exception By Gannalyo
						log.error(TxleConstants.LOG_ERROR_PREFIX + "Failed to execute method 'compensate' in scheduler.", e);
					}
				},
        0,
        eventPollingInterval * 2,
        MILLISECONDS);
  }

  // Once current server is elected as a leader, then it's always leader until dies.
  private boolean isMaster() {
    if (!isMaster) {
      isMaster = consulClient != null && consulClient.setKVValue(CONSUL_LEADER_KEY + "?acquire=" + consulSessionId, CONSUL_LEADER_KEY_VALUE).getValue();
      if (isMaster) {
        log.error("Server " + serverName + "-" + serverPort + " is leader.");
      }
    }
    return isMaster;
  }

  private void updateTimeoutStatus() {
    List<Long> timeoutIdList = timeoutRepository.selectTimeoutIdList();
    if (timeoutIdList != null && !timeoutIdList.isEmpty()) {
      timeoutRepository.markTimeoutAsDone(timeoutIdList);
    }
  }

  private void findTimeoutEvents() {
    // 查询未登记过的超时
    // SELECT t.surrogateId FROM TxTimeout t, TxEvent t1 WHERE t1.globalTxId = t.globalTxId AND t1.localTxId = t.localTxId AND t1.type != t.type
    eventRepository.findTimeoutEvents(unendedMinEventId)
        .forEach(event -> {
          CurrentThreadContext.put(event.globalTxId(), event);
          log.info("Found timeout event {}", event);
          try {
            if (timeoutRepository.findTxTimeoutByEventId(event.id()) < 1) {
              timeoutRepository.save(txTimeoutOf(event));
            }
          } catch (Exception e) {
            log.error("Failed to save timeout {} in method 'EventScanner.findTimeoutEvents()'.", event, e);
          }
        });
  }

  private void abortTimeoutEvents() {
    // 查找超时且状态为 NEW 的超时记录
    timeoutRepository.findFirstTimeout().forEach(timeout -> {
      log.info("Found timeout event {} to abort", timeout);

      TxEvent abortedEvent = toTxAbortedEvent(timeout);
      CurrentThreadContext.put(abortedEvent.globalTxId(), abortedEvent);
      if (!eventRepository.checkIsExistsEventType(abortedEvent.globalTxId(), abortedEvent.localTxId(), abortedEvent.type())) {
        // 查找到超时记录后，记录相应的(超时)终止状态
        eventRepository.save(abortedEvent);
        // 保存超时情况下的待补偿命令，当前超时全局事务下的所有应该补偿的子事件的待补偿命令 By Gannalyo
        commandRepository.saveWillCompensateCommandsForTimeout(abortedEvent.globalTxId());
        txleCache.putDistributedTxAbortStatusCache(abortedEvent.globalTxId(), true, 2);
      }
    });
  }

  private void compensate() {
    long a = System.currentTimeMillis();
    List<Command> commandList = commandRepository.findFirstCommandToCompensate();
    if (commandList == null || commandList.isEmpty()) {
      return;
    }
    log.info("Method 'find compensated command' took {} milliseconds, size = {}.", System.currentTimeMillis() - a, commandList.size());
    commandList.forEach(command -> {
      log.error("Compensating transaction with globalTxId {} and localTxId {}",
              command.globalTxId(),
              command.localTxId());

      // 该方法会最终调用客户端的org.apache.servicecomb.saga.omega.transaction.CompensationMessageHandler.onReceive方法进行补偿和请求存储补偿事件
      omegaCallback.compensate(txStartedEventOf(command));
    });
  }

  private void updateCompensatedCommands() {
    // The 'findFirstCompensatedEventByIdGreaterThan' interface did not think about the 'SagaEndedEvent' type so that would do too many thing those were wasted.
    long a = System.currentTimeMillis();
    List<TxEvent> compensatedUnendEventList = eventRepository.findSequentialCompensableEventOfUnended(unendedMinEventId);
    if (compensatedUnendEventList == null || compensatedUnendEventList.isEmpty()) {
      return;
    }
    log.info("Method 'find compensated(unend) event' took {} milliseconds, size = {}.", System.currentTimeMillis() - a, compensatedUnendEventList.size());
    compensatedUnendEventList.forEach(event -> {
      CurrentThreadContext.put(event.globalTxId(), event);
      log.info("Found compensated event {}", event);
      updateCompensationStatus(event);
    });
  }

  private void updateCompensationStatus(TxEvent event) {
    commandRepository.markCommandAsDone(event.globalTxId(), event.localTxId());
    log.info("Transaction with globalTxId {} and localTxId {} was compensated",
        event.globalTxId(),
        event.localTxId());

    CurrentThreadContext.put(event.globalTxId(), event);

//    markSagaEnded(event);
  }

  private void markSagaEnded(TxEvent event) {
    CurrentThreadContext.put(event.globalTxId(), event);
    // 如果没有未补偿的命令，则结束当前全局事务
    // TODO 检查当前全局事务对应的事件是否还有需要补偿的子事务???
    if (commandRepository.findUncompletedCommands(event.globalTxId()).isEmpty()) {
      markGlobalTxEndWithEvent(event);
    }
  }

  private void markGlobalTxEndWithEvent(TxEvent event) {
    try {
      CurrentThreadContext.put(event.globalTxId(), event);
      if (!eventRepository.checkIsExistsEventType(event.globalTxId(), event.globalTxId(), SagaEndedEvent.name())) {
        // The variable 'event' is only a sub-transaction, 'SagaEndedEvent' should be converted from 'SagaStartedEvent'.
        TxEvent startEvent = eventRepository.selectEventByGlobalTxIdType(event.globalTxId(), SagaStartedEvent.name());
        TxEvent sagaEndedEvent = toSagaEndedEvent(startEvent);
        eventRepository.save(sagaEndedEvent);
        // To send message to Kafka.
        kafkaMessageProducer.send(sagaEndedEvent);
        log.info("Marked end of transaction with globalTxId {}", event.globalTxId());
      }
    } catch (Exception e) {
      log.error("Failed to save event globalTxId {} localTxId {} type {}", event.globalTxId(), event.localTxId(), event.type(), e);
    }
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
        "Transaction timeout".getBytes());
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
            emptyPayload);
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

  private void getMinUnendedEventId() {
    try {
      if (UNENDED_MIN_EVENT_ID_SELECT_COUNT.get() == 0) {
        return;
      }
      UNENDED_MIN_EVENT_ID_SELECT_COUNT.decrementAndGet();
      // 上面的方法，既能保证准确性，又不节省性能开销，但对性能的开销会越来越大，且也不太适合ServerCluster场景
      // 不保证是未完成最小的，但保证比最小未完成的还小。即：id：1、2、3...10，如果2未完成，此时定时器执行该方法，则返回2，之后2立即结束，3-7结束，8-10执行，此时再次检测，min id仍是2，而不是7/8，相当于多查询了几条数据，但保证足够的准确性。
      long currentMinid = eventRepository.selectMinUnendedTxEventId(unendedMinEventId);
      if (unendedMinEventId < currentMinid) {
        unendedMinEventId = currentMinid;
      } else if (currentMinid == 0 || currentMinid == unendedMinEventId) {
        UNENDED_MIN_EVENT_ID_SELECT_COUNT.set(0);
      }
    } catch (Exception e) {
      log.error(TxleConstants.LOG_ERROR_PREFIX + "Failed to get the min id of global transaction which is not ended.", e);
    }
  }

  public static long getUnendedMinEventId() {
    return unendedMinEventId;
  }

  /**
   * Multiple txle apps register the same key 'CONSUL_LEADER_KEY', it would be leader in case of getting 'true'.
   * The Session, Checks and Services have to be destroyed/deregistered before shutting down JVM, so that the lock of leader key could be released.
   * @return String session id
   */
  private String registerConsulSession() {
    if (consulClient == null) {
      return null;
    }
    String serverHost = "127.0.0.1";
    try {
      destroyConsulCriticalServices();
      // TODO 判断当前leader是否可达，如果不可达则剔除其session，重新竞选leader
      // To create a key for leader election no matter if it is exists.
      consulClient.setKVValue(CONSUL_LEADER_KEY, CONSUL_LEADER_KEY_VALUE);
      NewSession session = new NewSession();
      serverHost = InetAddress.getLocalHost().getHostAddress();
      session.setName("session-" + serverName + "-" + serverHost + "-" + serverPort + "-" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
      consulSessionId = consulClient.sessionCreate(session, null).getValue();
    } catch (Exception e) {
      log.error("Failed to register Consul Session, serverName [{}], serverHost [{}], serverPort [{}].", serverName, serverHost, serverPort, e);
    } finally {
      try {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          destroyConsulCriticalServices();
        }));
      } catch (Exception e) {
        log.error("Failed to add ShutdownHook for destroying/deregistering Consul Session, Checks and Services, serverName [{}], serverPort [{}].", serverName, serverPort, e);
      }
    }
    return consulSessionId;
  }

  private void destroyConsulCriticalServices() {
    // To deregister service could not destroy session so that current service still held the lock for leader's key.
    // So to destroy session was necessary as well.
    if (consulSessionId != null) {
      consulClient.sessionDestroy(consulSessionId, null);
    }
    // consulClient.agentServiceDeregister(consulInstanceId);
    List<Check> checkList = consulClient.getHealthChecksState(null).getValue();
    if (checkList != null) {
      log.error("checkList size = " + checkList.size());
    }
    checkList.forEach(check -> {
      if (check.getStatus() != Check.CheckStatus.PASSING || check.getServiceId().equals(consulInstanceId)) {
        log.error("Executing method 'destroyConsulCriticalServices', check id = " + check.getCheckId() + ", service id = " + check.getServiceId() + " .");
        consulClient.agentCheckDeregister(check.getCheckId());
        consulClient.agentServiceDeregister(check.getServiceId());
      }
    });
  }

  public static String getConsulSessionId() {
    return getConsulSessionId();
  }
}
