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

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.HttpResponseException;
import org.junit.Test;

import com.helger.phase4.dynamicdiscovery.Phase4SMPException;
import com.helger.phase4.util.Phase4Exception;
import com.helger.smpclient.exception.SMPClientBadRequestException;
import com.helger.smpclient.exception.SMPClientBadResponseException;
import com.helger.smpclient.exception.SMPClientException;
import com.helger.smpclient.exception.SMPClientHttpException;
import com.helger.smpclient.exception.SMPClientSMPUnavailableException;
import com.helger.smpclient.exception.SMPClientUnauthorizedException;

/**
 * Test class for class {@link SmpLookupFailureClassifier}.
 *
 * @author Philip Helger
 */
public final class SmpLookupFailureClassifierTest
{
  private static final String IDS = "iso6523-actorid-upis::9915:test, " +
                                    "busdox-docid-qns::urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1, " +
                                    "cenbii-procid-ubl::urn:fdc:peppol.eu:2017:poacc:billing:01:1.0, " +
                                    "peppol-transport-as4-v2_0";

  /**
   * The exact message created by {@code AS4EndpointDetailProviderPeppol.init} if the SMP returned
   * no Service Metadata at all (this is what a HTTP 404 looks like).
   */
  private static final String MSG_NO_SERVICE_METADATA = "Failed to resolve SMP endpoint (" +
                                                        IDS +
                                                        ") [static] - server error";
  /**
   * The exact message created by {@code AS4EndpointDetailProviderPeppol.init} if the SMP returned
   * Service Metadata without a matching endpoint.
   */
  private static final String MSG_NO_MATCHING_ENDPOINT = "Failed to resolve SMP endpoint (" +
                                                         IDS +
                                                         ") [static] - failed to select endpoint from ServiceMetadata";
  /** Same as before, but for a wildcard document type. */
  private static final String MSG_NO_MATCHING_ENDPOINT_WILDCARD = "Failed to resolve SMP endpoint (" +
                                                                  IDS +
                                                                  ") [wildcard] - failed to select endpoint from ServiceMetadata";
  /**
   * The exact message created by {@code AS4EndpointDetailProviderPeppol.init} if an
   * {@link SMPClientException} was caught - note that it has no " - ..." suffix.
   */
  private static final String MSG_SMP_CLIENT_EXCEPTION = "Failed to resolve SMP endpoint (" + IDS + ")";

  private static ESmpLookupFailureKind _classify (final Phase4Exception aEx)
  {
    return SmpLookupFailureClassifier.getFailureKind (aEx);
  }

  private static Phase4SMPException _wrap (final SMPClientException aCause)
  {
    // This is exactly what AS4EndpointDetailProviderPeppol.init does
    return (Phase4SMPException) new Phase4SMPException (MSG_SMP_CLIENT_EXCEPTION, aCause).setRetryFeasible (false);
  }

  @Test
  public void testNoServiceMetadataIsANegativeAnswer ()
  {
    // A cause-less Phase4SMPException with retryFeasible == true - the phase4 default
    final Phase4SMPException aEx = new Phase4SMPException (MSG_NO_SERVICE_METADATA);
    assertTrue (aEx.isRetryFeasible ());
    assertEquals (ESmpLookupFailureKind.NEGATIVE_ANSWER, _classify (aEx));
    assertTrue (_classify (aEx).isNegativeAnswer ());
    assertFalse (_classify (aEx).isSmpUnavailable ());
  }

  @Test
  public void testNoMatchingEndpointIsANegativeAnswer ()
  {
    assertEquals (ESmpLookupFailureKind.NEGATIVE_ANSWER, _classify (new Phase4SMPException (MSG_NO_MATCHING_ENDPOINT)));
    assertEquals (ESmpLookupFailureKind.NEGATIVE_ANSWER,
                  _classify (new Phase4SMPException (MSG_NO_MATCHING_ENDPOINT_WILDCARD)));
  }

  @Test
  public void testConnectTimeoutIsSmpUnavailable ()
  {
    // org.apache.hc.client5.http.ConnectTimeoutException extends SocketTimeoutException
    final SMPClientException aCause = new SMPClientSMPUnavailableException (new SocketTimeoutException ("connect timed out"));
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, _classify (_wrap (aCause)));
  }

  @Test
  public void testReadTimeoutIsSmpUnavailable ()
  {
    final SMPClientException aCause = new SMPClientSMPUnavailableException (new SocketTimeoutException ("Read timed out"));
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, _classify (_wrap (aCause)));
  }

  @Test
  public void testConnectionRefusedIsSmpUnavailable ()
  {
    // org.apache.hc.client5.http.HttpHostConnectException extends ConnectException
    final SMPClientException aCause = new SMPClientSMPUnavailableException (new ConnectException ("Connection refused"));
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, _classify (_wrap (aCause)));
  }

  @Test
  public void testUnknownHostIsSmpUnavailable ()
  {
    final SMPClientException aCause = new SMPClientSMPUnavailableException (new UnknownHostException ("smp.example.org"));
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, _classify (_wrap (aCause)));
  }

  @Test
  public void testHttp5xxIsSmpUnavailable ()
  {
    final SMPClientException aCause = new SMPClientHttpException (503,
                                                                  "Error thrown with HTTP status code 503",
                                                                  new HttpResponseException (503,
                                                                                             "Service Unavailable"));
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, _classify (_wrap (aCause)));
  }

  @Test
  public void testInvalidXmlOrSignatureIsSmpUnavailable ()
  {
    final SMPClientException aCause = new SMPClientBadResponseException ("Failed to parse the SMP response",
                                                                         new IOException ("Malformed XML"));
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, _classify (_wrap (aCause)));
  }

  @Test
  public void testGenericIoProblemIsSmpUnavailable ()
  {
    // The generic fallback of AbstractGenericSMPClient.getConvertedException
    final SMPClientException aCause = new SMPClientException ("Unknown error thrown by SMP server (broken pipe)",
                                                              new IOException ("broken pipe"));
    assertEquals (ESmpLookupFailureKind.SMP_UNAVAILABLE, _classify (_wrap (aCause)));
  }

  @Test
  public void testHttp4xxIsOther ()
  {
    final SMPClientException aBadRequest = new SMPClientBadRequestException (new HttpResponseException (400,
                                                                                                        "Bad Request"));
    assertEquals (ESmpLookupFailureKind.OTHER, _classify (_wrap (aBadRequest)));

    final SMPClientException aUnauthorized = new SMPClientUnauthorizedException (new HttpResponseException (403,
                                                                                                            "Forbidden"));
    assertEquals (ESmpLookupFailureKind.OTHER, _classify (_wrap (aUnauthorized)));
  }

  @Test
  public void testUnrelatedPhase4ExceptionIsOther ()
  {
    // E.g. from AS4EndpointDetailProviderPeppol.getReceiverAPCertificate
    assertEquals (ESmpLookupFailureKind.OTHER,
                  _classify (new Phase4Exception ("Failed to extract AP certificate from SMP endpoint: null")));
    // A cause-less Phase4SMPException with an unknown message must not be guessed to be a
    // negative answer
    assertEquals (ESmpLookupFailureKind.OTHER, _classify (new Phase4SMPException ("Something else went wrong")));
    assertEquals (ESmpLookupFailureKind.OTHER, _classify (new Phase4SMPException (MSG_SMP_CLIENT_EXCEPTION)));
  }
}
