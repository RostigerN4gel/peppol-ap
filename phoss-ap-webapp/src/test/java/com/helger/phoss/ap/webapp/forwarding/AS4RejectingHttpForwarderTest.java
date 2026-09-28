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
package com.helger.phoss.ap.webapp.forwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.StatusLine;
import org.junit.jupiter.api.Test;

import com.helger.base.state.ESuccess;
import com.helger.collection.commons.CommonsHashMap;
import com.helger.collection.commons.ICommonsMap;
import com.helger.config.ConfigFactory;
import com.helger.config.fallback.ConfigWithFallback;
import com.helger.config.source.MultiConfigurationValueProvider;
import com.helger.config.source.appl.ConfigurationSourceFunction;
import com.helger.httpclient.response.ExtendedHttpResponseException;
import com.helger.phoss.ap.api.model.ForwardingResult;

/**
 * Test class for class {@link AS4RejectingHttpForwarder}.
 *
 * @author phoss-ap fork
 */
final class AS4RejectingHttpForwarderTest
{
  @Test
  void testRejectOnlyOnHttpStatus ()
  {
    final AS4RejectingHttpForwarder aForwarder = new AS4RejectingHttpForwarder ();

    // HTTP error status, as produced by HttpDocumentForwarder
    assertTrue (aForwarder.isRejectViaAS4 (ForwardingResult.failure ("http_status",
                                                                     "status code: 500, reason phrase: Server Error")));

    // Everything else keeps the original behaviour
    assertFalse (aForwarder.isRejectViaAS4 (ForwardingResult.success ()));
    assertFalse (aForwarder.isRejectViaAS4 (ForwardingResult.failureNoRetry ("http_sync_no_retry", "No retry")));
    assertFalse (aForwarder.isRejectViaAS4 (ForwardingResult.failure ("http_io_error", "Connection refused")));
    assertFalse (aForwarder.isRejectViaAS4 (ForwardingResult.failure ("http_response_error", "No JSON")));
    assertFalse (aForwarder.isRejectViaAS4 (ForwardingResult.failure ("http_error", "Something")));
  }

  /**
   * The error details exactly as {@link com.helger.phoss.ap.forwarding.http.HttpDocumentForwarder}
   * creates them for an HTTP error status.
   */
  private static ForwardingResult _httpStatusFailure (final int nStatus, final String sBody)
  {
    final BasicClassicHttpResponse aResponse = new BasicClassicHttpResponse (nStatus, "Server Error");
    aResponse.addHeader ("Content-Type", "application/json");
    final ExtendedHttpResponseException ex = new ExtendedHttpResponseException (new StatusLine (aResponse),
                                                                                aResponse,
                                                                                sBody == null ? null
                                                                                              : sBody.getBytes (StandardCharsets.UTF_8),
                                                                                StandardCharsets.UTF_8);
    return ForwardingResult.failure ("http_status", ex.getMessage ());
  }

  @Test
  void testAS4ErrorDetailFromBackend ()
  {
    final AS4RejectingHttpForwarder aForwarder = new AS4RejectingHttpForwarder ();

    // The Middleware's error body
    ForwardingResult aResult = _httpStatusFailure (500,
                                                   "{\"retry\":\"none\",\"errorMessage\":\"An error occurred while trying to process the request.\"}");
    assertTrue (aForwarder.isRejectViaAS4 (aResult));
    assertEquals ("An error occurred while trying to process the request.", aForwarder.getAS4ErrorDetail (aResult));

    // No errorMessage, no JSON, no body -> generic default
    assertNull (aForwarder.getAS4ErrorDetail (_httpStatusFailure (500, "{\"retry\":\"none\"}")));
    assertNull (aForwarder.getAS4ErrorDetail (_httpStatusFailure (502, "<html>Bad Gateway</html>")));
    assertNull (aForwarder.getAS4ErrorDetail (_httpStatusFailure (503, null)));
    assertNull (aForwarder.getAS4ErrorDetail (_httpStatusFailure (500, "{\"errorMessage\":\"  \"}")));
    assertNull (aForwarder.getAS4ErrorDetail (ForwardingResult.failure ("http_status", null)));

    // Truncated
    aResult = _httpStatusFailure (500, "{\"errorMessage\":\"" + "x".repeat (2000) + "\"}");
    assertEquals (AS4RejectingHttpForwarder.MAX_AS4_ERROR_DETAIL_LENGTH,
                  aForwarder.getAS4ErrorDetail (aResult).length ());
  }

  @Test
  void testDelegatesToSyncHttpForwarder ()
  {
    final ICommonsMap <String, String> aValues = new CommonsHashMap <> ();
    aValues.put ("forwarding.http.endpoint", "http://localhost:8888/forwarding/url/sync");
    final MultiConfigurationValueProvider aVP = ConfigFactory.createDefaultValueProvider ();
    aVP.addConfigurationSource (new ConfigurationSourceFunction (aValues::get), Integer.MAX_VALUE);

    final AS4RejectingHttpForwarder aForwarder = new AS4RejectingHttpForwarder ();
    assertEquals (AS4RejectingHttpForwarder.PROVIDER_ID, aForwarder.getID ());
    assertEquals (ESuccess.SUCCESS, aForwarder.initFromConfiguration (new ConfigWithFallback (aVP), "forwarding."));
    // Same as the built-in http_post_sync forwarder
    assertTrue (aForwarder.isWithDeliveryConfirmation ());
  }
}
