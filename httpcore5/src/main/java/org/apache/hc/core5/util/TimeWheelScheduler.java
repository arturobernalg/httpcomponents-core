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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimeWheelScheduler {

    private final TimeWheel timeWheel;
    private final WorkerPool workerPool;
    private final ScheduledExecutorService scheduler;

    public TimeWheelScheduler(final Duration tickDuration, final int wheelSize, final int workerPoolSize) {
        this.timeWheel = new TimeWheel(tickDuration, wheelSize);
        this.workerPool = new WorkerPool(workerPoolSize);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.scheduler.scheduleAtFixedRate(this::tick, tickDuration.toMillis(), tickDuration.toMillis(), TimeUnit.MILLISECONDS);

    }

    public void schedule(final Runnable task, final Duration delay) {
        final TimeWheelTask timeWheelTask = new TimeWheelTask(task);
        timeWheel.add(timeWheelTask, delay);
    }

    private void tick() {
        final TimeWheelTask task = timeWheel.pollNextTask();
        if (task != null) {
            workerPool.submit(task);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        workerPool.shutdown();
    }
}