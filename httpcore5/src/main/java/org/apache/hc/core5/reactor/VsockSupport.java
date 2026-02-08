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
package org.apache.hc.core5.reactor;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

import org.apache.hc.core5.annotation.Internal;

/**
 * VSOCK support utilities for reactor integration.
 *
 * @since 5.5
 */
@Internal
public final class VsockSupport {
    private static final String[] PROVIDER_CLASS_NAMES = {
            "org.newsclub.net.unix.vsock.AFVSOCKSelectorProvider",
            "org.newsclub.net.vsock.AFVSOCKSelectorProvider"
    };

    private VsockSupport() {
    }

    public static boolean isVsockAddress(final SocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return false;
        }
        if (remoteAddress instanceof InetSocketAddress) {
            final String hostName = ((InetSocketAddress) remoteAddress).getHostName();
            if (hostName != null && hostName.contains(".vsock.junixsocket")) {
                return true;
            }
        }
        final String className = remoteAddress.getClass().getName();
        return "org.newsclub.net.unix.AFVSOCKSocketAddress".equals(className)
            || "org.newsclub.net.vsock.AFVSOCKSocketAddress".equals(className)
            || "org.newsclub.net.unix.vsock.AFVSOCKSocketAddress".equals(className);
    }

    public static SelectorProvider resolveSelectorProvider() throws ReflectiveOperationException {
        for (final String className : PROVIDER_CLASS_NAMES) {
            try {
                final Class<?> providerClass = Class.forName(className);
                try {
                    return (SelectorProvider) providerClass.getMethod("provider").invoke(null);
                } catch (final NoSuchMethodException ignore) {
                }
                return (SelectorProvider) providerClass.getMethod("getInstance").invoke(null);
            } catch (final ClassNotFoundException ignore) {
            }
        }
        throw new ClassNotFoundException("AFVSOCKSelectorProvider not found");
    }

    public static SocketChannel openVsockChannel() throws ReflectiveOperationException {
        final SelectorProvider provider = resolveSelectorProvider();
        final Method openMethod = provider.getClass().getMethod("openSocketChannel");
        return (SocketChannel) openMethod.invoke(provider);
    }
}
