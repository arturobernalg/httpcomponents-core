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
package org.apache.hc.core5.http2.nio.support;

import java.io.IOException;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.AsyncClientEndpoint;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.nio.ssl.TlsUpgradeCapable;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestH2TunnelSupport {

    @Test
    void testEstablishBuildsConnectForTargetAuthorityAndUpgradesTls() throws Exception {
        final ScriptedTlsEndpoint endpoint = new ScriptedTlsEndpoint(HttpStatus.SC_OK);
        final HttpHost target = new HttpHost("https", "example.org");
        final RecordingCallback<AsyncClientEndpoint> callback = new RecordingCallback<>();

        H2TunnelSupport.establish(endpoint, target, callback);

        Assertions.assertTrue(callback.completed);
        Assertions.assertNull(callback.failed);
        Assertions.assertFalse(callback.cancelled);
        Assertions.assertNotNull(endpoint.capturedRequest);
        Assertions.assertEquals("CONNECT", endpoint.capturedRequest.getMethod());
        Assertions.assertEquals(HttpVersion.HTTP_1_1, endpoint.capturedRequest.getVersion());
        Assertions.assertEquals("example.org:443", endpoint.capturedRequest.getPath());
        Assertions.assertEquals("example.org:443", endpoint.capturedRequest.getFirstHeader(HttpHeaders.HOST).getValue());
        Assertions.assertEquals("example.org", endpoint.capturedRequest.getAuthority().getHostName());
        Assertions.assertEquals(443, endpoint.capturedRequest.getAuthority().getPort());
        Assertions.assertEquals(target, endpoint.upgradeEndpoint);
        Assertions.assertFalse(endpoint.discarded);
    }

    @Test
    void testEstablishFailsWhenProxyRefusesTunnel() {
        final ScriptedTlsEndpoint endpoint = new ScriptedTlsEndpoint(HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED);
        final HttpHost target = new HttpHost("https", "example.org", 8443);
        final RecordingCallback<AsyncClientEndpoint> callback = new RecordingCallback<>();

        H2TunnelSupport.establish(endpoint, target, callback);

        Assertions.assertFalse(callback.completed);
        Assertions.assertNotNull(callback.failed);
        Assertions.assertInstanceOf(HttpException.class, callback.failed);
        Assertions.assertNull(endpoint.upgradeEndpoint);
        Assertions.assertTrue(endpoint.discarded);
    }

    @Test
    void testEstablishFailsWhenEndpointCannotUpgradeTls() {
        final ScriptedPlainEndpoint endpoint = new ScriptedPlainEndpoint(HttpStatus.SC_OK);
        final HttpHost target = new HttpHost("https", "example.org", 443);
        final RecordingCallback<AsyncClientEndpoint> callback = new RecordingCallback<>();

        H2TunnelSupport.establish(endpoint, target, callback);

        Assertions.assertFalse(callback.completed);
        Assertions.assertNotNull(callback.failed);
        Assertions.assertInstanceOf(IllegalStateException.class, callback.failed);
        Assertions.assertTrue(endpoint.discarded);
    }

    @Test
    void testEstablishPropagatesExchangeFailure() {
        final ScriptedTlsEndpoint endpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.FAIL, TlsOutcome.COMPLETE);
        final HttpHost target = new HttpHost("https", "example.org", 443);
        final RecordingCallback<AsyncClientEndpoint> callback = new RecordingCallback<>();

        H2TunnelSupport.establish(endpoint, target, callback);

        Assertions.assertFalse(callback.completed);
        Assertions.assertFalse(callback.cancelled);
        Assertions.assertNotNull(callback.failed);
        Assertions.assertTrue(endpoint.discarded);
    }

    @Test
    void testEstablishPropagatesExchangeCancellation() {
        final ScriptedTlsEndpoint endpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.CANCEL, TlsOutcome.COMPLETE);
        final HttpHost target = new HttpHost("https", "example.org", 443);
        final RecordingCallback<AsyncClientEndpoint> callback = new RecordingCallback<>();

        H2TunnelSupport.establish(endpoint, target, callback);

        Assertions.assertFalse(callback.completed);
        Assertions.assertNull(callback.failed);
        Assertions.assertTrue(callback.cancelled);
        Assertions.assertTrue(endpoint.discarded);
    }

    @Test
    void testEstablishPropagatesTlsUpgradeFailure() {
        final ScriptedTlsEndpoint endpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.NORMAL, TlsOutcome.FAIL);
        final HttpHost target = new HttpHost("https", "example.org", 443);
        final RecordingCallback<AsyncClientEndpoint> callback = new RecordingCallback<>();

        H2TunnelSupport.establish(endpoint, target, callback);

        Assertions.assertFalse(callback.completed);
        Assertions.assertFalse(callback.cancelled);
        Assertions.assertNotNull(callback.failed);
        Assertions.assertTrue(endpoint.discarded);
    }

    @Test
    void testEstablishPropagatesTlsUpgradeCancellation() {
        final ScriptedTlsEndpoint endpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.NORMAL, TlsOutcome.CANCEL);
        final HttpHost target = new HttpHost("https", "example.org", 443);
        final RecordingCallback<AsyncClientEndpoint> callback = new RecordingCallback<>();

        H2TunnelSupport.establish(endpoint, target, callback);

        Assertions.assertFalse(callback.completed);
        Assertions.assertNull(callback.failed);
        Assertions.assertTrue(callback.cancelled);
        Assertions.assertTrue(endpoint.discarded);
    }

    @Test
    void testEstablishWithNullCallbackOnFailureAndCancellationDoesNotThrow() {
        final HttpHost target = new HttpHost("https", "example.org", 443);
        final ScriptedTlsEndpoint failEndpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.FAIL, TlsOutcome.COMPLETE);
        final ScriptedTlsEndpoint cancelEndpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.CANCEL, TlsOutcome.COMPLETE);
        final ScriptedTlsEndpoint tlsFailEndpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.NORMAL, TlsOutcome.FAIL);
        final ScriptedTlsEndpoint tlsCancelEndpoint = new ScriptedTlsEndpoint(
                HttpStatus.SC_OK, ExchangeOutcome.NORMAL, TlsOutcome.CANCEL);

        Assertions.assertDoesNotThrow(() -> H2TunnelSupport.establish(failEndpoint, target, null));
        Assertions.assertDoesNotThrow(() -> H2TunnelSupport.establish(cancelEndpoint, target, null));
        Assertions.assertDoesNotThrow(() -> H2TunnelSupport.establish(tlsFailEndpoint, target, null));
        Assertions.assertDoesNotThrow(() -> H2TunnelSupport.establish(tlsCancelEndpoint, target, null));
        Assertions.assertTrue(failEndpoint.discarded);
        Assertions.assertTrue(cancelEndpoint.discarded);
        Assertions.assertTrue(tlsFailEndpoint.discarded);
        Assertions.assertTrue(tlsCancelEndpoint.discarded);
    }

    enum ExchangeOutcome { NORMAL, FAIL, CANCEL }

    enum TlsOutcome { COMPLETE, FAIL, CANCEL }

    static class ScriptedTlsEndpoint extends AsyncClientEndpoint implements TlsUpgradeCapable {

        private final int responseCode;
        private final ExchangeOutcome exchangeOutcome;
        private final TlsOutcome tlsOutcome;
        private HttpRequest capturedRequest;
        private NamedEndpoint upgradeEndpoint;
        private boolean discarded;

        ScriptedTlsEndpoint(final int responseCode) {
            this(responseCode, ExchangeOutcome.NORMAL, TlsOutcome.COMPLETE);
        }

        ScriptedTlsEndpoint(
                final int responseCode,
                final ExchangeOutcome exchangeOutcome,
                final TlsOutcome tlsOutcome) {
            this.responseCode = responseCode;
            this.exchangeOutcome = exchangeOutcome;
            this.tlsOutcome = tlsOutcome;
        }

        @Override
        public void execute(
                final AsyncClientExchangeHandler exchangeHandler,
                final HandlerFactory<AsyncPushConsumer> pushHandlerFactory,
                final HttpContext context) {
            final HttpContext executionContext = context != null ? context : HttpCoreContext.create();
            try {
                exchangeHandler.produceRequest(new RequestChannel() {
                    @Override
                    public void sendRequest(
                            final HttpRequest request,
                            final org.apache.hc.core5.http.EntityDetails entityDetails,
                            final HttpContext requestContext) {
                        capturedRequest = request;
                    }
                }, executionContext);
                if (exchangeOutcome == ExchangeOutcome.FAIL) {
                    exchangeHandler.failed(new IOException("synthetic exchange failure"));
                    return;
                }
                if (exchangeOutcome == ExchangeOutcome.CANCEL) {
                    exchangeHandler.cancel();
                    return;
                }
                exchangeHandler.consumeResponse(new BasicHttpResponse(responseCode), null, executionContext);
            } catch (final IOException | HttpException ex) {
                exchangeHandler.failed(ex);
            }
        }

        @Override
        public void releaseAndReuse() {
        }

        @Override
        public void releaseAndDiscard() {
            this.discarded = true;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void tlsUpgrade(final NamedEndpoint endpoint, final FutureCallback<ProtocolIOSession> callback) {
            this.upgradeEndpoint = endpoint;
            if (tlsOutcome == TlsOutcome.FAIL) {
                callback.failed(new IOException("synthetic tls failure"));
                return;
            }
            if (tlsOutcome == TlsOutcome.CANCEL) {
                callback.cancelled();
                return;
            }
            callback.completed(null);
        }
    }

    static class ScriptedPlainEndpoint extends AsyncClientEndpoint {

        private final int responseCode;
        private boolean discarded;

        ScriptedPlainEndpoint(final int responseCode) {
            this.responseCode = responseCode;
        }

        @Override
        public void execute(
                final AsyncClientExchangeHandler exchangeHandler,
                final HandlerFactory<AsyncPushConsumer> pushHandlerFactory,
                final HttpContext context) {
            final HttpContext executionContext = context != null ? context : HttpCoreContext.create();
            try {
                exchangeHandler.produceRequest(new RequestChannel() {
                    @Override
                    public void sendRequest(
                            final HttpRequest request,
                            final org.apache.hc.core5.http.EntityDetails entityDetails,
                            final HttpContext requestContext) {
                    }
                }, executionContext);
                exchangeHandler.consumeResponse(new BasicHttpResponse(responseCode), null, executionContext);
            } catch (final IOException | HttpException ex) {
                exchangeHandler.failed(ex);
            }
        }

        @Override
        public void releaseAndReuse() {
        }

        @Override
        public void releaseAndDiscard() {
            this.discarded = true;
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    static class RecordingCallback<T> implements FutureCallback<T> {

        volatile boolean completed;
        volatile boolean cancelled;
        volatile Exception failed;

        @Override
        public void completed(final T result) {
            this.completed = true;
        }

        @Override
        public void failed(final Exception ex) {
            this.failed = ex;
        }

        @Override
        public void cancelled() {
            this.cancelled = true;
        }
    }
}
