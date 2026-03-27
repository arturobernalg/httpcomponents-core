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
package org.apache.hc.core5.jaxrs.examples;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.protocol.HttpDateGenerator;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.jaxrs.JaxrsServerBootstrap;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.hc.core5.util.TimeValue;

/**
 * Example of an asynchronous embedded HTTP server using JAX-RS style
 * annotations for request routing. This example implements a simple
 * in-memory product catalog with full CRUD operations.
 *
 * <pre>
 * # List all products
 * curl <a href="http://localhost:8080/products">...</a>
 *
 * # Create a product
 * curl -X POST -H "Content-Type: application/json" \
 *      -d '{"name":"Widget","price":9.99}' \
 *      http://localhost:8080/products
 *
 * # Get a single product
 * curl http://localhost:8080/products/1
 *
 * # Update a product
 * curl -X PUT -H "Content-Type: application/json" \
 *      -d '{"name":"Widget Pro","price":19.99}' \
 *      http://localhost:8080/products/1
 *
 * # Delete a product
 * curl -X DELETE http://localhost:8080/products/1
 *
 * # Search by name
 * curl "http://localhost:8080/products?name=Widget"
 *
 * # Health check (plain text)
 * curl http://localhost:8080/health
 * </pre>
 */
public class JaxrsServerExample {

    // --- Domain model ---

    public static class Product {
        public int id;
        public String name;
        public double price;

        public Product() {
        }

        public Product(final int id, final String name,
                        final double price) {
            this.id = id;
            this.name = name;
            this.price = price;
        }
    }

    // --- Resources ---

    @Path("/products")
    @Produces(MediaType.APPLICATION_JSON)
    public static class ProductResource {

        private final Map<Integer, Product> store =
                new ConcurrentHashMap<>();
        private final AtomicInteger sequence =
                new AtomicInteger();

        @GET
        public Product[] list(
                @QueryParam("name") final String name) {
            if (name != null) {
                return store.values().stream()
                        .filter(p -> p.name.toLowerCase()
                                .contains(name.toLowerCase()))
                        .toArray(Product[]::new);
            }
            return store.values().toArray(new Product[0]);
        }

        @GET
        @Path("/{id}")
        public Response get(
                @PathParam("id") final int id) {
            final Product p = store.get(id);
            if (p == null) {
                return Response.status(404)
                        .entity("Product not found: " + id)
                        .type(MediaType.TEXT_PLAIN)
                        .build();
            }
            return Response.ok(p).build();
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(final Product product) {
            final int id = sequence.incrementAndGet();
            product.id = id;
            store.put(id, product);
            return Response.created(
                    URI.create("/products/" + id))
                    .entity(product)
                    .build();
        }

        @PUT
        @Path("/{id}")
        @Consumes(MediaType.APPLICATION_JSON)
        public Response update(
                @PathParam("id") final int id,
                final Product product) {
            if (!store.containsKey(id)) {
                return Response.status(404)
                        .entity("Product not found: " + id)
                        .type(MediaType.TEXT_PLAIN)
                        .build();
            }
            product.id = id;
            store.put(id, product);
            return Response.ok(product).build();
        }

        @DELETE
        @Path("/{id}")
        public Response delete(
                @PathParam("id") final int id) {
            if (store.remove(id) == null) {
                return Response.status(404)
                        .entity("Product not found: " + id)
                        .type(MediaType.TEXT_PLAIN)
                        .build();
            }
            return Response.noContent().build();
        }
    }

    @Path("/health")
    public static class HealthResource {

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String check() {
            return "OK";
        }
    }

    // --- Exception mapper ---

    public static class RuntimeExceptionMapper
            implements ExceptionMapper<RuntimeException> {

        @Override
        public Response toResponse(
                final RuntimeException exception) {
            return Response.serverError()
                    .entity(exception.getMessage())
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
    }

    // --- Main ---

    /**
     * Example command line args: {@code 8080}
     */
    public static void main(final String[] args) throws Exception {
        int port = 8080;
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }

        final HttpAsyncServer server =
                JaxrsServerBootstrap.bootstrap()
                        .setIOReactorConfig(IOReactorConfig.custom()
                                .setSoTimeout(15, TimeUnit.SECONDS)
                                .setTcpNoDelay(true)
                                .build())
                        .register(new ProductResource())
                        .register(new HealthResource())
                        .addExceptionMapper(
                                new RuntimeExceptionMapper())
                        .create();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            println("HTTP server shutting down");
            server.close(CloseMode.GRACEFUL);
        }));

        server.start();
        final Future<ListenerEndpoint> future = server.listen(
                new InetSocketAddress(port), URIScheme.HTTP);
        final ListenerEndpoint endpoint = future.get();
        println("Listening on " + endpoint.getAddress());
        server.awaitShutdown(TimeValue.MAX_VALUE);
    }

    static void println(final String msg) {
        System.out.println(
                HttpDateGenerator.INSTANCE.getCurrentDate()
                        + " | " + msg);
    }

}
