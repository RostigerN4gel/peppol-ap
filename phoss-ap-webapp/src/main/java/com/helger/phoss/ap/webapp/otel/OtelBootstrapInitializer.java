/*
 * Copyright (C) 2026 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phoss.ap.webapp.otel;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * Installs the OpenTelemetry SDK, if the {@code phoss-ap-otel} module is on the classpath and the
 * application property {@code otel.enabled} is {@code true}.
 * <p>
 * This is deliberately an {@link ApplicationContextInitializer} - registered via
 * {@code META-INF/spring.factories} - and <b>not</b> a bean or an event listener. The
 * initializers run after the environment is prepared but <b>before</b> the context is refreshed,
 * which means before the first bean is created. That ordering is the whole point: the very first
 * span of the application must be able to reach the SDK. Up to 0.12.0 the SDK was installed on the
 * {@code ApplicationStartedEvent} instead, which fires after the refresh - so the Flyway migration,
 * which happens during the creation of the AS4 servlet bean and is wrapped in a telemetry span,
 * always got there first. Back then that aborted the startup, because
 * {@code GlobalOpenTelemetry.get ()} registers the official no-op instance as the global one and
 * every later {@code GlobalOpenTelemetry.set (...)} then fails with an
 * {@link IllegalStateException} (issue #102). Since ph-telemetry 1.1.1 the bindings no longer claim
 * the global slot, so a late bootstrap is no longer fatal - but every span taken before it would
 * still be a silent no-op, which is why the SDK belongs here and not in a bean.
 * </p>
 * <p>
 * All other OpenTelemetry configuration (endpoints, headers, sampling, resource attributes) is
 * applied via standard OTel environment variables / system properties such as
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code OTEL_SERVICE_NAME}, {@code OTEL_RESOURCE_ATTRIBUTES}.
 * Refer to the OpenTelemetry Java SDK documentation for the full list.
 * </p>
 *
 * @author Philip Helger
 * @since 0.13.0
 */
public class OtelBootstrapInitializer implements ApplicationContextInitializer <ConfigurableApplicationContext>
{
  /** The application property that enables the OpenTelemetry SDK */
  public static final String CONFIG_KEY_OTEL_ENABLED = "otel.enabled";

  /** Only present if the "phoss-ap-otel" module is on the classpath */
  private static final String CLASS_NAME_AUTOCONFIGURE = "io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk";

  private static final Logger LOGGER = LoggerFactory.getLogger (OtelBootstrapInitializer.class);

  public void initialize (@NonNull final ConfigurableApplicationContext aContext)
  {
    final boolean bEnabled = aContext.getEnvironment ()
                                     .getProperty (CONFIG_KEY_OTEL_ENABLED, Boolean.class, Boolean.FALSE)
                                     .booleanValue ();
    if (!bEnabled)
      return;

    if (!ClassUtils.isPresent (CLASS_NAME_AUTOCONFIGURE, OtelBootstrapInitializer.class.getClassLoader ()))
    {
      LOGGER.warn ("'" +
                   CONFIG_KEY_OTEL_ENABLED +
                   "' is set, but the OpenTelemetry SDK is not on the classpath - add the 'phoss-ap-otel' module. Telemetry stays disabled.");
      return;
    }

    // Deliberately in a separate class, so that the OpenTelemetry types are only resolved once
    // they are known to be present
    OtelSdkInstaller.installSdk ();
  }
}
