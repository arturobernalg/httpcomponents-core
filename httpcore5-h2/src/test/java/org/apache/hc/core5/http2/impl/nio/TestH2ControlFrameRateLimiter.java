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
package org.apache.hc.core5.http2.impl.nio;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestH2ControlFrameRateLimiter {

    @Test
    void testBurstAndRejection() {
        final H2ControlFrameRateLimiter limiter = new H2ControlFrameRateLimiter();
        for (int i = 0; i < H2ControlFrameRateLimiter.CAPACITY; i++) {
            Assertions.assertTrue(limiter.accept(10L));
        }
        Assertions.assertFalse(limiter.accept(10L));
    }

    @Test
    void testRefillAndRecovery() {
        final H2ControlFrameRateLimiter limiter = new H2ControlFrameRateLimiter();
        for (int i = 0; i < H2ControlFrameRateLimiter.CAPACITY; i++) {
            Assertions.assertTrue(limiter.accept(1L));
        }
        Assertions.assertFalse(limiter.accept(1L));
        Assertions.assertTrue(limiter.accept(1L + 1_000_000_000L / H2ControlFrameRateLimiter.REFILL_PER_SECOND));
        Assertions.assertFalse(limiter.accept(1L + 1_000_000_000L / H2ControlFrameRateLimiter.REFILL_PER_SECOND));
        Assertions.assertTrue(limiter.accept(2_000_000_001L));
    }

    @Test
    void testClockRollbackDoesNotRefill() {
        final H2ControlFrameRateLimiter limiter = new H2ControlFrameRateLimiter();
        for (int i = 0; i < H2ControlFrameRateLimiter.CAPACITY; i++) {
            Assertions.assertTrue(limiter.accept(100L));
        }
        Assertions.assertFalse(limiter.accept(99L));
    }

    @Test
    void testFractionalRefill() {
        final H2ControlFrameRateLimiter limiter = new H2ControlFrameRateLimiter();
        for (int i = 0; i < H2ControlFrameRateLimiter.CAPACITY; i++) {
            Assertions.assertTrue(limiter.accept(0L));
        }
        Assertions.assertFalse(limiter.accept(0L));
        Assertions.assertFalse(limiter.accept(3_000_000L));
        Assertions.assertTrue(limiter.accept(8_000_000L));
    }
}
