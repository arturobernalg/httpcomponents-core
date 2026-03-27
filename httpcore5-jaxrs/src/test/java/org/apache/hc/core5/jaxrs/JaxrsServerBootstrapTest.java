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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.jaxrs.annotation.Consumes;
import org.apache.hc.core5.jaxrs.annotation.DELETE;
import org.apache.hc.core5.jaxrs.annotation.DefaultValue;
import org.apache.hc.core5.jaxrs.annotation.GET;
import org.apache.hc.core5.jaxrs.annotation.HeaderParam;
import org.apache.hc.core5.jaxrs.annotation.POST;
import org.apache.hc.core5.jaxrs.annotation.Path;
import org.apache.hc.core5.jaxrs.annotation.PathParam;
import org.apache.hc.core5.jaxrs.annotation.Produces;
import org.apache.hc.core5.jaxrs.annotation.QueryParam;
import org.apache.hc.core5.jaxrs.core.MediaType;
import org.apache.hc.core5.jaxrs.core.Response;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class JaxrsServerBootstrapTest {

    static HttpAsyncServer server;
    static int port;

    @Path("/widgets")
    @Produces(MediaType.APPLICATION_JSON)
    public static class WidgetResource {

        @GET
        public Widget[] list(
                @QueryParam("limit") @DefaultValue("10")
                final int limit) {
            final Widget[] result = new Widget[limit];
            for (int i = 0; i < limit; i++) {
                result[i] = new Widget(i, "Widget-" + i);
            }
            return result;
        }

        @GET
        @Path("/{id}")
        public Widget get(@PathParam("id") final int id) {
            return new Widget(id, "Widget-" + id);
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(final Widget widget) {
            return Response.created("/widgets/" + widget.id)
                    .entity(widget)
                    .build();
        }

        @DELETE
        @Path("/{id}")
        public Response delete(
                @PathParam("id") final int id) {
            return Response.noContent().build();
        }
    }

    @Path("/echo")
    public static class EchoResource {

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String echo(
                @QueryParam("msg") final String msg,
                @HeaderParam("X-Tag") final String tag) {
            return (tag != null ? tag + ": " : "") + msg;
        }
    }

    @Path("/multi")
    public static class MultiProducesResource {

        @GET
        @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
        public String multi() {
            return "hello";
        }
    }

    @Path("/textbody")
    public static class TextBodyResource {

        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public String post(final String body) {
            return "echo:" + body;
        }
    }

    @Path("/rawbody")
    public static class RawBodyResource {

        @POST
        @Consumes(MediaType.APPLICATION_OCTET_STREAM)
        @Produces(MediaType.TEXT_PLAIN)
        public String post(final byte[] body) {
            return "len:" + body.length;
        }
    }

    public static class Widget {
        public int id;
        public String name;

        public Widget() {
        }

        public Widget(final int id, final String name) {
            this.id = id;
            this.name = name;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        server = JaxrsServerBootstrap.bootstrap()
                .setIOReactorConfig(IOReactorConfig.custom()
                        .setSoTimeout(5, TimeUnit.SECONDS)
                        .build())
                .setObjectMapper(new ObjectMapper())
                .register(new WidgetResource())
                .register(new EchoResource())
                .register(new MultiProducesResource())
                .register(new TextBodyResource())
                .register(new RawBodyResource())
                .create();
        server.start();
        final ListenerEndpoint endpoint = server.listen(
                new InetSocketAddress(0), URIScheme.HTTP).get();
        port = ((InetSocketAddress)
                endpoint.getAddress()).getPort();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close(CloseMode.IMMEDIATE);
        }
    }

    // --- Happy path ---

    @Test
    void testGetSingleWidget() throws Exception {
        final String body = httpGet("/widgets/42");
        final ObjectMapper mapper = new ObjectMapper();
        final Widget w = mapper.readValue(body, Widget.class);
        assertThat(w.id).isEqualTo(42);
        assertThat(w.name).isEqualTo("Widget-42");
    }

    @Test
    void testGetListWithDefault() throws Exception {
        final String body = httpGet("/widgets");
        final ObjectMapper mapper = new ObjectMapper();
        final Widget[] ws = mapper.readValue(body, Widget[].class);
        assertThat(ws).hasSize(10);
    }

    @Test
    void testGetListWithQueryParam() throws Exception {
        final String body = httpGet("/widgets?limit=3");
        final ObjectMapper mapper = new ObjectMapper();
        final Widget[] ws = mapper.readValue(body, Widget[].class);
        assertThat(ws).hasSize(3);
    }

    @Test
    void testPostWidget() throws Exception {
        final HttpURLConnection conn = open("/widgets");
        conn.setRequestMethod("POST");
        conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("{\"id\":99,\"name\":\"New\"}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertThat(conn.getResponseCode()).isEqualTo(201);
        assertThat(conn.getHeaderField("Location"))
                .isEqualTo("/widgets/99");
        final String body = readBody(conn);
        assertThat(body).contains("\"id\":99");
    }

    @Test
    void testDeleteWidget() throws Exception {
        final HttpURLConnection conn = open("/widgets/1");
        conn.setRequestMethod("DELETE");
        assertThat(conn.getResponseCode()).isEqualTo(204);
    }

    @Test
    void testQueryAndHeaderParams() throws Exception {
        final HttpURLConnection conn = open("/echo?msg=hello");
        conn.setRequestProperty("X-Tag", "test");
        final String body = readBody(conn);
        assertThat(body).isEqualTo("test: hello");
    }

    // --- @Produces(TEXT_PLAIN) returns raw text, not JSON ---

    @Test
    void testPlainTextNotJsonEncoded() throws Exception {
        final String body = httpGet("/echo?msg=hello");
        // Raw text, not a JSON string like "hello"
        assertThat(body).isEqualTo("hello");
        assertThat(body).doesNotStartWith("\"");
    }

    @Test
    void testPlainTextContentTypeHeader() throws Exception {
        final HttpURLConnection conn = open("/echo?msg=x");
        assertThat(conn.getContentType())
                .startsWith("text/plain");
    }

    // --- 404, 405 ---

    @Test
    void testNotFound() throws Exception {
        final HttpURLConnection conn = open("/nonexistent");
        assertThat(conn.getResponseCode()).isEqualTo(404);
    }

    @Test
    void testMethodNotAllowed() throws Exception {
        final HttpURLConnection conn = open("/widgets/1");
        conn.setRequestMethod("PUT");
        assertThat(conn.getResponseCode()).isEqualTo(405);
        assertThat(conn.getHeaderField("Allow")).contains("GET");
        assertThat(conn.getHeaderField("Allow")).contains("DELETE");
    }

    // --- 415 Unsupported Media Type ---

    @Test
    void testUnsupportedMediaType() throws Exception {
        final HttpURLConnection conn = open("/widgets");
        conn.setRequestMethod("POST");
        conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, "text/plain");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("not json".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(conn.getResponseCode()).isEqualTo(415);
    }

    // --- 406 Not Acceptable ---

    @Test
    void testNotAcceptable() throws Exception {
        final HttpURLConnection conn = open("/widgets/1");
        conn.setRequestProperty("Accept", "application/xml");
        assertThat(conn.getResponseCode()).isEqualTo(406);
    }

    @Test
    void testAcceptWildcardOk() throws Exception {
        final HttpURLConnection conn = open("/widgets/1");
        conn.setRequestProperty("Accept", "*/*");
        assertThat(conn.getResponseCode()).isEqualTo(200);
    }

    @Test
    void testAcceptCompatibleOk() throws Exception {
        final HttpURLConnection conn = open("/widgets/1");
        conn.setRequestProperty("Accept", "application/json");
        assertThat(conn.getResponseCode()).isEqualTo(200);
    }

    // --- 400 Bad Request for param conversion ---

    @Test
    void testBadPathParam() throws Exception {
        final HttpURLConnection conn = open("/widgets/abc");
        assertThat(conn.getResponseCode()).isEqualTo(400);
    }

    @Test
    void testBadQueryParam() throws Exception {
        final HttpURLConnection conn = open("/widgets?limit=xyz");
        assertThat(conn.getResponseCode()).isEqualTo(400);
    }

    // --- 400 Bad Request for malformed JSON ---

    @Test
    void testMalformedJson() throws Exception {
        final HttpURLConnection conn = open("/widgets");
        conn.setRequestMethod("POST");
        conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("{bad json".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(conn.getResponseCode()).isEqualTo(400);
    }

    // --- HEAD handling ---

    @Test
    void testHeadReturnsNoBody() throws Exception {
        final HttpURLConnection conn = open("/widgets/1");
        conn.setRequestMethod("HEAD");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType())
                .startsWith("application/json");
        // HEAD must not have a body
        final InputStream is = conn.getInputStream();
        assertThat(is.read()).isEqualTo(-1);
    }

    @Test
    void testHeadOnTextEndpoint() throws Exception {
        final HttpURLConnection conn = open("/echo?msg=hi");
        conn.setRequestMethod("HEAD");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        final InputStream is = conn.getInputStream();
        assertThat(is.read()).isEqualTo(-1);
    }

    // --- URL-decoded path params ---

    @Test
    void testPathParamUrlDecoded() throws Exception {
        // %34%32 == "42" URL-encoded
        final String body = httpGet("/widgets/%34%32");
        final ObjectMapper mapper = new ObjectMapper();
        final Widget w = mapper.readValue(body, Widget.class);
        assertThat(w.id).isEqualTo(42);
    }

    // --- Content type selection with multiple @Produces ---

    @Test
    void testMultiProducesSelectsJson() throws Exception {
        final HttpURLConnection conn = open("/multi");
        conn.setRequestProperty("Accept", "application/json");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType())
                .startsWith("application/json");
    }

    @Test
    void testMultiProducesSelectsTextPlain() throws Exception {
        final HttpURLConnection conn = open("/multi");
        conn.setRequestProperty("Accept", "text/plain");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType())
                .startsWith("text/plain");
        final String body = readBody(conn);
        // text/plain selected so raw string, not JSON-encoded
        assertThat(body).isEqualTo("hello");
    }

    @Test
    void testAcceptQZeroExcludesType() throws Exception {
        final HttpURLConnection conn = open("/multi");
        conn.setRequestProperty("Accept",
                "application/json;q=0, text/plain;q=1");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType())
                .startsWith("text/plain");
    }

    @Test
    void testAcceptQZeroAllExcluded406() throws Exception {
        final HttpURLConnection conn = open("/multi");
        conn.setRequestProperty("Accept",
                "application/json;q=0, text/plain;q=0");
        assertThat(conn.getResponseCode()).isEqualTo(406);
    }

    // --- Text/plain request body ---

    @Test
    void testTextPlainRequestBody() throws Exception {
        final HttpURLConnection conn = open("/textbody");
        conn.setRequestMethod("POST");
        conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, "text/plain");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("raw text".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(conn.getResponseCode()).isEqualTo(200);
        final String body = readBody(conn);
        assertThat(body).isEqualTo("echo:raw text");
    }

    // --- byte[] request body ---

    @Test
    void testOctetStreamRequestBody() throws Exception {
        final HttpURLConnection conn = open("/rawbody");
        conn.setRequestMethod("POST");
        conn.setRequestProperty(HttpHeaders.CONTENT_TYPE,
                "application/octet-stream");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(new byte[]{1, 2, 3, 4, 5});
        }
        assertThat(conn.getResponseCode()).isEqualTo(200);
        final String body = readBody(conn);
        assertThat(body).isEqualTo("len:5");
    }

    // --- Duplicate route rejection ---

    @Path("/dup")
    public static class DuplicateResource1 {
        @GET
        public String get() { return "a"; }
    }

    @Path("/dup")
    public static class DuplicateResource2 {
        @GET
        public String get() { return "b"; }
    }

    @Test
    void testDuplicateRouteRejected() {
        assertThatThrownBy(() ->
                JaxrsServerBootstrap.bootstrap()
                        .register(new DuplicateResource1())
                        .register(new DuplicateResource2())
                        .create())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate route");
    }

    // --- Helpers ---

    private static String httpGet(final String path)
            throws Exception {
        final HttpURLConnection conn = open(path);
        return readBody(conn);
    }

    private static HttpURLConnection open(final String path)
            throws Exception {
        final URL url =
                new URL("http://localhost:" + port + path);
        return (HttpURLConnection) url.openConnection();
    }

    private static String readBody(final HttpURLConnection conn)
            throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

}
