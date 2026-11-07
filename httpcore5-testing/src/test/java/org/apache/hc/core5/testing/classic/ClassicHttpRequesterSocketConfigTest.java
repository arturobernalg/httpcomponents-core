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

package org.apache.hc.core5.testing.classic;

import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.bootstrap.HttpRequester;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.RequesterBootstrap;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.impl.io.DefaultBHttpClientConnection;
import org.apache.hc.core5.http.impl.io.DefaultBHttpClientConnectionFactory;
import org.apache.hc.core5.http.io.HttpConnectionFactory;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link SocketConfig} is applied to sockets of {@link HttpRequester}.
 */
class ClassicHttpRequesterSocketConfigTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        this.server = ServerBootstrap.bootstrap()
                .setLocalAddress(InetAddress.getLoopbackAddress())
                .register("*", (request, response, context) -> response.setCode(200))
                .create();
        this.server.start();
    }

    @AfterEach
    void shutDown() {
        if (this.server != null) {
            this.server.close(CloseMode.GRACEFUL);
        }
    }

    @Test
    void testSocketConfigApplied() throws Exception {
        final AtomicReference<Socket> socketRef = new AtomicReference<>();
        final HttpConnectionFactory<DefaultBHttpClientConnection> connectionFactory = socket -> {
            socketRef.set(socket);
            return DefaultBHttpClientConnectionFactory.builder().build().createConnection(socket);
        };
        final HttpRequester requester = RequesterBootstrap.bootstrap()
                .setConnectionFactory(connectionFactory)
                .setSocketConfig(SocketConfig.custom()
                        .setSoLinger(TimeValue.ofSeconds(7))
                        .setTcpNoDelay(false)
                        .setSoKeepAlive(true)
                        .build())
                .create();

        final HttpHost target = new HttpHost("http", InetAddress.getLoopbackAddress().getHostAddress(), server.getLocalPort());
        // The socket is closed once the connection is released, so inspect it within the exchange
        final String actual = requester.execute(
                target,
                new BasicClassicHttpRequest("GET", target, "/"),
                Timeout.ofSeconds(5),
                HttpCoreContext.create(),
                response -> {
                    final Socket socket = socketRef.get();
                    final String options = socket.getSoLinger() + "/" + socket.getTcpNoDelay() + "/" + socket.getKeepAlive();
                    EntityUtils.consume(response.getEntity());
                    return options;
                });
        Assertions.assertEquals("7/false/true", actual);
    }

}
