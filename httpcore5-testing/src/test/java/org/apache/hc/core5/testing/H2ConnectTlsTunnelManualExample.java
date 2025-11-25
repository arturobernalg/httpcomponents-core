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
 */

package org.apache.hc.core5.testing;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.AsyncServerBootstrap;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.io.HttpServerRequestHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.nio.AsyncServerExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.ResponseChannel;
import org.apache.hc.core5.http.nio.ssl.BasicServerTlsStrategy;
import org.apache.hc.core5.http.nio.support.classic.ClassicToAsyncServerExchangeHandler;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.frame.RawFrame;
import org.apache.hc.core5.http2.impl.nio.H2StreamListener;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2AsyncRequester;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2RequesterBootstrap;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.http2.nio.command.H2TunnelCommand;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.Command;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;

/**
 * Manual example:
 * <ul>
 *   <li>start TLS HTTP/1.1 server on 9443 with test server.p12</li>
 *   <li>start cleartext H2 proxy on 8443</li>
 *   <li>issue H2 CONNECT tunnel to https://localhost:9443</li>
 * </ul>
 */
public final class H2ConnectTlsTunnelManualExample {

    private static final int TLS_TARGET_PORT = 9443;
    private static final int PROXY_PORT = 8443;
    private static final char[] STORE_PASSWORD = "nopassword".toCharArray();

    private H2ConnectTlsTunnelManualExample() {
    }

    public static void main(final String[] args) throws Exception {
        // 1) Install client trust context from test CA
        final SSLContext clientSslContext = createClientSSLContext();
        SSLContext.setDefault(clientSslContext);

        // 2) Start TLS target server (HTTP/1.1 over TLS)
        final HttpAsyncServer tlsTarget = startTlsTargetServer();

        // 3) Start H2 proxy
        final HttpAsyncServer h2Proxy = startH2Proxy();

        try {
            // 4) Run H2 CONNECT client
            runH2ConnectClient("localhost", PROXY_PORT, "localhost", TLS_TARGET_PORT);
        } finally {
            System.out.println("Shutting down H2 proxy");
            h2Proxy.close(CloseMode.GRACEFUL);

            System.out.println("Shutting down TLS target");
            tlsTarget.close(CloseMode.GRACEFUL);
        }
    }

    // =====================================================================
    // TLS target: HTTP/1.1 over TLS using AsyncServerBootstrap
    // =====================================================================

    private static HttpAsyncServer startTlsTargetServer() throws Exception {
        final SSLContext serverContext = createServerSSLContext();

        final IOReactorConfig config = IOReactorConfig.custom()
                .setSoTimeout(15, TimeUnit.SECONDS)
                .setTcpNoDelay(true)
                .build();

        final ExecutorService executor = Executors.newCachedThreadPool();

        final HttpServerRequestHandler classicHandler = new HttpServerRequestHandler() {
            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ResponseTrigger responseTrigger,
                    final HttpContext context) throws HttpException, IOException {

                System.out.println("[tls-target] " + request.getMethod() + " " + request.getRequestUri());

                final ClassicHttpResponse response = new BasicClassicHttpResponse(HttpStatus.SC_OK);
                response.setEntity(new StringEntity(
                        "Hello from TLS target: " + request.getRequestUri(),
                        ContentType.TEXT_PLAIN));

                responseTrigger.submitResponse(response);
            }
        };

        final HttpAsyncServer server = AsyncServerBootstrap.bootstrap()
                .setIOReactorConfig(config)
                .setTlsStrategy(new BasicServerTlsStrategy(serverContext))
                .register("*", () -> new ClassicToAsyncServerExchangeHandler(
                        executor,
                        classicHandler,
                        ex -> {
                            System.out.println("[tls-target] error: " + ex);
                            ex.printStackTrace(System.out);
                        }))
                .create();

        server.start();

