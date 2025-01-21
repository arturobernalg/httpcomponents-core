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
package org.apache.hc.core5.net;

import static org.hamcrest.MatcherAssert.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.NameValuePairListMatcher;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class TestURIBuilder {

    private static final String CH_HELLO = "\u0047\u0072\u00FC\u0065\u007A\u0069\u005F\u007A\u00E4\u006D\u00E4";
    private static final String RU_HELLO = "\u0412\u0441\u0435\u043C\u005F\u043F\u0440\u0438\u0432\u0435\u0442";

    static List<String> parsePath(final CharSequence s) {
        return URIBuilder.parsePath(s, null);
    }

    @Test
    void testParseSegments() {
        assertThat(parsePath("/this/that"), CoreMatchers.equalTo(Arrays.asList("this", "that")));
        assertThat(parsePath("this/that"), CoreMatchers.equalTo(Arrays.asList("this", "that")));
        assertThat(parsePath("this//that"), CoreMatchers.equalTo(Arrays.asList("this", "", "that")));
        assertThat(parsePath("this//that/"), CoreMatchers.equalTo(Arrays.asList("this", "", "that", "")));
        assertThat(parsePath("this//that/%2fthis%20and%20that"),
                CoreMatchers.equalTo(Arrays.asList("this", "", "that", "/this and that")));
        assertThat(parsePath("this///that//"),
                CoreMatchers.equalTo(Arrays.asList("this", "", "", "that", "", "")));
        assertThat(parsePath("/"), CoreMatchers.equalTo(Collections.singletonList("")));
        assertThat(parsePath(""), CoreMatchers.equalTo(Collections.<String>emptyList()));
    }

    static String formatPath(final String... pathSegments) {
        final StringBuilder buf = new StringBuilder();
        URIBuilder.formatPath(buf, Arrays.asList(pathSegments), false, null);
        return buf.toString();
    }

    @Test
    void testFormatSegments() {
        assertThat(formatPath("this", "that"), CoreMatchers.equalTo("/this/that"));
        assertThat(formatPath("this", "", "that"), CoreMatchers.equalTo("/this//that"));
        assertThat(formatPath("this", "", "that", "/this and that"),
                CoreMatchers.equalTo("/this//that/%2Fthis%20and%20that"));
        assertThat(formatPath("this", "", "", "that", "", ""),
                CoreMatchers.equalTo("/this///that//"));
        assertThat(formatPath(""), CoreMatchers.equalTo("/"));
        assertThat(formatPath(), CoreMatchers.equalTo(""));
    }

    static List<NameValuePair> parseQuery(final CharSequence s) {
        return URIBuilder.parseQuery(s, null, false);
    }

    @Test
    void testParseQuery() {
        assertThat(parseQuery(""), NameValuePairListMatcher.isEmpty());
        assertThat(parseQuery("Name0"),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name0", null)));
        assertThat(parseQuery("Name1=Value1"),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name1", "Value1")));
        assertThat(parseQuery("Name2="),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name2", "")));
        assertThat(parseQuery(" Name3  "),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name3", null)));
        assertThat(parseQuery("Name4=Value%204%21"),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name4", "Value 4!")));
        assertThat(parseQuery("Name4=Value%2B4%21"),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name4", "Value+4!")));
        assertThat(parseQuery("Name4=Value%204%21%20%214"),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name4", "Value 4! !4")));
        assertThat(parseQuery("Name5=aaa&Name6=bbb"),
                NameValuePairListMatcher.equalsTo(
                        new BasicNameValuePair("Name5", "aaa"),
                        new BasicNameValuePair("Name6", "bbb")));
        assertThat(parseQuery("Name7=aaa&Name7=b%2Cb&Name7=ccc"),
                NameValuePairListMatcher.equalsTo(
                        new BasicNameValuePair("Name7", "aaa"),
                        new BasicNameValuePair("Name7", "b,b"),
                        new BasicNameValuePair("Name7", "ccc")));
        assertThat(parseQuery("Name8=xx%2C%20%20yy%20%20%2Czz"),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("Name8", "xx,  yy  ,zz")));
        assertThat(parseQuery("price=10%20%E2%82%AC"),
                NameValuePairListMatcher.equalsTo(new BasicNameValuePair("price", "10 \u20AC")));
        assertThat(parseQuery("a=b\"c&d=e"),
                NameValuePairListMatcher.equalsTo(
                        new BasicNameValuePair("a", "b\"c"),
                        new BasicNameValuePair("d", "e")));
        assertThat(parseQuery("russian=" + PercentCodec.encode(RU_HELLO, StandardCharsets.UTF_8) +
                        "&swiss=" + PercentCodec.encode(CH_HELLO, StandardCharsets.UTF_8)),
                NameValuePairListMatcher.equalsTo(
                        new BasicNameValuePair("russian", RU_HELLO),
                        new BasicNameValuePair("swiss", CH_HELLO)));
    }

    static String formatQuery(final NameValuePair... params) {
        final StringBuilder buf = new StringBuilder();
        URIBuilder.formatQuery(buf, Arrays.asList(params), null, false);
        return buf.toString();
    }

    @Test
    void testFormatQuery() {
        assertThat(formatQuery(new BasicNameValuePair("Name0", null)), CoreMatchers.equalTo("Name0"));
        assertThat(formatQuery(new BasicNameValuePair("Name1", "Value1")), CoreMatchers.equalTo("Name1=Value1"));
        assertThat(formatQuery(new BasicNameValuePair("Name2", "")), CoreMatchers.equalTo("Name2="));
        assertThat(formatQuery(new BasicNameValuePair("Name4", "Value 4&")),
                CoreMatchers.equalTo("Name4=Value%204%26"));
        assertThat(formatQuery(new BasicNameValuePair("Name4", "Value+4&")),
                CoreMatchers.equalTo("Name4=Value%2B4%26"));
        assertThat(formatQuery(new BasicNameValuePair("Name4", "Value 4& =4")),
                CoreMatchers.equalTo("Name4=Value%204%26%20%3D4"));
        assertThat(formatQuery(
                new BasicNameValuePair("Name5", "aaa"),
                new BasicNameValuePair("Name6", "bbb")), CoreMatchers.equalTo("Name5=aaa&Name6=bbb"));
        assertThat(formatQuery(
                new BasicNameValuePair("Name7", "aaa"),
                new BasicNameValuePair("Name7", "b,b"),
                new BasicNameValuePair("Name7", "ccc")
        ), CoreMatchers.equalTo("Name7=aaa&Name7=b%2Cb&Name7=ccc"));
        assertThat(formatQuery(new BasicNameValuePair("Name8", "xx,  yy  ,zz")),
                CoreMatchers.equalTo("Name8=xx%2C%20%20yy%20%20%2Czz"));
        assertThat(formatQuery(
                new BasicNameValuePair("russian", RU_HELLO),
                new BasicNameValuePair("swiss", CH_HELLO)),
                CoreMatchers.equalTo("russian=" + PercentCodec.encode(RU_HELLO, StandardCharsets.UTF_8) +
                        "&swiss=" + PercentCodec.encode(CH_HELLO, StandardCharsets.UTF_8)));
    }

    @Test
    void testHierarchicalUri() throws Exception {
        final URI uri = new URI("http", "stuff", "localhost", 80, "/some stuff", "param=stuff", "fragment");
        final URIBuilder uribuilder = new URIBuilder(uri);
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://stuff@localhost:80/some%20stuff?param=stuff#fragment"), result);
    }

    @Test
    void testMutationRemoveFragment() throws Exception {
        final URI uri = new URI("http://stuff@localhost:80/stuff?param=stuff#fragment");
        final URI result = new URIBuilder(uri).setFragment(null).build();
        Assertions.assertEquals(new URI("http://stuff@localhost:80/stuff?param=stuff"), result);
    }

    @Test
    void testMutationRemoveUserInfo() throws Exception {
        final URI uri = new URI("http://stuff@localhost:80/stuff?param=stuff#fragment");
        final URI result = new URIBuilder(uri).setUserInfo(null).build();
        Assertions.assertEquals(new URI("http://localhost:80/stuff?param=stuff#fragment"), result);
    }

    @Test
    void testMutationRemovePort() throws Exception {
        final URI uri = new URI("http://stuff@localhost:80/stuff?param=stuff#fragment");
        final URI result = new URIBuilder(uri).setPort(-1).build();
        Assertions.assertEquals(new URI("http://stuff@localhost/stuff?param=stuff#fragment"), result);
    }

    @Test
    void testOpaqueUri() throws Exception {
        final URI uri = new URI("stuff", "some-stuff", "fragment");
        final URIBuilder uribuilder = new URIBuilder(uri);
        final URI result = uribuilder.build();
        Assertions.assertEquals(uri, result);
    }

    @Test
    void testOpaqueUriMutation() throws Exception {
        final URI uri = new URI("stuff", "some-stuff", "fragment");
        final URIBuilder uribuilder = new URIBuilder(uri).setCustomQuery("param1&param2=stuff").setFragment(null);
        Assertions.assertEquals(new URI("stuff:?param1&param2=stuff"), uribuilder.build());
    }

    @Test
    void testHierarchicalUriMutation() throws Exception {
        final URIBuilder uribuilder = new URIBuilder("/").setScheme("http").setHost("localhost").setPort(80).setPath("/stuff");
        Assertions.assertEquals(new URI("http://localhost:80/stuff"), uribuilder.build());
    }

    @Test
    void testLocalhost() throws Exception {
        // Check that the URI generated by URI builder agrees with that generated by using URI directly
        final String scheme = "https";
        final InetAddress host = InetAddress.getLocalHost();
        final String specials = "/abcd!$&*()_-+.,=:;'~@[]?<>|#^%\"{}\\\u00a3`\u00ac\u00a6xyz"; // N.B. excludes space
        final URI uri = new URI(scheme, specials, host.getHostAddress(), 80, specials, specials, specials);

        final URI bld = URIBuilder.localhost()
                .setScheme(scheme)
                .setUserInfo(specials)
                .setPath(specials)
                .setCustomQuery(specials)
                .setFragment(specials)
                .build();

        Assertions.assertEquals(uri.getHost(), bld.getHost());
        Assertions.assertEquals(uri.getUserInfo(), bld.getUserInfo());
        Assertions.assertEquals(uri.getPath(), bld.getPath());
        Assertions.assertEquals(uri.getQuery(), bld.getQuery());
        Assertions.assertEquals(uri.getFragment(), bld.getFragment());
    }

    @Test
    void testLoopbackAddress() throws Exception {
        // Check that the URI generated by URI builder agrees with that generated by using URI directly
        final String scheme = "https";
        final InetAddress host = InetAddress.getLoopbackAddress();
        final String specials = "/abcd!$&*()_-+.,=:;'~@[]?<>|#^%\"{}\\\u00a3`\u00ac\u00a6xyz"; // N.B. excludes space
        final URI uri = new URI(scheme, specials, host.getHostAddress(), 80, specials, specials, specials);

        final URI bld = URIBuilder.loopbackAddress()
                .setScheme(scheme)
                .setUserInfo(specials)
                .setPath(specials)
                .setCustomQuery(specials)
                .setFragment(specials)
                .build();

        Assertions.assertEquals(uri.getHost(), bld.getHost());
        Assertions.assertEquals(uri.getUserInfo(), bld.getUserInfo());
        Assertions.assertEquals(uri.getPath(), bld.getPath());
        Assertions.assertEquals(uri.getQuery(), bld.getQuery());
        Assertions.assertEquals(uri.getFragment(), bld.getFragment());
    }

    @Test
    void testEmpty() throws Exception {
        final URIBuilder uribuilder = new URIBuilder();
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI(""), result);
    }

    @Test
    void testEmptyPath() throws Exception {
        final URIBuilder uribuilder = new URIBuilder("http://thathost");
        Assertions.assertTrue(uribuilder.isPathEmpty());
    }

    @Test
    void testRemoveParameter() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/", "param=stuff&blah&blah", null);
        final URIBuilder uribuilder = new URIBuilder(uri);
        Assertions.assertFalse(uribuilder.isQueryEmpty());

        Assertions.assertThrows(NullPointerException.class, () -> uribuilder.removeParameter(null));

        uribuilder.removeParameter("DoesNotExist");
        Assertions.assertEquals("stuff", uribuilder.getFirstQueryParam("param").getValue());
        Assertions.assertNull(uribuilder.getFirstQueryParam("blah").getValue());

        uribuilder.removeParameter("blah");
        Assertions.assertEquals("stuff", uribuilder.getFirstQueryParam("param").getValue());
        Assertions.assertNull(uribuilder.getFirstQueryParam("blah"));

        uribuilder.removeParameter("param");
        Assertions.assertNull(uribuilder.getFirstQueryParam("param"));
        Assertions.assertTrue(uribuilder.isQueryEmpty());

        uribuilder.removeParameter("AlreadyEmpty");
        Assertions.assertTrue(uribuilder.isQueryEmpty());
        Assertions.assertEquals(new URI("http://localhost:80/"), uribuilder.build());
    }

    @Test
    void testRemoveQuery() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/", "param=stuff", null);
        final URIBuilder uribuilder = new URIBuilder(uri).removeQuery();
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/"), result);
    }

    @Test
    void testSetAuthorityFromNamedEndpointHost() throws Exception {
        final Host host = Host.create("localhost:88");
        final URIBuilder uribuilder = new URIBuilder().setScheme(URIScheme.HTTP.id).setAuthority(host);
        // Check builder
        Assertions.assertNull(uribuilder.getUserInfo());
        Assertions.assertEquals(host.getHostName(), uribuilder.getAuthority().getHostName());
        Assertions.assertEquals(host.getHostName(), uribuilder.getHost());
        // Check result
        final URI result = uribuilder.build();
        Assertions.assertEquals(host.getHostName(), result.getHost());
        Assertions.assertEquals(host.getPort(), result.getPort());
        Assertions.assertEquals(new URI("http://localhost:88"), result);
    }

    @Test
    void testSetAuthorityFromNamedEndpointHttpHost() throws Exception {
        final HttpHost httpHost = HttpHost.create("localhost:88");
        final URIBuilder uribuilder = new URIBuilder().setScheme(URIScheme.HTTP.id).setAuthority(httpHost);
        // Check builder
        Assertions.assertNull(uribuilder.getUserInfo());
        Assertions.assertEquals(httpHost.getHostName(), uribuilder.getAuthority().getHostName());
        Assertions.assertEquals(httpHost.getHostName(), uribuilder.getHost());
        // Check result
        final URI result = uribuilder.build();
        Assertions.assertEquals(httpHost.getHostName(), result.getHost());
        Assertions.assertEquals(httpHost.getPort(), result.getPort());
        Assertions.assertEquals(new URI("http://localhost:88"), result);
    }

    @Test
    void testSetAuthorityFromURIAuthority() throws Exception {
        final URIAuthority authority = URIAuthority.create("u:p@localhost:88");
        final URIBuilder uribuilder = new URIBuilder().setScheme(URIScheme.HTTP.id).setAuthority(authority);
        // Check builder
        Assertions.assertEquals(authority.getUserInfo(), uribuilder.getAuthority().getUserInfo());
        Assertions.assertEquals(authority.getHostName(), uribuilder.getAuthority().getHostName());
        Assertions.assertEquals(authority.getHostName(), uribuilder.getHost());
        // Check result
        final URI result = uribuilder.build();
        Assertions.assertEquals(authority.getUserInfo(), result.getUserInfo());
        Assertions.assertEquals(authority.getHostName(), result.getHost());
        Assertions.assertEquals(authority.getPort(), result.getPort());
        Assertions.assertEquals(authority.toString(), result.getAuthority());
        Assertions.assertEquals(new URI("http://u:p@localhost:88"), result);
    }

    @Test
    void testSetParameter() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/", "param=stuff&blah&blah", null);
        final URIBuilder uribuilder = new URIBuilder(uri).setParameter("param", "some other stuff")
                .setParameter("blah", "blah")
                .setParameter("blah", "blah2");
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/?param=some%20other%20stuff&blah=blah2"), result);
    }

    @Test
    void testGetFirstNamedParameter() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/", "param=stuff&blah&blah", null);
        URIBuilder uribuilder = new URIBuilder(uri).setParameter("param", "some other stuff")
            .setParameter("blah", "blah");
        Assertions.assertEquals("some other stuff", uribuilder.getFirstQueryParam("param").getValue());
        Assertions.assertEquals("blah", uribuilder.getFirstQueryParam("blah").getValue());
        Assertions.assertNull(uribuilder.getFirstQueryParam("DoesNotExist"));
        //
        uribuilder = new URIBuilder("http://localhost:80/?param=some%20other%20stuff&blah=blah&blah=blah2");
        Assertions.assertEquals("blah", uribuilder.getFirstQueryParam("blah").getValue());
        uribuilder.removeQuery();
        Assertions.assertNull(uribuilder.getFirstQueryParam("param"));
    }

    @Test
    void testSetParametersWithEmptyArrayArg() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/test", "param=test", null);
        final URIBuilder uribuilder = new URIBuilder(uri).setParameters();
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/test"), result);
    }

    @Test
    void testSetParametersWithNullArrayArg() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/test", "param=test", null);
        final URIBuilder uribuilder = new URIBuilder(uri).setParameters((NameValuePair[]) null);
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/test"), result);
    }

    @Test
    void testSetParametersWithEmptyList() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/test", "param=test", null);
        final URIBuilder uribuilder = new URIBuilder(uri).setParameters(Collections.emptyList());
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/test"), result);
    }

    @Test
    void testSetParametersWithNullList() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/test", "param=test", null);
        final URIBuilder uribuilder = new URIBuilder(uri).setParameters((List<NameValuePair>) null);
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/test"), result);
    }

    @Test
    void testParameterWithSpecialChar() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/", "param=stuff", null);
        final URIBuilder uribuilder = new URIBuilder(uri).addParameter("param", "1 + 1 = 2")
            .addParameter("param", "blah&blah");
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/?param=stuff&param=1%20%2B%201%20%3D%202&" +
                "param=blah%26blah"), result);
    }

    @Test
    void testAddParameter() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/", "param=stuff&blah&blah", null);
        final URIBuilder uribuilder = new URIBuilder(uri).addParameter("param", "some other stuff")
            .addParameter("blah", "blah");
        final URI result = uribuilder.build();
        Assertions.assertEquals(new URI("http://localhost:80/?param=stuff&blah&blah&" +
                "param=some%20other%20stuff&blah=blah"), result);
    }

    @Test
    void testQueryEncoding() throws Exception {
        final URI uri1 = new URI("https://somehost.com/stuff?client_id=1234567890" +
                "&redirect_uri=https%3A%2F%2Fsomehost.com%2Fblah%20blah%2F");
        final URI uri2 = new URIBuilder("https://somehost.com/stuff")
            .addParameter("client_id","1234567890")
            .addParameter("redirect_uri","https://somehost.com/blah blah/").build();
        Assertions.assertEquals(uri1, uri2);
    }

    @Test
    void testQueryAndParameterEncoding() throws Exception {
        final URI uri1 = new URI("https://somehost.com/stuff?param1=12345&param2=67890");
        final URI uri2 = new URIBuilder("https://somehost.com/stuff")
            .setCustomQuery("this&that")
            .addParameter("param1","12345")
            .addParameter("param2","67890").build();
        Assertions.assertEquals(uri1, uri2);
    }

    @Test
    void testPathEncoding() throws Exception {
        final URI uri1 = new URI("https://somehost.com/some%20path%20with%20blanks/");
        final URI uri2 = new URIBuilder()
            .setScheme("https")
            .setHost("somehost.com")
            .setPath("/some path with blanks/")
            .build();
        Assertions.assertEquals(uri1, uri2);
    }

    @Test
    void testAgainstURI() throws Exception {
        // Check that the URI generated by URI builder agrees with that generated by using URI directly
        final String scheme = "https";
        final String host = "localhost";
        final String specials = "/abcd!$&*()_-+.,=:;'~@[]?<>|#^%\"{}\\\u00a3`\u00ac\u00a6xyz"; // N.B. excludes space
        final URI uri = new URI(scheme, specials, host, 80, specials, specials, specials);

        final URI bld = new URIBuilder()
                .setScheme(scheme)
                .setHost(host)
                .setUserInfo(specials)
                .setPath(specials)
                .setCustomQuery(specials)
                .setFragment(specials)
                .build();

        Assertions.assertEquals(uri.getHost(), bld.getHost());
        Assertions.assertEquals(uri.getUserInfo(), bld.getUserInfo());
        Assertions.assertEquals(uri.getPath(), bld.getPath());
        Assertions.assertEquals(uri.getQuery(), bld.getQuery());
        Assertions.assertEquals(uri.getFragment(), bld.getFragment());
    }

    @Test
    void testBuildAddParametersUTF8() throws Exception {
        assertAddParameters(StandardCharsets.UTF_8);
    }

    @Test
    void testBuildAddParametersISO88591() throws Exception {
        assertAddParameters(StandardCharsets.ISO_8859_1);
    }

    void assertAddParameters(final Charset charset) throws Exception {
        final URI uri = new URIBuilder("https://somehost.com/stuff")
                .setCharset(charset)
                .addParameters(createParameterList()).build();

        assertBuild(charset, uri);
        // null addParameters
        final URI uri2 = new URIBuilder("https://somehost.com/stuff")
                .setCharset(charset)
                .addParameters(null).build();

        Assertions.assertEquals("https://somehost.com/stuff", uri2.toString());
    }

    @Test
    void testBuildSetParametersUTF8() throws Exception {
        assertSetParameters(StandardCharsets.UTF_8);
    }

    @Test
    void testBuildSetParametersISO88591() throws Exception {
        assertSetParameters(StandardCharsets.ISO_8859_1);
    }

    void assertSetParameters(final Charset charset) throws Exception {
        final URI uri = new URIBuilder("https://somehost.com/stuff")
                .setCharset(charset)
                .setParameters(createParameterList()).build();

        assertBuild(charset, uri);
    }

    void assertBuild(final Charset charset, final URI uri) {
        final String encodedData1 = PercentCodec.encode("\"1\u00aa position\"", charset);
        final String encodedData2 = PercentCodec.encode("Jos\u00e9 Abra\u00e3o", charset);

        final String uriExpected = String.format("https://somehost.com/stuff?parameter1=value1&parameter2=%s&parameter3=%s", encodedData1, encodedData2);

        Assertions.assertEquals(uriExpected, uri.toString());
    }

    private List<NameValuePair> createParameterList() {
        final List<NameValuePair> parameters = new ArrayList<>();
        parameters.add(new BasicNameValuePair("parameter1", "value1"));
        parameters.add(new BasicNameValuePair("parameter2", "\"1\u00aa position\""));
        parameters.add(new BasicNameValuePair("parameter3", "Jos\u00e9 Abra\u00e3o"));
        return parameters;
    }

    @Test
    void testMalformedPath() throws Exception {
        final String path = "@notexample.com/mypath";
        final URI uri = new URIBuilder(path).setHost("example.com").build();
        Assertions.assertEquals("example.com", uri.getHost());
    }

    @Test
    void testRelativePath() throws Exception {
        final URI uri = new URIBuilder("./mypath").build();
        Assertions.assertEquals(new URI("./mypath"), uri);
    }

    @Test
    void testRelativePathWithAuthority() throws Exception {
        final URI uri = new URIBuilder("./mypath").setHost("somehost").setScheme("http").build();
        Assertions.assertEquals(new URI("http://somehost/./mypath"), uri);
    }

    @Test
    void testTolerateNullInput() throws Exception {
        assertThat(new URIBuilder()
                        .setScheme(null)
                        .setHost("localhost")
                        .setUserInfo(null)
                        .setPort(8443)
                        .setPath(null)
                        .setCustomQuery(null)
                        .setFragment(null)
                        .build(),
                CoreMatchers.equalTo(URI.create("//localhost:8443")));
    }

    @Test
    void testTolerateBlankInput() throws Exception {
        assertThat(new URIBuilder()
                        .setScheme("")
                        .setHost("localhost")
                        .setUserInfo("")
                        .setPort(8443)
                        .setPath("")
                        .setPath("")
                        .setCustomQuery("")
                        .setFragment("")
                        .build(),
                CoreMatchers.equalTo(URI.create("//localhost:8443")));
    }

    @Test
    void testHttpHost() throws Exception {
        final HttpHost httpHost = new HttpHost("http", "example.com", 1234);
        final URIBuilder uribuilder = new URIBuilder();
        uribuilder.setHttpHost(httpHost);
        Assertions.assertEquals(URI.create("http://example.com:1234"), uribuilder.build());
    }

    @Test
    void testSetHostWithReservedChars() throws Exception {
        final URIBuilder uribuilder = new URIBuilder();
        uribuilder.setScheme("http").setHost("!example!.com");
        Assertions.assertEquals(URI.create("http://%21example%21.com"), uribuilder.build());
    }

    @Test
    void testGetHostWithReservedChars() throws Exception {
        final URIBuilder uribuilder = new URIBuilder("http://someuser%21@%21example%21.com/");
        Assertions.assertEquals("!example!.com", uribuilder.getHost());
        Assertions.assertEquals("someuser!", uribuilder.getUserInfo());
    }

    @Test
    void testMultipleLeadingPathSlashes() throws Exception {
        final URI uri = new URIBuilder()
                .setScheme("ftp")
                .setHost("somehost")
                .setPath("//blah//blah")
                .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("ftp://somehost//blah//blah")));
    }

    @Test
    void testNoAuthorityAndPath() throws Exception {
        final URI uri = new URIBuilder()
                .setScheme("file")
                .setPath("/blah")
                .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("file:/blah")));
    }

    @Test
    void testSetPathSegmentList() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .setPathSegments(Arrays.asList("api", "products"))
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost/api/products")));
    }

    @Test
    void testSetPathSegmentsVarargs() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .setPathSegments("api", "products")
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost/api/products")));
    }

    @Test
    void testSetPathSegmentsRootlessList() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("file")
            .setPathSegmentsRootless(Arrays.asList("dir", "foo"))
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("file:dir/foo")));
    }

    @Test
    void testSetPathSegmentsRootlessVarargs() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("file")
            .setPathSegmentsRootless("dir", "foo")
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("file:dir/foo")));
    }

    @Test
    void testAppendToExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .setPath("api")
            .appendPath("v1/resources")
            .appendPath("idA")
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost/api/v1/resources/idA")));
    }

    @Test
    void testAppendToNonExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .appendPath("api/v2/customers")
            .appendPath("idA")
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost/api/v2/customers/idA")));
    }

    @Test
    void testAppendNullToExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .setPath("api")
            .appendPath(null)
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost/api")));
    }

    @Test
    void testAppendNullToNonExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .appendPath(null)
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost")));
    }

    @Test
    void testAppendSegmentsVarargsToExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("myhost")
            .setPath("api")
            .appendPathSegments("v3", "products")
            .appendPathSegments("idA")
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://myhost/api/v3/products/idA")));
    }

    @Test
    void testAppendSegmentsVarargsToNonExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .appendPathSegments("api", "v2", "customers")
            .appendPathSegments("idA")
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost/api/v2/customers/idA")));
    }

    @Test
    void testAppendNullSegmentsVarargs() throws Exception {
        final String pathSegment = null;
        final URI uri = new URIBuilder()
            .setScheme("https")
            .setHost("somehost")
            .appendPathSegments(pathSegment)
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("https://somehost/")));
    }

    @Test
    void testAppendSegmentsListToExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("http")
            .setHost("myhost")
            .setPath("api")
            .appendPathSegments(Arrays.asList("v3", "products"))
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("http://myhost/api/v3/products")));
    }

    @Test
    void testAppendSegmentsListToNonExistingPath() throws Exception {
        final URI uri = new URIBuilder()
            .setScheme("http")
            .setHost("myhost")
            .appendPathSegments(Arrays.asList("api", "v3", "customers"))
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("http://myhost/api/v3/customers")));
    }

    @Test
    void testAppendNullSegmentsList() throws Exception {
        final List<String> pathSegments = null;
        final URI uri = new URIBuilder()
            .setScheme("http")
            .setHost("myhost")
            .appendPathSegments(pathSegments)
            .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("http://myhost")));
    }

    @Test
    void testNoAuthorityAndPathSegments() throws Exception {
        final URI uri = new URIBuilder()
                .setScheme("file")
                .setPathSegments("this", "that")
                .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("file:/this/that")));
    }

    @Test
    void testNoAuthorityAndRootlessPath() throws Exception {
        final URI uri = new URIBuilder()
                .setScheme("file")
                .setPath("blah")
                .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("file:blah")));
    }

    @Test
    void testNoAuthorityAndRootlessPathSegments() throws Exception {
        final URI uri = new URIBuilder()
                .setScheme("file")
                .setPathSegmentsRootless("this", "that")
                .build();
        assertThat(uri, CoreMatchers.equalTo(URI.create("file:this/that")));
    }

    @Test
    void testOpaque() throws Exception {
        final URIBuilder uriBuilder = new URIBuilder("http://host.com");
        final URI uri = uriBuilder.build();
        assertThat(uriBuilder.isOpaque(), CoreMatchers.equalTo(uri.isOpaque()));
    }

    @Test
    void testAddParameterEncodingEquivalence() throws Exception {
        final URI uri = new URI("http", null, "localhost", 80, "/",
                "param=stuff with spaces", null);
        final URIBuilder uribuilder = new URIBuilder().setScheme("http").setHost("localhost").setPort(80).setPath("/").addParameter(
                "param", "stuff with spaces");
        final URI result = uribuilder.build();
        Assertions.assertEquals(uri, result);
    }

    @Test
    void testSchemeSpecificPartParametersNull() throws Exception {
       final URIBuilder uribuilder = new URIBuilder("http://host.com").setParameter("par", "parvalue")
               .setSchemeSpecificPart("", (NameValuePair)null);
       Assertions.assertEquals(new URI("http://host.com?par=parvalue"), uribuilder.build());
    }

    @Test
    void testSchemeSpecificPartSetGet() {
       final URIBuilder uribuilder = new URIBuilder().setSchemeSpecificPart("specificpart");
       Assertions.assertEquals("specificpart", uribuilder.getSchemeSpecificPart());
    }

    /** Common use case: mailto: scheme. See https://tools.ietf.org/html/rfc6068#section-2 */
    @Test
    void testSchemeSpecificPartNameValuePairByRFC6068Sample() throws Exception {
       final URIBuilder uribuilder = new URIBuilder().setScheme("mailto")
               .setSchemeSpecificPart("my@email.server", new BasicNameValuePair("subject", "mail subject"));
       final String result = uribuilder.build().toString();
       Assertions.assertTrue(result.contains("my@email.server"), "mail address as scheme specific part expected");
       Assertions.assertTrue(result.contains("mail%20subject"), "correct parameter encoding expected for that scheme");
    }

    /** Common use case: mailto: scheme. See https://tools.ietf.org/html/rfc6068#section-2 */
    @Test
    void testSchemeSpecificPartNameValuePairListByRFC6068Sample() throws Exception {
        final List<NameValuePair> parameters = new ArrayList<>();
        parameters.add(new BasicNameValuePair("subject", "mail subject"));

       final URIBuilder uribuilder = new URIBuilder().setScheme("mailto").setSchemeSpecificPart("my@email.server", parameters);
       final String result = uribuilder.build().toString();
       Assertions.assertTrue(result.contains("my@email.server"), "mail address as scheme specific part expected");
       Assertions.assertTrue(result.contains("mail%20subject"), "correct parameter encoding expected for that scheme");
    }

    @Test
    void testOptimize() throws Exception {
        Assertions.assertEquals("example://a/b/c/%7Bfoo%7D",
                new URIBuilder("eXAMPLE://a/./b/../b/%63/%7bfoo%7d").optimize().build().toASCIIString());
        Assertions.assertEquals("http://www.example.com/%3C",
                new URIBuilder("http://www.example.com/%3c").optimize().build().toASCIIString());
        Assertions.assertEquals("http://www.example.com/",
                new URIBuilder("HTTP://www.EXAMPLE.com/").optimize().build().toASCIIString());
        Assertions.assertEquals("http://www.example.com/a%2F",
                new URIBuilder("http://www.example.com/a%2f").optimize().build().toASCIIString());
        Assertions.assertEquals("http://www.example.com/?a%2F",
                new URIBuilder("http://www.example.com/?a%2f").optimize().build().toASCIIString());
        Assertions.assertEquals("http://www.example.com/?q=%26",
                new URIBuilder("http://www.example.com/?q=%26").optimize().build().toASCIIString());
        Assertions.assertEquals("http://www.example.com/%23?q=%26",
                new URIBuilder("http://www.example.com/%23?q=%26").optimize().build().toASCIIString());
        Assertions.assertEquals("http://www.example.com/blah-%28%20-blah-%20%26%20-blah-%20%29-blah/",
                new URIBuilder("http://www.example.com/blah-%28%20-blah-%20&%20-blah-%20)-blah/").optimize().build().toASCIIString());
        Assertions.assertEquals("../../.././",
                new URIBuilder("../../.././").optimize().build().toASCIIString());
        Assertions.assertEquals("file:../../.././",
                new URIBuilder("file:../../.././").optimize().build().toASCIIString());
        Assertions.assertEquals("http://host/",
                new URIBuilder("http://host/../../.././").optimize().build().toASCIIString());
        Assertions.assertThrows(URISyntaxException.class, () -> new URIBuilder("http:///../../.././").optimize().build().toASCIIString());
    }

    @Test
    void testIpv6Host() throws Exception {
        final URIBuilder builder = new URIBuilder("https://[::1]:432/path");
        final URI uri = builder.build();
        Assertions.assertEquals(432, builder.getPort());
        Assertions.assertEquals(432, uri.getPort());
        Assertions.assertEquals("https", builder.getScheme());
        Assertions.assertEquals("https", uri.getScheme());
        Assertions.assertEquals("::1", builder.getHost());
        Assertions.assertEquals("[::1]", uri.getHost());
        Assertions.assertEquals("/path", builder.getPath());
        Assertions.assertEquals("/path", uri.getPath());
    }

    @Test
    void testIpv6HostWithPortUpdate() throws Exception {
        // Updating the port clears URIBuilder.encodedSchemeSpecificPart
        // and bypasses the fast/simple path which preserves input.
        final URIBuilder builder = new URIBuilder("https://[::1]:432/path").setPort(123);
        final URI uri = builder.build();
        Assertions.assertEquals(123, builder.getPort());
        Assertions.assertEquals(123, uri.getPort());
        Assertions.assertEquals("https", builder.getScheme());
        Assertions.assertEquals("https", uri.getScheme());
        Assertions.assertEquals("::1", builder.getHost());
        Assertions.assertEquals("[::1]", uri.getHost());
        Assertions.assertEquals("/path", builder.getPath());
        Assertions.assertEquals("/path", uri.getPath());
    }

    @Test
    void testBuilderWithUnbracketedIpv6Host() throws Exception {
        final URIBuilder builder = new URIBuilder().setScheme("https").setHost("::1").setPort(443).setPath("/path");
        final URI uri = builder.build();
        Assertions.assertEquals("https", builder.getScheme());
        Assertions.assertEquals("https", uri.getScheme());
        Assertions.assertEquals(443, builder.getPort());
        Assertions.assertEquals(443, uri.getPort());
        Assertions.assertEquals("::1", builder.getHost());
        Assertions.assertEquals("[::1]", uri.getHost());
        Assertions.assertEquals("/path", builder.getPath());
        Assertions.assertEquals("/path", uri.getPath());
    }

    @Test
    void testHttpsUriWithEmptyHost() {
        final URIBuilder uribuilder = new URIBuilder()
                .setScheme("https")
                .setUserInfo("stuff")
                .setHost("")
                .setPort(80)
                .setPath("/some stuff")
                .setParameter("param", "stuff")
                .setFragment("fragment");
        Assertions.assertThrows(URISyntaxException.class, uribuilder::build);
    }

    @Test
    void testHttpUriWithEmptyHost() {
        final URIBuilder uribuilder = new URIBuilder()
                .setScheme("http")
                .setUserInfo("stuff")
                .setHost("")
                .setPort(80)
                .setPath("/some stuff")
                .setParameter("param", "stuff")
                .setFragment("fragment");
        Assertions.assertThrows(URISyntaxException.class, uribuilder::build);
    }

    @Test
    void testSetPlusAsBlank() throws Exception {
        // Case 1: Plus as blank, "+" should be treated as space
        URIBuilder uriBuilder = new URIBuilder("http://localhost?param=hello+world")
                .setPlusAsBlank(true);
        List<NameValuePair> params = uriBuilder.getQueryParams();
        Assertions.assertEquals("hello world", params.get(0).getValue());

        // Case 2: Plus as plus, "+" should remain "+"
        uriBuilder = new URIBuilder("http://localhost?param=hello+world")
                .setPlusAsBlank(false);
        params = uriBuilder.getQueryParams();
        Assertions.assertEquals("hello+world", params.get(0).getValue());

        // Case 3: '%20' always interpreted as space
        uriBuilder = new URIBuilder("http://localhost?param=hello%20world")
                .setPlusAsBlank(true);
        params = uriBuilder.getQueryParams();
        Assertions.assertEquals("hello world", params.get(0).getValue());

        uriBuilder = new URIBuilder("http://localhost?param=hello%20world")
                .setPlusAsBlank(false);
        params = uriBuilder.getQueryParams();
        Assertions.assertEquals("hello world", params.get(0).getValue());
    }


    private static final Map<String, Object> vars = new HashMap<>();

    static {
        final Map<String, String> keysMap = new LinkedHashMap<>();
        keysMap.put("semi", ";");
        keysMap.put("dot", ".");
        keysMap.put("comma", ",");
        vars.put("keys", keysMap);

        // Common variables across all RFC examples
        vars.put("var", "value");
        vars.put("hello", "Hello World!");
        vars.put("path", "/foo/bar");
        vars.put("empty", "");
        vars.put("x", "1024");
        vars.put("y", "768");
        vars.put("list", Arrays.asList("red", "green", "blue"));
    }

    //
    // ========== Level 1 Tests ==========
    //
    @Test
    void testLevel1_var() throws URISyntaxException {
        // {var} => value
        final String result = new URITemplate("{var}").expand(vars).toString();
        Assertions.assertEquals("value", result);
    }

    @Test
    void testLevel1_hello() throws URISyntaxException {
        // {hello} => Hello%20World%21
        final String result = new URITemplate("{hello}").expand(vars).toString();
        Assertions.assertEquals("Hello%20World%21", result);
    }

    //
    // ========== Level 2 Tests ==========
    //
    @Test
    void testLevel2_plusVar() throws URISyntaxException {
        // {+var} => value
        final String result = new URITemplate("{+var}").expand(vars).toString();
        Assertions.assertEquals("value", result);
    }

    @Test
    void testLevel2_plusHello() throws URISyntaxException {
        // {+hello} => Hello%20World!
        // The exclamation mark remains unencoded because it's a reserved character
        final String result = new URITemplate("{+hello}").expand(vars).toString();
        Assertions.assertEquals("Hello%20World!", result);
    }

    @Test
    void testLevel2_plusPathAndThenLiteral() throws URISyntaxException {
        // {+path}/here => /foo/bar/here
        final String result = new URITemplate("{+path}/here").expand(vars).toString();
        Assertions.assertEquals("/foo/bar/here", result);
    }

    @Test
    void testLevel2_plusPathInQuery() throws URISyntaxException {
        // here?ref={+path} => here?ref=/foo/bar
        final String result = new URITemplate("here?ref={+path}").expand(vars).toString();
        Assertions.assertEquals("here?ref=/foo/bar", result);
    }

    @Test
    void testLevel2_fragmentVar() throws URISyntaxException {
        // X{#var} => X#value
        final String result = new URITemplate("X{#var}").expand(vars).toString();
        Assertions.assertEquals("X#value", result);
    }

    @Test
    void testLevel2_fragmentHello() throws URISyntaxException {
        // X{#hello} => X#Hello%20World!
        final String result = new URITemplate("X{#hello}").expand(vars).toString();
        Assertions.assertEquals("X#Hello%20World!", result);
    }

    //
    // ========== Level 3 Tests ==========
    //
    @Test
    void testLevel3_multiSimple() throws URISyntaxException {
        // map?{x,y} => map?1024,768
        String result = new URITemplate("map?{x,y}").expand(vars).toString();
        Assertions.assertEquals("map?1024,768", result);

        // {x,hello,y} => 1024,Hello%20World%21,768
        result = new URITemplate("{x,hello,y}").expand(vars).toString();
        Assertions.assertEquals("1024,Hello%20World%21,768", result);
    }

    @Test
    void testLevel3_multiReserved() throws URISyntaxException {
        // {+x,hello,y} => 1024,Hello%20World!,768
        String result = new URITemplate("{+x,hello,y}").expand(vars).toString();
        Assertions.assertEquals("1024,Hello%20World!,768", result);

        // {+path,x}/here => /foo/bar,1024/here
        result = new URITemplate("{+path,x}/here").expand(vars).toString();
        Assertions.assertEquals("/foo/bar,1024/here", result);
    }

    @Test
    void testLevel3_multiFragment() throws URISyntaxException {
        // {#x,hello,y} => #1024,Hello%20World!,768
        String result = new URITemplate("{#x,hello,y}").expand(vars).toString();
        Assertions.assertEquals("#1024,Hello%20World!,768", result);

        // {#path,x}/here => #/foo/bar,1024/here
        result = new URITemplate("{#path,x}/here").expand(vars).toString();
        Assertions.assertEquals("#/foo/bar,1024/here", result);
    }

    @Test
    void testLevel3_labelExpansion() throws URISyntaxException {
        // X{.var} => X.value
        String result = new URITemplate("X{.var}").expand(vars).toString();
        Assertions.assertEquals("X.value", result);

        // X{.x,y} => X.1024.768
        result = new URITemplate("X{.x,y}").expand(vars).toString();
        Assertions.assertEquals("X.1024.768", result);
    }

    @Test
    void testLevel3_pathSegments() throws URISyntaxException {
        // {/var} => /value
        String result = new URITemplate("{/var}").expand(vars).toString();
        Assertions.assertEquals("/value", result);

        // {/var,x}/here => /value/1024/here
        result = new URITemplate("{/var,x}/here").expand(vars).toString();
        Assertions.assertEquals("/value/1024/here", result);
    }

    @Test
    void testLevel3_pathStyleParameters() throws URISyntaxException {
        // {;x,y} => ;x=1024;y=768
        String result = new URITemplate("{;x,y}").expand(vars).toString();
        Assertions.assertEquals(";x=1024;y=768", result);

        // {;x,y,empty} => ;x=1024;y=768;empty
        result = new URITemplate("{;x,y,empty}").expand(vars).toString();
        Assertions.assertEquals(";x=1024;y=768;empty", result);
    }

    @Test
    void testLevel3_formStyleQuery() throws URISyntaxException {
        // {?x,y} => ?x=1024&y=768
        String result = new URITemplate("{?x,y}").expand(vars).toString();
        Assertions.assertEquals("?x=1024&y=768", result);

        // {?x,y,empty} => ?x=1024&y=768&empty=
        result = new URITemplate("{?x,y,empty}").expand(vars).toString();
        Assertions.assertEquals("?x=1024&y=768&empty=", result);
    }

    @Test
    void testLevel3_formStyleQueryContinuation() throws URISyntaxException {
        // ?fixed=yes{&x} => ?fixed=yes&x=1024
        String result = new URITemplate("?fixed=yes{&x}").expand(vars).toString();
        Assertions.assertEquals("?fixed=yes&x=1024", result);

        // {&x,y,empty} => &x=1024&y=768&empty=
        result = new URITemplate("{&x,y,empty}").expand(vars).toString();
        Assertions.assertEquals("&x=1024&y=768&empty=", result);
    }

    //
    // ========== Level 4 Tests ==========
    // prefix modifiers (:N) and explode (*)
    //
    @Test
    void testLevel4_stringExpansionWithValueModifiers() throws URISyntaxException {
        // {var:3} => val
        String result = new URITemplate("{var:3}").expand(vars).toString();
        Assertions.assertEquals("val", result);

        // {var:30} => value (since var is "value", prefix 30 is the whole string)
        result = new URITemplate("{var:30}").expand(vars).toString();
        Assertions.assertEquals("value", result);

        // {list} => red,green,blue
        result = new URITemplate("{list}").expand(vars).toString();
        Assertions.assertEquals("red,green,blue", result);

        // {list*} => red,green,blue
        result = new URITemplate("{list*}").expand(vars).toString();
        Assertions.assertEquals("red,green,blue", result);

        // {keys} => semi,%3B,dot,.,comma,%2C
        // semicolon => %3B, period => ., comma => %2C
        result = new URITemplate("{keys}").expand(vars).toString();
        Assertions.assertEquals("semi,%3B,dot,.,comma,%2C", result);

        // {keys*} => semi=%3B,dot=.,comma=%2C
        result = new URITemplate("{keys*}").expand(vars).toString();
        Assertions.assertEquals("semi=%3B,dot=.,comma=%2C", result);
    }

    @Test
    void testLevel4_reservedExpansionWithValueModifiers() throws URISyntaxException {
        // {+path:6}/here => /foo/b/here  ("/foo/bar" truncated to 6 => "/foo/b")
        String result = new URITemplate("{+path:6}/here").expand(vars).toString();
        Assertions.assertEquals("/foo/b/here", result);

        // {+list} => red,green,blue  (reserved expansion allows reserved chars unencoded)
        result = new URITemplate("{+list}").expand(vars).toString();
        Assertions.assertEquals("red,green,blue", result);

        // {+list*} => red,green,blue
        result = new URITemplate("{+list*}").expand(vars).toString();
        Assertions.assertEquals("red,green,blue", result);

        // {+keys} => semi,;,dot,.,comma,,
        // Notice the semicolon `;` and comma `,` remain unencoded because it's + expansion
        result = new URITemplate("{+keys}").expand(vars).toString();
        Assertions.assertEquals("semi,;,dot,.,comma,,", result);

        // {+keys*} => semi=;,dot=.,comma=,
        result = new URITemplate("{+keys*}").expand(vars).toString();
        Assertions.assertEquals("semi=;,dot=.,comma=,", result);
    }

    @Test
    void testLevel4_fragmentExpansionWithValueModifiers() throws URISyntaxException {
        // {#path:6}/here => #/foo/b/here
        String result = new URITemplate("{#path:6}/here").expand(vars).toString();
        Assertions.assertEquals("#/foo/b/here", result);

        // {#list} => #red,green,blue
        result = new URITemplate("{#list}").expand(vars).toString();
        Assertions.assertEquals("#red,green,blue", result);

        // {#list*} => #red,green,blue
        result = new URITemplate("{#list*}").expand(vars).toString();
        Assertions.assertEquals("#red,green,blue", result);

        // {#keys} => #semi,;,dot,.,comma,,
        result = new URITemplate("{#keys}").expand(vars).toString();
        Assertions.assertEquals("#semi,;,dot,.,comma,,", result);

        // {#keys*} => #semi=;,dot=.,comma=,
        result = new URITemplate("{#keys*}").expand(vars).toString();
        Assertions.assertEquals("#semi=;,dot=.,comma=,", result);
    }

    @Test
    void testLevel4_labelExpansionWithValueModifiers() throws URISyntaxException {
        // X{.var:3} => X.val
        String result = new URITemplate("X{.var:3}").expand(vars).toString();
        Assertions.assertEquals("X.val", result);

        // X{.list} => X.red,green,blue
        result = new URITemplate("X{.list}").expand(vars).toString();
        Assertions.assertEquals("X.red,green,blue", result);

        // X{.list*} => X.red.green.blue
        result = new URITemplate("X{.list*}").expand(vars).toString();
        Assertions.assertEquals("X.red.green.blue", result);

        // X{.keys} => X.semi,%3B,dot,.,comma,%2C
        result = new URITemplate("X{.keys}").expand(vars).toString();
        Assertions.assertEquals("X.semi,%3B,dot,.,comma,%2C", result);

        // X{.keys*} => X.semi=%3B.dot=..comma=%2C
        result = new URITemplate("X{.keys*}").expand(vars).toString();
        Assertions.assertEquals("X.semi=%3B.dot=..comma=%2C", result);
    }

    @Test
    void testLevel4_pathSegmentsWithValueModifiers() throws URISyntaxException {
        // {/var:1,var} => /v/value
        String result = new URITemplate("{/var:1,var}").expand(vars).toString();
        Assertions.assertEquals("/v/value", result);

        // {/list} => /red,green,blue
        result = new URITemplate("{/list}").expand(vars).toString();
        Assertions.assertEquals("/red,green,blue", result);

        // {/list*} => /red/green/blue
        result = new URITemplate("{/list*}").expand(vars).toString();
        Assertions.assertEquals("/red/green/blue", result);

        // {/list*,path:4} => /red/green/blue/%2Ffoo
        // "path" = "/foo/bar", truncated to 4 => "/foo", then percent-encoded => "%2Ffoo"
        result = new URITemplate("{/list*,path:4}").expand(vars).toString();
        Assertions.assertEquals("/red/green/blue/%2Ffoo", result);

        // {/keys} => /semi,%3B,dot,.,comma,%2C
        result = new URITemplate("{/keys}").expand(vars).toString();
        Assertions.assertEquals("/semi,%3B,dot,.,comma,%2C", result);

        // {/keys*} => /semi=%3B/dot=./comma=%2C
        result = new URITemplate("{/keys*}").expand(vars).toString();
        Assertions.assertEquals("/semi=%3B/dot=./comma=%2C", result);
    }

    @Test
    void testLevel4_pathStyleParametersWithValueModifiers() throws URISyntaxException {
        // {;hello:5} => ;hello=Hello
        String result = new URITemplate("{;hello:5}").expand(vars).toString();
        Assertions.assertEquals(";hello=Hello", result);

        // {;list} => ;list=red,green,blue
        result = new URITemplate("{;list}").expand(vars).toString();
        Assertions.assertEquals(";list=red,green,blue", result);

        // {;list*} => ;list=red;list=green;list=blue
        result = new URITemplate("{;list*}").expand(vars).toString();
        Assertions.assertEquals(";list=red;list=green;list=blue", result);

        // {;keys} => ;keys=semi,%3B,dot,.,comma,%2C
        result = new URITemplate("{;keys}").expand(vars).toString();
        Assertions.assertEquals(";keys=semi,%3B,dot,.,comma,%2C", result);

        // {;keys*} => ;semi=%3B;dot=.;comma=%2C
        result = new URITemplate("{;keys*}").expand(vars).toString();
        Assertions.assertEquals(";semi=%3B;dot=.;comma=%2C", result);
    }

    @Test
    void testLevel4_formStyleQueryWithValueModifiers() throws URISyntaxException {
        // {?var:3} => ?var=val
        String result = new URITemplate("{?var:3}").expand(vars).toString();
        Assertions.assertEquals("?var=val", result);

        // {?list} => ?list=red,green,blue
        result = new URITemplate("{?list}").expand(vars).toString();
        Assertions.assertEquals("?list=red,green,blue", result);

        // {?list*} => ?list=red&list=green&list=blue
        result = new URITemplate("{?list*}").expand(vars).toString();
        Assertions.assertEquals("?list=red&list=green&list=blue", result);

        // {?keys} => ?keys=semi,%3B,dot,.,comma,%2C
        result = new URITemplate("{?keys}").expand(vars).toString();
        Assertions.assertEquals("?keys=semi,%3B,dot,.,comma,%2C", result);

        // {?keys*} => ?semi=%3B&dot=.&comma=%2C
        result = new URITemplate("{?keys*}").expand(vars).toString();
        Assertions.assertEquals("?semi=%3B&dot=.&comma=%2C", result);
    }

    @Test
    void testLevel4_formStyleQueryContinuationWithValueModifiers() throws URISyntaxException {
        // {&var:3} => &var=val
        String result = new URITemplate("{&var:3}").expand(vars).toString();
        Assertions.assertEquals("&var=val", result);

        // {&list} => &list=red,green,blue
        result = new URITemplate("{&list}").expand(vars).toString();
        Assertions.assertEquals("&list=red,green,blue", result);

        // {&list*} => &list=red&list=green&list=blue
        result = new URITemplate("{&list*}").expand(vars).toString();
        Assertions.assertEquals("&list=red&list=green&list=blue", result);

        // {&keys} => &keys=semi,%3B,dot,.,comma,%2C
        result = new URITemplate("{&keys}").expand(vars).toString();
        Assertions.assertEquals("&keys=semi,%3B,dot,.,comma,%2C", result);

        // {&keys*} => &semi=%3B&dot=.&comma=%2C
        result = new URITemplate("{&keys*}").expand(vars).toString();
        Assertions.assertEquals("&semi=%3B&dot=.&comma=%2C", result);
    }

    @Test
    void testUriTemplateAdvancedExamples() throws URISyntaxException {
        final Map<String, Object> vars = new HashMap<>();
        vars.put("var", "value");
        vars.put("hello", "Hello World!");
        vars.put("half", "50%");
        vars.put("empty", "");
        vars.put("undef", null);
        vars.put("x", "1024");
        vars.put("y", "768");
        vars.put("list", Arrays.asList("red", "green", "blue"));

        final Map<String, String> keys = new LinkedHashMap<>(); // Ensure insertion order
        keys.put("semi", ";");
        keys.put("dot", ".");
        keys.put("comma", ",");
        vars.put("keys", keys);

        vars.put("base", "http://example.com/home/");
        vars.put("path", "/foo/bar");
        vars.put("who", "fred");
        vars.put("v", "6");
        vars.put("dub", "me/too");
        vars.put("dom", Arrays.asList("example", "com"));

        // + Operator
        Assertions.assertEquals("value", new URITemplate("{+var}").expand(vars).toString());
        Assertions.assertEquals("Hello%20World!", new URITemplate("{+hello}").expand(vars).toString());
        Assertions.assertEquals("50%25", new URITemplate("{+half}").expand(vars).toString());
        Assertions.assertEquals("http://example.com/home/index", new URITemplate("{+base}index").expand(vars).toString());
        Assertions.assertEquals("OX", new URITemplate("O{+empty}X").expand(vars).toString());
        Assertions.assertEquals("OX", new URITemplate("O{+undef}X").expand(vars).toString());
        Assertions.assertEquals("/foo/bar/here", new URITemplate("{+path}/here").expand(vars).toString());
        Assertions.assertEquals("here?ref=/foo/bar", new URITemplate("here?ref={+path}").expand(vars).toString());
        Assertions.assertEquals("up/foo/barvalue/here", new URITemplate("up{+path}{var}/here").expand(vars).toString());
        Assertions.assertEquals("1024,Hello%20World!,768", new URITemplate("{+x,hello,y}").expand(vars).toString());
        Assertions.assertEquals("/foo/bar,1024/here", new URITemplate("{+path,x}/here").expand(vars).toString());
        Assertions.assertEquals("/foo/b/here", new URITemplate("{+path:6}/here").expand(vars).toString());
        Assertions.assertEquals("red,green,blue", new URITemplate("{+list}").expand(vars).toString());
        Assertions.assertEquals("red,green,blue", new URITemplate("{+list*}").expand(vars).toString());
        Assertions.assertEquals("semi,;,dot,.,comma,,", new URITemplate("{+keys}").expand(vars).toString());
        Assertions.assertEquals("semi=;,dot=.,comma=,", new URITemplate("{+keys*}").expand(vars).toString());

        // # Operator
        Assertions.assertEquals("#value", new URITemplate("{#var}").expand(vars).toString());
        Assertions.assertEquals("#Hello%20World!", new URITemplate("{#hello}").expand(vars).toString());
        Assertions.assertEquals("#50%25", new URITemplate("{#half}").expand(vars).toString());
        Assertions.assertEquals("foo#", new URITemplate("foo{#empty}").expand(vars).toString());
        Assertions.assertEquals("foo", new URITemplate("foo{#undef}").expand(vars).toString());
        Assertions.assertEquals("#1024,Hello%20World!,768", new URITemplate("{#x,hello,y}").expand(vars).toString());
        Assertions.assertEquals("#/foo/bar,1024/here", new URITemplate("{#path,x}/here").expand(vars).toString());
        Assertions.assertEquals("#/foo/b/here", new URITemplate("{#path:6}/here").expand(vars).toString());
        Assertions.assertEquals("#red,green,blue", new URITemplate("{#list}").expand(vars).toString());
        Assertions.assertEquals("#red,green,blue", new URITemplate("{#list*}").expand(vars).toString());
        Assertions.assertEquals("#semi,;,dot,.,comma,,", new URITemplate("{#keys}").expand(vars).toString());
        Assertions.assertEquals("#semi=;,dot=.,comma=,", new URITemplate("{#keys*}").expand(vars).toString());

        // . Operator
        Assertions.assertEquals(".fred", new URITemplate("{.who}").expand(vars).toString());
        Assertions.assertEquals(".fred.fred", new URITemplate("{.who,who}").expand(vars).toString());
        Assertions.assertEquals(".50%25.fred", new URITemplate("{.half,who}").expand(vars).toString());
        Assertions.assertEquals("www.example.com", new URITemplate("www{.dom*}").expand(vars).toString());
        Assertions.assertEquals("X.value", new URITemplate("X{.var}").expand(vars).toString());
        Assertions.assertEquals("X.", new URITemplate("X{.empty}").expand(vars).toString());
        Assertions.assertEquals("X", new URITemplate("X{.undef}").expand(vars).toString());
        Assertions.assertEquals("X.val", new URITemplate("X{.var:3}").expand(vars).toString());
        Assertions.assertEquals("X.red,green,blue", new URITemplate("X{.list}").expand(vars).toString());
        Assertions.assertEquals("X.red.green.blue", new URITemplate("X{.list*}").expand(vars).toString());
        Assertions.assertEquals("X.semi,%3B,dot,.,comma,%2C", new URITemplate("X{.keys}").expand(vars).toString());
        Assertions.assertEquals("X.semi=%3B.dot=..comma=%2C", new URITemplate("X{.keys*}").expand(vars).toString());
        Assertions.assertEquals("X", new URITemplate("X{.empty_keys}").expand(vars).toString());
        Assertions.assertEquals("X", new URITemplate("X{.empty_keys*}").expand(vars).toString());

    }

    @Test
    void testUriTemplateExamples() throws URISyntaxException {

        final Map<String, Object> vars = new HashMap<>();
        vars.put("count", Arrays.asList("one", "two", "three"));
        vars.put("dom", Arrays.asList("example", "com"));
        vars.put("dub", "me/too");
        vars.put("hello", "Hello World!");
        vars.put("half", "50%");
        vars.put("var", "value");
        vars.put("who", "fred");
        vars.put("base", "http://example.com/home/");
        vars.put("path", "/foo/bar");
        vars.put("list", Arrays.asList("red", "green", "blue"));
        vars.put("keys", new LinkedHashMap<String, String>() {{
            put("semi", ";");
            put("dot", ".");
            put("comma", ",");
        }});
        vars.put("v", "6");
        vars.put("x", "1024");
        vars.put("y", "768");
        vars.put("empty", "");
        vars.put("empty_keys", new ArrayList<>());
        vars.put("undef", null);

        // Test cases for various operators and patterns
        assertExpansion(vars,"{count}", "one,two,three");
        assertExpansion(vars,"{count*}", "one,two,three");
        assertExpansion(vars,"{/count}", "/one,two,three");
        assertExpansion(vars,"{/count*}", "/one/two/three");
        assertExpansion(vars,"{;count}", ";count=one,two,three");
        assertExpansion(vars,"{;count*}", ";count=one;count=two;count=three");
        assertExpansion(vars,"{?count}", "?count=one,two,three");
        assertExpansion(vars,"{?count*}", "?count=one&count=two&count=three");
        assertExpansion(vars,"{&count*}", "&count=one&count=two&count=three");

        assertExpansion(vars,"{var}", "value");
        assertExpansion(vars,"{hello}", "Hello%20World%21");
        assertExpansion(vars,"{half}", "50%25");
        assertExpansion(vars,"O{empty}X", "OX");
        assertExpansion(vars,"O{undef}X", "OX");
        assertExpansion(vars,"{x,y}", "1024,768");
        assertExpansion(vars,"{x,hello,y}", "1024,Hello%20World%21,768");
        assertExpansion(vars,"?{x,empty}", "?1024,");
        assertExpansion(vars,"?{x,undef}", "?1024");
        assertExpansion(vars,"?{undef,y}", "?768");
        assertExpansion(vars,"{var:3}", "val");
        assertExpansion(vars,"{var:30}", "value");
        assertExpansion(vars,"{list}", "red,green,blue");
        assertExpansion(vars,"{list*}", "red,green,blue");
        assertExpansion(vars,"{keys}", "semi,%3B,dot,.,comma,%2C");
        assertExpansion(vars,"{keys*}", "semi=%3B,dot=.,comma=%2C");

        // Continue with other examples, adapting the pattern to match the operator used
        assertExpansion(vars,"{+var}", "value");
        assertExpansion(vars,"{+hello}", "Hello%20World!");
        assertExpansion(vars,"{+half}", "50%25");
        assertExpansion(vars,"{base}index", "http%3A%2F%2Fexample.com%2Fhome%2Findex");
        assertExpansion(vars,"{+base}index", "http://example.com/home/index");
        assertExpansion(vars,"O{+empty}X", "OX");
        assertExpansion(vars,"O{+undef}X", "OX");
        assertExpansion(vars,"{+path}/here", "/foo/bar/here");
        assertExpansion(vars,"here?ref={+path}", "here?ref=/foo/bar");
        assertExpansion(vars,"up{+path}{var}/here", "up/foo/barvalue/here");
        assertExpansion(vars,"{+x,hello,y}", "1024,Hello%20World!,768");
        assertExpansion(vars,"{+path,x}/here", "/foo/bar,1024/here");
        assertExpansion(vars,"{+path:6}/here", "/foo/b/here");
        assertExpansion(vars,"{+list}", "red,green,blue");
        assertExpansion(vars,"{+list*}", "red,green,blue");
        assertExpansion(vars,"{+keys}", "semi,;,dot,.,comma,,");
        assertExpansion(vars,"{+keys*}", "semi=;,dot=.,comma=,");

    }

    private void assertExpansion(final Map<String, Object> vars, final String template, final String expected) throws URISyntaxException {
        final URIBuilder expanded = new URITemplate(template).expand(vars);
        Assertions.assertEquals(expected, expanded.toString(), "Expansion of '" + template + "' failed");
    }



    @Test
    void testMatchSimpleVariables() {
        final URITemplate template = new URITemplate("/users/{userId}/orders/{orderId}");
        final Map<String, String> result = template.match("/users/123/orders/456");
        Assertions.assertEquals("123", result.get("userId"));
        Assertions.assertEquals("456", result.get("orderId"));
    }

    @Test
    void testMatchWithExtraSegments() {
        final URITemplate template = new URITemplate("/products/{productId}");
        final Map<String, String> result = template.match("/products/987/details");
        Assertions.assertTrue(result.isEmpty(), "Should not match extra segments");
    }

    @Test
    void testMatchNoVariables() {
        final URITemplate template = new URITemplate("/static/path");
        final Map<String, String> result = template.match("/static/path");
        Assertions.assertTrue(result.isEmpty(), "No variables expected");
    }

    @Test
    void testMatchWithSpecialCharacters() {
        final URITemplate template = new URITemplate("/files/{fileName}");
        final Map<String, String> result = template.match("/files/special%20file.txt");
        Assertions.assertEquals("special%20file.txt", result.get("fileName"));
    }

    @Test
    void testMatchWithEmptyTemplate() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new URITemplate(""));
    }

    @Test
    void testMatchVariableNotPresent() {
        final URITemplate template = new URITemplate("/categories/{categoryId}/items/{itemId}");
        final Map<String, String> result = template.match("/categories/42");
        Assertions.assertTrue(result.isEmpty(), "Partial match should not extract variables");
    }

    @Test
    void testMatchUriWithQueryString() {
        final URITemplate template = new URITemplate("/search/{query}");
        final Map<String, String> result = template.match("/search/laptops?sort=price");
        Assertions.assertEquals("laptops", result.get("query"));
    }

    @Test
    void testMatchComplexTemplate() {
        final URITemplate template = new URITemplate("/store/{storeId}/product/{productId}/details");
        final Map<String, String> result = template.match("/store/101/product/202/details");
        Assertions.assertEquals("101", result.get("storeId"));
        Assertions.assertEquals("202", result.get("productId"));
    }

    @Test
    void testMatchTemplateWithoutDelimiters() {
        final URITemplate template = new URITemplate("{param1}-{param2}");
        final Map<String, String> result = template.match("value1-value2");
        Assertions.assertEquals("value1", result.get("param1"));
        Assertions.assertEquals("value2", result.get("param2"));
    }


}
