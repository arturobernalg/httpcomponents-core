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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;

/**
 * Reads and writes {@code String} entities. For reading, this provider
 * always handles {@code String} parameters regardless of media type.
 * For writing, it handles {@code String} values when the target media
 * type is not JSON, producing raw text output.
 *
 * @since 5.5
 */
public final class StringEntityProvider
        implements MessageBodyReader<String>, MessageBodyWriter<String> {

    @Override
    public boolean isReadable(final Class<?> type,
                              final Type genericType,
                              final Annotation[] annotations,
                              final MediaType mediaType) {
        return type == String.class;
    }

    @Override
    public String readFrom(final Class<String> type,
                           final Type genericType,
                           final Annotation[] annotations,
                           final MediaType mediaType,
                           final MultivaluedMap<String, String> httpHeaders,
                           final InputStream entityStream)
            throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final byte[] buf = new byte[4096];
        int n;
        while ((n = entityStream.read(buf)) >= 0) {
            baos.write(buf, 0, n);
        }
        return baos.toString(resolveCharset(mediaType).name());
    }

    @Override
    public boolean isWriteable(final Class<?> type,
                               final Type genericType,
                               final Annotation[] annotations,
                               final MediaType mediaType) {
        return type == String.class && !isJsonType(mediaType);
    }

    @Override
    public long getSize(final String s, final Class<?> type,
                        final Type genericType,
                        final Annotation[] annotations,
                        final MediaType mediaType) {
        return -1;
    }

    @Override
    public void writeTo(final String s, final Class<?> type,
                        final Type genericType,
                        final Annotation[] annotations,
                        final MediaType mediaType,
                        final MultivaluedMap<String, Object> httpHeaders,
                        final OutputStream entityStream)
            throws IOException {
        entityStream.write(s.getBytes(resolveCharset(mediaType)));
    }

    private static Charset resolveCharset(final MediaType mediaType) {
        if (mediaType != null) {
            final String cs =
                    mediaType.getParameters().get("charset");
            if (cs != null) {
                return Charset.forName(cs);
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isJsonType(final MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        final String sub = mediaType.getSubtype();
        return "json".equalsIgnoreCase(sub)
                || sub != null && sub.endsWith("+json");
    }

}
