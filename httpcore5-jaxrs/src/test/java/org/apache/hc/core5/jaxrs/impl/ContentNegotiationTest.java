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
package org.apache.hc.core5.jaxrs.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentNegotiationTest {

    private static final String[] JSON_AND_TEXT =
            {"application/json", "text/plain"};

    @Test
    void testSelectsFirstMatchingType() {
        final String selected =
                JaxrsAsyncServerRequestHandler.selectProducesType(
                        "application/json", JSON_AND_TEXT);
        assertThat(selected).isEqualTo("application/json");
    }

    @Test
    void testSelectsSecondType() {
        final String selected =
                JaxrsAsyncServerRequestHandler.selectProducesType(
                        "text/plain", JSON_AND_TEXT);
        assertThat(selected).isEqualTo("text/plain");
    }

    @Test
    void testWildcardSelectsFirst() {
        final String selected =
                JaxrsAsyncServerRequestHandler.selectProducesType(
                        "*/*", JSON_AND_TEXT);
        assertThat(selected).isEqualTo("application/json");
    }

    @Test
    void testNoMatchReturnsNull() {
        final String selected =
                JaxrsAsyncServerRequestHandler.selectProducesType(
                        "application/xml", JSON_AND_TEXT);
        assertThat(selected).isNull();
    }

    @Test
    void testQZeroExcludesType() {
        final String selected =
                JaxrsAsyncServerRequestHandler.selectProducesType(
                        "application/json;q=0, text/plain;q=1",
                        JSON_AND_TEXT);
        assertThat(selected).isEqualTo("text/plain");
    }

    @Test
    void testAllQZeroReturnsNull() {
        final String selected =
                JaxrsAsyncServerRequestHandler.selectProducesType(
                        "application/json;q=0, text/plain;q=0",
                        JSON_AND_TEXT);
        assertThat(selected).isNull();
    }

    @Test
    void testParseQualityDefault() {
        assertThat(JaxrsAsyncServerRequestHandler
                .parseQuality("text/plain"))
                .isEqualTo(1.0f);
    }

    @Test
    void testParseQualityExplicit() {
        assertThat(JaxrsAsyncServerRequestHandler
                .parseQuality("text/plain;q=0.5"))
                .isEqualTo(0.5f);
    }

    @Test
    void testParseQualityZero() {
        assertThat(JaxrsAsyncServerRequestHandler
                .parseQuality("application/json;q=0"))
                .isEqualTo(0f);
    }

}
