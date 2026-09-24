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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

/**
 * Builds the OpenTelemetry SDK via the SDK autoconfigure module and registers it as the global
 * instance. All OpenTelemetry types are confined to this class, so that
 * {@link OtelBootstrapInitializer} can decide whether they are present at all without resolving
 * them.
 *
 * @author Philip Helger
 * @since 0.13.0
 */
final class OtelSdkInstaller
{
  private static final Logger LOGGER = LoggerFactory.getLogger (OtelSdkInstaller.class);

  private OtelSdkInstaller ()
  {}

  /**
   * Build the OpenTelemetry SDK and make it the global instance, so that
   * {@code GlobalOpenTelemetry.get ()} returns it - which is how the ph-telemetry OTel bindings
   * resolve it.
   */
  static void installSdk ()
  {
    LOGGER.info ("Initializing OpenTelemetry via SDK autoconfigure");

    // Deliberately built without "setResultAsGlobal ()", which would build and register in one
    // step and throw if a global instance is already present - e.g. one installed by the
    // OpenTelemetry Java agent. That would abort the startup and leak the SDK that was just
    // built, because the exception leaves no handle to close it. The shutdown hook that flushes
    // the exporters on JVM exit is registered by build () either way.
    final OpenTelemetrySdk aSdk = AutoConfiguredOpenTelemetrySdk.builder ().build ().getOpenTelemetrySdk ();
    try
    {
      GlobalOpenTelemetry.set (aSdk);
    }
    catch (final IllegalStateException ex)
    {
      // Somebody registered a global instance before this initializer ran
      LOGGER.warn ("An OpenTelemetry instance is already registered globally - keeping it and shutting down the SDK that was created here. " +
                   "If this AP runs with the OpenTelemetry Java agent, set '" +
                   OtelBootstrapInitializer.CONFIG_KEY_OTEL_ENABLED +
                   "=false' and let the agent do the instrumentation.",
                   ex);
      aSdk.close ();
      return;
    }

    LOGGER.info ("Successfully installed the OpenTelemetry SDK: " + aSdk.getClass ().getName ());
  }
}
