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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.Test;

import com.helger.datetime.helper.PDTFactory;

/**
 * Test class for {@link OutboundOrchestrator#getCircuitBreakerNextRetryDT(OffsetDateTime,
 * OffsetDateTime, Duration, Duration, Duration)}.
 *
 * @author Philip Helger
 */
public final class OutboundOrchestratorCircuitBreakerTest
{
  private static final Duration MIN_DELAY = Duration.ofMinutes (1);
  private static final Duration MAX_DEFER_DURATION = Duration.ofHours (12);

  private static final OffsetDateTime NOW = PDTFactory.getCurrentOffsetDateTimeUTC ();

  @Test
  public void testNextRetryIsAtLeastTheRemainingDelay ()
  {
    final Duration aRemainingDelay = Duration.ofMinutes (5);
    final OffsetDateTime aNextRetry = OutboundOrchestrator.getCircuitBreakerNextRetryDT (NOW,
                                                                                         NOW.minusMinutes (10),
                                                                                         aRemainingDelay,
                                                                                         MIN_DELAY,
                                                                                         MAX_DEFER_DURATION);
    assertNotNull (aNextRetry);
    assertEquals (NOW.plus (aRemainingDelay), aNextRetry);
    assertTrue ("The next retry must not be before the circuit breaker may grant a permit again",
                !aNextRetry.isBefore (NOW.plus (aRemainingDelay)));
  }

  @Test
  public void testTheMinimumDelayWins ()
  {
    // A half-open circuit breaker has no remaining delay at all
    final OffsetDateTime aNextRetry = OutboundOrchestrator.getCircuitBreakerNextRetryDT (NOW,
                                                                                         NOW.minusMinutes (10),
                                                                                         Duration.ZERO,
                                                                                         MIN_DELAY,
                                                                                         MAX_DEFER_DURATION);
    assertNotNull (aNextRetry);
    assertEquals (NOW.plus (MIN_DELAY), aNextRetry);
  }

  @Test
  public void testAnUnknownCreationDateIsAlwaysDeferred ()
  {
    assertNotNull (OutboundOrchestrator.getCircuitBreakerNextRetryDT (NOW,
                                                                      null,
                                                                      Duration.ofSeconds (30),
                                                                      MIN_DELAY,
                                                                      MAX_DEFER_DURATION));
  }

  @Test
  public void testAnOldTransactionIsNotDeferredAnymore ()
  {
    // Exactly at the limit it is still deferred
    assertNotNull (OutboundOrchestrator.getCircuitBreakerNextRetryDT (NOW,
                                                                      NOW.minus (MAX_DEFER_DURATION),
                                                                      Duration.ofSeconds (30),
                                                                      MIN_DELAY,
                                                                      MAX_DEFER_DURATION));

    // One second later the regular attempt counting takes over again
    assertNull (OutboundOrchestrator.getCircuitBreakerNextRetryDT (NOW,
                                                                   NOW.minus (MAX_DEFER_DURATION).minusSeconds (1),
                                                                   Duration.ofSeconds (30),
                                                                   MIN_DELAY,
                                                                   MAX_DEFER_DURATION));
  }
}
