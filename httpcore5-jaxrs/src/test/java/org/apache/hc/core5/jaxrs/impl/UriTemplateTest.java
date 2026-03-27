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

import java.util.Map;

import org.junit.jupiter.api.Test;

class UriTemplateTest {

    @Test
    void testExactMatch() {
        final UriTemplate t = new UriTemplate("/widgets");
        final Map<String, String> r = t.match("/widgets");
        assertThat(r).isNotNull().isEmpty();
        assertThat(t.match("/other")).isNull();
    }

    @Test
    void testSingleVariable() {
        final UriTemplate t = new UriTemplate("/widgets/{id}");
        final Map<String, String> r = t.match("/widgets/42");
        assertThat(r).containsEntry("id", "42");
        assertThat(t.match("/widgets/")).isNull();
        assertThat(t.match("/widgets")).isNull();
    }

    @Test
    void testMultipleVariables() {
        final UriTemplate t =
                new UriTemplate("/widgets/{id}/parts/{partId}");
        final Map<String, String> r =
                t.match("/widgets/7/parts/abc");
        assertThat(r)
                .containsEntry("id", "7")
                .containsEntry("partId", "abc");
    }

    @Test
    void testCustomRegex() {
        final UriTemplate t =
                new UriTemplate("/widgets/{id: [0-9]+}");
        assertThat(t.match("/widgets/42"))
                .containsEntry("id", "42");
        assertThat(t.match("/widgets/abc")).isNull();
    }

    @Test
    void testCustomRegexWithInnerGroups() {
        // Inner capturing groups must not break extraction
        final UriTemplate t = new UriTemplate(
                "/items/{x: ([a-z]+|[0-9]+)}/{y}");
        final Map<String, String> r =
                t.match("/items/abc/def");
        assertThat(r)
                .containsEntry("x", "abc")
                .containsEntry("y", "def");
        final Map<String, String> r2 =
                t.match("/items/123/zzz");
        assertThat(r2)
                .containsEntry("x", "123")
                .containsEntry("y", "zzz");
    }

    @Test
    void testCombinePaths() {
        assertThat(UriTemplate.combinePaths("/a", "/b"))
                .isEqualTo("/a/b");
        assertThat(UriTemplate.combinePaths("/a/", "/b"))
                .isEqualTo("/a/b");
        assertThat(UriTemplate.combinePaths("/a", "b"))
                .isEqualTo("/a/b");
        assertThat(UriTemplate.combinePaths("/a", null))
                .isEqualTo("/a");
        assertThat(UriTemplate.combinePaths("", "/b"))
                .isEqualTo("/b");
    }

    @Test
    void testLiteralLength() {
        assertThat(new UriTemplate("/widgets").getLiteralLength())
                .isEqualTo(8);
        assertThat(new UriTemplate("/widgets/{id}").getLiteralLength())
                .isEqualTo(9);
        assertThat(new UriTemplate("/{a}/{b}").getLiteralLength())
                .isEqualTo(2);
    }

    @Test
    void testUrlDecodesMatchedValues() {
        final UriTemplate t = new UriTemplate("/items/{name}");
        final Map<String, String> r =
                t.match("/items/hello%20world");
        assertThat(r).containsEntry("name", "hello world");
    }

}
