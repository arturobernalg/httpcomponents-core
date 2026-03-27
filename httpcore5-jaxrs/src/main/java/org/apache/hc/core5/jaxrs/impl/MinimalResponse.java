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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

/**
 * Minimal {@link Response} implementation that stores status, entity
 * and headers. Only the methods needed by the request handler are
 * fully implemented; the rest throw
 * {@link UnsupportedOperationException}.
 *
 * @since 5.5
 */
final class MinimalResponse extends Response {

    private final int status;
    private final Object entity;
    private final MultivaluedMap<String, Object> headers;

    MinimalResponse(final int status, final Object entity,
                    final MultivaluedMap<String, Object> headers) {
        this.status = status;
        this.entity = entity;
        this.headers = new MultivaluedHashMap<>();
        this.headers.putAll(headers);
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public StatusType getStatusInfo() {
        return Status.fromStatusCode(status);
    }

    @Override
    public Object getEntity() {
        return entity;
    }

    @Override
    public <T> T readEntity(final Class<T> entityType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T readEntity(final GenericType<T> entityType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T readEntity(final Class<T> entityType,
                            final Annotation[] annotations) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T readEntity(final GenericType<T> entityType,
                            final Annotation[] annotations) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasEntity() {
        return entity != null;
    }

    @Override
    public boolean bufferEntity() {
        return false;
    }

    @Override
    public void close() {
        // nothing to close
    }

    @Override
    public MediaType getMediaType() {
        final Object ct = headers.getFirst(
                javax.ws.rs.core.HttpHeaders.CONTENT_TYPE);
        if (ct instanceof MediaType) {
            return (MediaType) ct;
        }
        if (ct instanceof String) {
            return MediaType.valueOf((String) ct);
        }
        return null;
    }

    @Override
    public Locale getLanguage() {
        return null;
    }

    @Override
    public int getLength() {
        return -1;
    }

    @Override
    public Set<String> getAllowedMethods() {
        return Collections.emptySet();
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return Collections.emptyMap();
    }

    @Override
    public EntityTag getEntityTag() {
        return null;
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public Date getLastModified() {
        return null;
    }

    @Override
    public URI getLocation() {
        final Object loc = headers.getFirst(
                javax.ws.rs.core.HttpHeaders.LOCATION);
        if (loc instanceof URI) {
            return (URI) loc;
        }
        if (loc instanceof String) {
            return URI.create((String) loc);
        }
        return null;
    }

    @Override
    public Set<Link> getLinks() {
        return Collections.emptySet();
    }

    @Override
    public boolean hasLink(final String relation) {
        return false;
    }

    @Override
    public Link getLink(final String relation) {
        return null;
    }

    @Override
    public Link.Builder getLinkBuilder(final String relation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        return headers;
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        final MultivaluedMap<String, String> result =
                new MultivaluedHashMap<>();
        for (final Map.Entry<String, List<Object>> entry
                : headers.entrySet()) {
            final List<String> values = new ArrayList<>();
            for (final Object v : entry.getValue()) {
                values.add(v != null ? v.toString() : null);
            }
            result.put(entry.getKey(), values);
        }
        return result;
    }

    @Override
    public String getHeaderString(final String name) {
        final List<Object> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (final Object v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (v != null) {
                sb.append(v);
            }
        }
        return sb.toString();
    }

}
