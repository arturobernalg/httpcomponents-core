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

package org.apache.hc.core5.http2.protocol;

import java.io.IOException;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;

/**
 * This request interceptor is responsible for execution of the protocol conformance
 * checks on incoming or outgoing HTTP/2 request messages.
 *
 * @since 5.4
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class H2RequestConformance implements HttpRequestInterceptor {

    public static final H2RequestConformance INSTANCE = new H2RequestConformance();

    private final String[] illegalHeaderNames;

    @Internal
    public H2RequestConformance(final String... illegalHeaderNames) {
        super();
        this.illegalHeaderNames = illegalHeaderNames;
    }

    public H2RequestConformance() {
        this(
                HttpHeaders.CONNECTION,
                HttpHeaders.KEEP_ALIVE,
                HttpHeaders.PROXY_CONNECTION,
                HttpHeaders.TRANSFER_ENCODING,
                HttpHeaders.UPGRADE,
                HttpHeaders.TE);
    }

    @Override
    public void process(final HttpRequest request, final EntityDetails entity, final HttpContext localContext)
            throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        for (int i = 0; i < illegalHeaderNames.length; i++) {
            final String headerName = illegalHeaderNames[i];
            final Header header = request.getFirstHeader(headerName);
            if (header != null) {
                if (headerName.equalsIgnoreCase(HttpHeaders.TE)) {
                    final String value = header.getValue();
                    if (!"trailers".equalsIgnoreCase(value)) {
                        throw new ProtocolException("Header '%s: %s' is illegal for HTTP/2 messages", HttpHeaders.TE, value);
                    }
                } else {
                    throw new ProtocolException("Header '%s' is illegal for HTTP/2 messages", headerName);
                }
            }
        }
    }

}