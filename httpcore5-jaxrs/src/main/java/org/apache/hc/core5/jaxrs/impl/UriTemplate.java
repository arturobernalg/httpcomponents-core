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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.core5.util.Args;

/**
 * Compiles a URI path template such as {@code /widgets/{id}} into a
 * regular expression and extracts named path parameter values from a
 * concrete request path.
 *
 * @since 5.5
 */
public final class UriTemplate {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{([^}]+)}");

    private final String template;
    private final Pattern pattern;
    private final List<String> variableNames;

    /**
     * Compiles the given URI path template.
     *
     * @param template the path template, for example {@code /items/{id}}.
     */
    public UriTemplate(final String template) {
        this.template = Args.notNull(template, "Template");
        final List<String> names = new ArrayList<>();
        final Matcher m = VAR_PATTERN.matcher(template);
        final StringBuilder regex = new StringBuilder("^");
        int end = 0;
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(end, m.start())));
            final String varSpec = m.group(1);
            final int colon = varSpec.indexOf(':');
            if (colon >= 0) {
                names.add(varSpec.substring(0, colon).trim());
                final String custom = varSpec.substring(colon + 1).trim();
                // Convert inner capturing groups to non-capturing
                // so each template variable maps to exactly one group
                regex.append('(')
                        .append(toNonCapturing(custom))
                        .append(')');
            } else {
                names.add(varSpec.trim());
                regex.append("([^/]+)");
            }
            end = m.end();
        }
        regex.append(Pattern.quote(template.substring(end)));
        regex.append("$");
        this.pattern = Pattern.compile(regex.toString());
        this.variableNames = Collections.unmodifiableList(names);
    }

    /**
     * Returns the original template string.
     */
    public String getTemplate() {
        return template;
    }

    /**
     * Returns the ordered list of variable names defined in the template.
     */
    public List<String> getVariableNames() {
        return variableNames;
    }

    /**
     * Attempts to match the given path against this template.
     *
     * @param path the request path to match.
     * @return a map of variable names to extracted values, or {@code null}
     *         if the path does not match this template.
     */
    public Map<String, String> match(final String path) {
        final Matcher m = pattern.matcher(path);
        if (!m.matches()) {
            return null;
        }
        final Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < variableNames.size(); i++) {
            result.put(variableNames.get(i),
                    urlDecode(m.group(i + 1)));
        }
        return result;
    }

    /**
     * Combines a class-level path and a method-level path into a single
     * normalized path template. Leading slashes are ensured and duplicate
     * separators are removed.
     *
     * @param classPath the path from the class-level {@code @Path} annotation.
     * @param methodPath the path from the method-level {@code @Path} annotation,
     *                   may be {@code null}.
     * @return the combined path template.
     */
    public static String combinePaths(final String classPath, final String methodPath) {
        String base = classPath != null ? classPath.trim() : "";
        if (!base.isEmpty() && !base.startsWith("/")) {
            base = "/" + base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (methodPath == null || methodPath.trim().isEmpty()) {
            return base.isEmpty() ? "/" : base;
        }
        String sub = methodPath.trim();
        if (!sub.startsWith("/")) {
            sub = "/" + sub;
        }
        return base + sub;
    }

    /**
     * Returns the number of literal characters in the template. Templates
     * with more literal characters are considered more specific and are
     * preferred during matching.
     */
    public int getLiteralLength() {
        final Matcher m = VAR_PATTERN.matcher(template);
        int length = 0;
        int end = 0;
        while (m.find()) {
            length += m.start() - end;
            end = m.end();
        }
        length += template.length() - end;
        return length;
    }

    /**
     * Converts capturing groups in a regex to non-capturing groups
     * so that each template variable always maps to exactly one
     * regex group index.
     */
    private static String toNonCapturing(final String regex) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < regex.length(); i++) {
            final char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                sb.append(c).append(regex.charAt(++i));
            } else if (c == '('
                    && i + 1 < regex.length()
                    && regex.charAt(i + 1) != '?') {
                sb.append("(?:");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String urlDecode(final String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            return value;
        }
    }

    @Override
    public String toString() {
        return template;
    }

}
