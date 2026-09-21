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

/**
 * Classification of a failed SMP lookup, as determined by {@link SmpLookupFailureClassifier}. It
 * decides whether the failure counts against the SMP circuit breaker and whether the receiver
 * should be retried.
 *
 * @author Philip Helger
 * @since 0.13.0
 */
enum ESmpLookupFailureKind
{
  /**
   * The SMP answered, but the receiver participant or the requested service is not registered. This
   * is a valid answer of a perfectly healthy SMP, so it must not count as an SMP failure.
   */
  NEGATIVE_ANSWER,
  /**
   * The SMP could not be contacted at all, or it did not deliver a usable answer. This is the only
   * kind of failure the SMP circuit breaker is meant to protect against, and it is always worth a
   * retry.
   */
  SMP_UNAVAILABLE,
  /**
   * Anything else - e.g. a malformed request or a problem with the resolved endpoint data. The
   * failure counts for the circuit breaker and the retry decision is left to
   * {@code Phase4Exception.isRetryFeasible()}.
   */
  OTHER;

  /**
   * @return <code>true</code> if this failure kind means the SMP itself is unavailable and the
   *         failure must therefore be counted by the circuit breaker.
   */
  boolean isSmpUnavailable ()
  {
    return this == SMP_UNAVAILABLE;
  }

  /**
   * @return <code>true</code> if the SMP delivered a valid negative answer.
   */
  boolean isNegativeAnswer ()
  {
    return this == NEGATIVE_ANSWER;
  }
}
