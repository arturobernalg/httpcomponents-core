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

package org.apache.hc.core5.testing.extension;

import java.io.IOException;

import org.apache.hc.core5.testing.SocksProxy;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocksProxyResource implements AfterEachCallback {

    private static final Logger LOG = LoggerFactory.getLogger(SocksProxyResource.class);

    private final SocksProxy proxy;

    public SocksProxyResource() {
        LOG.debug("Starting up SOCKS proxy");
        this.proxy = new SocksProxy();
        try {
            this.proxy.start();
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void afterEach(final ExtensionContext extensionContext) throws Exception {
        LOG.debug("Shutting down SOCKS proxy");
        if (proxy != null) {
            try {
                proxy.shutdown(TimeValue.ofSeconds(5));
            } catch (final Exception ignore) {
            }
        }
    }

    public SocksProxy proxy() {
        return proxy;
    }

}
