/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information regarding
 * copyright ownership.  The ASF licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 * ====================================================================
 */
package org.apache.hc.core5.ws;

/**
 * RFC 6455, Section 5.2 — Opcode values.
 */
public enum WsOpcode {
    CONTINUATION(0x0),
    TEXT(0x1),
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA);

    public final int code;

    WsOpcode(final int code) {
        this.code = code;
    }

    public static WsOpcode fromCode(final int code) {
        switch (code) {
            case 0x0:
                return CONTINUATION;
            case 0x1:
                return TEXT;
            case 0x2:
                return BINARY;
            case 0x8:
                return CLOSE;
            case 0x9:
                return PING;
            case 0xA:
                return PONG;
            default:
                throw new IllegalArgumentException("Reserved/unknown opcode: " + code);
        }
    }
}
