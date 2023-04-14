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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A time wheel implementation that allows for scheduling timeouts that can be executed after a
 * specified duration has elapsed.
 *
 * <p>The time wheel operates using a circular array of linked lists, where each linked list
 * represents a "bucket" of timeouts that will expire at a given time. When timeouts are added to
 * the time wheel, they are placed in the appropriate bucket based on their expiration time. When
 * the time wheel advances, each bucket is checked for timeouts that have expired, and if any are
 * found, their associated tasks are executed.</p>
 *
 * <p>The time wheel uses a fixed tick duration to determine the size of the buckets, and
 * a fixed wheel size to determine the number of buckets. When the time wheel advances, it moves its
 * current position forward by one bucket, and executes any timeouts that have expired in that
 * bucket. By default, the tick duration is set to 1 second, and the wheel size is set to 512.</p>
 *
 * <p>This implementation is thread-safe, and can be used to schedule timeouts from multiple
 * threads. When a timeout is added, it is assigned an index that indicates which bucket it should
 * be placed in. The index is computed based on the timeout's expiration time and the current
 * position of the time wheel. When a timeout is removed, its index is set to -1.</p>
 *
 * <p>Timeouts are represented by the {@link TimeWheelTimeout} class, which contains the
 * duration of the timeout, the task to execute when the timeout expires, and the index of the
 * bucket where the timeout is currently located. Timeouts are added to the time wheel using the
 * {@link #add(TimeWheelTimeout)} method, and removed using the {@link #remove(TimeWheelTimeout)}
 * method.</p>
 *
 * <p>When a timeout is executed, its associated task is executed in the context of the time
 * wheel's scheduled executor service. By default, a new single-threaded executor service is created
 * for this purpose, but an existing executor service can also be provided to the constructor.</p>
 *
 * <p>The time wheel can be stopped using the {@link #stop()} method. When the time wheel is
 * stopped, any remaining timeouts are cancelled, and the executor service is shutdown.</p>
 *
 * @since 5.3
 */
public class TimeWheel {

    /**
     * The duration of each tick in the time wheel.
     */
    private final Duration tickDuration;
    /**
     * The number of buckets in the time wheel.
     */
    private final int wheelSize;
    /**
     * The list of linked lists that represent the buckets in the time wheel. Each linked list
     * contains timeouts that will expire at the same time.
     */
    private final List<LinkedList<TimeWheelTimeout>> wheel;
    /**
     * The current time of the time wheel. This is used to determine which bucket should be
     * processed when the time wheel advances.
     */
    private Instant currentTime;
    /**
     * The current index of the time wheel. This is used to determine which bucket should be
     * processed when the time wheel advances.
     */
    private final AtomicInteger currentIndex;
    /**
     * The executor service used to schedule the advancement of the time wheel and the execution of
     * timeout tasks.
     */
    private final ScheduledExecutorService executor;
    /**
     * The handler to be executed when the time wheel is stopped.
     */
    private final AtomicReference<Runnable> stopHandler = new AtomicReference<>(null);

    /**
     * Constructs a time wheel with a tick duration of 1 second and a wheel size of 512.
     */
    public TimeWheel() {
        this(Duration.ofSeconds(1));
    }

    /**
     * Constructs a time wheel with the given tick duration and a wheel size of 512.
     *
     * @param tickDuration the duration of each slot
     */
    public TimeWheel(final Duration tickDuration) {
        this(tickDuration, 512);
    }

    /**
     * Constructs a time wheel with the given tick duration and wheel size.
     *
     * @param tickDuration the duration of each slot
     * @param wheelSize    the number of slots in the wheel
     */
    public TimeWheel(final Duration tickDuration, final int wheelSize) {
        this(tickDuration, wheelSize, null);
    }

    /**
     * Constructs a time wheel with the given tick duration, wheel size, and executor.
     *
     * @param tickDuration the duration of each slot
     * @param wheelSize    the number of slots in the wheel
     * @param executor     the executor for advancing the wheel
     */
    public TimeWheel(final Duration tickDuration, final int wheelSize, final ScheduledExecutorService executor) {
        this.tickDuration = tickDuration;
        this.wheelSize = wheelSize;
        this.wheel = new ArrayList<>(wheelSize);
        for (int i = 0; i < wheelSize; i++) {
            wheel.add(new LinkedList<>());
        }
        this.currentTime = Instant.now();
        this.currentIndex = new AtomicInteger(0);
        this.executor = executor != null ? executor : Executors.newSingleThreadScheduledExecutor();
        this.executor.scheduleAtFixedRate(this::advance, tickDuration.toMillis(), tickDuration.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Adds a new timeout to the time wheel.
     * <p>
     * The timeout will be inserted into the slot representing its end time. If the end time is in
     * the past, the timeout's task will be executed immediately.
     *
     * @param timeout the timeout to add
     * @throws IllegalArgumentException if the timeout is null
     */
    public void add(final TimeWheelTimeout timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("Timeout cannot be null");
        }
        final Instant endTime = timeout.getEndTime();
        if (endTime.isBefore(currentTime.plus(tickDuration))) {
            timeout.setIndex(-1);
        } else {
            final int index = (int) ((Duration.between(currentTime, endTime).toMillis() / tickDuration.toMillis() + currentIndex.get()) % wheelSize);
            timeout.setIndex(index);
            wheel.get(index).add(timeout);
        }
    }

    /**
     * Removes the specified timeout from the time wheel.
     *
     * <p>If the specified timeout is {@code null} or has an index of {@code -1}, this method
     * has no effect. Otherwise, the timeout is removed from the bucket where it is currently
     * located, and its index is set to {@code -1}.</p>
     *
     * @param timeout the timeout to remove, may be {@code null}
     */
    public void remove(final TimeWheelTimeout timeout) {
        if (timeout != null && timeout.getIndex() >= 0) {
            wheel.get(timeout.getIndex()).remove(timeout);
            timeout.setIndex(-1);
        }
    }

    /**
     * Advances the time wheel by one tick, and executes any timeouts that have expired.
     *
     * <p>When this method is called, it first determines how many ticks have elapsed since
     * the last time it was called. For each tick, it removes any timeouts that have expired from
     * the current bucket, and executes their associated tasks. It then advances the current
     * position of the time wheel by one bucket.</p>
     */
    private void advance() {
        final Instant newTime = Instant.now();
        final long elapsedTicks = Duration.between(currentTime, newTime).toMillis() / tickDuration.toMillis();
        for (int i = 0; i < elapsedTicks; i++) {
            final List<TimeWheelTimeout> currentBucket = wheel.get(currentIndex.get());
            final Iterator<TimeWheelTimeout> iterator = currentBucket.iterator();
            while (iterator.hasNext()) {
                final TimeWheelTimeout timeout = iterator.next();
                if (timeout.getEndTime().isBefore(newTime)) {
                    iterator.remove();
                    timeout.getTask().run();
                }
            }
            currentTime = currentTime.plus(tickDuration);
            currentIndex.set((currentIndex.get() + 1) % wheelSize);
        }
    }

    /**
     * Stops the time wheel, cancelling any remaining timeouts and shutting down the executor
     * service.
     */
    public void stop() {
        final Runnable handler = stopHandler.getAndSet(null);
        if (handler != null) {
            handler.run();
        }
        executor.shutdown();
        clearWheel();
    }

    /**
     * Clears all timeouts from the time wheel.
     *
     * <p>This method is called by the {@link #stop()} method to ensure that all timeouts are
     * cancelled when the time wheel is stopped.</p>
     */
    private void clearWheel() {
        for (final List<TimeWheelTimeout> bucket : wheel) {
            bucket.clear();
        }
    }
}