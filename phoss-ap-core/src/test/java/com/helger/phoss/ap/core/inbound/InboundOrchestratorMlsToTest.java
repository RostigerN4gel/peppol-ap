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
package com.helger.phoss.ap.core.inbound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.helger.peppolid.peppol.PeppolIdentifierHelper;

/**
 * Test class for {@link InboundOrchestrator#getValidMlsTo(String, String, String)} covering the MLS
 * SPOG section 5.1 syntactic and correlation rules.
 *
 * @author Philip Helger
 */
public final class InboundOrchestratorMlsToTest
{
  private static final String SCHEME = PeppolIdentifierHelper.PARTICIPANT_SCHEME_ISO6523_ACTORID_UPIS;
  // Seat ID = 3-char prefix + 6-digit Main ID
  private static final String C2_SEAT_ID = "POP987654";

  @Test
  public void testValidDefaultSpidMainIDOnly ()
  {
    final String sRet = InboundOrchestrator.getValidMlsTo (SCHEME, "0242:987654", C2_SEAT_ID);
    assertEquals (SCHEME + "::0242:987654", sRet);
  }

  @Test
  public void testValidWithUseCaseIDSameMainID ()
  {
    // Custom MLS_TO with a Use Case suffix but the same C2 Main ID is allowed
    final String sRet = InboundOrchestrator.getValidMlsTo (SCHEME, "0242:987654-MLS.svc99", C2_SEAT_ID);
    assertEquals (SCHEME + "::0242:987654-MLS.svc99", sRet);
  }

  @Test
  public void testCaseInsensitiveUseCase ()
  {
    // The service provider suffix is compared case-insensitively; the Main ID still correlates
    final String sRet = InboundOrchestrator.getValidMlsTo (SCHEME, "0242:987654-mls.SVC99", C2_SEAT_ID);
    assertEquals (SCHEME + "::0242:987654-mls.SVC99", sRet);
  }

  @Test
  public void testDifferentMainIDIsRejected ()
  {
    // Different Main ID than C2 -> would redirect to a different SP -> rejected
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:123456", C2_SEAT_ID));
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:123456-MLS", C2_SEAT_ID));
  }

  @Test
  public void testWrongSchemeIsRejected ()
  {
    assertNull (InboundOrchestrator.getValidMlsTo ("iso6523-actorid-XXX", "0242:987654", C2_SEAT_ID));
    assertNull (InboundOrchestrator.getValidMlsTo (null, "0242:987654", C2_SEAT_ID));
  }

  @Test
  public void testNonSpisParticipantSchemeValueIsRejected ()
  {
    // Value does not use the 0242 SPIS scheme
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0208:987654", C2_SEAT_ID));
  }

  @Test
  public void testSyntacticallyInvalidValueIsRejected ()
  {
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:", C2_SEAT_ID));
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:12345", C2_SEAT_ID));
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:ABCDEF", C2_SEAT_ID));
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, null, C2_SEAT_ID));
  }

  @Test
  public void testMissingC2SeatIDIsRejected ()
  {
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:987654", null));
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:987654", ""));
    assertNull (InboundOrchestrator.getValidMlsTo (SCHEME, "0242:987654", "AB"));
  }
}
