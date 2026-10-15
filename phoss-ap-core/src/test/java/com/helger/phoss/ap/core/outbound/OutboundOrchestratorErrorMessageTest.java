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
import static org.junit.Assert.assertTrue;

import java.security.cert.CertificateException;

import org.junit.Test;

import com.helger.phase4.dynamicdiscovery.Phase4SMPException;
import com.helger.smpclient.exception.SMPClientBadResponseException;

/**
 * Test class for {@link OutboundOrchestrator#getErrorMessageWithCauses(Throwable)}.
 *
 * @author Philip Helger
 */
public final class OutboundOrchestratorErrorMessageTest
{
  @Test
  public void testNoCause ()
  {
    assertEquals ("Just a message", OutboundOrchestrator.getErrorMessageWithCauses (new Exception ("Just a message")));
    // No message at all
    assertEquals ("IllegalStateException", OutboundOrchestrator.getErrorMessageWithCauses (new IllegalStateException ()));
  }

  @Test
  public void testUntrustedSmpCertificate ()
  {
    // The exception chain of an SMP response signed by an untrusted certificate
    final Phase4SMPException aEx = new Phase4SMPException ("Failed to resolve SMP endpoint (a, b, c, d)",
                                                           new SMPClientBadResponseException ("Error in validating signature returned from SMP server",
                                                                                              new CertificateException ("The provided Certificate issuer 'CN=G2' is not in the list of trusted issuers")));
    assertEquals ("Failed to resolve SMP endpoint (a, b, c, d). Technical details: SMPClientBadResponseException: Error in validating signature returned from SMP server - CertificateException: The provided Certificate issuer 'CN=G2' is not in the list of trusted issuers",
                  OutboundOrchestrator.getErrorMessageWithCauses (aEx));
  }

  @Test
  public void testCauseWithoutMessage ()
  {
    assertEquals ("Outer. Technical details: IllegalStateException",
                  OutboundOrchestrator.getErrorMessageWithCauses (new Exception ("Outer", new IllegalStateException ())));
  }

  @Test
  public void testDepthIsLimited ()
  {
    Throwable aEx = new Exception ("Root");
    for (int i = 0; i < 50; ++i)
      aEx = new Exception ("Level " + i, aEx);

    final String sMsg = OutboundOrchestrator.getErrorMessageWithCauses (aEx);
    assertTrue (sMsg.startsWith ("Level 49. Technical details: Exception: Level 48 - "));
    // Only the first 10 causes are contained
    assertTrue (sMsg.endsWith (" - Exception: Level 39"));
  }
}
