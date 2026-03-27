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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

class ResourceMethodTest {

    @Path("/items")
    @Produces(MediaType.APPLICATION_JSON)
    static class SampleResource {

        @GET
        public String list(
                @QueryParam("page") @DefaultValue("1")
                final int page) {
            return "page=" + page;
        }

        @GET
        @Path("/{id}")
        public String get(@PathParam("id") final String id) {
            return "id=" + id;
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public String create(final String body) {
            return "created";
        }
    }

    @Test
    void testScanFindsAllMethods() {
        final List<ResourceMethod> methods =
                ResourceMethod.scan(new SampleResource());
        assertThat(methods).hasSize(3);
    }

    @Test
    void testScanResolvesHttpMethods() {
        final List<ResourceMethod> methods =
                ResourceMethod.scan(new SampleResource());
        assertThat(methods)
                .extracting(ResourceMethod::getHttpMethod)
                .containsExactlyInAnyOrder("GET", "GET", "POST");
    }

    @Test
    void testScanCombinesPaths() {
        final List<ResourceMethod> methods =
                ResourceMethod.scan(new SampleResource());
        assertThat(methods)
                .extracting(rm -> rm.getUriTemplate().getTemplate())
                .containsExactlyInAnyOrder(
                        "/items", "/items/{id}", "/items");
    }

    @Test
    void testScanResolvesParameters() {
        final List<ResourceMethod> methods =
                ResourceMethod.scan(new SampleResource());
        final ResourceMethod listMethod = methods.stream()
                .filter(rm -> rm.getMethod().getName().equals("list"))
                .findFirst().orElseThrow(AssertionError::new);
        final ResourceMethod.ParamInfo[] params =
                listMethod.getParameters();
        assertThat(params).hasSize(1);
        assertThat(params[0].source)
                .isEqualTo(ResourceMethod.ParamSource.QUERY);
        assertThat(params[0].name).isEqualTo("page");
        assertThat(params[0].type).isEqualTo(int.class);
        assertThat(params[0].defaultValue).isEqualTo("1");
    }

    @Test
    void testScanResolvesBodyParameter() {
        final List<ResourceMethod> methods =
                ResourceMethod.scan(new SampleResource());
        final ResourceMethod createMethod = methods.stream()
                .filter(rm -> rm.getMethod().getName().equals("create"))
                .findFirst().orElseThrow(AssertionError::new);
        final ResourceMethod.ParamInfo[] params =
                createMethod.getParameters();
        assertThat(params).hasSize(1);
        assertThat(params[0].source)
                .isEqualTo(ResourceMethod.ParamSource.BODY);
    }

    @Test
    void testScanInheritsClassProduces() {
        final List<ResourceMethod> methods =
                ResourceMethod.scan(new SampleResource());
        for (final ResourceMethod rm : methods) {
            assertThat(rm.getProduces())
                    .contains(MediaType.APPLICATION_JSON);
        }
    }

    // --- Scan-time validation ---

    @Path("/bad")
    static class TwoBodyParams {
        @POST
        public String bad(final String a, final String b) {
            return "nope";
        }
    }

    @Test
    void testRejectsMultipleBodyParams() {
        assertThatThrownBy(
                () -> ResourceMethod.scan(new TwoBodyParams()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body parameters");
    }

    @Path("/bad2")
    static class MismatchedPathParam {
        @GET
        @Path("/{id}")
        public String get(
                @PathParam("wrong") final String x) {
            return "nope";
        }
    }

    @Test
    void testRejectsMismatchedPathParam() {
        assertThatThrownBy(
                () -> ResourceMethod.scan(
                        new MismatchedPathParam()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wrong")
                .hasMessageContaining("{wrong}");
    }

}
