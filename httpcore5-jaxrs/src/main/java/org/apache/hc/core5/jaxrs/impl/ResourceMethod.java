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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hc.core5.jaxrs.annotation.Consumes;
import org.apache.hc.core5.jaxrs.annotation.DefaultValue;
import org.apache.hc.core5.jaxrs.annotation.HeaderParam;
import org.apache.hc.core5.jaxrs.annotation.HttpMethod;
import org.apache.hc.core5.jaxrs.annotation.Path;
import org.apache.hc.core5.jaxrs.annotation.PathParam;
import org.apache.hc.core5.jaxrs.annotation.Produces;
import org.apache.hc.core5.jaxrs.annotation.QueryParam;

/**
 * Immutable descriptor for a single JAX-RS resource endpoint. Each
 * instance binds an HTTP method and URI template to a concrete Java
 * method together with its parameter extraction rules.
 *
 * @since 5.5
 */
public final class ResourceMethod {

    /**
     * Identifies the source of a method parameter value.
     */
    enum ParamSource {
        PATH, QUERY, HEADER, BODY
    }

    /**
     * Describes a single method parameter and how its value is obtained.
     */
    static final class ParamInfo {
        final ParamSource source;
        final String name;
        final Class<?> type;
        final String defaultValue;

        ParamInfo(final ParamSource source, final String name,
                  final Class<?> type, final String defaultValue) {
            this.source = source;
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }

    private final Object instance;
    private final Method method;
    private final String httpMethod;
    private final UriTemplate uriTemplate;
    private final String[] produces;
    private final String[] consumes;
    private final ParamInfo[] parameters;

    ResourceMethod(final Object instance, final Method method,
                   final String httpMethod, final UriTemplate uriTemplate,
                   final String[] produces, final String[] consumes,
                   final ParamInfo[] parameters) {
        this.instance = instance;
        this.method = method;
        this.httpMethod = httpMethod;
        this.uriTemplate = uriTemplate;
        this.produces = produces;
        this.consumes = consumes;
        this.parameters = parameters;
    }

    Object getInstance() {
        return instance;
    }

    Method getMethod() {
        return method;
    }

    String getHttpMethod() {
        return httpMethod;
    }

    UriTemplate getUriTemplate() {
        return uriTemplate;
    }

    String[] getProduces() {
        return produces;
    }

    String[] getConsumes() {
        return consumes;
    }

    ParamInfo[] getParameters() {
        return parameters;
    }

    /**
     * Scans the given resource instance for JAX-RS annotated methods
     * and returns a list of fully resolved resource method descriptors.
     *
     * @param resourceInstance the resource object to scan.
     * @return the discovered resource methods.
     */
    public static List<ResourceMethod> scan(final Object resourceInstance) {
        final Class<?> clazz = resourceInstance.getClass();
        final Path classPath = clazz.getAnnotation(Path.class);
        final String basePath = classPath != null ? classPath.value() : "";
        final Produces classProduces = clazz.getAnnotation(Produces.class);
        final Consumes classConsumes = clazz.getAnnotation(Consumes.class);

        final List<ResourceMethod> result = new ArrayList<>();
        for (final Method m : clazz.getMethods()) {
            final String verb = resolveHttpMethod(m);
            if (verb == null) {
                continue;
            }
            final Path methodPath = m.getAnnotation(Path.class);
            final String combinedPath = UriTemplate.combinePaths(
                    basePath, methodPath != null ? methodPath.value() : null);
            final UriTemplate template = new UriTemplate(combinedPath);

            final Produces methodProduces = m.getAnnotation(Produces.class);
            final String[] prod = methodProduces != null
                    ? methodProduces.value()
                    : classProduces != null ? classProduces.value() : new String[]{"*/*"};

            final Consumes methodConsumes = m.getAnnotation(Consumes.class);
            final String[] cons = methodConsumes != null
                    ? methodConsumes.value()
                    : classConsumes != null ? classConsumes.value() : new String[]{"*/*"};

            final ParamInfo[] params = scanParameters(m);
            validate(m, template, params);
            m.setAccessible(true);

            result.add(new ResourceMethod(
                    resourceInstance, m, verb, template, prod, cons, params));
        }
        return result;
    }

    /**
     * Validates that no two resource methods share the same HTTP method
     * and URI template. Throws {@link IllegalStateException} if a
     * duplicate route is detected.
     *
     * @param methods the resource methods to check.
     */
    public static void validateNoDuplicateRoutes(
            final List<ResourceMethod> methods) {
        final Set<String> seen = new HashSet<>();
        for (final ResourceMethod rm : methods) {
            final String key = rm.httpMethod + " "
                    + rm.uriTemplate.getTemplate();
            if (!seen.add(key)) {
                throw new IllegalStateException(
                        "Duplicate route: " + key);
            }
        }
    }

    private static void validate(final Method m,
                                 final UriTemplate template,
                                 final ParamInfo[] params) {
        // At most one body parameter
        int bodyCount = 0;
        for (final ParamInfo pi : params) {
            if (pi.source == ParamSource.BODY) {
                bodyCount++;
            }
        }
        if (bodyCount > 1) {
            throw new IllegalArgumentException(
                    "Method " + m.getDeclaringClass().getName()
                            + "." + m.getName()
                            + " has " + bodyCount
                            + " body parameters; at most one is allowed");
        }
        // @PathParam names must match template variables
        final List<String> vars = template.getVariableNames();
        for (final ParamInfo pi : params) {
            if (pi.source == ParamSource.PATH
                    && !vars.contains(pi.name)) {
                throw new IllegalArgumentException(
                        "Method " + m.getDeclaringClass().getName()
                                + "." + m.getName()
                                + " has @PathParam(\"" + pi.name
                                + "\") but the URI template "
                                + template.getTemplate()
                                + " has no {" + pi.name + "}");
            }
        }
    }

    private static String resolveHttpMethod(final Method m) {
        String verb = null;
        for (final Annotation a : m.getAnnotations()) {
            final HttpMethod hm =
                    a.annotationType().getAnnotation(HttpMethod.class);
            if (hm != null) {
                if (verb != null) {
                    throw new IllegalArgumentException(
                            "Method "
                                    + m.getDeclaringClass().getName()
                                    + "." + m.getName()
                                    + " has multiple HTTP method"
                                    + " annotations");
                }
                verb = hm.value();
            }
        }
        return verb;
    }

    private static ParamInfo[] scanParameters(final Method m) {
        final Class<?>[] types = m.getParameterTypes();
        final Annotation[][] annotations = m.getParameterAnnotations();
        final ParamInfo[] result = new ParamInfo[types.length];
        for (int i = 0; i < types.length; i++) {
            result[i] = resolveParam(types[i], annotations[i]);
        }
        return result;
    }

    private static ParamInfo resolveParam(final Class<?> type,
                                          final Annotation[] annotations) {
        String defaultVal = null;
        for (final Annotation a : annotations) {
            if (a instanceof DefaultValue) {
                defaultVal = ((DefaultValue) a).value();
            }
        }
        for (final Annotation a : annotations) {
            if (a instanceof PathParam) {
                return new ParamInfo(ParamSource.PATH,
                        ((PathParam) a).value(), type, defaultVal);
            }
            if (a instanceof QueryParam) {
                return new ParamInfo(ParamSource.QUERY,
                        ((QueryParam) a).value(), type, defaultVal);
            }
            if (a instanceof HeaderParam) {
                return new ParamInfo(ParamSource.HEADER,
                        ((HeaderParam) a).value(), type, defaultVal);
            }
        }
        return new ParamInfo(ParamSource.BODY, null, type, null);
    }

}
