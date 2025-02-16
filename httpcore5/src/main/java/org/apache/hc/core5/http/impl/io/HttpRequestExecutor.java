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

package org.apache.hc.core5.http.impl.io;

import java.io.IOException;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HeaderElements;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.UnsupportedHttpVersionException;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.impl.Http1StreamListener;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.Closer;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLException;

/**
 * {@code HttpRequestExecutor} is a client side HTTP protocol handler based
 * on the blocking (classic) I/O model.
 * <p>
 * {@code HttpRequestExecutor} relies on {@link HttpProcessor} to generate
 * mandatory protocol headers for all outgoing messages and apply common,
 * cross-cutting message transformations to all incoming and outgoing messages.
 * Application specific processing can be implemented outside
 * {@code HttpRequestExecutor} once the request has been executed and
 * a response has been received.
 *
 * @since 4.0
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class HttpRequestExecutor {

    public static final Timeout DEFAULT_WAIT_FOR_CONTINUE = Timeout.ofSeconds(3);

    private final Http1Config http1Config;
    private final ConnectionReuseStrategy connReuseStrategy;
    private final Http1StreamListener streamListener;

    /**
     * Creates new instance of HttpRequestExecutor.
     *
     * @since 5.3
     */
    public HttpRequestExecutor(
            final Http1Config http1Config,
            final ConnectionReuseStrategy connReuseStrategy,
            final Http1StreamListener streamListener) {
        super();
        this.http1Config = http1Config != null ? http1Config : Http1Config.DEFAULT;
        this.connReuseStrategy = connReuseStrategy != null ? connReuseStrategy : DefaultConnectionReuseStrategy.INSTANCE;
        this.streamListener = streamListener;
    }

    /**
     * @deprecated Use {@link #HttpRequestExecutor(Http1Config, ConnectionReuseStrategy, Http1StreamListener)}
     */
    @Deprecated
    public HttpRequestExecutor(
            final Timeout waitForContinue,
            final ConnectionReuseStrategy connReuseStrategy,
            final Http1StreamListener streamListener) {
        this(Http1Config.custom()
                .setWaitForContinueTimeout(waitForContinue)
                .build(),
                connReuseStrategy,
                streamListener);
    }

    public HttpRequestExecutor(final ConnectionReuseStrategy connReuseStrategy) {
        this(Http1Config.DEFAULT, connReuseStrategy, null);
    }

    public HttpRequestExecutor() {
        this(Http1Config.DEFAULT, null, null);
    }

    /**
     * Sends the request and obtain a response.
     *
     * @param request   the request to execute.
     * @param conn      the connection over which to execute the request.
     * @param informationCallback   callback to execute upon receipt of information status (1xx).
     *                              May be null.
     * @param localContext the context
     * @return  the response to the request.
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public ClassicHttpResponse execute(
            final ClassicHttpRequest request,
            final HttpClientConnection conn,
            final HttpResponseInformationCallback informationCallback,
            final HttpContext localContext) throws IOException, HttpException {
        Args.notNull(request, "HTTP request");
        Args.notNull(conn, "Client connection");
        Args.notNull(localContext, "HTTP context");
        final HttpCoreContext context = HttpCoreContext.castOrCreate(localContext);
        try {
            context.setSSLSession(conn.getSSLSession());
            context.setEndpointDetails(conn.getEndpointDetails());

            conn.sendRequestHeader(request);
            if (streamListener != null) {
                streamListener.onRequestHead(conn, request);
            }
            boolean expectContinue = false;
            final HttpEntity entity = request.getEntity();
            if (entity != null) {
                final Header expect = request.getFirstHeader(HttpHeaders.EXPECT);
                expectContinue = expect != null && HeaderElements.CONTINUE.equalsIgnoreCase(expect.getValue());
                if (!expectContinue) {
                    conn.sendRequestEntity(request);
                }
            }
            conn.flush();
            ClassicHttpResponse response = null;
            while (response == null) {
                if (expectContinue) {
                    final Timeout timeout = http1Config.getWaitForContinueTimeout() != null ? http1Config.getWaitForContinueTimeout() : DEFAULT_WAIT_FOR_CONTINUE;
                    if (conn.isDataAvailable(timeout)) {
                        response = conn.receiveResponseHeader();
                        if (streamListener != null) {
                            streamListener.onResponseHead(conn, response);
                        }
                        final int status = response.getCode();
                        if (status == HttpStatus.SC_CONTINUE) {
                            // discard 100-continue
                            response = null;
                            conn.sendRequestEntity(request);
                        } else if (status < HttpStatus.SC_SUCCESS) {
                            if (informationCallback != null) {
                                informationCallback.execute(response, conn, context);
                            }
                            response = null;
                            continue;
                        } else if (status >= HttpStatus.SC_CLIENT_ERROR) {
                            conn.terminateRequest(request);
                        } else {
                            conn.sendRequestEntity(request);
                        }
                    } else {
                        conn.sendRequestEntity(request);
                    }
                    conn.flush();
                    expectContinue = false;
                } else {
                    response = conn.receiveResponseHeader();
                    if (streamListener != null) {
                        streamListener.onResponseHead(conn, response);
                    }
                    final int status = response.getCode();
                    if (status < HttpStatus.SC_INFORMATIONAL) {
                        throw new ProtocolException("Invalid response: " + new StatusLine(response));
                    }
                    if (status < HttpStatus.SC_SUCCESS) {
                        if (informationCallback != null && status != HttpStatus.SC_CONTINUE) {
                            informationCallback.execute(response, conn, context);
                        }
                        response = null;
                    }
                }
            }
            if (MessageSupport.canResponseHaveBody(request.getMethod(), response)) {
                conn.receiveResponseEntity(response);
            }
            return response;

        } catch (final HttpException | SSLException ex) {
            Closer.closeQuietly(conn);
            throw ex;
        } catch (final IOException | RuntimeException ex) {
            Closer.close(conn, CloseMode.IMMEDIATE);
            throw ex;
        }
    }

    /**
     * Sends the request and obtain a response.
     *
     * @param request   the request to execute.
     * @param conn      the connection over which to execute the request.
     * @param context the context
     * @return  the response to the request.
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public ClassicHttpResponse execute(
            final ClassicHttpRequest request,
            final HttpClientConnection conn,
            final HttpContext context) throws IOException, HttpException {
        return execute(request, conn, null, context);
    }

    /**
     * Pre-process the given request using the given protocol processor and
     * initiates the process of request execution.
     *
     * @param request   the request to prepare
     * @param processor the processor to use
     * @param localContext the context for sending the request
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public void preProcess(
            final ClassicHttpRequest request,
            final HttpProcessor processor,
            final HttpContext localContext) throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        Args.notNull(processor, "HTTP processor");
        Args.notNull(localContext, "HTTP context");
        final ProtocolVersion transportVersion = request.getVersion();
        if (transportVersion != null && !transportVersion.lessEquals(http1Config.getVersion())) {
            throw new UnsupportedHttpVersionException(transportVersion);
        }
        final HttpCoreContext context = HttpCoreContext.cast(localContext);
        context.setProtocolVersion(transportVersion != null ? transportVersion : http1Config.getVersion());
        context.setRequest(request);
        processor.process(request, request.getEntity(), context);
    }

    /**
     * Post-processes the given response using the given protocol processor and
     * completes the process of request execution.
     * <p>
     * This method does <i>not</i> read the response entity, if any.
     * The connection over which content of the response entity is being
     * streamed from cannot be reused until the response entity has been
     * fully consumed.
     *
     * @param response  the response object to post-process
     * @param processor the processor to use
     * @param localContext the context for post-processing the response
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public void postProcess(
            final ClassicHttpResponse response,
            final HttpProcessor processor,
            final HttpContext localContext) throws HttpException, IOException {
        Args.notNull(response, "HTTP response");
        Args.notNull(processor, "HTTP processor");
        Args.notNull(localContext, "HTTP context");
        final HttpCoreContext context = HttpCoreContext.cast(localContext);
        final ProtocolVersion transportVersion = response.getVersion();
        if (transportVersion != null) {
            if (transportVersion.greaterEquals(HttpVersion.HTTP_2)) {
                throw new UnsupportedHttpVersionException(transportVersion);
            }
            context.setProtocolVersion(transportVersion);
        }
        context.setResponse(response);
        processor.process(response, response.getEntity(), context);
    }

    /**
     * Determines whether the connection can be kept alive and is safe to be re-used for subsequent message exchanges.
     *
     * @param request current request object.
     * @param response  current response object.
     * @param connection actual connection.
     * @param context current context.
     * @return {@code true} is the connection can be kept-alive and re-used.
     * @throws IOException in case of an I/O error.
     */
    public boolean keepAlive(
            final ClassicHttpRequest request,
            final ClassicHttpResponse response,
            final HttpClientConnection connection,
            final HttpContext context) throws IOException {
        Args.notNull(connection, "HTTP connection");
        Args.notNull(request, "HTTP request");
        Args.notNull(response, "HTTP response");
        Args.notNull(context, "HTTP context");
        final boolean keepAlive = connection.isConsistent() && connReuseStrategy.keepAlive(request, response, context);
        if (streamListener != null) {
            streamListener.onExchangeComplete(connection, keepAlive);
        }
        return keepAlive;
    }

    /**
     * Create a new {@link Builder}.
     *
     * @since 5.2
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link HttpRequestExecutor}.
     *
     * @since 5.2
     */
    public static final class Builder {

        private Timeout waitForContinue;
        private ConnectionReuseStrategy connReuseStrategy;
        private Http1StreamListener streamListener;

        private Builder() {
        }

        public Builder withWaitForContinue(final Timeout waitForContinue) {
            this.waitForContinue = waitForContinue;
            return this;
        }

        public Builder withConnectionReuseStrategy(final ConnectionReuseStrategy connReuseStrategy) {
            this.connReuseStrategy = connReuseStrategy;
            return this;
        }

        public Builder withHttp1StreamListener(final Http1StreamListener streamListener) {
            this.streamListener = streamListener;
            return this;
        }
        /**
         * Create a new HTTP Request Executor.
         *
         * @since 5.2
         */
        public HttpRequestExecutor build() {
            return new HttpRequestExecutor(
                    waitForContinue,
                    connReuseStrategy,
                    streamListener);
        }
    }

}
