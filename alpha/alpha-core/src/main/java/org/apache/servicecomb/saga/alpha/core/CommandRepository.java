/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 *  Copyright (c) 2018-2019 ActionTech.
 *  License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package org.apache.servicecomb.saga.alpha.core;

import java.util.List;

public interface CommandRepository {

  void saveCompensationCommands(String globalTxId);

  void saveCommandsForNeedCompensationEvent(String globalTxId, String localTxId);

  void saveWillCompensateCommandsForTimeout(String globalTxId);

  void saveWillCompensateCommandsForException(String globalTxId, String localTxId);

  void saveWillCompensateCommandsWhenGlobalTxAborted(String globalTxId);

  void saveWillCompensateCmdForCurSubTx(String globalTxId, String localTxId);

  void markCommandAsDone(String globalTxId, String localTxId);

  List<Command> findUncompletedCommands(String globalTxId);

  List<Command> findFirstCommandToCompensate();
}
