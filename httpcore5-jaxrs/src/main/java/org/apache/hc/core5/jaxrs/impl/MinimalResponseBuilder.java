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

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Variant;

/**
 * Minimal {@link Response.ResponseBuilder} supporting the subset of
 * operations used by resource classes in this module: status, entity,
 * header, type and location.
 *
 * @since 5.5
 */
final class MinimalResponseBuilder extends Response.ResponseBuilder {

    private int status;
    private Object entity;
    private final MultivaluedMap<String, Object> headers =
            new MultivaluedHashMap<>();

    @Override
    public Response build() {
        return new MinimalResponse(status, entity, headers);
    }

    @Override
    public Response.ResponseBuilder clone() {
        final MinimalResponseBuilder copy =
                new MinimalResponseBuilder();
        copy.status = this.status;
        copy.entity = this.entity;
        copy.headers.putAll(this.headers);
        return copy;
    }

    @Override
    public Response.ResponseBuilder status(final int status) {
        this.status = status;
        return this;
    }

    @Override
    public Response.ResponseBuilder status(final int status,
                                           final String reasonPhrase) {
        this.status = status;
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(final Object entity) {
        this.entity = entity;
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(
            final Object entity,
            final Annotation[] annotations) {
        this.entity = entity;
        return this;
    }

    @Override
    public Response.ResponseBuilder allow(final String... methods) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder allow(
            final Set<String> methods) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder cacheControl(
            final CacheControl cacheControl) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder encoding(
            final String encoding) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder header(final String name,
                                           final Object value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.add(name, value);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder replaceAll(
            final MultivaluedMap<String, Object> headers) {
        this.headers.clear();
        if (headers != null) {
            this.headers.putAll(headers);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder language(
            final String language) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder language(
            final Locale language) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder type(final MediaType type) {
        if (type == null) {
            headers.remove(HttpHeaders.CONTENT_TYPE);
        } else {
            headers.putSingle(HttpHeaders.CONTENT_TYPE,
                    type.toString());
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder type(final String type) {
        if (type == null) {
            headers.remove(HttpHeaders.CONTENT_TYPE);
        } else {
            headers.putSingle(HttpHeaders.CONTENT_TYPE, type);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder variant(
            final Variant variant) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder variants(
            final Variant... variants) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder variants(
            final List<Variant> variants) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder contentLocation(
            final URI location) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder cookie(
            final NewCookie... cookies) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder expires(final Date expires) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder lastModified(
            final Date lastModified) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder location(final URI location) {
        if (location == null) {
            headers.remove(HttpHeaders.LOCATION);
        } else {
            headers.putSingle(HttpHeaders.LOCATION,
                    location.toASCIIString());
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder tag(final EntityTag tag) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder tag(final String tag) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder link(final URI uri,
                                         final String rel) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder link(final String uri,
                                         final String rel) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Response.ResponseBuilder links(final Link... links) {
        throw new UnsupportedOperationException();
    }

}
