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

import org.apache.hc.core5.annotation.Experimental;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.nio.AsyncClientEndpoint;
import org.apache.hc.core5.http.nio.ssl.TlsUpgradeCapable;
import org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.nio.support.BasicResponseConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Args;

/**
 * Helper for establishing HTTP/2 tunnels through HTTP/1.1 proxies using
 * a CONNECT handshake.
 *
 * <p>
 * Note: This helper does not implement proxy authentication (407 challenge handling).
 * That belongs in HttpClient (auth state + retries), not in HttpCore.
 * </p>
 *
 * @since 5.5
 */
@Experimental
public final class H2TunnelSupport {

    private H2TunnelSupport() {
    }

    private static int resolvePort(final NamedEndpoint target) {
        final int port = target.getPort();
        if (port > 0) {
            return port;
        }
        if (target instanceof HttpHost) {
            final HttpHost httpHost = (HttpHost) target;
            if (URIScheme.HTTPS.same(httpHost.getSchemeName())) {
                return 443;
            }
            if (URIScheme.HTTP.same(httpHost.getSchemeName())) {
                return 80;
            }
        }
        throw new IllegalArgumentException("Tunnel target port is undefined: " + target.getHostName());
    }

    private static String resolveAuthority(final NamedEndpoint target) {
        final int port = resolvePort(target);
        return target.getHostName() + ":" + port;
    }

    private static HttpHost resolveConnectHost(final NamedEndpoint target) {
        final int port = resolvePort(target);
        return new HttpHost(target.getHostName(), port);
    }

    /**
     * Establishes a CONNECT tunnel on an already-connected endpoint and upgrades to HTTP/2.
     * <p>
     * The endpoint must be {@link TlsUpgradeCapable} for TLS tunnels (typical case for HTTP/2).
     * </p>
     */
    public static void establish(
            final AsyncClientEndpoint endpoint,
            final NamedEndpoint target,
            final FutureCallback<AsyncClientEndpoint> callback) {
        Args.notNull(endpoint, "Client endpoint");
        Args.notNull(target, "Tunnel target");

        final String authority = resolveAuthority(target);
        final HttpHost connectHost = resolveConnectHost(target);

        final BasicHttpRequest connect = new BasicHttpRequest(Method.CONNECT, connectHost, authority);
        connect.setVersion(HttpVersion.HTTP_1_1);
        connect.setHeader(HttpHeaders.HOST, authority);

        final HttpContext context = HttpCoreContext.create();

        endpoint.execute(
                new BasicRequestProducer(connect, null),
                new BasicResponseConsumer<>(new DiscardingEntityConsumer<>()),
                context,
                new FutureCallback<Message<HttpResponse, Void>>() {

                    @Override
                    public void completed(final Message<HttpResponse, Void> message) {
                        final HttpResponse response = message.getHead();
                        if (response.getCode() != HttpStatus.SC_OK) {
                            endpoint.releaseAndDiscard();
                            if (callback != null) {
                                callback.failed(new HttpException("Tunnel refused: " + new StatusLine(response)));
                            }
                            return;
                        }
                        if (!(endpoint instanceof TlsUpgradeCapable)) {
                            endpoint.releaseAndDiscard();
                            if (callback != null) {
                                callback.failed(new IllegalStateException("TLS upgrade not supported"));
                            }
                            return;
                        }
                        ((TlsUpgradeCapable) endpoint).tlsUpgrade(target, new FutureCallback<ProtocolIOSession>() {

                            @Override
                            public void completed(final ProtocolIOSession protocolSession) {
                                if (callback != null) {
                                    callback.completed(endpoint);
                                }
                            }

                            @Override
                            public void failed(final Exception ex) {
                                endpoint.releaseAndDiscard();
                                if (callback != null) {
                                    callback.failed(ex);
                                }
                            }

                            @Override
                            public void cancelled() {
                                endpoint.releaseAndDiscard();
                                if (callback != null) {
                                    callback.cancelled();
                                }
                            }
                        });
                    }

                    @Override
                    public void failed(final Exception ex) {
                        endpoint.releaseAndDiscard();
                        if (callback != null) {
                            callback.failed(ex);
                        }
                    }

                    @Override
                    public void cancelled() {
                        endpoint.releaseAndDiscard();
                        if (callback != null) {
                            callback.cancelled();
                        }
                    }
                });
    }

}
