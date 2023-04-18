/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.core5.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TimeWheelTest {

    private TimeWheelScheduler timeWheelScheduler;

    @BeforeEach
    void setUp() {
        timeWheelScheduler = new TimeWheelScheduler(Duration.ofMillis(200), 10, 4);
    }

    @AfterEach
    void tearDown() {
        timeWheelScheduler.shutdown();
    }

    @Test
    void testAddAndExecuteTask() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        timeWheelScheduler.schedule(latch::countDown, Duration.ofMillis(1000));

        assertTrue(latch.await(2000, TimeUnit.MILLISECONDS), "Task should be executed within the specified duration");
    }

    @Test
    void testRemoveTask() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        final TimeWheelTask task = new TimeWheelTask(latch::countDown);
        timeWheelScheduler.schedule(task, Duration.ofMillis(300));
        task.cancel();

        assertFalse(latch.await(500, TimeUnit.MILLISECONDS), "Task should not be executed after removal");
    }

    @Test
    void testStopCancelsTasks() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        timeWheelScheduler.schedule(latch::countDown, Duration.ofMillis(300));
        timeWheelScheduler.shutdown();

        assertFalse(latch.await(500, TimeUnit.MILLISECONDS), "Task should not be executed after TimeWheelScheduler is stopped");
    }
}


