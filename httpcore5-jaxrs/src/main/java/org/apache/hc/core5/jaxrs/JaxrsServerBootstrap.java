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
package org.apache.hc.core5.jaxrs;

import java.util.ArrayList;
import java.util.List;

import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.bootstrap.AsyncServerBootstrap;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.impl.routing.RequestRouter;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import org.apache.hc.core5.jaxrs.impl.JaxrsAsyncServerRequestHandler;
import org.apache.hc.core5.jaxrs.impl.ResourceMethod;
import org.apache.hc.core5.jaxrs.impl.provider.ByteArrayEntityProvider;
import org.apache.hc.core5.jaxrs.impl.provider.JacksonJsonProvider;
import org.apache.hc.core5.jaxrs.impl.provider.StringEntityProvider;
import org.apache.hc.core5.reactor.IOReactorConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bootstrap for creating an {@link HttpAsyncServer} that dispatches
 * requests to JAX-RS annotated resource classes. Resource instances
 * are scanned for {@code @Path}, HTTP method, and parameter annotations
 * and bound to URI templates. Request and response bodies are serialized
 * through pluggable {@link MessageBodyReader} and {@link MessageBodyWriter}
 * providers; Jackson is registered by default.
 *
 * <pre>
 * HttpAsyncServer server = JaxrsServerBootstrap.bootstrap()
 *         .register(new WidgetResource())
 *         .create();
 * server.start();
 * server.listen(new InetSocketAddress(8080), URIScheme.HTTP);
 * </pre>
 *
 * @since 5.5
 */
public final class JaxrsServerBootstrap {

    private final List<Object> resources;
    private final List<ExceptionMapper<?>> exceptionMappers;
    private final List<Object> providers;
    private ObjectMapper objectMapper;
    private IOReactorConfig ioReactorConfig;
    private Http1Config http1Config;
    private Callback<Exception> exceptionCallback;

    private JaxrsServerBootstrap() {
        this.resources = new ArrayList<>();
        this.exceptionMappers = new ArrayList<>();
        this.providers = new ArrayList<>();
    }

    /**
     * Creates a new bootstrap instance.
     */
    public static JaxrsServerBootstrap bootstrap() {
        return new JaxrsServerBootstrap();
    }

    /**
     * Registers a JAX-RS annotated resource instance. The instance is
     * scanned for annotated methods and each method is bound to its
     * URI template and HTTP method.
     *
     * @param resource the resource instance.
     * @return this bootstrap for chaining.
     */
    public JaxrsServerBootstrap register(final Object resource) {
        resources.add(resource);
        return this;
    }

    /**
     * Registers a {@link MessageBodyReader} or {@link MessageBodyWriter}
     * provider for custom entity serialization. User-registered providers
     * take precedence over the built-in providers.
     *
     * @param provider the provider instance.
     * @return this bootstrap for chaining.
     * @since 5.5
     */
    public JaxrsServerBootstrap registerProvider(
            final Object provider) {
        providers.add(provider);
        return this;
    }

    /**
     * Registers an exception mapper that converts exceptions thrown by
     * resource methods into HTTP responses.
     *
     * @param mapper the exception mapper.
     * @return this bootstrap for chaining.
     */
    public JaxrsServerBootstrap addExceptionMapper(
            final ExceptionMapper<?> mapper) {
        exceptionMappers.add(mapper);
        return this;
    }

    /**
     * Sets the Jackson {@link ObjectMapper} for JSON serialization. If
     * not set, a default instance is created.
     *
     * @param objectMapper the object mapper.
     * @return this bootstrap for chaining.
     */
    public JaxrsServerBootstrap setObjectMapper(
            final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    /**
     * Sets the I/O reactor configuration.
     *
     * @param ioReactorConfig the reactor config.
     * @return this bootstrap for chaining.
     */
    public JaxrsServerBootstrap setIOReactorConfig(
            final IOReactorConfig ioReactorConfig) {
        this.ioReactorConfig = ioReactorConfig;
        return this;
    }

    /**
     * Sets the HTTP/1.1 protocol configuration.
     *
     * @param http1Config the protocol config.
     * @return this bootstrap for chaining.
     */
    public JaxrsServerBootstrap setHttp1Config(
            final Http1Config http1Config) {
        this.http1Config = http1Config;
        return this;
    }

    /**
     * Sets the callback for unrecoverable I/O exceptions.
     *
     * @param exceptionCallback the exception callback.
     * @return this bootstrap for chaining.
     */
    public JaxrsServerBootstrap setExceptionCallback(
            final Callback<Exception> exceptionCallback) {
        this.exceptionCallback = exceptionCallback;
        return this;
    }

    /**
     * Scans all registered resources, builds the request routing table
     * and creates the async HTTP server.
     *
     * @return the configured server, ready to be started.
     */
    public HttpAsyncServer create() {
        final List<ResourceMethod> methods = new ArrayList<>();
        for (final Object resource : resources) {
            methods.addAll(ResourceMethod.scan(resource));
        }
        if (methods.isEmpty()) {
            throw new IllegalStateException(
                    "No JAX-RS resource methods found");
        }
        ResourceMethod.validateNoDuplicateRoutes(methods);

        final ObjectMapper mapper = objectMapper != null
                ? objectMapper : new ObjectMapper();

        // Build provider lists: user-registered first, then defaults
        final List<MessageBodyReader<?>> readers = new ArrayList<>();
        final List<MessageBodyWriter<?>> writers = new ArrayList<>();
        for (final Object p : providers) {
            if (p instanceof MessageBodyReader) {
                readers.add((MessageBodyReader<?>) p);
            }
            if (p instanceof MessageBodyWriter) {
                writers.add((MessageBodyWriter<?>) p);
            }
        }
        final StringEntityProvider stringProvider =
                new StringEntityProvider();
        final ByteArrayEntityProvider byteArrayProvider =
                new ByteArrayEntityProvider();
        final JacksonJsonProvider jacksonProvider =
                new JacksonJsonProvider(mapper);
        readers.add(stringProvider);
        readers.add(byteArrayProvider);
        readers.add(jacksonProvider);
        writers.add(stringProvider);
        writers.add(byteArrayProvider);
        writers.add(jacksonProvider);

        final JaxrsAsyncServerRequestHandler handler =
                new JaxrsAsyncServerRequestHandler(
                        methods, readers, writers,
                        exceptionMappers);

        final AsyncServerBootstrap bootstrap =
                AsyncServerBootstrap.bootstrap();
        if (ioReactorConfig != null) {
            bootstrap.setIOReactorConfig(ioReactorConfig);
        }
        if (http1Config != null) {
            bootstrap.setHttp1Config(http1Config);
        }
        if (exceptionCallback != null) {
            bootstrap.setExceptionCallback(exceptionCallback);
        }
        bootstrap.setCanonicalHostName("localhost");
        bootstrap.setAuthorityResolver(
                RequestRouter.LOCAL_AUTHORITY_RESOLVER);
        bootstrap.register("*", handler);
        return bootstrap.create();
    }

}
