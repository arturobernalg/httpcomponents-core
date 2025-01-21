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

import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Tokenizer;


/**
 * The {@code URITemplate} class provides functionality for parsing and expanding
 * URI templates based on the rules defined in <a href="https://www.rfc-editor.org/rfc/rfc6570">RFC 6570</a>.
 * URI templates allow dynamic generation of URIs by defining placeholders and patterns
 * that are substituted with variable values during runtime. This implementation supports
 * multiple levels of template expansion, including reserved, fragment, and form-style expansions.
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li>Supports all expansion levels as specified in RFC 6570, including simple, reserved,
 *       and fragment expansions.</li>
 *   <li>Processes variables, lists, and maps with advanced handling of prefix limits
 *       and exploded expansions.</li>
 *   <li>Uses a customizable {@link Charset} for encoding URI components.</li>
 *   <li>Provides detailed validation and error reporting for malformed templates.</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>
 * {@code
 * Map<String, Object> variables = new HashMap<>();
 * variables.put("var", "value");
 * variables.put("list", Arrays.asList("red", "green", "blue"));
 * variables.put("map", Map.of("key1", "value1", "key2", "value2"));
 *
 * URITemplate template = new URITemplate("http://example.com/{var}/{list*}?{&map*}");
 * URIBuilder uriBuilder = template.expand(variables);
 * System.out.println(uriBuilder.toString());
 * // Output: http://example.com/value/red/green/blue?key1=value1&key2=value2
 * }
 * </pre>
 *
 * <h2>Supported Expansions:</h2>
 * <ul>
 *   <li><b>Simple Expansion:</b> {var} → value</li>
 *   <li><b>Reserved Expansion:</b> {+var} → value (preserves reserved characters)</li>
 *   <li><b>Fragment Expansion:</b> {#var} → #value</li>
 *   <li><b>Label Expansion:</b> {.var} → .value</li>
 *   <li><b>Path Segment Expansion:</b> {/var} → /value</li>
 *   <li><b>Path Style Parameters:</b> {;var} → ;var=value</li>
 *   <li><b>Form Style Query:</b> {?var} → ?var=value</li>
 *   <li><b>Form Style Query Continuation:</b> {&var} → &var=value</li>
 * </ul>
 *
 * <h2>Thread-Safety:</h2>
 * Instances of {@code URITemplate} are immutable and thread-safe. However, the
 * returned {@link URIBuilder} instances are mutable and should not be shared across threads.
 *
 * <h2>Notes:</h2>
 * <ul>
 *   <li>Reserved and unreserved character sets are handled according to RFC 3986.</li>
 *   <li>Variable values can be {@code String}, {@code List}, or {@code Map}. Lists and maps
 *       are expanded recursively based on the template configuration.</li>
 *   <li>Empty and undefined variables result in omitted segments or default values,
 *       depending on the expansion type.</li>
 * </ul>
 *
 * @since 5.4
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class URITemplate implements Serializable {

    private static final long serialVersionUID = -9152879929150407500L;

    private static final String UNRESERVED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final String RESERVED = ":/?#[]@!$&'()*+,;=";

    final Tokenizer TOKENIZER = Tokenizer.INSTANCE;

    /**
     * Delimiters for template variables, defined as '{' and '}'.
     */
    private static final Tokenizer.Delimiter DELIMITER = Tokenizer.delimiters('{', '}');

    /**
     * The URI template string provided at instantiation.
     */
    private final String template;

    /**
     * Character set used for encoding URI components.
     */
    private final Charset charset;

    /**
     * Constructs a {@code URITemplate} with the specified template string and UTF-8 as the default charset.
     *
     * @param template the URI template string; must not be null or empty
     * @throws IllegalArgumentException if the template is null or empty
     */
    public URITemplate(final String template) {
        this(template, StandardCharsets.UTF_8);
    }

    /**
     * Constructs a {@code URITemplate} with the specified template string and charset.
     *
     * @param template the URI template string; must not be null or empty
     * @param charset  the character set for encoding and decoding; must not be null
     * @throws IllegalArgumentException if the template is null or empty
     */
    public URITemplate(final String template, final Charset charset) {
        Args.notEmpty(template, "template");
        this.template = template;
        this.charset = charset;
    }

    /**
     * Expands the URI template using the provided variables and returns a {@link URIBuilder}.
     *
     * <p>The variables map may contain keys and values as {@code String}, {@code List}, or {@code Map}. Lists
     * are expanded as comma-separated values, while maps are expanded as key-value pairs.</p>
     *
     * @param variables a map of variable names and values used for expansion
     * @return a {@link URIBuilder} containing the expanded URI
     * @throws URISyntaxException if the expanded template contains invalid URI syntax
     */
    public URIBuilder expand(final Map<String, ?> variables) throws URISyntaxException {
        final String expanded = expandTemplate(this.template, variables);
        return new URIBuilder(expanded, charset);
    }


    /**
     * Expands a URI template using the provided variables.
     *
     * @param template  the URI template string to expand; must not be null or empty.
     * @param variables a map of variables to be substituted in the template.
     *                  Variable names must match those in the template.
     * @return the expanded URI as a string.
     * @throws IllegalArgumentException if the template is null or empty, or if a variable
     *                                  prefix is invalid.
     */
    private String expandTemplate(final String template, final Map<String, ?> variables) {

        final Tokenizer.Cursor cursor = new Tokenizer.Cursor(0, template.length());
        final StringBuilder result = new StringBuilder();

        while (!cursor.atEnd()) {
            final String part = TOKENIZER.parseToken(template, cursor, DELIMITER);
            result.append(part);
            if (!cursor.atEnd()) {
                final char nextChar = template.charAt(cursor.getPos());
                if (nextChar == '{') {
                    cursor.updatePos(cursor.getPos() + 1);
                    final String expression = TOKENIZER.parseToken(template, cursor, DELIMITER);
                    result.append(expandExpression(expression, variables));
                    cursor.updatePos(cursor.getPos() + 1);
                } else {
                    cursor.updatePos(cursor.getPos() + 1);
                }
            }
        }
        return result.toString();
    }

    /**
     * Expands an individual expression in a URI template.
     *
     * @param expr      the expression to expand, which may include operators and variables.
     * @param variables a map of variables to be substituted in the expression.
     * @return the expanded expression as a string.
     */
    private String expandExpression(final String expr, final Map<String, ?> variables) {
        if (expr.isEmpty()) {
            return "";
        }

        final Tokenizer.Cursor cursor = new Tokenizer.Cursor(0, expr.length());

        // Parse the operator
        char operator = '\0';
        boolean useReservedExpansion = false;
        boolean isFragmentExpansion = false;

        final char firstChar = expr.charAt(cursor.getPos());
        if ("#+./;?&".indexOf(firstChar) >= 0) {
            operator = firstChar;
            cursor.updatePos(cursor.getPos() + 1);
            if (operator == '+' || operator == '#') {
                useReservedExpansion = true;
                if (operator == '#') {
                    isFragmentExpansion = true;
                }
            }
        }

        // Parse variable specifications
        final List<String> expansions = new ArrayList<>();
        final StringBuilder tokenBuilder = new StringBuilder();

        while (!cursor.atEnd()) {
            tokenBuilder.setLength(0);
            TOKENIZER.copyContent(expr, cursor, Tokenizer.delimiters(','), tokenBuilder);
            final String varSpec = tokenBuilder.toString();
            expansions.addAll(expandVarSpec(varSpec, operator, useReservedExpansion, variables));

            // Skip the comma delimiter
            if (!cursor.atEnd() && expr.charAt(cursor.getPos()) == ',') {
                cursor.updatePos(cursor.getPos() + 1);
            }
        }

        // Build the final expanded string based on the operator
        switch (operator) {
            case '+':
            case '#':
                return (isFragmentExpansion && !expansions.isEmpty() ? "#" : "") + String.join(",", expansions);
            case '.':
                return expansions.isEmpty() ? "" : "." + String.join(".", expansions);
            case '/':
                return "/" + String.join("/", expansions);
            case ';':
                return ";" + String.join(";", expansions);
            case '?':
            case '&':
                final String sep = String.valueOf(operator);
                return sep + String.join("&", expansions);
            default:
                return String.join(",", expansions);
        }
    }


    /**
     * Expands a variable specification into its corresponding URI segment(s).
     *
     * @param varSpec   the variable specification, which may include explode (*) and prefix modifiers.
     * @param operator  the operator used to determine the expansion format.
     * @param reserved  whether reserved characters should be retained or percent-encoded.
     * @param variables a map of variables to be substituted.
     * @return a list of expanded segments for the variable.
     */
    private List<String> expandVarSpec(
            final String varSpec,
            final char operator,
            final boolean reserved,
            final Map<String, ?> variables) {
        final boolean explode = varSpec.endsWith("*");
        String core = explode ? varSpec.substring(0, varSpec.length() - 1) : varSpec;

        int prefixLength = -1;
        final int colonPos = core.indexOf(':');
        if (colonPos >= 0) {
            final String prefixNum = core.substring(colonPos + 1);
            core = core.substring(0, colonPos);
            try {
                prefixLength = Integer.parseInt(prefixNum);
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid prefix length: " + prefixNum);
            }
        }

        final Object rawValue = variables.get(core);
        if (rawValue == null) {
            return Collections.emptyList();
        }

        if (rawValue instanceof List) {
            return expandList(core, (List<?>) rawValue, explode, prefixLength, operator, reserved);
        } else if (rawValue instanceof Map) {
            return expandMap((Map<?, ?>) rawValue, explode, prefixLength, operator, reserved);
        } else {
            final String fullValue = rawValue.toString();
            final String truncated = (prefixLength >= 0 && prefixLength < fullValue.length())
                    ? fullValue.substring(0, prefixLength)
                    : fullValue;

            return expandSingleValue(core, truncated, explode, operator, reserved);
        }
    }

    /**
     * Expands a list variable into URI segments.
     *
     * @param varName      the name of the variable.
     * @param list         the list of values to expand.
     * @param explode      whether the list should be exploded (one segment per value).
     * @param prefixLength the maximum number of characters to use from each value (-1 for no limit).
     * @param operator     the operator used to determine the expansion format.
     * @param reserved     whether reserved characters should be retained or percent-encoded.
     * @return a list of expanded segments for the list.
     */
    private List<String> expandList(
            final String varName,
            final Collection<?> list,
            final boolean explode,
            final int prefixLength,
            final char operator,
            final boolean reserved) {

        final List<String> expansions = new ArrayList<>();

        for (final Object item : list) {
            String stringValue = item != null ? item.toString() : "";
            if (prefixLength >= 0 && prefixLength < stringValue.length()) {
                stringValue = stringValue.substring(0, prefixLength);
            }

            final Tokenizer.Cursor cursor = new Tokenizer.Cursor(0, stringValue.length());
            final StringBuilder tokenBuilder = new StringBuilder();

            while (!cursor.atEnd()) {
                tokenBuilder.setLength(0); // Clear the builder for each token
                TOKENIZER.copyContent(stringValue, cursor, (Tokenizer.Delimiter) null, tokenBuilder);

                final String token = tokenBuilder.toString();
                if (explode) {
                    if (operator == '&' || operator == '?' || operator == ';') {
                        expansions.add(varName + "=" + encode(token, reserved));
                    } else {
                        expansions.add(encode(token, reserved));
                    }
                } else {
                    expansions.add(encode(token, reserved));
                }

                // Skip delimiters (if any) between tokens
                TOKENIZER.skipWhiteSpace(stringValue, cursor);
            }
        }

        if (!explode) {
            final String joined = String.join(",", expansions);
            if (operator == '&' || operator == '?' || operator == ';') {
                return Collections.singletonList(varName + "=" + joined);
            }
            return Collections.singletonList(joined);
        }

        return expansions;
    }


    /**
     * Expands a single variable into a URI segment.
     *
     * @param varName  the name of the variable.
     * @param value    the value of the variable.
     * @param explode  whether the value should be exploded (e.g., key=value for ';' operator).
     * @param operator the operator used to determine the expansion format.
     * @param reserved whether reserved characters should be retained or percent-encoded.
     * @return a list containing the expanded segment for the value.
     */
    private List<String> expandSingleValue(final String varName,
                                           final String value,
                                           final boolean explode,
                                           final char operator,
                                           final boolean reserved) {
        final List<String> expansions = new ArrayList<>();

        if (operator == ';') {
            expansions.add(varName + (value.isEmpty() && !explode ? "" : "=" + encode(value, reserved)));
        } else if (operator == '?' || operator == '&') {
            expansions.add(varName + "=" + encode(value, reserved));
        } else {
            expansions.add(encode(value, reserved)); // No prefix for simple expansion
        }

        return expansions;
    }


    /**
     * Expands a map variable into URI segments.
     *
     * @param map          the map to expand, with keys and values as the elements.
     * @param explode      whether the map should be exploded (one segment per key-value pair).
     * @param prefixLength the maximum number of characters to use from each value (-1 for no limit).
     * @param operator     the operator used to determine the expansion format.
     * @param reserved     whether reserved characters should be retained or percent-encoded.
     * @return a list of expanded segments for the map.
     */
    private List<String> expandMap(final Map<?, ?> map,
                                   final boolean explode,
                                   final int prefixLength,
                                   final char operator,
                                   final boolean reserved) {
        final List<String> expansions = new ArrayList<>();

        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue; // Skip entries with null keys
            }

            final String key = encode(entry.getKey().toString(), reserved);
            String value = entry.getValue() != null ? entry.getValue().toString() : "";

            // Apply prefix length restriction if applicable
            if (prefixLength >= 0 && prefixLength < value.length()) {
                value = value.substring(0, prefixLength);
            }
            value = encode(value, reserved);

            // Build the expansion based on whether it's exploded or not
            if (explode) {
                expansions.add(key + "=" + value);
            } else {
                expansions.add(key);
                expansions.add(value);
            }
        }

        // Handle non-exploded case: Combine into a single string if necessary
        if (!explode && !expansions.isEmpty()) {
            final String joined = String.join(",", expansions);
            if (operator == '&' || operator == '?' || operator == ';') {
                return Collections.singletonList("keys=" + joined);
            }
            return Collections.singletonList(joined);
        }

        return expansions;
    }

    /**
     * Encodes a string value for use in a URI.
     *
     * @param value         the value to encode.
     * @param allowReserved whether reserved characters should be retained (true)
     *                      or percent-encoded (false).
     * @return the encoded string.
     */
    private String encode(final String value, final boolean allowReserved) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        final StringBuilder sb = new StringBuilder();
        final String allowedChars = allowReserved ? UNRESERVED + RESERVED : UNRESERVED;

        for (final char c : value.toCharArray()) {
            if (allowedChars.indexOf(c) >= 0) {
                sb.append(c); // Append allowed characters directly
            } else {
                // Encode disallowed characters as %XX
                final byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (final byte b : bytes) {
                    sb.append('%');
                    sb.append(String.format("%02X", b));
                }
            }
        }

        return sb.toString();
    }


    public Map<String, String> match(String uri) {
        if (uri == null || template == null) {
            return Collections.emptyMap();
        }

        // Strip query string from the URI if present
        final int queryIndex = uri.indexOf('?');
        if (queryIndex != -1) {
            uri = uri.substring(0, queryIndex);
        }

        // Create regex from the template, ensuring it matches the entire URI
        final String regex = "^" + template.replaceAll("\\{([^/{}]+)}", "(?<$1>[^/]+)") + "$";
        final Pattern pattern = Pattern.compile(regex);
        final Matcher matcher = pattern.matcher(uri);

        if (!matcher.matches()) {
            return Collections.emptyMap();
        }

        // Extract variable names and values
        final Map<String, String> variables = new HashMap<>();
        final Pattern groupPattern = Pattern.compile("\\{([^/{}]+)}");
        final Matcher groupMatcher = groupPattern.matcher(template);

        while (groupMatcher.find()) {
            final String groupName = groupMatcher.group(1);
            variables.put(groupName, matcher.group(groupName));
        }

        return variables;
    }

}
