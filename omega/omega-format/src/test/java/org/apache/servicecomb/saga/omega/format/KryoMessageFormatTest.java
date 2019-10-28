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

package org.apache.servicecomb.saga.omega.format;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import org.junit.BeforeClass;
import org.junit.Test;

public class KryoMessageFormatTest extends MessageFormatTestBase {

  @BeforeClass
  public static void setUp() {
    format = new KryoMessageFormat();
  }

  @Test
  public void serializeEmptyClassIntoBytes() {
    byte[] bytes = format.serialize(new Object[]{new EmptyClass()});

    Object[] message = format.deserialize(bytes);

    assertThat(message[0], instanceOf(EmptyClass.class));
  }
}
