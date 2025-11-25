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
package org.apache.hc.core5.http2.nio.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

class H2TunnelCommandTest {

    @Test
    void shouldExposeConstructorArguments() {
        final HttpHost target = new HttpHost(URIScheme.HTTPS.id, "example.com", 443);
        final Timeout timeout = Timeout.ofSeconds(10);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);

        final H2TunnelCommand command = new H2TunnelCommand(target, timeout, callback);

        assertSame(target, command.getTargetHost());
        assertSame(timeout, command.getTimeout());
        assertSame(callback, command.getCallback());
        assertFalse(command.isCancelled());
    }

    @Test
    void cancelShouldSetCancelledFlagAndNotifyCallbackOnce() {
        final HttpHost target = new HttpHost(URIScheme.HTTPS.id, "example.com", 443);
        final Timeout timeout = Timeout.ofSeconds(5);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);

        final H2TunnelCommand command = new H2TunnelCommand(target, timeout, callback);

        assertFalse(command.isCancelled());

        final boolean first = command.cancel();
        final boolean second = command.cancel();

        assertTrue(first);
        assertTrue(second);
        assertTrue(command.isCancelled());
        verify(callback, times(1)).cancelled();
        verifyNoMoreInteractions(callback);
    }

    @Test
    void cancelWithoutCallbackShouldNotFail() {
        final HttpHost target = new HttpHost(URIScheme.HTTPS.id, "example.com", 443);
        final Timeout timeout = Timeout.ofSeconds(5);

        final H2TunnelCommand command = new H2TunnelCommand(target, timeout, null);

        final boolean result = command.cancel();

        assertTrue(result);
        assertTrue(command.isCancelled());
    }

}
