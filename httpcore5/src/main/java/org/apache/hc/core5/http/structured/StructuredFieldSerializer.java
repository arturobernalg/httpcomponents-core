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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import org.apache.hc.core5.util.CharArrayBuffer;

/**
 * Canonical serializer for Structured Field Structured Field Values.
 *
 * @since 5.5
 */
public final class StructuredFieldSerializer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private StructuredFieldSerializer() {
    }

    /**
     * Serializes a top-level field value.
     *
     * @param value the Structured Field value.
     * @return the field value, or {@code null} for an empty List or Dictionary,
     *         which Structured Field represents by omitting the field.
     */
    public static String serialize(final StructuredFieldValue value) {
        Objects.requireNonNull(value, "Structured Field value");
        if (isEmpty(value)) {
            return null;
        }
        final CharArrayBuffer buffer = new CharArrayBuffer(64);
        serialize(buffer, value);
        return buffer.toString();
    }

    /**
     * Appends the canonical serialization of a top-level Structured Field value.
     * Empty Lists and Dictionaries append no characters because Structured Field
     * represents them by omitting the field.
     *
     * @param buffer the destination buffer.
     * @param value the Structured Field value.
     */
    public static void serialize(final CharArrayBuffer buffer, final StructuredFieldValue value) {
        Objects.requireNonNull(buffer, "Destination");
        Objects.requireNonNull(value, "Structured Field value");
        if (value instanceof StructuredFieldItem) {
            serializeItem(buffer, (StructuredFieldItem) value);
        } else if (value instanceof StructuredFieldList) {
            serializeList(buffer, (StructuredFieldList) value);
        } else if (value instanceof StructuredFieldDictionary) {
            serializeDictionary(buffer, (StructuredFieldDictionary) value);
        } else {
            throw new IllegalArgumentException("Unsupported Structured Field value: " + value.getClass());
        }
    }

    /**
     * @param item the Item.
     * @return the canonical serialized Item.
     */
    public static String serializeItem(final StructuredFieldItem item) {
        Objects.requireNonNull(item, "Item");
        final CharArrayBuffer buffer = new CharArrayBuffer(32);
        serializeItem(buffer, item);
        return buffer.toString();
    }

    /**
     * Appends a canonical serialized Item.
     *
     * @param buffer the destination buffer.
     * @param item the Item.
     */
    public static void serializeItem(final CharArrayBuffer buffer, final StructuredFieldItem item) {
        Objects.requireNonNull(buffer, "Destination");
        Objects.requireNonNull(item, "Item");
        serializeBareItem(buffer, item.getBareItem());
        serializeParameters(buffer, item.getParameters());
    }

    /**
     * @param list the List.
     * @return the canonical serialized List, or {@code null} when empty.
     */
    public static String serializeList(final StructuredFieldList list) {
        Objects.requireNonNull(list, "List");
        if (list.isEmpty()) {
            return null;
        }
        final CharArrayBuffer buffer = new CharArrayBuffer(64);
        serializeList(buffer, list);
        return buffer.toString();
    }

    /**
     * Appends a canonical serialized List. An empty List appends no characters.
     *
     * @param buffer the destination buffer.
     * @param list the List.
     */
    public static void serializeList(final CharArrayBuffer buffer, final StructuredFieldList list) {
        Objects.requireNonNull(buffer, "Destination");
        Objects.requireNonNull(list, "List");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                buffer.append(", ");
            }
            serializeMember(buffer, list.get(i));
        }
    }

    /**
     * @param dictionary the Dictionary.
     * @return the canonical serialized Dictionary, or {@code null} when empty.
     */
    public static String serializeDictionary(final StructuredFieldDictionary dictionary) {
        Objects.requireNonNull(dictionary, "Dictionary");
        if (dictionary.isEmpty()) {
            return null;
        }
        final CharArrayBuffer buffer = new CharArrayBuffer(64);
        serializeDictionary(buffer, dictionary);
        return buffer.toString();
    }

    /**
     * Appends a canonical serialized Dictionary. An empty Dictionary appends no characters.
     *
     * @param buffer the destination buffer.
     * @param dictionary the Dictionary.
     */
    public static void serializeDictionary(
            final CharArrayBuffer buffer, final StructuredFieldDictionary dictionary) {
        Objects.requireNonNull(buffer, "Destination");
        Objects.requireNonNull(dictionary, "Dictionary");
        int index = 0;
        for (final Map.Entry<String, StructuredFieldMember> entry : dictionary) {
            if (index++ > 0) {
                buffer.append(", ");
            }
            buffer.append(entry.getKey());
            final StructuredFieldMember member = entry.getValue();
            if (member instanceof StructuredFieldItem
                    && isTrue(((StructuredFieldItem) member).getBareItem())) {
                serializeParameters(buffer, member.getParameters());
            } else {
                buffer.append('=');
                serializeMember(buffer, member);
            }
        }
    }

    /**
     * @param innerList the Inner List.
     * @return the canonical serialized Inner List.
     */
    public static String serializeInnerList(final StructuredFieldInnerList innerList) {
        Objects.requireNonNull(innerList, "Inner List");
        final CharArrayBuffer buffer = new CharArrayBuffer(32);
        serializeInnerList(buffer, innerList);
        return buffer.toString();
    }

    /**
     * Appends a canonical serialized Inner List.
     *
     * @param buffer the destination buffer.
     * @param innerList the Inner List.
     */
    public static void serializeInnerList(
            final CharArrayBuffer buffer, final StructuredFieldInnerList innerList) {
        Objects.requireNonNull(buffer, "Destination");
        Objects.requireNonNull(innerList, "Inner List");
        buffer.append('(');
        for (int i = 0; i < innerList.size(); i++) {
            if (i > 0) {
                buffer.append(' ');
            }
            serializeItem(buffer, innerList.get(i));
        }
        buffer.append(')');
        serializeParameters(buffer, innerList.getParameters());
    }

    /**
     * @param parameters the Parameters map.
     * @return the canonical serialized parameters.
     */
    public static String serializeParameters(final StructuredFieldParameters parameters) {
        Objects.requireNonNull(parameters, "Parameters");
        final CharArrayBuffer buffer = new CharArrayBuffer(32);
        serializeParameters(buffer, parameters);
        return buffer.toString();
    }

    /**
     * Appends canonical serialized parameters.
     *
     * @param buffer the destination buffer.
     * @param parameters the Parameters map.
     */
    public static void serializeParameters(
            final CharArrayBuffer buffer, final StructuredFieldParameters parameters) {
        Objects.requireNonNull(buffer, "Destination");
        Objects.requireNonNull(parameters, "Parameters");
        for (final Map.Entry<String, StructuredFieldBareItem> entry : parameters) {
            buffer.append(';');
            buffer.append(entry.getKey());
            if (!isTrue(entry.getValue())) {
                buffer.append('=');
                serializeBareItem(buffer, entry.getValue());
            }
        }
    }

    /**
     * @param item the bare Item.
     * @return the canonical serialized bare Item.
     */
    public static String serializeBareItem(final StructuredFieldBareItem item) {
        Objects.requireNonNull(item, "Bare Item");
        final CharArrayBuffer buffer = new CharArrayBuffer(32);
        serializeBareItem(buffer, item);
        return buffer.toString();
    }

    /**
     * Appends a canonical serialized bare Item.
     *
     * @param buffer the destination buffer.
     * @param item the bare Item.
     */
    public static void serializeBareItem(final CharArrayBuffer buffer, final StructuredFieldBareItem item) {
        Objects.requireNonNull(buffer, "Destination");
        Objects.requireNonNull(item, "Bare Item");
        switch (item.getType()) {
        case INTEGER:
            buffer.append(Long.toString(item.getLongValue()));
            break;
        case DECIMAL:
            buffer.append(item.getDecimalValue().toPlainString());
            break;
        case STRING:
            serializeString(buffer, item.getTextValue());
            break;
        case TOKEN:
            buffer.append(item.getTextValue());
            break;
        case BYTE_SEQUENCE:
            buffer.append(':');
            buffer.append(Base64.getEncoder().encodeToString(item.getByteSequenceValue()));
            buffer.append(':');
            break;
        case BOOLEAN:
            buffer.append(item.getBooleanValue() ? "?1" : "?0");
            break;
        case DATE:
            buffer.append('@');
            buffer.append(Long.toString(item.getLongValue()));
            break;
        case DISPLAY_STRING:
            serializeDisplayString(buffer, item.getTextValue());
            break;
        default:
            throw new IllegalStateException("Unsupported bare Item type: " + item.getType());
        }
    }

    private static void serializeMember(final CharArrayBuffer buffer, final StructuredFieldMember member) {
        if (member instanceof StructuredFieldItem) {
            serializeItem(buffer, (StructuredFieldItem) member);
        } else if (member instanceof StructuredFieldInnerList) {
            serializeInnerList(buffer, (StructuredFieldInnerList) member);
        } else {
            throw new IllegalArgumentException("Unsupported Structured Field member: " + member.getClass());
        }
    }

    private static boolean isTrue(final StructuredFieldBareItem value) {
        return value.getType() == StructuredFieldType.BOOLEAN && value.getBooleanValue();
    }

    private static void serializeString(final CharArrayBuffer buffer, final String value) {
        buffer.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                buffer.append('\\');
            }
            buffer.append(ch);
        }
        buffer.append('"');
    }

    private static void serializeDisplayString(final CharArrayBuffer buffer, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.append('%');
        buffer.append('"');
        for (final byte signedByte : bytes) {
            final int b = signedByte & 0xff;
            if (b == 0x25 || b == 0x22 || b <= 0x1f || b >= 0x7f) {
                buffer.append('%');
                buffer.append(HEX[b >>> 4]);
                buffer.append(HEX[b & 0x0f]);
            } else {
                buffer.append((char) b);
            }
        }
        buffer.append('"');
    }

    private static boolean isEmpty(final StructuredFieldValue value) {
        return value instanceof StructuredFieldList && ((StructuredFieldList) value).isEmpty()
                || value instanceof StructuredFieldDictionary && ((StructuredFieldDictionary) value).isEmpty();
    }
}
