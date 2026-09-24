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
package com.helger.phoss.ap.webapp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;

/**
 * Test class to load the {@link PhossAPApplication} and make sure we're good.
 * <p>
 * OpenTelemetry is deliberately enabled here, because starting with {@code otel.enabled=true} used
 * to fail with {@code IllegalStateException: GlobalOpenTelemetry.set has already been called}
 * (issue #102). The registered {@code OpenTelemetry} instance lives in a JVM global, so exactly one
 * test class of this module may load a context with it - a second one would find the global already
 * set and could not tell a regression from the leftovers of its predecessor. All exporters are
 * switched off, so a real SDK is built but nothing goes onto the network.
 * </p>
 *
 * @author Philip Helger
 */
@SpringBootTest (properties = { "otel.enabled=true",
                                "otel.traces.exporter=none",
                                "otel.metrics.exporter=none",
                                "otel.logs.exporter=none" })
final class PhossAPApplicationTest
{
  @BeforeAll
  static void init ()
  {
    System.setProperty ("phossap.internal.skip-peppol-certificate-check", "true");
  }

  @Test
  void testContextLoads ()
  {}

  @Test
  void testOpenTelemetrySdkIsTheGlobalInstance ()
  {
    // GlobalOpenTelemetry.get () wraps the registered instance, so the SDK cannot be recognized by
    // its type. It can be recognized by what it does: the no-op instance - which is what every span
    // taken before the SDK bootstrap receives - hands out an invalid, non-recording span
    final Span aSpan = GlobalOpenTelemetry.get ().getTracer ("phoss-ap-test").spanBuilder ("test").startSpan ();
    try
    {
      assertTrue (aSpan.getSpanContext ().isValid (), "The global OpenTelemetry instance is a no-op");
      assertTrue (aSpan.isRecording (), "The global OpenTelemetry instance does not record spans");
    }
    finally
    {
      aSpan.end ();
    }
  }
}
