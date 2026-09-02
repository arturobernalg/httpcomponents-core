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

package org.apache.hc.core5.http.structured;

import java.util.Iterator;
import java.util.Objects;

import org.apache.hc.core5.http.FormattedHeader;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.MessageHeaders;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.message.BufferedHeader;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.hc.core5.util.Tokenizer;

/**
 * Integration between Structured Field values and HttpComponents message headers.
 *
 * @since 5.5
 */
public final class StructuredFieldHeaders {

    private StructuredFieldHeaders() {
    }

    /**
     * Parses one header as an Structured Field Item.
     *
     * @param header the header.
     * @return the parsed Item.
     * @throws ParseException if the complete field value is invalid.
     */
    public static StructuredFieldItem parseItem(final Header header) throws ParseException {
        final HeaderInput input = input(header);
        return StructuredFieldParser.parseItem(input.value, input.cursor);
    }

    /**
     * Combines all matching field lines and parses them as an Structured Field Item.
     *
     * @param headers the message headers.
     * @param name the case-insensitive field name.
     * @return the parsed Item.
     * @throws ParseException if the complete field value is invalid or absent.
     */
    public static StructuredFieldItem parseItem(final MessageHeaders headers, final String name)
            throws ParseException {
        final HeaderInput input = combine(matching(headers, name));
        return StructuredFieldParser.parseItem(input.value, input.cursor);
    }

    /**
     * Parses one header as an Structured Field List.
     *
     * @param header the header.
     * @return the parsed List.
     * @throws ParseException if the complete field value is invalid.
     */
    public static StructuredFieldList parseList(final Header header) throws ParseException {
        final HeaderInput input = input(header);
        return StructuredFieldParser.parseList(input.value, input.cursor);
    }

    /**
     * Combines all matching field lines and parses them as an Structured Field List.
     *
     * @param headers the message headers.
     * @param name the case-insensitive field name.
     * @return the parsed List, empty when the field is absent.
     * @throws ParseException if the complete field value is invalid.
     */
    public static StructuredFieldList parseList(final MessageHeaders headers, final String name)
            throws ParseException {
        final HeaderInput input = combine(matching(headers, name));
        return StructuredFieldParser.parseList(input.value, input.cursor);
    }

    /**
     * Parses one header as an Structured Field Dictionary.
     *
     * @param header the header.
     * @return the parsed Dictionary.
     * @throws ParseException if the complete field value is invalid.
     */
    public static StructuredFieldDictionary parseDictionary(final Header header) throws ParseException {
        final HeaderInput input = input(header);
        return StructuredFieldParser.parseDictionary(input.value, input.cursor);
    }

    /**
     * Combines all matching field lines and parses them as an Structured Field Dictionary.
     *
     * @param headers the message headers.
     * @param name the case-insensitive field name.
     * @return the parsed Dictionary, empty when the field is absent.
     * @throws ParseException if the complete field value is invalid.
     */
    public static StructuredFieldDictionary parseDictionary(final MessageHeaders headers, final String name)
            throws ParseException {
        final HeaderInput input = combine(matching(headers, name));
        return StructuredFieldParser.parseDictionary(input.value, input.cursor);
    }

    /**
     * Creates a header for a Structured Field value.
     *
     * @param name the field name.
     * @param value the Structured Field value.
     * @return a header, or {@code null} for an empty List or Dictionary.
     */
    public static Header format(final String name, final StructuredFieldValue value) {
        Args.notBlank(name, "Header name");
        Objects.requireNonNull(value, "Structured Field value");
        if (value instanceof StructuredFieldList && ((StructuredFieldList) value).isEmpty()
                || value instanceof StructuredFieldDictionary && ((StructuredFieldDictionary) value).isEmpty()) {
            return null;
        }
        final CharArrayBuffer buffer = new CharArrayBuffer(name.length() + 66);
        buffer.append(name);
        buffer.append(": ");
        StructuredFieldSerializer.serialize(buffer, value);
        return BufferedHeader.create(buffer);
    }

    private static Iterator<Header> matching(final MessageHeaders headers, final String name) {
        Args.notNull(headers, "Message headers");
        Args.notBlank(name, "Header name");
        return headers.headerIterator(name);
    }

    private static HeaderInput combine(final Iterator<Header> matching) {
        if (!matching.hasNext()) {
            return new HeaderInput("", new Tokenizer.Cursor(0, 0));
        }
        final Header first = matching.next();
        if (!matching.hasNext()) {
            return input(first);
        }
        final CharArrayBuffer buffer = new CharArrayBuffer(64);
        append(buffer, first);
        do {
            buffer.append(", ");
            append(buffer, matching.next());
        } while (matching.hasNext());
        return new HeaderInput(buffer, new Tokenizer.Cursor(0, buffer.length()));
    }

    private static void append(final CharArrayBuffer buffer, final Header header) {
        if (header instanceof FormattedHeader) {
            final FormattedHeader formatted = (FormattedHeader) header;
            final CharArrayBuffer source = formatted.getBuffer();
            final int offset = formatted.getValuePos();
            buffer.append(source, offset, source.length() - offset);
        } else {
            buffer.append(Objects.toString(header.getValue(), ""));
        }
    }

    private static HeaderInput input(final Header header) {
        Args.notNull(header, "Header");
        if (header instanceof FormattedHeader) {
            final FormattedHeader formatted = (FormattedHeader) header;
            final CharArrayBuffer buffer = formatted.getBuffer();
            return new HeaderInput(buffer, new Tokenizer.Cursor(formatted.getValuePos(), buffer.length()));
        }
        final String value = Objects.toString(header.getValue(), "");
        return new HeaderInput(value, new Tokenizer.Cursor(0, value.length()));
    }

    private static final class HeaderInput {

        private final CharSequence value;
        private final Tokenizer.Cursor cursor;

        private HeaderInput(final CharSequence value, final Tokenizer.Cursor cursor) {
            this.value = value;
            this.cursor = cursor;
        }
    }
}
