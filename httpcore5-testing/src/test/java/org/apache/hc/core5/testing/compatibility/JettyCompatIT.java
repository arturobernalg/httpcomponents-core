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

package org.apache.hc.core5.testing.compatibility;

import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.testing.compatibility.classic.ClassicHttpCompatTest;
import org.apache.hc.core5.testing.compatibility.nio.AsyncHttp1CompatTest;
import org.apache.hc.core5.testing.compatibility.nio.AsyncHttp2CompatNoPushTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JettyCompatIT {

    private static Network NETWORK = Network.newNetwork();
    @Container
    static final GenericContainer<?> JETTY_CONTAINER = ContainerImages.jetty(NETWORK);

    @AfterAll
    static void cleanup() {
        JETTY_CONTAINER.close();
    }

    static HttpHost targetContainerHost() {
        return new HttpHost(URIScheme.HTTP.id, JETTY_CONTAINER.getHost(), JETTY_CONTAINER.getMappedPort(ContainerImages.HTTP_EXT_PORT));
    }

    static HttpHost targetContainerTLSHost() {
        return new HttpHost(URIScheme.HTTPS.id, JETTY_CONTAINER.getHost(), JETTY_CONTAINER.getMappedPort(ContainerImages.HTTPS_EXT_PORT));
    }

    @Nested
    @DisplayName("Classic, HTTP/1, plain")
    class ClassicHttp1 extends ClassicHttpCompatTest {

        public ClassicHttp1() throws Exception {
            super(targetContainerHost());
        }

    }

    @Nested
    @DisplayName("Async, HTTP/1, plain")
    class AsyncHttp1 extends AsyncHttp1CompatTest {

        public AsyncHttp1() throws Exception {
            super(targetContainerHost());
        }

    }

    @Nested
    @DisplayName("Async, HTTP/2, TLS")
    class AsyncHttp2Tls extends AsyncHttp2CompatNoPushTest {

        public AsyncHttp2Tls() throws Exception {
            super(targetContainerTLSHost(), HttpVersionPolicy.FORCE_HTTP_2);
        }

    }

}
