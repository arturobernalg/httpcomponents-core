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
package org.apache.hc.core5.jaxrs.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.core5.http.HttpHeaders;

/**
 * Defines an HTTP response with status code, headers and an optional
 * entity body. Instances are created through the static factory methods
 * and the {@link ResponseBuilder} fluent API.
 *
 * @since 5.5
 */
public final class Response {

    private final int status;
    private final Object entity;
    private final Map<String, List<String>> headers;

    Response(final int status, final Object entity, final Map<String, List<String>> headers) {
        this.status = status;
        this.entity = entity;
        this.headers = headers;
    }

    /**
     * Returns the HTTP status code.
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the response entity or {@code null}.
     */
    public Object getEntity() {
        return entity;
    }

    /**
     * Returns the response headers as an unmodifiable map.
     */
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    /**
     * Creates a builder with the given status code.
     */
    public static ResponseBuilder status(final int status) {
        return new ResponseBuilder(status);
    }

    /**
     * Creates a builder for a 200 OK response.
     */
    public static ResponseBuilder ok() {
        return status(200);
    }

    /**
     * Creates a builder for a 200 OK response with the given entity.
     */
    public static ResponseBuilder ok(final Object entity) {
        return ok().entity(entity);
    }

    /**
     * Creates a builder for a 201 Created response with the given location.
     */
    public static ResponseBuilder created(final String location) {
        return status(201).header("Location", location);
    }

    /**
     * Creates a builder for a 202 Accepted response.
     */
    public static ResponseBuilder accepted() {
        return status(202);
    }

    /**
     * Creates a builder for a 204 No Content response.
     */
    public static ResponseBuilder noContent() {
        return status(204);
    }

    /**
     * Creates a builder for a 400 Bad Request response.
     */
    public static ResponseBuilder badRequest() {
        return status(400);
    }

    /**
     * Creates a builder for a 404 Not Found response.
     */
    public static ResponseBuilder notFound() {
        return status(404);
    }

    /**
     * Creates a builder for a 500 Internal Server Error response.
     */
    public static ResponseBuilder serverError() {
        return status(500);
    }

    /**
     * Fluent builder for {@link Response} instances.
     *
     * @since 5.5
     */
    public static final class ResponseBuilder {

        private final int status;
        private Object entity;
        private final Map<String, List<String>> headers;

        ResponseBuilder(final int status) {
            this.status = status;
            this.headers = new LinkedHashMap<>();
        }

        /**
         * Sets the response entity body.
         */
        public ResponseBuilder entity(final Object entity) {
            this.entity = entity;
            return this;
        }

        /**
         * Adds a response header. Multiple values for the same name are allowed.
         */
        public ResponseBuilder header(final String name, final String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            return this;
        }

        /**
         * Sets the Content-Type header.
         */
        public ResponseBuilder type(final String mediaType) {
            final List<String> values = new ArrayList<>();
            values.add(mediaType);
            headers.put(HttpHeaders.CONTENT_TYPE, values);
            return this;
        }

        /**
         * Builds the {@link Response} instance.
         */
        public Response build() {
            final Map<String, List<String>> copy =
                    new LinkedHashMap<>();
            for (final Map.Entry<String, List<String>> entry
                    : headers.entrySet()) {
                copy.put(entry.getKey(),
                        Collections.unmodifiableList(
                                new ArrayList<>(entry.getValue())));
            }
            return new Response(status, entity,
                    Collections.unmodifiableMap(copy));
        }

    }

}
