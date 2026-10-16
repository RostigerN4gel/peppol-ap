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
package com.helger.phoss.ap.webapp.sk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.helger.base.io.stream.StreamHelper;
import com.helger.collection.commons.ICommonsList;
import com.helger.io.resource.ClassPathResource;
import com.helger.peppol.sk.tdd.codelist.ESKTDDDocumentScope;
import com.helger.peppol.sk.tdd.codelist.ESKTDDReporterRole;
import com.helger.peppol.sk.tdd.codelist.ESKTDDTaxDataTypeCode;
import com.helger.peppol.sk.tdd.testfiles.PeppolSKTestFiles;
import com.helger.peppol.sk.tdd.v2026_04_14.TaxDataType;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.factory.IIdentifierFactory;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.phoss.ap.api.model.VerificationIssue;

/**
 * Test class for class {@link SKTDDService}.
 *
 * @author phoss-ap fork
 */
final class SKTDDServiceTest
{
  private static final IIdentifierFactory IF = PeppolIdentifierFactory.INSTANCE;
  private static final IParticipantIdentifier TAX_AUTHORITY = IF.createParticipantIdentifierWithDefaultScheme ("0242:000357-TaxAuthority.SK");
  private static final IParticipantIdentifier REPRESENTATIVE = IF.createParticipantIdentifierWithDefaultScheme ("0242:987654");

  /** A Slovak end user - the reporting party of a domestic SK invoice */
  private static final IParticipantIdentifier SK_PARTICIPANT = IF.createParticipantIdentifierWithDefaultScheme ("0245:2020123456");

  private static void _assertValidTDD (final SKTDDService.ReportedDocument aDoc,
                                       final ESKTDDReporterRole eRole,
                                       final String sPath)
  {
    // The test files of peppol-sk use non-SK buyers (partly with invalid IDs), so as C3 a Slovak
    // participant is passed explicitly - just like the "reportingParty" parameter of the API
    final IParticipantIdentifier aReportingParty = eRole == ESKTDDReporterRole.SENDER ? SKTDDService.getReportingParticipantID (aDoc,
                                                                                                                                eRole,
                                                                                                                                IF)
                                                                                      : SK_PARTICIPANT;
    assertNotNull (aReportingParty, sPath);

    final TaxDataType aTDD = SKTDDService.createTDD (aDoc,
                                                     ESKTDDTaxDataTypeCode.SUBMIT,
                                                     ESKTDDDocumentScope.DOMESTIC,
                                                     eRole,
                                                     aReportingParty,
                                                     TAX_AUTHORITY,
                                                     REPRESENTATIVE,
                                                     "SK",
                                                     null,
                                                     null);
    assertNotNull (aTDD, sPath);
    assertNotNull (SKTDDService.getTDDUUID (aTDD), sPath);
    assertNotNull (SKTDDService.getReportedDocumentUUID (aTDD), sPath);

    final String sXML = SKTDDService.getAsXML (aTDD);
    final ICommonsList <VerificationIssue> aErrors = SKTDDService.validateTDD (sXML)
                                                                 .getAll (VerificationIssue::isError);
    assertTrue (aErrors.isEmpty (), sPath + ": " + aErrors);
  }

  @Test
  void testAllGoodInvoices () throws Exception
  {
    final ICommonsList <ClassPathResource> aFiles = PeppolSKTestFiles.getAllGoodBillingInvoiceFiles ();
    assertFalse (aFiles.isEmpty ());
    for (final ClassPathResource aRes : aFiles)
    {
      final SKTDDService.ReportedDocument aDoc = SKTDDService.parseReportedDocument (StreamHelper.getAllBytes (aRes));
      assertNotNull (aDoc, aRes.getPath ());
      assertNotNull (aDoc.invoice (), aRes.getPath ());
      _assertValidTDD (aDoc, ESKTDDReporterRole.SENDER, aRes.getPath ());
      _assertValidTDD (aDoc, ESKTDDReporterRole.RECEIVER, aRes.getPath ());
    }
  }

  @Test
  void testAllGoodCreditNotes () throws Exception
  {
    final ICommonsList <ClassPathResource> aFiles = PeppolSKTestFiles.getAllGoodBillingCreditNoteFiles ();
    assertFalse (aFiles.isEmpty ());
    for (final ClassPathResource aRes : aFiles)
    {
      final SKTDDService.ReportedDocument aDoc = SKTDDService.parseReportedDocument (StreamHelper.getAllBytes (aRes));
      assertNotNull (aDoc, aRes.getPath ());
      assertNotNull (aDoc.creditNote (), aRes.getPath ());
      _assertValidTDD (aDoc, ESKTDDReporterRole.SENDER, aRes.getPath ());
    }
  }

  @Test
  void testReportedDocumentUUIDIsStable () throws Exception
  {
    // The Invoice UUID v5 depends on the invoice only, the TDD UUID v4 is new for every TDD
    final ClassPathResource aRes = PeppolSKTestFiles.getAllGoodBillingInvoiceFiles ().getFirstOrNull ();
    final SKTDDService.ReportedDocument aDoc = SKTDDService.parseReportedDocument (StreamHelper.getAllBytes (aRes));
    final IParticipantIdentifier aSeller = SKTDDService.getReportingParticipantID (aDoc,
                                                                                   ESKTDDReporterRole.SENDER,
                                                                                   IF);
    final TaxDataType aTDD1 = SKTDDService.createTDD (aDoc,
                                                      ESKTDDTaxDataTypeCode.SUBMIT,
                                                      ESKTDDDocumentScope.DOMESTIC,
                                                      ESKTDDReporterRole.SENDER,
                                                      aSeller,
                                                      TAX_AUTHORITY,
                                                      REPRESENTATIVE,
                                                      "SK",
                                                      null,
                                                      null);
    final TaxDataType aTDD2 = SKTDDService.createTDD (aDoc,
                                                      ESKTDDTaxDataTypeCode.RESUBMIT,
                                                      ESKTDDDocumentScope.DOMESTIC,
                                                      ESKTDDReporterRole.SENDER,
                                                      aSeller,
                                                      TAX_AUTHORITY,
                                                      REPRESENTATIVE,
                                                      "SK",
                                                      null,
                                                      "0d6d1b5e-6c6e-4c55-9d07-3c2c1d1f8a10");
    assertEquals (SKTDDService.getReportedDocumentUUID (aTDD1), SKTDDService.getReportedDocumentUUID (aTDD2));
    assertFalse (SKTDDService.getTDDUUID (aTDD1).equals (SKTDDService.getTDDUUID (aTDD2)));
    assertEquals ("0d6d1b5e-6c6e-4c55-9d07-3c2c1d1f8a10", SKTDDService.getTDDUUID (aTDD2));
  }

  @Test
  void testParseNoUBL ()
  {
    assertNull (SKTDDService.parseReportedDocument ("<Order xmlns='urn:x'/>".getBytes (StandardCharsets.UTF_8)));
    assertNull (SKTDDService.parseReportedDocument ("no xml".getBytes (StandardCharsets.UTF_8)));
  }
}
