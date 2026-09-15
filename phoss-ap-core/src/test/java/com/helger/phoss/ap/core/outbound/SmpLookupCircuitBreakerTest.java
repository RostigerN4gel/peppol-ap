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
package com.helger.phoss.ap.core.outbound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.helger.config.ConfigFactory;
import com.helger.config.fallback.ConfigWithFallback;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.phase4.dynamicdiscovery.Phase4SMPException;
import com.helger.phase4.util.Phase4Exception;
import com.helger.phoss.ap.api.config.APConfigProvider;
import com.helger.phoss.ap.api.config.APConfigurationProperties;
import com.helger.phoss.ap.core.CircuitBreakerManager;
import com.helger.smpclient.exception.SMPClientSMPUnavailableException;

/**
 * Test the interaction of {@link SmpLookupFailureClassifier} and {@link CircuitBreakerManager} the
 * way {@code OutboundOrchestrator._performSmpLookup} uses them.
 *
 * @author Philip Helger
 */
public final class SmpLookupCircuitBreakerTest
{
  private static final String KEY = "smp$https://unittest.example.org";
  private static final int FAILURE_THRESHOLD = 5;

  private static final String MSG_NOT_REGISTERED = "Failed to resolve SMP endpoint (iso6523-actorid-upis::9915:test, " +
                                                   "busdox-docid-qns::doctype, cenbii-procid-ubl::process, " +
                                                   "peppol-transport-as4-v2_0) [static] - server error";

  private IConfigWithFallback m_aOldConfig;

  /**
   * Record the outcome of a single SMP lookup on the circuit breaker, exactly as
   * {@code OutboundOrchestrator._performSmpLookup} does.
   */
  private static void _recordLookupFailure (final Phase4Exception aEx)
  {
    if (SmpLookupFailureClassifier.getFailureKind (aEx).isNegativeAnswer ())
      CircuitBreakerManager.recordSuccess (KEY);
    else
      CircuitBreakerManager.recordFailure (KEY);
  }

  @Before
  public void before ()
  {
    m_aOldConfig = APConfigProvider.getConfig ();
    System.setProperty (APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_THRESHOLD,
                        Integer.toString (FAILURE_THRESHOLD));
    System.setProperty (APConfigurationProperties.CIRCUIT_BREAKER_OPEN_DURATION, "1m");
    APConfigProvider.setConfig (new ConfigWithFallback (ConfigFactory.createDefaultValueProvider ()));
    CircuitBreakerManager.removeAll ();
  }

  @After
  public void after ()
  {
    CircuitBreakerManager.removeAll ();
    System.clearProperty (APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_THRESHOLD);
    System.clearProperty (APConfigurationProperties.CIRCUIT_BREAKER_OPEN_DURATION);
    APConfigProvider.setConfig (m_aOldConfig);
  }

  @Test
  public void testManyNotRegisteredLookupsDoNotOpenTheCircuitBreaker ()
  {
    final Phase4SMPException aEx = new Phase4SMPException (MSG_NOT_REGISTERED);

    // Way more than the failure threshold
    for (int i = 0; i < FAILURE_THRESHOLD * 4; ++i)
    {
      assertTrue ("Permit " + i + " was not granted", CircuitBreakerManager.tryAcquirePermit (KEY));
      _recordLookupFailure (aEx);
    }

    // The SMP answered every single time, so it is still fully usable
    assertTrue (CircuitBreakerManager.tryAcquirePermit (KEY));
    CircuitBreakerManager.recordSuccess (KEY);
  }

  @Test
  public void testNotRegisteredLookupsDoNotConsumeRetryAttempts ()
  {
    // A negative answer is never retried against the same SMP, independent of what phase4 reports
    // as "retry feasible"
    final Phase4SMPException aEx = new Phase4SMPException (MSG_NOT_REGISTERED);
    assertTrue ("phase4 reports a negative answer as retry feasible", aEx.isRetryFeasible ());
    assertTrue (SmpLookupFailureClassifier.getFailureKind (aEx).isNegativeAnswer ());
    assertFalse (SmpLookupFailureClassifier.getFailureKind (aEx).isSmpUnavailable ());
  }

  @Test
  public void testSmpUnavailableLookupsStillOpenTheCircuitBreaker ()
  {
    final Phase4SMPException aEx = (Phase4SMPException) new Phase4SMPException ("Failed to resolve SMP endpoint (x)",
                                                                                new SMPClientSMPUnavailableException (new SocketTimeoutException ("Read timed out"))).setRetryFeasible (false);
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, SmpLookupFailureClassifier.getFailureKind (aEx));

    for (int i = 0; i < FAILURE_THRESHOLD; ++i)
    {
      assertTrue ("Permit " + i + " was not granted", CircuitBreakerManager.tryAcquirePermit (KEY));
      _recordLookupFailure (aEx);
    }

    // Now the SMP is suspended
    assertFalse (CircuitBreakerManager.tryAcquirePermit (KEY));
  }
}
