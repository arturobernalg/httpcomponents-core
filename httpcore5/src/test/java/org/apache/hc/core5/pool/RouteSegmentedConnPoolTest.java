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
package org.apache.hc.core5.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

public class RouteSegmentedConnPoolTest {

    private static <R, C extends ModalCloseable> RouteSegmentedConnPool<R, C> newPool(
            final int defPerRoute, final int maxTotal, final TimeValue ttl, final PoolReusePolicy reuse,
            final DisposalCallback<C> disposal) {
        return new RouteSegmentedConnPool<>(defPerRoute, maxTotal, ttl, reuse, disposal);
    }

    private static RouteSegmentedConnPool<String, FakeConnection> newPool(
            final int defPerRoute, final int maxTotal) {
        return new RouteSegmentedConnPool<>(
                defPerRoute,
                maxTotal,
                TimeValue.NEG_ONE_MILLISECOND,
                PoolReusePolicy.LIFO,
                FakeConnection::close);
    }

    @Test
    void basicLeaseReleaseAndHandoff() throws Exception {
        final DisposalCallback<FakeConnection> disposal = FakeConnection::close;
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(2, 2, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, disposal);

        final PoolEntry<String, FakeConnection> e1 = pool.lease("r1", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        assertNotNull(e1);
        assertEquals("r1", e1.getRoute());
        assertFalse(e1.hasConnection());
        e1.assignConnection(new FakeConnection());
        e1.updateState("A");
        e1.updateExpiry(TimeValue.ofSeconds(30));
        pool.release(e1, true);

        final Future<PoolEntry<String, FakeConnection>> f2 =
                pool.lease("r1", "A", Timeout.ofSeconds(1), null);
        final PoolEntry<String, FakeConnection> e2 = f2.get(1, TimeUnit.SECONDS);
        assertSame(e1, e2, "Should receive same entry via direct hand-off");
        pool.release(e2, true);
        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void perRouteAndTotalLimits() throws Exception {
        final DisposalCallback<FakeConnection> disposal = FakeConnection::close;
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(1, 2, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, disposal);

        final PoolEntry<String, FakeConnection> r1a = pool.lease("r1", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        final PoolEntry<String, FakeConnection> r2a = pool.lease("r2", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);

        final Future<PoolEntry<String, FakeConnection>> blocked = pool.lease("r1", null, Timeout.ofMilliseconds(150), null);
        final ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> blocked.get(400, TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertEquals("Lease timed out", ex.getCause().getMessage());

        r1a.assignConnection(new FakeConnection());
        r1a.updateExpiry(TimeValue.ofSeconds(5));
        pool.release(r1a, true);

        final PoolEntry<String, FakeConnection> r1b =
                pool.lease("r1", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        assertNotNull(r1b);
        pool.release(r2a, false); // drop
        pool.release(r1b, false);
        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void stateCompatibilityNullMatchesAnything() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(1, 1, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, FakeConnection::close);

        final PoolEntry<String, FakeConnection> e = pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        e.assignConnection(new FakeConnection());
        e.updateState("X");
        e.updateExpiry(TimeValue.ofSeconds(30));
        pool.release(e, true);

        // waiter with null state must match
        final PoolEntry<String, FakeConnection> got =
                pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        assertSame(e, got);
        pool.release(got, false);
        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void closeIdleRemovesStaleAvailable() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(2, 2, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, FakeConnection::close);

        final PoolEntry<String, FakeConnection> e = pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        e.assignConnection(new FakeConnection());
        e.updateExpiry(TimeValue.ofSeconds(30));
        pool.release(e, true);

        // sleep to make it idle
        Thread.sleep(120);
        pool.closeIdle(TimeValue.ofMilliseconds(50));

        final PoolStats stats = pool.getStats("r");
        assertEquals(0, stats.getAvailable());
        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void closeExpiredHonorsEntryExpiryOrTtl() throws Exception {
        // TTL = 100ms, so entries become past-ttl quickly
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(1, 1, TimeValue.ofMilliseconds(100), PoolReusePolicy.LIFO, FakeConnection::close);

        final PoolEntry<String, FakeConnection> e = pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        e.assignConnection(new FakeConnection());
        // keep alive "far", TTL will still kill it
        e.updateExpiry(TimeValue.ofSeconds(10));
        pool.release(e, true);

        Thread.sleep(150);
        pool.closeExpired();

        final PoolStats stats = pool.getStats("r");
        assertEquals(0, stats.getAvailable(), "Expired/TTL entry should be gone");
        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void waiterTimesOutAndIsFailed() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(1, 1, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, FakeConnection::close);

        // Occupy single slot and don't release
        final PoolEntry<String, FakeConnection> e = pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);

        final Future<PoolEntry<String, FakeConnection>> waiter =
                pool.lease("r", null, Timeout.ofMilliseconds(150), null);

        final ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> waiter.get(500, TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertEquals("Lease timed out", ex.getCause().getMessage());
        // cleanup
        pool.release(e, false);
        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void poolCloseCancelsWaitersAndDrainsAvailable() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(1, 1, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, FakeConnection::close);

        // Consume the only slot so the next lease becomes a waiter
        final Future<PoolEntry<String, FakeConnection>> first = pool.lease("r", null, Timeout.ofSeconds(5), null);
        first.get(); // allocated immediately, not released

        // Now this one queues as a waiter
        final Future<PoolEntry<String, FakeConnection>> waiter =
                pool.lease("r", null, Timeout.ofSeconds(5), null);

        pool.close(CloseMode.IMMEDIATE);

        final ExecutionException ex = assertThrows(ExecutionException.class, waiter::get);
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertEquals("Pool closed", ex.getCause().getMessage());
    }

    @Test
    void reusePolicyLifoVsFifoIsObservable() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> lifo =
                newPool(2, 2, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, FakeConnection::close);

        final PoolEntry<String, FakeConnection> a = lifo.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        final PoolEntry<String, FakeConnection> b = lifo.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        a.assignConnection(new FakeConnection());
        a.updateExpiry(TimeValue.ofSeconds(10));
        lifo.release(a, true);
        b.assignConnection(new FakeConnection());
        b.updateExpiry(TimeValue.ofSeconds(10));
        lifo.release(b, true);

        final PoolEntry<String, FakeConnection> firstLifo =
                lifo.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        assertSame(b, firstLifo, "LIFO should return last released");
        lifo.release(firstLifo, false);
        lifo.close(CloseMode.IMMEDIATE);

        final RouteSegmentedConnPool<String, FakeConnection> fifo =
                newPool(2, 2, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.FIFO, FakeConnection::close);
        final PoolEntry<String, FakeConnection> a2 = fifo.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        final PoolEntry<String, FakeConnection> b2 = fifo.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        a2.assignConnection(new FakeConnection());
        a2.updateExpiry(TimeValue.ofSeconds(10));
        fifo.release(a2, true);
        b2.assignConnection(new FakeConnection());
        b2.updateExpiry(TimeValue.ofSeconds(10));
        fifo.release(b2, true);

        final PoolEntry<String, FakeConnection> firstFifo =
                fifo.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        assertSame(a2, firstFifo, "FIFO should return first released");
        fifo.release(firstFifo, false);
        fifo.close(CloseMode.IMMEDIATE);
    }

    @Test
    void disposalIsCalledOnDiscard() throws Exception {
        final List<FakeConnection> closed = new ArrayList<>();
        final CountDownLatch disposed = new CountDownLatch(1);
        final DisposalCallback<FakeConnection> disposal = (c, m) -> {
            try {
                c.close(m);
            } finally {
                closed.add(c);
                disposed.countDown();
            }
        };
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(1, 1, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, disposal);

        final PoolEntry<String, FakeConnection> e = pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        final FakeConnection conn = new FakeConnection();
        e.assignConnection(conn);
        pool.release(e, false);

        // Wait for async disposer to run
        assertTrue(disposed.await(2, TimeUnit.SECONDS), "Disposal did not complete in time");
        assertEquals(1, closed.size());
        assertEquals(1, closed.get(0).closeCount());
        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void slowDisposalDoesNotBlockOtherRoutes() throws Exception {
        final CountDownLatch disposed = new CountDownLatch(1);
        final AtomicLong closedAt = new AtomicLong(0L);
        final DisposalCallback<FakeConnection> disposal = (c, m) -> {
            try {
                c.close(m); // FakeConnection sleeps closeDelayMs internally
            } finally {
                closedAt.set(System.nanoTime());
                disposed.countDown();
            }
        };
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(2, 2, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, disposal);

        final PoolEntry<String, FakeConnection> e1 = pool.lease("r1", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        e1.assignConnection(new FakeConnection(600)); // close sleeps ~600ms

        final long startDiscard = System.nanoTime();
        pool.release(e1, false); // triggers async disposal

        // Lease on another route must not be blocked by slow disposal
        final long t0 = System.nanoTime();
        final PoolEntry<String, FakeConnection> e2 = pool.lease("r2", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        final long tLeaseMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(tLeaseMs < 200, "Other route lease blocked by disposal: " + tLeaseMs + "ms");

        pool.release(e2, false);

        // Wait for disposer to finish, then assert the slow path really took ~600ms
        assertTrue(disposed.await(2, TimeUnit.SECONDS), "Disposal did not complete in time");
        final long discardMs = TimeUnit.NANOSECONDS.toMillis(closedAt.get() - startDiscard);
        assertTrue(discardMs >= 600, "Discard should reflect slow close path (took " + discardMs + "ms)");

        pool.close(CloseMode.IMMEDIATE);
    }

    @Test
    void getRoutesCoversAllocatedAvailableAndWaiters() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool =
                newPool(1, 1, TimeValue.NEG_ONE_MILLISECOND, PoolReusePolicy.LIFO, FakeConnection::close);

        assertTrue(pool.getRoutes().isEmpty(), "Initially there should be no routes");

        // Allocate on rA
        final PoolEntry<String, FakeConnection> a =
                pool.lease("rA", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        assertEquals(new HashSet<String>(Collections.singletonList("rA")), pool.getRoutes(),
                "rA must be listed because it is leased (allocated > 0)");

        // Make rA available
        a.assignConnection(new FakeConnection());
        a.updateExpiry(TimeValue.ofSeconds(30));
        pool.release(a, true);
        assertEquals(new HashSet<>(Collections.singletonList("rA")), pool.getRoutes(),
                "rA must be listed because it has AVAILABLE entries");

        // Enqueue waiter on rB (will time out)
        final Future<PoolEntry<String, FakeConnection>> waiterB =
                pool.lease("rB", null, Timeout.ofMilliseconds(300), null);
        final Set<String> routesNow = pool.getRoutes();
        assertTrue(routesNow.contains("rA") && routesNow.contains("rB"),
                "Both rA (available) and rB (waiter) must be listed");

        // Let rB time out (do NOT free capacity before the timeout fires)
        final ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> waiterB.get(600, TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertEquals("Lease timed out", ex.getCause().getMessage());

        // Now drain rA by leasing and discarding to trigger segment cleanup
        final PoolEntry<String, FakeConnection> a2 =
                pool.lease("rA", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
        pool.release(a2, false); // discard
        final Set<String> afterDropA = pool.getRoutes();
        assertFalse(afterDropA.contains("rA"), "rA segment should be cleaned up");
        assertFalse(afterDropA.contains("rB"), "rB waiter timed out; should not remain listed");

        // Final cleanup
        pool.close(CloseMode.IMMEDIATE);
        assertTrue(pool.getRoutes().isEmpty(), "All routes must be gone after close()");
    }

    @Test
    void repro_roundRobinCompletionLossLeaksGhostLease() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool = newPool(5, 1);
        final ExecutorService drainer = Executors.newSingleThreadExecutor();
        try {
            final PoolEntry<String, FakeConnection> blocker =
                    pool.lease("blocker", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);

            final Future<PoolEntry<String, FakeConnection>> waiterFuture =
                    pool.lease("target", null, Timeout.ofSeconds(5), null);

            final Object targetSegment = getSegment(pool, "target");
            assertNotNull(targetSegment);
            final Object waiter = getFirstWaiter(targetSegment);
            assertNotNull(waiter);

            final BlockingScheduledFuture blockingTimeoutTask = new BlockingScheduledFuture();
            setWaiterTimeoutTask(waiter, blockingTimeoutTask);

            // Free global headroom. We'll invoke RR manually so we can orchestrate timing.
            pool.release(blocker, false);

            final Future<?> drainFuture = drainer.submit(() -> invokeServeRoundRobin(pool, 1));
            assertTrue(blockingTimeoutTask.awaitCancelEntered(1, TimeUnit.SECONDS));

            ((CompletableFuture<?>) waiter).completeExceptionally(new TimeoutException("Injected completion"));
            blockingTimeoutTask.allowCancelToReturn();
            drainFuture.get(1, TimeUnit.SECONDS);

            final ExecutionException ex = assertThrows(
                    ExecutionException.class,
                    () -> waiterFuture.get(1, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, ex.getCause());

            // Expected safe behavior: no ghost leased entries after failed completion hand-off.
            final PoolStats stats = pool.getStats("target");
            assertEquals(0, stats.getLeased(), "ghost lease leaked after failed RR completion");
            assertEquals(0, stats.getPending());
        } finally {
            drainer.shutdownNow();
            pool.close(CloseMode.IMMEDIATE);
        }
    }

    @Test
    void repro_lateHitCompletionLossLeaksAvailableEntry() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool = newPool(1, 1);
        final ExecutorService leaseThread = Executors.newSingleThreadExecutor();
        try {
            final PoolEntry<String, FakeConnection> seed =
                    pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
            seed.assignConnection(new FakeConnection());
            seed.updateState("seed");
            seed.updateExpiry(TimeValue.ofSeconds(30));
            pool.release(seed, true);

            final TwoPhaseState requestedState = new TwoPhaseState();
            final Future<Future<PoolEntry<String, FakeConnection>>> outer =
                    leaseThread.submit(() -> pool.lease("r", requestedState, Timeout.ofSeconds(5), null));

            requestedState.awaitLatePollEntered(1, TimeUnit.SECONDS);

            final Object segment = getSegment(pool, "r");
            assertNotNull(segment);
            final Object waiter = getFirstWaiter(segment);
            assertNotNull(waiter);

            // Complete the waiter before lease() reaches w.complete(late), while keeping it in waiters.
            ((CompletableFuture<?>) waiter).completeExceptionally(new TimeoutException("Injected completion"));
            requestedState.allowLatePollToProceed();

            final Future<PoolEntry<String, FakeConnection>> leaseFuture = outer.get(1, TimeUnit.SECONDS);
            final ExecutionException ex = assertThrows(
                    ExecutionException.class,
                    () -> leaseFuture.get(1, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, ex.getCause());

            // Expected safe behavior: late-hit entry should remain available; no ghost lease.
            final PoolStats stats = pool.getStats("r");
            assertEquals(0, stats.getLeased(), "ghost lease leaked after failed late-hit completion");
            assertEquals(1, stats.getAvailable(), "late-hit entry disappeared from available pool");
            assertEquals(0, stats.getPending());
        } finally {
            leaseThread.shutdownNow();
            pool.close(CloseMode.IMMEDIATE);
        }
    }

    @Test
    void repro_releasingForeignEntryCreatesPhantomCapacity() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool = newPool(1, 1);
        try {
            final PoolEntry<String, FakeConnection> held =
                    pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);

            final PoolEntry<String, FakeConnection> foreign =
                    new PoolEntry<>("r", TimeValue.NEG_ONE_MILLISECOND, FakeConnection::close);
            foreign.assignConnection(new FakeConnection());
            foreign.updateExpiry(TimeValue.ofSeconds(30));

            final IllegalStateException releaseEx = assertThrows(
                    IllegalStateException.class,
                    () -> pool.release(foreign, false));
            assertEquals("Pool entry is not present in the set of leased entries", releaseEx.getMessage());

            final Future<PoolEntry<String, FakeConnection>> unexpected =
                    pool.lease("r", null, Timeout.ofMilliseconds(150), null);

            // Expected safe behavior: while 'held' is leased and max=1, this should time out.
            final ExecutionException ex = assertThrows(
                    ExecutionException.class,
                    () -> unexpected.get(500, TimeUnit.MILLISECONDS));
            assertInstanceOf(TimeoutException.class, ex.getCause());

            pool.release(held, false);
        } finally {
            pool.close(CloseMode.IMMEDIATE);
        }
    }

    @Test
    void repro_waiterCallbackReleaseMustNotSeeUnknownEntry() throws Exception {
        final RouteSegmentedConnPool<String, FakeConnection> pool = newPool(1, 1);
        try {
            final PoolEntry<String, FakeConnection> held =
                    pool.lease("r", null, Timeout.ofSeconds(1), null).get(1, TimeUnit.SECONDS);
            held.assignConnection(new FakeConnection());
            held.updateExpiry(TimeValue.ofSeconds(30));

            final CountDownLatch callbackDone = new CountDownLatch(1);
            final AtomicReference<Throwable> callbackError = new AtomicReference<>();

            final Future<PoolEntry<String, FakeConnection>> waiter =
                    pool.lease("r", null, Timeout.ofSeconds(1), new FutureCallback<PoolEntry<String, FakeConnection>>() {
                        @Override
                        public void completed(final PoolEntry<String, FakeConnection> result) {
                            try {
                                // Callback immediately releases the handed-off entry.
                                pool.release(result, false);
                            } catch (final Throwable ex) {
                                callbackError.compareAndSet(null, ex);
                            } finally {
                                callbackDone.countDown();
                            }
                        }

                        @Override
                        public void failed(final Exception ex) {
                            callbackError.compareAndSet(null, ex);
                            callbackDone.countDown();
                        }

                        @Override
                        public void cancelled() {
                            callbackDone.countDown();
                        }
                    });

            pool.release(held, true);

            assertNotNull(waiter.get(1, TimeUnit.SECONDS));
            assertTrue(callbackDone.await(1, TimeUnit.SECONDS), "callback did not complete in time");
            assertNull(callbackError.get(), "callback saw an unexpected error while releasing handoff entry");
        } finally {
            pool.close(CloseMode.IMMEDIATE);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object getSegment(
            final RouteSegmentedConnPool<String, FakeConnection> pool,
            final String route) throws Exception {
        final Field segmentsField = RouteSegmentedConnPool.class.getDeclaredField("segments");
        segmentsField.setAccessible(true);
        final Map<String, Object> segments = (Map<String, Object>) segmentsField.get(pool);
        return segments.get(route);
    }

    private static Object getFirstWaiter(final Object segment) throws Exception {
        final Field waitersField = segment.getClass().getDeclaredField("waiters");
        waitersField.setAccessible(true);
        final Deque<?> waiters = (Deque<?>) waitersField.get(segment);
        return waiters.peekFirst();
    }

    private static void setWaiterTimeoutTask(final Object waiter, final ScheduledFuture<?> timeoutTask) throws Exception {
        final Field timeoutTaskField = waiter.getClass().getDeclaredField("timeoutTask");
        timeoutTaskField.setAccessible(true);
        timeoutTaskField.set(waiter, timeoutTask);
    }

    private static void invokeServeRoundRobin(
            final RouteSegmentedConnPool<String, FakeConnection> pool,
            final int budget) {
        try {
            final Method method = RouteSegmentedConnPool.class.getDeclaredMethod("serveRoundRobin", int.class);
            method.setAccessible(true);
            method.invoke(pool, budget);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final class BlockingScheduledFuture implements ScheduledFuture<Object> {
        private final CountDownLatch cancelEntered = new CountDownLatch(1);
        private final CountDownLatch allowCancelReturn = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        boolean awaitCancelEntered(final long timeout, final TimeUnit unit) throws InterruptedException {
            return cancelEntered.await(timeout, unit);
        }

        void allowCancelToReturn() {
            allowCancelReturn.countDown();
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            cancelled.set(true);
            cancelEntered.countDown();
            try {
                allowCancelReturn.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(final long timeout, final TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDelay(final TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(final Delayed other) {
            return 0;
        }
    }

    private static final class TwoPhaseState {
        private final AtomicInteger equalsCalls = new AtomicInteger(0);
        private final CountDownLatch latePollEntered = new CountDownLatch(1);
        private final CountDownLatch allowLatePoll = new CountDownLatch(1);

        void awaitLatePollEntered(final long timeout, final TimeUnit unit) throws InterruptedException {
            assertTrue(latePollEntered.await(timeout, unit), "late poll was not reached");
        }

        void allowLatePollToProceed() {
            allowLatePoll.countDown();
        }

        @Override
        public boolean equals(final Object other) {
            final int call = equalsCalls.incrementAndGet();
            if (call == 1) {
                return false;
            }
            if (call == 2) {
                latePollEntered.countDown();
                try {
                    if (!allowLatePoll.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to continue late poll");
                    }
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
                return true;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return 31;
        }
    }
}
