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
import java.util.concurrent.atomic.AtomicLong;

public class TimeWheel {
    private final Duration tickDuration;
    private final int wheelSize;
    private final Duration interval;
    private final TimeWheelBucket[] wheel;
    private final AtomicLong currentTime;

    public TimeWheel(final Duration tickDuration, final int wheelSize) {
        this.tickDuration = tickDuration;
        this.wheelSize = wheelSize;
        this.interval = tickDuration.multipliedBy(wheelSize);
        this.wheel = new TimeWheelBucket[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            wheel[i] = new TimeWheelBucket();
        }
        this.currentTime = new AtomicLong(System.currentTimeMillis());
    }

    public void add(final TimeWheelTask task, final Duration delay) {
        final long deadline = System.currentTimeMillis() + delay.toMillis();
        task.setDeadline(deadline);
        final int idx = (int) ((deadline - currentTime.get()) / tickDuration.toMillis() % wheelSize);
        wheel[idx].addTask(task);
    }

    public TimeWheelTask pollNextTask() {
        final long oldTime = currentTime.get();
        final long newTime = oldTime + tickDuration.toMillis();
        currentTime.set(newTime);

        final int idx = (int) (oldTime % interval.toMillis() / tickDuration.toMillis());
        final TimeWheelBucket bucket = wheel[idx];
        final TimeWheelTask task = bucket.pollTask();

        if (task != null) {
            final long remainingRounds = (task.getDeadline() - oldTime) / interval.toMillis();
            if (remainingRounds > 0) {
                add(task, Duration.ofMillis(task.getDeadline() - newTime));
                return null;
            }
        }

        return task;
    }

    public Duration getTickDuration() {
        return tickDuration;
    }
}


