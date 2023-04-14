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
import java.time.Instant;


public class TimeWheelTimeout {


    private final Instant endTime;


    private int index;


    private final Runnable task;


    public TimeWheelTimeout(final Duration duration, final Runnable task) {
        Args.notNull(duration, "Duration");
        Args.notNull(task, "Task");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be negative");
        }
        this.endTime = Instant.now().plus(duration);
        this.index = -1;
        this.task = task;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(final int index) {
        this.index = index;
    }


    public Runnable getTask() {
        return task;
    }
}