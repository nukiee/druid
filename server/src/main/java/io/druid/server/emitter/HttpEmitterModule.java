/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.server.emitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.name.Named;
import com.google.inject.util.Providers;
import io.druid.guice.LazySingleton;
import io.druid.java.util.common.logger.Logger;
import io.druid.java.util.emitter.core.Emitter;
import io.druid.java.util.emitter.core.HttpEmitterConfig;
import io.druid.java.util.emitter.core.HttpPostEmitter;
import io.druid.guice.JsonConfigProvider;
import io.druid.guice.ManageLifecycle;
import io.druid.java.util.common.concurrent.Execs;
import io.druid.java.util.common.lifecycle.Lifecycle;
import io.druid.server.security.TLSUtils;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.HashedWheelTimer;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;

import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

/**
 */
public class HttpEmitterModule implements Module
{
  private static final Logger log = new Logger(HttpEmitterModule.class);

  @Override
  public void configure(Binder binder)
  {
    JsonConfigProvider.bind(binder, "druid.emitter.http", HttpEmitterConfig.class);
    JsonConfigProvider.bind(binder, "druid.emitter.http.ssl", HttpEmitterSSLClientConfig.class);

    configureSsl(binder);
  }

  static void configureSsl(Binder binder)
  {
    final SSLContext context;
    try {
      context = SSLContext.getDefault();
    }
    catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }

    binder.bind(SSLContext.class).toProvider(Providers.of(context)).in(LazySingleton.class);
  }

  static AsyncHttpClient createAsyncHttpClient(
      String nameFormat,
      String timerThreadNameFormat,
      @Nullable SSLContext sslContext
  )
  {
    final DefaultAsyncHttpClientConfig.Builder builder = new DefaultAsyncHttpClientConfig.Builder()
        .setThreadFactory(Execs.makeThreadFactory(nameFormat))
        .setNettyTimer(new HashedWheelTimer(Execs.makeThreadFactory(timerThreadNameFormat)));
    if (sslContext != null) {
      builder.setSslContext(new JdkSslContext(sslContext, true, ClientAuth.NONE));
    }
    return new DefaultAsyncHttpClient(builder.build());
  }

  @Provides
  @ManageLifecycle
  @Named("http")
  public Emitter getEmitter(
      Supplier<HttpEmitterConfig> config,
      Supplier<HttpEmitterSSLClientConfig> sslConfig,
      @Nullable SSLContext sslContext,
      Lifecycle lifecycle,
      ObjectMapper jsonMapper
  )
  {
    return new HttpPostEmitter(
        config.get(),
        lifecycle.addCloseableInstance(
            createAsyncHttpClient(
                "HttpPostEmitter-AsyncHttpClient-%d",
                "HttpPostEmitter-AsyncHttpClient-Timer-%d",
                getEffectiveSSLContext(sslConfig.get(), sslContext)
            )
        ),
        jsonMapper
    );
  }

  public static SSLContext getEffectiveSSLContext(HttpEmitterSSLClientConfig sslConfig, SSLContext sslContext)
  {
    SSLContext effectiveSSLContext;
    if (sslConfig.isUseDefaultJavaContext()) {
      try {
        effectiveSSLContext = SSLContext.getDefault();
      }
      catch (NoSuchAlgorithmException nsae) {
        throw new RuntimeException(nsae);
      }
    } else if (sslConfig.getTrustStorePath() != null) {
      log.info("Creating SSLContext for HttpEmitter client using config [%s]", sslConfig);
      effectiveSSLContext = TLSUtils.createSSLContext(
          sslConfig.getProtocol(),
          sslConfig.getTrustStoreType(),
          sslConfig.getTrustStorePath(),
          sslConfig.getTrustStoreAlgorithm(),
          sslConfig.getTrustStorePasswordProvider()
      );
    } else {
      effectiveSSLContext = sslContext;
    }
    return effectiveSSLContext;
  }
}
