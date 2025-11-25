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
package org.apache.hc.core5.http2.examples;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.annotation.Experimental;
import org.apache.hc.core5.concurrent.DefaultThreadFactory;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.RequestNotExecutedException;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.impl.routing.RequestRouter;
import org.apache.hc.core5.http.io.HttpRequestHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
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
import org.apache.hc.core5.util.Timeout;

/**
 * Example that:
 * - starts a fake cleartext HTTP/2 proxy (h2c)
 * - receives a CONNECT in HTTP/2
 * - answers 200 and lets {@link H2TunnelCommand} do {@code startTls()} on the underlying session
 * <p>
 * The CONNECT/421 part is purely about the proxy behaviour. The actual TLS
 * upgrade is handled in your {@code H2TunnelCommand} / {@code H2StreamProtocolIOSession}.
 */
@Experimental
public final class H2ConnectTunnelExample {

    private H2ConnectTunnelExample() {
    }

    public static void main(final String[] args) throws Exception {
        final int proxyPort = 8443;

        final HttpAsyncServer proxyServer = startFakeH2Proxy(proxyPort);

        try {
            runH2ConnectClient(args, proxyPort);
        } finally {
            System.out.println("Shutting down fake H2 proxy");
            proxyServer.close(CloseMode.GRACEFUL);
        }
    }

    // =====================================================================
    // Fake H2 proxy (server side)
    // =====================================================================

    private static HttpAsyncServer startFakeH2Proxy(final int port) throws Exception {
        final IOReactorConfig config = IOReactorConfig.custom()
                .setSoTimeout(15, TimeUnit.SECONDS)
                .setTcpNoDelay(true)
                .build();

        final ExecutorService executorService = Executors.newFixedThreadPool(
                4,
                new DefaultThreadFactory("fake-h2-proxy-worker", true));

        // Classic blocking handler; we bridge it to async via ClassicToAsyncServerExchangeHandler.
        final HttpRequestHandler requestHandler = new HttpRequestHandler() {
            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws IOException {

                System.out.println("[proxy] " + request.getMethod() + " " + request.getRequestUri());

                // Special case: CONNECT used for tunnelling
                if (Method.CONNECT.isSame(request.getMethod())) {
                    response.setCode(HttpStatus.SC_OK);
                    response.setReasonPhrase("Connection Established");
                    // No entity at all for CONNECT success
                    return;
                }

                // For non-CONNECT requests: tiny dummy response.
                final String body = "Fake H2 proxy here. You sent "
                        + request.getMethod()
                        + " " + request.getRequestUri() + "\n";

                final HttpEntity responseEntity = new StringEntity(
                        body, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));

                response.setCode(HttpStatus.SC_OK);
                response.setEntity(responseEntity);
            }
        };

        // Force every request to be treated as "local authority" to avoid 421
        final RequestRouter<HttpRequestHandler> requestRouter = RequestRouter.<HttpRequestHandler>builder()
                .resolveAuthority((req, ctx) -> RequestRouter.LOCAL_AUTHORITY)
                .addRoute(RequestRouter.LOCAL_AUTHORITY, "*", requestHandler)
                .build();

        final Callback<Exception> exceptionCallback = ex -> {
            // Ignore benign shutdown noise
            if (ex instanceof RequestNotExecutedException || ex instanceof InterruptedIOException) {
                return;
            }
            System.out.println("[proxy-io] " + ex);
            ex.printStackTrace(System.out);
        };


