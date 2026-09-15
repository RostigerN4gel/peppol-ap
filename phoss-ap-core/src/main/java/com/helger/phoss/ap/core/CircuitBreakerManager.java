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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.annotation.concurrent.ThreadSafe;
import com.helger.phoss.ap.api.config.APConfigurationProperties;
import com.helger.annotation.style.VisibleForTesting;

import dev.failsafe.CircuitBreaker;
import dev.failsafe.CircuitBreakerBuilder;

/**
 * This class manages the different circuit breakers used by phoss AP.
 *
 * @author Philip Helger
 */
@ThreadSafe
public final class CircuitBreakerManager
{
  private static final Logger LOGGER = LoggerFactory.getLogger (CircuitBreakerManager.class);

  /** The minimum failure thresholding period accepted by Failsafe. */
  private static final long MIN_FAILURE_PERIOD_MILLIS = 10;

  private static final ConcurrentHashMap <String, CircuitBreaker <Void>> BREAKERS = new ConcurrentHashMap <> ();

  private CircuitBreakerManager ()
  {}

  /**
   * Apply the configured failure thresholding to the provided circuit breaker builder. Three modes
   * are supported:
   * <ol>
   * <li>a rolling time window with a failure rate, if {@code circuit-breaker.failure-period} and
   * {@code circuit-breaker.failure-rate} are set</li>
   * <li>a rolling time window with an absolute failure count, if only
   * {@code circuit-breaker.failure-period} is set</li>
   * <li>an absolute failure count over the last N executions, if only
   * {@code circuit-breaker.failure-executions} is set</li>
   * </ol>
   * If neither of them is set, the default applies: N <b>consecutive</b> failures open the circuit
   * breaker. An invalid combination is logged as an error and falls back to that default, because
   * a circuit breaker that cannot be built would take the whole sending down.
   *
   * @param aBuilder
   *        The builder to apply the thresholding to. May not be <code>null</code>.
   * @param sCircuitKey
   *        The circuit breaker key, for logging only. May not be <code>null</code>.
   * @return The provided builder for chaining. Never <code>null</code>.
   */
  @NonNull
  private static CircuitBreakerBuilder <Void> _applyFailureThreshold (@NonNull final CircuitBreakerBuilder <Void> aBuilder,
                                                                      @NonNull final String sCircuitKey)
  {
    final int nFailureThreshold = APCoreConfig.getCircuitBreakerFailureThreshold ();
    final int nFailureExecutions = APCoreConfig.getCircuitBreakerFailureExecutions ();
    final int nFailureRate = APCoreConfig.getCircuitBreakerFailureRate ();
    final Duration aFailurePeriod = APCoreConfig.getCircuitBreakerFailurePeriod ();

    if (aFailurePeriod != null)
    {
      if (aFailurePeriod.toMillis () < MIN_FAILURE_PERIOD_MILLIS)
      {
        LOGGER.error ("The configuration property '" +
                      APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_PERIOD +
                      "' must be at least " +
                      MIN_FAILURE_PERIOD_MILLIS +
                      " ms - ignoring the time based thresholding of circuit breaker '" +
                      sCircuitKey +
                      "'");
      }
      else
        if (nFailureRate > 0)
        {
          if (nFailureRate > 100)
          {
            LOGGER.error ("The configuration property '" +
                          APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_RATE +
                          "' must be between 1 and 100 - ignoring the time based thresholding of circuit breaker '" +
                          sCircuitKey +
                          "'");
          }
          else
          {
            // Minimum number of executions before the rate is evaluated at all
            final int nExecutionThreshold = nFailureExecutions > 0 ? nFailureExecutions : nFailureThreshold;
            LOGGER.info ("The circuit breaker for '" +
                         sCircuitKey +
                         "' opens at a failure rate of " +
                         nFailureRate +
                         "% over at least " +
                         nExecutionThreshold +
                         " executions within " +
                         aFailurePeriod);
            return aBuilder.withFailureRateThreshold (nFailureRate, nExecutionThreshold, aFailurePeriod);
          }
        }
        else
        {
          final int nExecutionThreshold = nFailureExecutions > 0 ? nFailureExecutions : nFailureThreshold;
          if (nExecutionThreshold < nFailureThreshold)
          {
            LOGGER.error ("The configuration property '" +
                          APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_EXECUTIONS +
                          "' must not be smaller than '" +
                          APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_THRESHOLD +
                          "' - ignoring the time based thresholding of circuit breaker '" +
                          sCircuitKey +
                          "'");
          }
          else
          {
            LOGGER.info ("The circuit breaker for '" +
                         sCircuitKey +
                         "' opens at " +
                         nFailureThreshold +
                         " failures over at least " +
                         nExecutionThreshold +
                         " executions within " +
                         aFailurePeriod);
            return aBuilder.withFailureThreshold (nFailureThreshold, nExecutionThreshold, aFailurePeriod);
          }
        }
    }
    else
      if (nFailureExecutions > 0)
      {
        if (nFailureExecutions < nFailureThreshold)
        {
          LOGGER.error ("The configuration property '" +
                        APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_EXECUTIONS +
                        "' must not be smaller than '" +
                        APConfigurationProperties.CIRCUIT_BREAKER_FAILURE_THRESHOLD +
                        "' - ignoring the count based thresholding of circuit breaker '" +
                        sCircuitKey +
                        "'");
        }
        else
        {
          LOGGER.info ("The circuit breaker for '" +
                       sCircuitKey +
                       "' opens at " +
                       nFailureThreshold +
                       " failures over the last " +
                       nFailureExecutions +
                       " executions");
          return aBuilder.withFailureThreshold (nFailureThreshold, nFailureExecutions);
        }
      }

    // Default: consecutive failures
    return aBuilder.withFailureThreshold (nFailureThreshold);
  }

