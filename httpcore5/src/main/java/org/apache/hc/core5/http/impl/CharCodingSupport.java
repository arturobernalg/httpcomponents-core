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

package org.apache.hc.core5.http.impl;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

import org.apache.hc.core5.http.config.CharCodingConfig;

public final class CharCodingSupport {

    private CharCodingSupport() {
    }

    public static CharsetDecoder createDecoder(final CharCodingConfig codingConfig) {
        if (codingConfig == null) {
            return null;
        }
        final Charset charset = codingConfig.getCharset();
        final CodingErrorAction malformed = codingConfig.getMalformedInputAction();
        final CodingErrorAction unmappable = codingConfig.getUnmappableInputAction();
        if (charset != null) {
            return charset.newDecoder()
                    .onMalformedInput(malformed != null ? malformed : CodingErrorAction.REPORT)
                    .onUnmappableCharacter(unmappable != null ? unmappable: CodingErrorAction.REPORT);
        }
        return null;
    }

    public static CharsetEncoder createEncoder(final CharCodingConfig codingConfig) {
        if (codingConfig == null) {
            return null;
        }
        final Charset charset = codingConfig.getCharset();
        if (charset != null) {
            final CodingErrorAction malformed = codingConfig.getMalformedInputAction();
            final CodingErrorAction unmappable = codingConfig.getUnmappableInputAction();
            return charset.newEncoder()
                    .onMalformedInput(malformed != null ? malformed : CodingErrorAction.REPORT)
                    .onUnmappableCharacter(unmappable != null ? unmappable: CodingErrorAction.REPORT);
        }
        return null;
    }

}