        final HttpAsyncServer server = H2ServerBootstrap.bootstrap()
                .setIOReactorConfig(config)
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                .setStreamListener(new H2StreamListener() {

                    @Override
                    public void onHeaderInput(
                            final HttpConnection connection,
                            final int streamId,
                            final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println("[proxy] " + connection.getRemoteAddress()
                                    + " (" + streamId + ") << " + headers.get(i));
                        }
                    }

                    @Override
                    public void onHeaderOutput(
                            final HttpConnection connection,
                            final int streamId,
                            final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println("[proxy] " + connection.getRemoteAddress()
                                    + " (" + streamId + ") >> " + headers.get(i));
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
                .setRequestRouter((request, context) -> {
                    // Route by authority/path first...
                    final HttpRequestHandler handler = requestRouter.resolve(request, context);
                    // ...then adapt classic handler to async handler
                    return () -> new ClassicToAsyncServerExchangeHandler(
                            executorService,
                            handler,
                            exceptionCallback);
                })
                .setExceptionCallback(exceptionCallback)
                .create();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Fake H2 proxy shutting down");
            server.close(CloseMode.GRACEFUL);
            executorService.shutdownNow();
        }));

        server.start();

        final Future<ListenerEndpoint> future =
                server.listen(new InetSocketAddress(port), URIScheme.HTTP);
        final ListenerEndpoint listenerEndpoint = future.get();
        System.out.println("Fake H2 proxy listening on " + listenerEndpoint.getAddress());

        return server;
    }

    // =====================================================================
    // Client side: HTTP/2 CONNECT tunnel example
    // =====================================================================

    private static void runH2ConnectClient(final String[] args, final int defaultProxyPort) throws Exception {
        final String proxyHostName;
        final int proxyPort;
        final String targetHostName;
        final int targetPort;

        if (args.length >= 4) {
            proxyHostName = args[0];
            proxyPort = Integer.parseInt(args[1]);
            targetHostName = args[2];
            targetPort = Integer.parseInt(args[3]);
        } else {
            proxyHostName = "localhost";
            proxyPort = defaultProxyPort;
            targetHostName = "example.com";
            targetPort = 443;
        }

        System.out.println("Proxy  : " + proxyHostName + ":" + proxyPort);
        System.out.println("Target : " + targetHostName + ":" + targetPort);

        final IOReactorConfig reactorConfig = IOReactorConfig.custom()
                .setSoTimeout(15, TimeUnit.SECONDS)
                .setTcpNoDelay(true)
                .build();

        final H2AsyncRequester requester = H2RequesterBootstrap.bootstrap()
                .setIOReactorConfig(reactorConfig)
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                .setStreamListener(new H2StreamListener() {

                    @Override
                    public void onHeaderInput(
                            final HttpConnection connection,
                            final int streamId,
                            final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println(connection.getRemoteAddress()
                                    + " (" + streamId + ") << " + headers.get(i));
                        }
                    }

                    @Override
                    public void onHeaderOutput(
                            final HttpConnection connection,
                            final int streamId,
                            final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println(connection.getRemoteAddress()
                                    + " (" + streamId + ") >> " + headers.get(i));
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

        // Proxy is cleartext HTTP/2 (h2c)
        final HttpHost proxyHost = new HttpHost(
                URIScheme.HTTP.id,
                proxyHostName,
                proxyPort);

        final Future<IOSession> connectFuture = requester.connect(
                proxyHost,
                new InetSocketAddress(proxyHostName, proxyPort),
                null,
                Timeout.ofSeconds(10),
                null,
                null);

        final IOSession ioSession = connectFuture.get(15, TimeUnit.SECONDS);
        if (!(ioSession instanceof ProtocolIOSession)) {
            throw new IllegalStateException(
                    "Expected ProtocolIOSession but got: " + ioSession.getClass());
        }

        final ProtocolIOSession protocolSession = (ProtocolIOSession) ioSession;

        final HttpHost targetHost = new HttpHost(
                URIScheme.HTTPS.id,
                targetHostName,
                targetPort);

        final CountDownLatch tunnelLatch = new CountDownLatch(1);

        System.out.println("Requesting HTTP/2 CONNECT tunnel to " + targetHost);

        final H2TunnelCommand tunnelCommand = new H2TunnelCommand(
                targetHost,
                Timeout.ofSeconds(10),
                new FutureCallback<ProtocolIOSession>() {

                    @Override
                    public void completed(final ProtocolIOSession tunnelSession) {
                        try {
                            System.out.println("Tunnel established to "
                                    + targetHost + ", tunnel session id="
                                    + tunnelSession.getId());

                            System.out.println("Tunnel TLS details: "
                                    + tunnelSession.getTlsDetails());

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

        protocolSession.enqueue(tunnelCommand, Command.Priority.NORMAL);

        tunnelLatch.await(30, TimeUnit.SECONDS);

        requester.close(CloseMode.GRACEFUL);
    }

}
