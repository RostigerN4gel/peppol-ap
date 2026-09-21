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

import java.io.IOException;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.helger.phase4.dynamicdiscovery.Phase4SMPException;
import com.helger.phase4.util.Phase4Exception;
import com.helger.smpclient.exception.SMPClientBadResponseException;
import com.helger.smpclient.exception.SMPClientException;
import com.helger.smpclient.exception.SMPClientHttpException;
import com.helger.smpclient.exception.SMPClientSMPUnavailableException;

/**
 * Classifies a {@link Phase4Exception} thrown by an SMP lookup, so that only failures of the SMP
 * infrastructure are counted by the SMP circuit breaker. A "not registered" answer is a valid
 * answer of a perfectly healthy SMP - counting it as a failure suspends a large SMP for all of its
 * participants after a handful of lookups for unregistered receivers.
 *
 * @author Philip Helger
 * @since 0.13.0
 */
final class SmpLookupFailureClassifier
{
  /**
   * Prefix of the messages created by {@code AS4EndpointDetailProviderPeppol.init}.
   */
  private static final String MSG_PREFIX = "Failed to resolve SMP endpoint (";
  /**
   * Message suffix used if the SMP returned no Service Metadata at all. This is what a HTTP 404 of
   * the SMP looks like, because {@code SMPClientReadOnly.getServiceMetadataOrNull} swallows the
   * {@code SMPClientNotFoundException} and returns <code>null</code> instead.
   */
  private static final String MSG_SUFFIX_NO_SERVICE_METADATA = " - server error";
  /**
   * Message suffix used if the SMP returned Service Metadata, but none of the contained endpoints
   * matches the requested process and transport profile.
   */
  private static final String MSG_SUFFIX_NO_MATCHING_ENDPOINT = " - failed to select endpoint from ServiceMetadata";

  /** The minimum HTTP status code that indicates a problem on the SMP side. */
  private static final int HTTP_SERVER_ERROR_MIN = 500;

  private SmpLookupFailureClassifier ()
  {}

  /**
   * Check whether the provided exception message is one of the "the SMP answered, but there is no
   * (matching) registration" messages of the phase4 endpoint detail provider.
   * <p>
   * TODO remove with phase4 4.7 (Phase4SMPException.getErrorType) - until then the negative answers
   * cannot be told apart from a real error by any other means than the exception message, because
   * both are reported as a cause-less {@link Phase4SMPException} with
   * {@code isRetryFeasible() == true}.
   * </p>
   */
  private static boolean _isNegativeAnswerMessage (@Nullable final String sMessage)
  {
    if (sMessage == null || !sMessage.startsWith (MSG_PREFIX))
      return false;
    return sMessage.endsWith (MSG_SUFFIX_NO_SERVICE_METADATA) || sMessage.endsWith (MSG_SUFFIX_NO_MATCHING_ENDPOINT);
  }

  /**
   * Classify a single {@link SMPClientException} from the cause chain.
   */
  @NonNull
  private static ESmpLookupFailureKind _getKindOfSmpClientException (@NonNull final SMPClientException aEx)
  {
    // The SMP host could not be resolved, the connection failed or it timed out
    if (aEx instanceof SMPClientSMPUnavailableException)
      return ESmpLookupFailureKind.SMP_UNAVAILABLE;

    // The SMP answered, but the answer could not be parsed or its signature is invalid
    if (aEx instanceof SMPClientBadResponseException)
      return ESmpLookupFailureKind.SMP_UNAVAILABLE;

    // A HTTP 5xx is a problem of the SMP, a HTTP 4xx is a problem of the request
    if (aEx instanceof final SMPClientHttpException aHttpEx)
      return aHttpEx.getResponseStatusCode () >= HTTP_SERVER_ERROR_MIN ? ESmpLookupFailureKind.SMP_UNAVAILABLE
                                                                       : ESmpLookupFailureKind.OTHER;

    // The generic fallback of SMPClientReadOnly.getConvertedException for any other I/O problem
    if (aEx.getClass ().equals (SMPClientException.class))
      return ESmpLookupFailureKind.SMP_UNAVAILABLE;

    return ESmpLookupFailureKind.OTHER;
  }

  /**
   * Classify the provided SMP lookup exception.
   *
   * @param aEx
   *        The exception thrown by the SMP lookup. May not be <code>null</code>.
   * @return The failure kind. Never <code>null</code>.
   */
  @NonNull
  public static ESmpLookupFailureKind getFailureKind (@NonNull final Phase4Exception aEx)
  {
    // Walk the cause chain - the SMP specific exception is the interesting one
    Throwable aCur = aEx.getCause ();
    while (aCur != null)
    {
      if (aCur instanceof final SMPClientException aSMPEx)
        return _getKindOfSmpClientException (aSMPEx);

      // Defensive: if phase4 ever stops wrapping into an SMPClientException, the plain I/O
      // exception still means the SMP could not be reached
      if (aCur instanceof IOException)
        return ESmpLookupFailureKind.SMP_UNAVAILABLE;

      aCur = aCur.getCause ();
    }

    // No cause at all - that is how the phase4 endpoint detail provider reports the negative
    // answers of an SMP that responded just fine
    if (aEx instanceof Phase4SMPException && _isNegativeAnswerMessage (aEx.getMessage ()))
      return ESmpLookupFailureKind.NEGATIVE_ANSWER;

    return ESmpLookupFailureKind.OTHER;
  }
}
