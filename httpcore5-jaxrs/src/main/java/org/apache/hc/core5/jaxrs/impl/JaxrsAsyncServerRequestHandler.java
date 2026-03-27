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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducers;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.nio.support.BasicResponseProducer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.jaxrs.core.MediaType;
import org.apache.hc.core5.jaxrs.core.Response;
import org.apache.hc.core5.jaxrs.ext.ExceptionMapper;

/**
 * Async server request handler that dispatches incoming HTTP requests
 * to JAX-RS annotated resource methods. The handler matches the request
 * URI against compiled URI templates and invokes the matching method
 * with extracted path, query and header parameters.
 *
 * @since 5.5
 */
public final class JaxrsAsyncServerRequestHandler
        implements AsyncServerRequestHandler<Message<HttpRequest, byte[]>> {

    private final List<ResourceMethod> resourceMethods;
    private final ObjectMapper objectMapper;
    private final List<ExceptionMapper<?>> exceptionMappers;

    public JaxrsAsyncServerRequestHandler(
            final List<ResourceMethod> resourceMethods,
            final ObjectMapper objectMapper,
            final List<ExceptionMapper<?>> exceptionMappers) {
        this.resourceMethods = resourceMethods;
        this.objectMapper = objectMapper;
        this.exceptionMappers = exceptionMappers != null
                ? exceptionMappers
                : Collections.<ExceptionMapper<?>>emptyList();
    }

    @Override
    public AsyncRequestConsumer<Message<HttpRequest, byte[]>> prepare(
            final HttpRequest request,
            final EntityDetails entityDetails,
            final HttpContext context) throws HttpException {
        return new BasicRequestConsumer<>(
                new BasicAsyncEntityConsumer());
    }

    @Override
    public void handle(
            final Message<HttpRequest, byte[]> requestMessage,
            final ResponseTrigger responseTrigger,
            final HttpContext context)
            throws HttpException, IOException {
        final HttpRequest request = requestMessage.getHead();
        final byte[] body = requestMessage.getBody();
        final String method = request.getMethod();
        final String requestUri = request.getRequestUri();
        final int queryStart = requestUri.indexOf('?');
        final String path = queryStart >= 0
                ? requestUri.substring(0, queryStart)
                : requestUri;
        final Map<String, String> queryParams = parseQueryString(
                queryStart >= 0
                        ? requestUri.substring(queryStart + 1)
                        : null);

        // Match path, then HTTP method
        final List<ResourceMethod> pathMatches = new ArrayList<>();
        ResourceMethod bestMatch = null;
        Map<String, String> bestPathParams = null;
        int bestLiteralLength = -1;
        final boolean isHead = "HEAD".equalsIgnoreCase(method);

        for (final ResourceMethod rm : resourceMethods) {
            final Map<String, String> pp =
                    rm.getUriTemplate().match(path);
            if (pp != null) {
                pathMatches.add(rm);
                final String rmMethod = rm.getHttpMethod();
                if (rmMethod.equalsIgnoreCase(method)
                        || isHead
                        && "GET".equalsIgnoreCase(rmMethod)) {
                    final int lit =
                            rm.getUriTemplate().getLiteralLength();
                    // Prefer exact method over HEAD->GET fallback
                    final boolean exact =
                            rmMethod.equalsIgnoreCase(method);
                    final boolean bestExact = bestMatch != null
                            && bestMatch.getHttpMethod()
                            .equalsIgnoreCase(method);
                    if (bestMatch == null
                            || exact && !bestExact
                            || exact == bestExact
                            && lit > bestLiteralLength) {
                        bestMatch = rm;
                        bestPathParams = pp;
                        bestLiteralLength = lit;
                    }
                }
            }
        }

        if (bestMatch == null) {
            if (!pathMatches.isEmpty()) {
                final TreeSet<String> allowed = new TreeSet<>();
                for (final ResourceMethod rm : pathMatches) {
                    allowed.add(rm.getHttpMethod());
                }
                final BasicHttpResponse resp =
                        new BasicHttpResponse(
                                HttpStatus.SC_METHOD_NOT_ALLOWED);
                resp.setHeader("Allow", join(allowed));
                responseTrigger.submitResponse(
                        new BasicResponseProducer(resp), context);
            } else {
                sendError(responseTrigger, context,
                        HttpStatus.SC_NOT_FOUND, "Not Found");
            }
            return;
        }

        // Enforce @Consumes
        if (body != null && body.length > 0) {
            final String[] cons = bestMatch.getConsumes();
            if (!isWildcard(cons)) {
                final Header ctHeader =
                        request.getFirstHeader(HttpHeaders.CONTENT_TYPE);
                final String ct = ctHeader != null
                        ? ctHeader.getValue() : null;
                if (ct == null
                        || !MediaType.isCompatible(ct, cons)) {
                    sendError(responseTrigger, context,
                            HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE,
                            "Unsupported Media Type");
                    return;
                }
            }
        }

        // Select response content type from Accept vs @Produces
        final Header acceptHeader =
                request.getFirstHeader("Accept");
        final String selectedType;
        final String[] prods = bestMatch.getProduces();
        if (isWildcard(prods)) {
            selectedType = null;
        } else if (acceptHeader == null) {
            selectedType = prods[0];
        } else {
            selectedType = selectProducesType(
                    acceptHeader.getValue(), prods);
            if (selectedType == null) {
                sendError(responseTrigger, context,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        "Not Acceptable");
                return;
            }
        }

        try {
            final Object result = invoke(
                    bestMatch, bestPathParams, queryParams,
                    request, body);
            sendResult(result, bestMatch, isHead,
                    selectedType, responseTrigger, context);
        } catch (final IllegalArgumentException e) {
            sendError(responseTrigger, context,
                    HttpStatus.SC_BAD_REQUEST,
                    e.getMessage() != null
                            ? e.getMessage() : "Bad Request");
        } catch (final JsonProcessingException e) {
            sendError(responseTrigger, context,
                    HttpStatus.SC_BAD_REQUEST,
                    "Malformed request body");
        } catch (final Exception e) {
            handleException(e, responseTrigger, context);
        }
    }

    private Object invoke(
            final ResourceMethod rm,
            final Map<String, String> pathParams,
            final Map<String, String> queryParams,
            final HttpRequest request,
            final byte[] body) throws Exception {
        final ResourceMethod.ParamInfo[] paramInfos =
                rm.getParameters();
        final Object[] args = new Object[paramInfos.length];
        for (int i = 0; i < paramInfos.length; i++) {
            final ResourceMethod.ParamInfo pi = paramInfos[i];
            switch (pi.source) {
                case PATH:
                    final String pathVal =
                            pathParams.get(pi.name);
                    args[i] = ParamConverter.convert(
                            pathVal != null
                                    ? pathVal : pi.defaultValue,
                            pi.type);
                    break;
                case QUERY:
                    final String queryVal =
                            queryParams.get(pi.name);
                    args[i] = ParamConverter.convert(
                            queryVal != null
                                    ? queryVal : pi.defaultValue,
                            pi.type);
                    break;
                case HEADER:
                    final Header header =
                            request.getFirstHeader(pi.name);
                    final String headerVal = header != null
                            ? header.getValue() : null;
                    args[i] = ParamConverter.convert(
                            headerVal != null
                                    ? headerVal : pi.defaultValue,
                            pi.type);
                    break;
                case BODY:
                    if (body != null && body.length > 0) {
                        if (pi.type == String.class) {
                            final Header ctHeader =
                                    request.getFirstHeader(
                                            HttpHeaders.CONTENT_TYPE);
                            final ContentType ct = ctHeader != null
                                    ? ContentType.parse(
                                    ctHeader.getValue())
                                    : ContentType.DEFAULT_TEXT;
                            args[i] = new String(body,
                                    ct.getCharset() != null
                                            ? ct.getCharset()
                                            : StandardCharsets
                                            .UTF_8);
                        } else if (pi.type == byte[].class) {
                            args[i] = body;
                        } else {
                            args[i] = objectMapper.readValue(
                                    body, pi.type);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        try {
            return rm.getMethod().invoke(rm.getInstance(), args);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private void sendResult(
            final Object result,
            final ResourceMethod rm,
            final boolean suppressBody,
            final String selectedType,
            final ResponseTrigger responseTrigger,
            final HttpContext context)
            throws HttpException, IOException {
        if (result instanceof Response) {
            sendJaxrsResponse((Response) result, rm,
                    suppressBody, selectedType,
                    responseTrigger, context);
        } else if (result == null) {
            responseTrigger.submitResponse(
                    new BasicResponseProducer(
                            new BasicHttpResponse(
                                    HttpStatus.SC_NO_CONTENT)),
                    context);
        } else {
            final ContentType ct =
                    resolveContentType(rm, selectedType);
            final byte[] encoded = encodeEntity(result, ct);
            if (suppressBody) {
                final BasicHttpResponse resp =
                        new BasicHttpResponse(HttpStatus.SC_OK);
                resp.setHeader(HttpHeaders.CONTENT_TYPE, ct.toString());
                responseTrigger.submitResponse(
                        new BasicResponseProducer(resp), context);
            } else {
                responseTrigger.submitResponse(
                        new BasicResponseProducer(
                                HttpStatus.SC_OK,
                                AsyncEntityProducers.create(
                                        encoded, ct)),
                        context);
            }
        }
    }

    private void sendJaxrsResponse(
            final Response jaxrsResponse,
            final ResourceMethod rm,
            final boolean suppressBody,
            final String selectedType,
            final ResponseTrigger responseTrigger,
            final HttpContext context)
            throws HttpException, IOException {
        final BasicHttpResponse httpResp =
                new BasicHttpResponse(jaxrsResponse.getStatus());
        for (final Map.Entry<String, List<String>> entry
                : jaxrsResponse.getHeaders().entrySet()) {
            for (final String value : entry.getValue()) {
                httpResp.addHeader(entry.getKey(), value);
            }
        }
        final Object entity = jaxrsResponse.getEntity();
        if (entity == null || suppressBody) {
            if (entity != null) {
                // HEAD: set Content-Type but no body
                final ContentType ct = resolveEntityContentType(
                        httpResp, rm, selectedType);
                httpResp.setHeader(HttpHeaders.CONTENT_TYPE,
                        ct.toString());
            }
            responseTrigger.submitResponse(
                    new BasicResponseProducer(httpResp), context);
        } else {
            final ContentType ct = resolveEntityContentType(
                    httpResp, rm, selectedType);
            final byte[] encoded = encodeEntity(entity, ct);
            if (httpResp.getFirstHeader(HttpHeaders.CONTENT_TYPE) == null) {
                httpResp.setHeader(HttpHeaders.CONTENT_TYPE,
                        ct.toString());
            }
            responseTrigger.submitResponse(
                    new BasicResponseProducer(httpResp,
                            AsyncEntityProducers.create(
                                    encoded, ct)),
                    context);
        }
    }

    private byte[] encodeEntity(final Object entity,
                                final ContentType ct)
            throws JsonProcessingException {
        if (entity instanceof byte[]) {
            return (byte[]) entity;
        }
        if (entity instanceof String
                && !isJsonType(ct.getMimeType())) {
            return ((String) entity).getBytes(
                    ct.getCharset() != null
                            ? ct.getCharset()
                            : StandardCharsets.UTF_8);
        }
        return objectMapper.writeValueAsBytes(entity);
    }

    private static ContentType resolveContentType(
            final ResourceMethod rm,
            final String selectedType) {
        if (selectedType != null) {
            return ContentType.parse(selectedType);
        }
        if (rm == null) {
            return ContentType.APPLICATION_JSON;
        }
        final String[] prods = rm.getProduces();
        if (prods.length > 0 && !"*/*".equals(prods[0])) {
            return ContentType.parse(prods[0]);
        }
        return ContentType.APPLICATION_JSON;
    }

    private static ContentType resolveEntityContentType(
            final BasicHttpResponse resp,
            final ResourceMethod rm,
            final String selectedType) {
        final Header existing =
                resp.getFirstHeader(HttpHeaders.CONTENT_TYPE);
        if (existing != null) {
            return ContentType.parse(existing.getValue());
        }
        return resolveContentType(rm, selectedType);
    }

    private static boolean isJsonType(final String mimeType) {
        return "application/json".equalsIgnoreCase(mimeType)
                || mimeType != null
                && mimeType.endsWith("+json");
    }

    @SuppressWarnings("unchecked")
    private void handleException(
            final Exception e,
            final ResponseTrigger responseTrigger,
            final HttpContext context)
            throws HttpException, IOException {
        for (final ExceptionMapper<?> mapper : exceptionMappers) {
            if (isApplicable(mapper, e)) {
                final Response jaxrsResponse =
                        ((ExceptionMapper<Exception>) mapper)
                                .toResponse(e);
                sendJaxrsResponse(jaxrsResponse, null, false,
                        null, responseTrigger, context);
                return;
            }
        }
        sendError(responseTrigger, context,
                HttpStatus.SC_INTERNAL_SERVER_ERROR,
                e.getMessage() != null
                        ? e.getMessage()
                        : "Internal Server Error");
    }

    private static boolean isApplicable(
            final ExceptionMapper<?> mapper,
            final Exception e) {
        for (final java.lang.reflect.Type iface
                : mapper.getClass().getGenericInterfaces()) {
            if (iface instanceof java.lang.reflect.ParameterizedType) {
                final java.lang.reflect.ParameterizedType pt =
                        (java.lang.reflect.ParameterizedType) iface;
                if (pt.getRawType() == ExceptionMapper.class) {
                    final java.lang.reflect.Type arg =
                            pt.getActualTypeArguments()[0];
                    if (arg instanceof Class
                            && ((Class<?>) arg).isInstance(e)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void sendError(
            final ResponseTrigger responseTrigger,
            final HttpContext context,
            final int status,
            final String message)
            throws HttpException, IOException {
        responseTrigger.submitResponse(
                new BasicResponseProducer(status,
                        message, ContentType.TEXT_PLAIN),
                context);
    }

    private static boolean isWildcard(final String[] types) {
        return types == null || types.length == 0
                || types.length == 1 && "*/*".equals(types[0]);
    }

    /**
     * Selects the first {@code @Produces} type that is compatible with
     * the client's {@code Accept} header. Entries with quality value
     * zero are excluded. Returns {@code null} if no compatible type
     * exists.
     */
    static String selectProducesType(
            final String acceptHeader,
            final String[] producesTypes) {
        for (final String token : acceptHeader.split(",")) {
            final String trimmed = token.trim();
            final int semi = trimmed.indexOf(';');
            final String base = semi >= 0
                    ? trimmed.substring(0, semi).trim()
                    : trimmed;
            if (parseQuality(trimmed) <= 0f) {
                continue;
            }
            if ("*/*".equals(base)) {
                return producesTypes[0];
            }
            final MediaType acceptType = MediaType.valueOf(base);
            for (final String p : producesTypes) {
                if (acceptType.isCompatible(
                        MediaType.valueOf(p))) {
                    return p;
                }
            }
        }
        return null;
    }

    static float parseQuality(final String token) {
        final int qIdx = token.indexOf("q=");
        if (qIdx < 0) {
            return 1.0f;
        }
        final String after = token.substring(qIdx + 2).trim();
        final int semi = after.indexOf(';');
        final String qVal = semi >= 0
                ? after.substring(0, semi).trim() : after;
        try {
            return Float.parseFloat(qVal);
        } catch (final NumberFormatException e) {
            return 1.0f;
        }
    }

    private static Map<String, String> parseQueryString(
            final String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> params = new LinkedHashMap<>();
        for (final String pair : queryString.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq >= 0) {
                params.put(
                        urlDecode(pair.substring(0, eq)),
                        urlDecode(pair.substring(eq + 1)));
            } else {
                params.put(urlDecode(pair), "");
            }
        }
        return params;
    }

    private static String urlDecode(final String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String join(final Iterable<String> values) {
        final StringBuilder sb = new StringBuilder();
        for (final String v : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v);
        }
        return sb.toString();
    }

}
