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

import java.util.LinkedHashMap;
import java.util.Map;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.Variant;
import javax.ws.rs.ext.RuntimeDelegate;

/**
 * Minimal {@link RuntimeDelegate} that supports the subset of JAX-RS
 * functionality used by this module: building {@link Response} objects
 * and parsing {@link MediaType} headers. This avoids a runtime
 * dependency on a full JAX-RS implementation such as Jersey.
 *
 * @since 5.5
 */
public final class MinimalRuntimeDelegate extends RuntimeDelegate {

    @Override
    public UriBuilder createUriBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new MinimalResponseBuilder();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T createEndpoint(final Application application,
                                final Class<T> endpointType) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeaderDelegate<T> createHeaderDelegate(
            final Class<T> type) {
        if (type == MediaType.class) {
            return (HeaderDelegate<T>) new MediaTypeHeaderDelegate();
        }
        throw new UnsupportedOperationException(
                "No HeaderDelegate for " + type.getName());
    }

    @Override
    public Link.Builder createLinkBuilder() {
        throw new UnsupportedOperationException();
    }

    /**
     * Header delegate that parses and serializes {@link MediaType}
     * values without requiring a full JAX-RS runtime.
     */
    static final class MediaTypeHeaderDelegate
            implements HeaderDelegate<MediaType> {

        @Override
        public MediaType fromString(final String value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "MediaType value is null");
            }
            final String trimmed = value.trim();
            final int semi = trimmed.indexOf(';');
            final String typeSubtype = semi >= 0
                    ? trimmed.substring(0, semi).trim() : trimmed;
            final int slash = typeSubtype.indexOf('/');
            if (slash < 0) {
                return new MediaType(typeSubtype,
                        MediaType.MEDIA_TYPE_WILDCARD);
            }
            final String type =
                    typeSubtype.substring(0, slash).trim();
            final String subtype =
                    typeSubtype.substring(slash + 1).trim();
            if (semi < 0) {
                return new MediaType(type, subtype);
            }
            final Map<String, String> params =
                    new LinkedHashMap<>();
            for (final String param
                    : trimmed.substring(semi + 1).split(";")) {
                final int eq = param.indexOf('=');
                if (eq >= 0) {
                    params.put(
                            param.substring(0, eq).trim()
                                    .toLowerCase(java.util.Locale.ROOT),
                            param.substring(eq + 1).trim());
                }
            }
            return new MediaType(type, subtype, params);
        }

        @Override
        public String toString(final MediaType mediaType) {
            if (mediaType == null) {
                throw new IllegalArgumentException(
                        "MediaType is null");
            }
            final StringBuilder sb = new StringBuilder();
            sb.append(mediaType.getType());
            sb.append('/');
            sb.append(mediaType.getSubtype());
            for (final Map.Entry<String, String> entry
                    : mediaType.getParameters().entrySet()) {
                sb.append(';');
                sb.append(entry.getKey());
                sb.append('=');
                sb.append(entry.getValue());
            }
            return sb.toString();
        }
    }

}
