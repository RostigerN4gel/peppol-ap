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
package com.helger.phoss.ap.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.helger.config.ConfigFactory;
import com.helger.config.fallback.ConfigWithFallback;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.phoss.ap.api.config.APConfigProvider;
import com.helger.phoss.ap.api.config.APConfigurationProperties;

/**
 * Test class for class {@link CircuitBreakerManager}.
 *
 * @author Philip Helger
 */
public final class CircuitBreakerManagerTest
{
  private static final String KEY = "unittest$" + CircuitBreakerManagerTest.class.getName ();

  private IConfigWithFallback m_aOldConfig;

  private static void _setSystemProperty (final String sKey, final String sValue)
  {
    if (sValue == null)
      System.clearProperty (sKey);
    else
      System.setProperty (sKey, sValue);
  }

  /**
   * Wait until the circuit breaker grants a permit again, but at most for the provided number of
   * milliseconds.
   */
  private static boolean _awaitPermit (final String sKey, final long nMaxWaitMillis) throws InterruptedException
  {
    final long nEnd = System.currentTimeMillis () + nMaxWaitMillis;
    do
    {
      if (CircuitBreakerManager.tryAcquirePermit (sKey))
        return true;
      Thread.sleep (10);
    } while (System.currentTimeMillis () < nEnd);
    return false;
  }

  @Before
  public void before ()
  {
    m_aOldConfig = APConfigProvider.getConfig ();

    // A very short open duration, so that the half-open state is reached quickly
    _setSystemProperty (APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_THRESHOLD, "2");
    _setSystemProperty (APConfigurationProperties.CIRCUIT_BREAKER_OPEN_DURATION, "50ms");
    _setSystemProperty (APConfigurationProperties.CIRCUIT_BREAKER_HALF_OPEN_MAX_ATTEMPTS, "1");
    APConfigProvider.setConfig (new ConfigWithFallback (ConfigFactory.createDefaultValueProvider ()));
    CircuitBreakerManager.removeAll ();
  }

  @After
  public void after ()
  {
    CircuitBreakerManager.removeAll ();
    _setSystemProperty (APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_THRESHOLD, null);
    _setSystemProperty (APConfigurationProperties.CIRCUIT_BREAKER_OPEN_DURATION, null);
    _setSystemProperty (APConfigurationProperties.CIRCUIT_BREAKER_HALF_OPEN_MAX_ATTEMPTS, null);
    APConfigProvider.setConfig (m_aOldConfig);
  }

  @Test
  public void testRecordedFailuresOpenAndReopenTheCircuitBreaker () throws InterruptedException
  {
    // Open the circuit breaker
    for (int i = 0; i < 2; ++i)
    {
      assertTrue (CircuitBreakerManager.tryAcquirePermit (KEY));
      CircuitBreakerManager.recordFailure (KEY);
    }
    assertFalse (CircuitBreakerManager.tryAcquirePermit (KEY));

    // After the open duration the half-open state grants a single permit again
    assertTrue (_awaitPermit (KEY, 5_000));
    CircuitBreakerManager.recordSuccess (KEY);

    // The circuit breaker is closed again, so every call is permitted
    assertTrue (CircuitBreakerManager.tryAcquirePermit (KEY));
    CircuitBreakerManager.recordSuccess (KEY);
  }

  @Test
  public void testUnrecordedPermitInHalfOpenStateDoesNotBlockForever () throws InterruptedException
  {
    // Open the circuit breaker
    for (int i = 0; i < 2; ++i)
    {
      assertTrue (CircuitBreakerManager.tryAcquirePermit (KEY));
      CircuitBreakerManager.recordFailure (KEY);
    }
    assertFalse (CircuitBreakerManager.tryAcquirePermit (KEY));

    // Wait for the half-open state and simulate a guarded block that throws a RuntimeException
    // after the permit was acquired. This is exactly what the "finally" blocks in the
    // orchestrators protect against - the permit of a half-open circuit breaker is only released
    // by recordSuccess or recordFailure.
    assertTrue (_awaitPermit (KEY, 5_000));
    boolean bResultRecorded = false;
    try
    {
      throw new IllegalStateException ("Something went wrong after the permit was acquired");
    }
    catch (final IllegalStateException ex)
    {
      // Expected
    }
    finally
    {
      if (!bResultRecorded)
      {
        CircuitBreakerManager.recordFailure (KEY);
        bResultRecorded = true;
      }
    }
    assertTrue (bResultRecorded);

    // Because the permit was released, the circuit breaker opens regularly and grants a new permit
    // after the open duration instead of rejecting every call forever
    assertTrue (_awaitPermit (KEY, 5_000));
    CircuitBreakerManager.recordSuccess (KEY);
    assertTrue (CircuitBreakerManager.tryAcquirePermit (KEY));
    CircuitBreakerManager.recordSuccess (KEY);
  }

  @Test
  public void testLeakedPermitInHalfOpenStateBlocksForever () throws InterruptedException
  {
    // This test documents the Failsafe behaviour that makes the "finally" blocks necessary: a
    // permit that is acquired in the half-open state and never recorded is never released again
    for (int i = 0; i < 2; ++i)
    {
      assertTrue (CircuitBreakerManager.tryAcquirePermit (KEY));
      CircuitBreakerManager.recordFailure (KEY);
    }

    // Acquire the single half-open permit and record nothing at all
    assertTrue (_awaitPermit (KEY, 5_000));

    // Waiting does not help anymore - the circuit breaker stays in the half-open state without a
    // free permit, so no further call is ever permitted
    if (_awaitPermit (KEY, 500))
      fail ("The circuit breaker unexpectedly granted a permit after a leaked one");
  }
}