  @NonNull
  private static CircuitBreaker <Void> _getOrCreate (@NonNull final String sCircuitKey)
  {
    return BREAKERS.computeIfAbsent (sCircuitKey, k -> {
      LOGGER.info ("Creating circuit breaker for '" + k + "'");
      return _applyFailureThreshold (CircuitBreaker.<Void> builder (), k).withDelay (APCoreConfig.getCircuitBreakerOpenDuration ())
                                                                         .withSuccessThreshold (APCoreConfig.getCircuitBreakerHalfOpenMaxAttempts ())
                                                                         .onOpen (e -> LOGGER.info ("The circuit breaker for '" +
                                                                                                    k +
                                                                                                    "' was opened"))
                                                                         .onClose (e -> LOGGER.info ("The circuit breaker for '" +
                                                                                                     k +
                                                                                                     "' was closed"))
                                                                         .onHalfOpen (e -> LOGGER.info ("The circuit breaker for '" +
                                                                                                        k +
                                                                                                        "' was half-opened"))
                                                                         .build ();
    });
  }

  /**
   * Try to acquire a permit from the circuit breaker identified by the given key. If the circuit
   * breaker is open, no permit will be granted.
   *
   * @param sCircuitKey
   *        The circuit breaker key to acquire a permit for. May not be <code>null</code>.
   * @return {@code true} if the permit was acquired, {@code false} if the circuit is open.
   */
  public static boolean tryAcquirePermit (@NonNull final String sCircuitKey)
  {
    return _getOrCreate (sCircuitKey).tryAcquirePermit ();
  }

  /**
   * Record a successful operation for the circuit breaker identified by the given key.
   *
   * @param sCircuitKey
   *        The circuit breaker key to record the success for. May not be <code>null</code>.
   */
  public static void recordSuccess (@NonNull final String sCircuitKey)
  {
    _getOrCreate (sCircuitKey).recordSuccess ();
  }

  /**
   * Record a failed operation for the circuit breaker identified by the given key. If the failure
   * threshold is reached, the circuit breaker will open.
   *
   * @param sCircuitKey
   *        The circuit breaker key to record the failure for. May not be <code>null</code>.
   */
  public static void recordFailure (@NonNull final String sCircuitKey)
  {
    _getOrCreate (sCircuitKey).recordFailure ();
  }

  /**
   * Get the remaining delay until the circuit breaker identified by the given key transitions from
   * the open state to the half-open state. A circuit breaker that is not open returns
   * {@link Duration#ZERO}.
   *
   * @param sCircuitKey
   *        The circuit breaker key to query. May not be <code>null</code>.
   * @return The remaining delay. Never <code>null</code>.
   * @since 0.13.0
   */
  @NonNull
  public static Duration getRemainingDelay (@NonNull final String sCircuitKey)
  {
    return _getOrCreate (sCircuitKey).getRemainingDelay ();
  }

  /**
   * Remove all known circuit breakers, so that the next usage of a key creates a new circuit
   * breaker from the current configuration. Only intended for testing.
   */
  @VisibleForTesting
  public static void removeAll ()
  {
    BREAKERS.clear ();
  }
}
