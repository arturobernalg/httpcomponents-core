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
package org.apache.hc.core5.jaxrs.impl.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads and writes entities as JSON using Jackson. This provider handles
 * all types except {@code String} and {@code byte[]} which are served
 * by dedicated providers.
 *
 * @since 5.5
 */
public final class JacksonJsonProvider
        implements MessageBodyReader<Object>, MessageBodyWriter<Object> {

    private final ObjectMapper objectMapper;

    /**
     * Creates a provider backed by the given mapper.
     *
     * @param objectMapper the Jackson object mapper.
     */
    public JacksonJsonProvider(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isReadable(final Class<?> type,
                              final Type genericType,
                              final Annotation[] annotations,
                              final MediaType mediaType) {
        return type != String.class && type != byte[].class;
    }

    @Override
    public Object readFrom(final Class<Object> type,
                           final Type genericType,
                           final Annotation[] annotations,
                           final MediaType mediaType,
                           final MultivaluedMap<String, String> httpHeaders,
                           final InputStream entityStream)
            throws IOException {
        return objectMapper.readValue(entityStream, type);
    }

    @Override
    public boolean isWriteable(final Class<?> type,
                               final Type genericType,
                               final Annotation[] annotations,
                               final MediaType mediaType) {
        return type != byte[].class
                && (type != String.class || isJsonType(mediaType));
    }

    @Override
    public long getSize(final Object o, final Class<?> type,
                        final Type genericType,
                        final Annotation[] annotations,
                        final MediaType mediaType) {
        return -1;
    }

    @Override
    public void writeTo(final Object o, final Class<?> type,
                        final Type genericType,
                        final Annotation[] annotations,
                        final MediaType mediaType,
                        final MultivaluedMap<String, Object> httpHeaders,
                        final OutputStream entityStream)
            throws IOException {
        objectMapper.writeValue(entityStream, o);
    }

    private static boolean isJsonType(final MediaType mediaType) {
        if (mediaType == null) {
            return true;
        }
        final String sub = mediaType.getSubtype();
        return "json".equalsIgnoreCase(sub)
                || sub != null && sub.endsWith("+json");
    }

}