        final Future<ListenerEndpoint> endpointFuture = server.listen(
                new InetSocketAddress("localhost", TLS_TARGET_PORT),
                URIScheme.HTTPS);
        final ListenerEndpoint endpoint = endpointFuture.get();
        System.out.println("[tls-target] Listening on " + endpoint.getAddress());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[tls-target] shutdown hook");
            server.close(CloseMode.GRACEFUL);
            executor.shutdownNow();
        }));

        return server;
    }

    private static SSLContext createServerSSLContext() throws Exception {
        final KeyStore serverStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = H2ConnectTlsTunnelManualExample.class.getResourceAsStream(
                "/docker/server.p12")) {
            if (in == null) {
                throw new IllegalStateException("server.p12 not found on classpath under /docker/");
            }
            serverStore.load(in, STORE_PASSWORD);
        }
        return SSLContexts.custom()
                .loadKeyMaterial(serverStore, STORE_PASSWORD)
                .build();
    }

    private static SSLContext createClientSSLContext() throws Exception {
        final KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream in = H2ConnectTlsTunnelManualExample.class.getResourceAsStream(
                "/test-ca.jks")) {
            if (in == null) {
                throw new IllegalStateException("test-ca.jks not found on classpath");
            }
            trustStore.load(in, STORE_PASSWORD);
        }
        final SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial(trustStore, null)
                .build();
        System.out.println("[trust] Using test CA trust store from classpath:/test-ca.jks");
        return sslContext;
    }

    // =====================================================================
    // Cleartext H2 proxy: only answers CONNECT with 200
    // =====================================================================

    private static HttpAsyncServer startH2Proxy() throws Exception {
        final IOReactorConfig config = IOReactorConfig.custom()
                .setSoTimeout(15, TimeUnit.SECONDS)
                .setTcpNoDelay(true)
                .build();

        final HttpAsyncServer proxy = H2ServerBootstrap.bootstrap()
                // *** IMPORTANT: make the H2 server authoritative for "localhost" ***
                .setCanonicalHostName("localhost")
                .setIOReactorConfig(config)
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                .setStreamListener(new H2StreamListener() {

                    @Override
                    public void onHeaderInput(
                            final HttpConnection connection,
                            final int streamId,
                            final List<? extends Header> headers) {
                        for (final Header h : headers) {
                            System.out.println("[proxy] " + connection.getRemoteAddress()
                                    + " (" + streamId + ") << " + h);
                        }
                    }

                    @Override
                    public void onHeaderOutput(
                            final HttpConnection connection,
                            final int streamId,
                            final List<? extends Header> headers) {
                        for (final Header h : headers) {
                            System.out.println("[proxy] " + connection.getRemoteAddress()
                                    + " (" + streamId + ") >> " + h);
                        }
                    }

                    @Override
                    public void onFrameInput(
                            final HttpConnection connection,
                            final int streamId,
                            final RawFrame frame) {
                    }

                    @Override
                    public void onFrameOutput(
                            final HttpConnection connection,
                            final int streamId,
                            final RawFrame frame) {
                    }

                    @Override
                    public void onInputFlowControl(
                            final HttpConnection connection,
                            final int streamId,
                            final int delta,
                            final int actualSize) {
                    }

                    @Override
                    public void onOutputFlowControl(
                            final HttpConnection connection,
                            final int streamId,
                            final int delta,
                            final int actualSize) {
                    }
                })
                .register("*", SimpleH2ConnectProxyHandler::new)
                .setExceptionCallback(ex -> {
                    if (ex instanceof org.apache.hc.core5.http.RequestNotExecutedException
                            || ex instanceof org.apache.hc.core5.http2.H2StreamResetException) {
                        return;
                    }
                    System.out.println("[proxy-io] " + ex);
                    ex.printStackTrace(System.out);
                })
                .create();

        proxy.start();

        final Future<ListenerEndpoint> listenFuture =
                proxy.listen(new InetSocketAddress(PROXY_PORT), URIScheme.HTTP);
        final ListenerEndpoint endpoint = listenFuture.get();
        System.out.println("H2 proxy listening on " + endpoint.getAddress());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("H2 proxy shutting down");
            proxy.close(CloseMode.GRACEFUL);
        }));

        return proxy;
    }


    /**
     * Minimal async handler that:
     *  - For CONNECT: returns 200 (no real TCP tunneling here).
     *  - For anything else: 400.
     */
    private static final class SimpleH2ConnectProxyHandler implements AsyncServerExchangeHandler {

        private volatile boolean responseCommitted;

        @Override
        public void handleRequest(
                final HttpRequest request,
                final EntityDetails entityDetails,
                final ResponseChannel responseChannel,
                final HttpContext context) throws HttpException, IOException {

            System.out.println("[proxy-handler] " + request.getMethod() + " " + request.getAuthority());

            if (Method.CONNECT.isSame(request.getMethod())) {
                final ClassicHttpResponse response = new BasicClassicHttpResponse(
                        HttpStatus.SC_OK);
                response.setEntity(null);
                responseChannel.sendResponse(response, null, null);
                responseCommitted = true;
                System.out.println("[proxy-handler] CONNECT established to " + request.getAuthority());
                return;
            }

            final ClassicHttpResponse response = new BasicClassicHttpResponse(
                    HttpStatus.SC_BAD_REQUEST);
            response.setEntity(new StringEntity(
                    "Only CONNECT is supported by this proxy",
                    ContentType.TEXT_PLAIN));
            responseChannel.sendResponse(response, response.getEntity(), null);
            responseCommitted = true;
        }

        @Override
        public void updateCapacity(final CapacityChannel capacityChannel) throws IOException {
            capacityChannel.update(Integer.MAX_VALUE);
        }

        @Override
        public void consume(final java.nio.ByteBuffer src) throws IOException {
            if (src != null) {
                src.position(src.limit());
            }
        }

        @Override
        public void streamEnd(final List<? extends Header> trailers) {
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public void produce(final DataStreamChannel channel) throws IOException {
            channel.endStream();
        }

        @Override
        public void failed(final Exception cause) {
            if (!responseCommitted) {
                System.out.println("[proxy-handler] failed: " + cause);
                cause.printStackTrace(System.out);
            }
        }

        @Override
        public void releaseResources() {
        }
    }

    // =====================================================================
    // H2 client that opens CONNECT tunnel
    // =====================================================================

    private static void runH2ConnectClient(
            final String proxyHostName,
            final int proxyPort,
            final String targetHostName,
            final int targetPort) throws Exception {

        System.out.println("Proxy  : " + proxyHostName + ":" + proxyPort);
        System.out.println("Target : " + targetHostName + ":" + targetPort);

        final IOReactorConfig reactorConfig = IOReactorConfig.custom()
                .setSoTimeout(15, TimeUnit.SECONDS)
                .setTcpNoDelay(true)
                .build();

        final H2AsyncRequester requester = H2RequesterBootstrap.bootstrap()
                .setIOReactorConfig(reactorConfig)
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                .setExceptionCallback(ex -> {
                    System.out.println("[client-io] " + ex);
                    ex.printStackTrace(System.out);
                })
                .create();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("HTTP/2 requester shutting down");
            requester.close(CloseMode.GRACEFUL);
        }));

        requester.start();

        final HttpHost proxyHost = new HttpHost(URIScheme.HTTP.id, proxyHostName, proxyPort);

        final Future<IOSession> connectFuture = requester.connect(
                proxyHost,
                new InetSocketAddress(proxyHostName, proxyPort),
                null,
                Timeout.ofSeconds(10),
                null,
                null);

        final IOSession ioSession = connectFuture.get(15, TimeUnit.SECONDS);

        final HttpHost targetHost = new HttpHost(URIScheme.HTTPS.id, targetHostName, targetPort);

        final CountDownLatch tunnelLatch = new CountDownLatch(1);

        System.out.println("Requesting HTTP/2 CONNECT tunnel to " + targetHost);

        final H2TunnelCommand tunnelCommand = new H2TunnelCommand(
                targetHost,
                Timeout.ofSeconds(10),
                new FutureCallback<ProtocolIOSession>() {
                    @Override
                    public void completed(final ProtocolIOSession tunnelSession) {
                        try {
                            System.out.println("Tunnel established to " + targetHost
                                    + ", tunnel session id=" + tunnelSession.getId());
                            System.out.println("Tunnel TLS details: " + tunnelSession.getTlsDetails());
                            tunnelSession.close(CloseMode.GRACEFUL);
                        } catch (final Exception ex) {
                            ex.printStackTrace(System.out);
                        } finally {
                            tunnelLatch.countDown();
                        }
                    }

                    @Override
                    public void failed(final Exception ex) {
                        try {
                            System.out.println("Tunnel establishment failed: " + ex);
                            ex.printStackTrace(System.out);
                        } finally {
                            tunnelLatch.countDown();
                        }
                    }

                    @Override
                    public void cancelled() {
                        System.out.println("Tunnel establishment cancelled");
                        tunnelLatch.countDown();
                    }
                });

        if (!(ioSession instanceof ProtocolIOSession)) {
            throw new IllegalStateException("Expected ProtocolIOSession but got: " + ioSession.getClass());
        }

        final ProtocolIOSession protocolSession = (ProtocolIOSession) ioSession;
        protocolSession.enqueue(tunnelCommand, Command.Priority.NORMAL);

        tunnelLatch.await(30, TimeUnit.SECONDS);

        requester.close(CloseMode.GRACEFUL);
    }

}
