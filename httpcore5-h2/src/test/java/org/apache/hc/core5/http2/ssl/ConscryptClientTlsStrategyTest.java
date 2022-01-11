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

package org.apache.hc.core5.http2.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.apache.hc.core5.reactor.ssl.SSLSessionVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ConscryptClientTlsStrategyTest {

    @Mock
    private SSLSessionVerifier sslSessionVerifier;

    @BeforeEach
    public void prepareMocks() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void test_valid_tls_strategy_creation() {
        final ConscryptClientTlsStrategy strategy = new ConscryptClientTlsStrategy();
        assertNotNull(strategy);
    }

    @Test
    void test_valid_tls_strategy_creation_with_verifier() {
        final ConscryptClientTlsStrategy strategy = new ConscryptClientTlsStrategy(sslSessionVerifier);
        assertNotNull(strategy);
    }
}