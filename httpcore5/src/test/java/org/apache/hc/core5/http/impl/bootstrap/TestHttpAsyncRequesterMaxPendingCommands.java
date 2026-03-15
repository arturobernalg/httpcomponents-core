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
package org.apache.hc.core5.http.impl.bootstrap;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.pool.PoolEntry;
import org.apache.hc.core5.reactor.Command;
import org.apache.hc.core5.reactor.IOEventHandlerFactory;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOReactorMetricsListener;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.IOSessionListener;
import org.apache.hc.core5.reactor.IOWorkerSelector;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

class TestHttpAsyncRequesterMaxPendingCommands {

    @Test
    void testExecuteRejectsWhenPendingCommandLimitReachedAndReusesEndpoint() throws Exception {
        final ManagedConnPool<HttpHost, IOSession> connPool = mock(ManagedConnPool.class);
        final IOSession ioSession = mock(IOSession.class);
        final PoolEntry<HttpHost, IOSession> poolEntry = new PoolEntry<>(new HttpHost("https", "localhost"));
        poolEntry.assignConnection(ioSession);

        when(ioSession.isOpen()).thenReturn(true);
        when(ioSession.getPendingCommandCount()).thenReturn(1);
        when(ioSession.getLock()).thenReturn(new ReentrantLock());

        doAnswer(invocation -> {
            final FutureCallback<PoolEntry<HttpHost, IOSession>> callback =
                    invocation.getArgument(3);
            callback.completed(poolEntry);
            return mock(Future.class);
        }).when(connPool).lease(any(HttpHost.class), eq(null), any(Timeout.class), any());

        final HttpAsyncRequester requester = new HttpAsyncRequester(
                IOReactorConfig.DEFAULT,
                mock(IOEventHandlerFactory.class),
                ioSession1 -> ioSession1,
                exception -> {
                },
                mock(IOSessionListener.class),
                connPool,
                null,
                null,
                mock(IOReactorMetricsListener.class),
                mock(IOWorkerSelector.class),
                1);

        final AsyncClientExchangeHandler exchangeHandler = mock(AsyncClientExchangeHandler.class);
        final AtomicReference<Exception> failureRef = new AtomicReference<>();

        doAnswer(invocation -> {
            final RequestChannel channel = invocation.getArgument(0);
            final BasicHttpRequest request = new BasicHttpRequest("GET", "/");
            channel.sendRequest(request, null, HttpCoreContext.create());
            return null;
        }).when(exchangeHandler).produceRequest(any(RequestChannel.class), any());

        doAnswer(invocation -> {
            failureRef.set(invocation.getArgument(0));
            return null;
        }).when(exchangeHandler).failed(any(Exception.class));

        requester.execute(
                new HttpHost("https", "localhost"),
                exchangeHandler,
                null,
                Timeout.ofSeconds(1),
                HttpCoreContext.create());

        assertInstanceOf(RejectedExecutionException.class, failureRef.get());
        verify(connPool, times(1)).release(same(poolEntry), eq(true));
        verify(ioSession, never()).enqueue(any(), eq(Command.Priority.NORMAL));
        verify(ioSession, never()).close(any(CloseMode.class));
        verify(exchangeHandler, times(1)).failed(any(Exception.class));
        verify(exchangeHandler, times(1)).releaseResources();
    }

    @Test
    void testExecuteEnqueuesWhenPendingCommandLimitNotReached() throws Exception {
        final ManagedConnPool<HttpHost, IOSession> connPool = mock(ManagedConnPool.class);
        final IOSession ioSession = mock(IOSession.class);
        final PoolEntry<HttpHost, IOSession> poolEntry = new PoolEntry<>(new HttpHost("https", "localhost"));
        poolEntry.assignConnection(ioSession);

        when(ioSession.isOpen()).thenReturn(true);
        when(ioSession.getPendingCommandCount()).thenReturn(0);
        when(ioSession.getLock()).thenReturn(new ReentrantLock());

        doAnswer(invocation -> {
            final FutureCallback<PoolEntry<HttpHost, IOSession>> callback =
                    invocation.getArgument(3);
            callback.completed(poolEntry);
            return mock(Future.class);
        }).when(connPool).lease(any(HttpHost.class), eq(null), any(Timeout.class), any());

        final HttpAsyncRequester requester = new HttpAsyncRequester(
                IOReactorConfig.DEFAULT,
                mock(IOEventHandlerFactory.class),
                ioSession1 -> ioSession1,
                exception -> {
                },
                mock(IOSessionListener.class),
                connPool,
                null,
                null,
                mock(IOReactorMetricsListener.class),
                mock(IOWorkerSelector.class),
                1);

        final AsyncClientExchangeHandler exchangeHandler = mock(AsyncClientExchangeHandler.class);

        doAnswer(invocation -> {
            final RequestChannel channel = invocation.getArgument(0);
            final BasicHttpRequest request = new BasicHttpRequest("GET", "/");
            channel.sendRequest(request, null, HttpCoreContext.create());
            return null;
        }).when(exchangeHandler).produceRequest(any(RequestChannel.class), any());

        requester.execute(
                new HttpHost("https", "localhost"),
                exchangeHandler,
                null,
                Timeout.ofSeconds(1),
                HttpCoreContext.create());

        verify(ioSession, times(1)).enqueue(any(), eq(Command.Priority.NORMAL));
        verify(connPool, never()).release(any(), anyBoolean());
        verify(exchangeHandler, never()).failed(any(Exception.class));
        verify(exchangeHandler, never()).releaseResources();
    }

}