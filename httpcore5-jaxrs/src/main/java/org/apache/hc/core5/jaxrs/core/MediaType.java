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
package org.apache.hc.core5.jaxrs.core;

import org.apache.hc.core5.util.Args;

/**
 * Represents an Internet media type as defined by RFC 2045 / RFC 2616.
 * Constants are provided for common media types used in REST services.
 *
 * @since 5.5
 */
public final class MediaType {

    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_XML = "application/xml";
    public static final String TEXT_PLAIN = "text/plain";
    public static final String TEXT_HTML = "text/html";
    public static final String TEXT_XML = "text/xml";
    public static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static final String WILDCARD = "*/*";

    private final String type;
    private final String subtype;

    /**
     * Creates a new media type from the given type and subtype.
     *
     * @param type the primary type, for example {@code "application"}.
     * @param subtype the subtype, for example {@code "json"}.
     */
    public MediaType(final String type, final String subtype) {
        this.type = Args.notNull(type, "Type");
        this.subtype = Args.notNull(subtype, "Subtype");
    }

    public String getType() {
        return type;
    }

    public String getSubtype() {
        return subtype;
    }

    /**
     * Returns whether this media type is compatible with the given type.
     * Two types are compatible if their primary types and subtypes match,
     * treating the wildcard {@code *} as matching any value.
     *
     * @param other the media type to check compatibility with.
     * @return {@code true} if compatible.
     */
    public boolean isCompatible(final MediaType other) {
        if (other == null) {
            return false;
        }
        if ("*".equals(type) || "*".equals(other.type)) {
            return true;
        }
        if (!type.equalsIgnoreCase(other.type)) {
            return false;
        }
        return "*".equals(subtype) || "*".equals(other.subtype)
                || subtype.equalsIgnoreCase(other.subtype);
    }

    /**
     * Parses a media type string such as {@code "application/json"}.
     *
     * @param mediaType the media type string.
     * @return the parsed instance.
     */
    public static MediaType valueOf(final String mediaType) {
        Args.notNull(mediaType, "Media type");
        final String trimmed = mediaType.trim();
        // Strip parameters (e.g. charset)
        final int semi = trimmed.indexOf(';');
        final String base = semi >= 0 ? trimmed.substring(0, semi).trim() : trimmed;
        final int slash = base.indexOf('/');
        if (slash < 0) {
            return new MediaType(base, "*");
        }
        return new MediaType(base.substring(0, slash), base.substring(slash + 1));
    }

    /**
     * Returns whether the given media type string is compatible with any
     * of the given accepted types. A wildcard entry matches everything.
     *
     * @param actual the actual content type string.
     * @param accepted the accepted media type strings.
     * @return {@code true} if compatible with at least one accepted type.
     */
    public static boolean isCompatible(final String actual, final String[] accepted) {
        if (accepted == null || accepted.length == 0) {
            return true;
        }
        final MediaType actualType = valueOf(actual);
        for (final String a : accepted) {
            if (valueOf(a).isCompatible(actualType)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return type + "/" + subtype;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MediaType)) {
            return false;
        }
        final MediaType other = (MediaType) obj;
        return type.equalsIgnoreCase(other.type) && subtype.equalsIgnoreCase(other.subtype);
    }

    @Override
    public int hashCode() {
        return type.toLowerCase().hashCode() * 31 + subtype.toLowerCase().hashCode();
    }

}
