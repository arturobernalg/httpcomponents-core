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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Converts string parameter values to typed Java values. Supports
 * primitive types, their wrapper types, and types that provide either
 * a {@code valueOf(String)} / {@code fromString(String)} static method
 * or a single-string constructor.
 *
 * @since 5.5
 */
final class ParamConverter {

    /**
     * Converts the given string value to the target type.
     *
     * @param value the string value, may be {@code null}.
     * @param type the target type.
     * @return the converted value.
     */
    static Object convert(final String value, final Class<?> type) {
        if (value == null) {
            return defaultValue(type);
        }
        if (type == String.class) {
            return value;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        }
        if (type == double.class || type == Double.class) {
            return Double.valueOf(value);
        }
        if (type == float.class || type == Float.class) {
            return Float.valueOf(value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value);
        }
        if (type == short.class || type == Short.class) {
            return Short.valueOf(value);
        }
        if (type == byte.class || type == Byte.class) {
            return Byte.valueOf(value);
        }
        if (type == char.class || type == Character.class) {
            return value.isEmpty() ? defaultValue(type) : value.charAt(0);
        }
        // Try valueOf(String)
        try {
            final Method valueOf = type.getMethod("valueOf", String.class);
            if (java.lang.reflect.Modifier.isStatic(valueOf.getModifiers())) {
                return valueOf.invoke(null, value);
            }
        } catch (final NoSuchMethodException ignored) {
            // fall through
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot convert '" + value + "' to " + type.getName(), e);
        }
        // Try fromString(String)
        try {
            final Method fromString = type.getMethod("fromString", String.class);
            if (java.lang.reflect.Modifier.isStatic(fromString.getModifiers())) {
                return fromString.invoke(null, value);
            }
        } catch (final NoSuchMethodException ignored) {
            // fall through
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot convert '" + value + "' to " + type.getName(), e);
        }
        // Try single-String constructor
        try {
            final Constructor<?> ctor = type.getConstructor(String.class);
            return ctor.newInstance(value);
        } catch (final NoSuchMethodException ignored) {
            // fall through
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot convert '" + value + "' to " + type.getName(), e);
        }
        throw new IllegalArgumentException("No conversion from String to " + type.getName());
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type.isPrimitive()) {
            return 0;
        }
        return null;
    }

    private ParamConverter() {
    }

}
