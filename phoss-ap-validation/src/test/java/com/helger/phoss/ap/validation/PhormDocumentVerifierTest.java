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
package com.helger.phoss.ap.validation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import com.helger.mime.CMimeType;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.factory.SimpleIdentifierFactory;
import com.helger.peppolid.simple.doctype.SimpleDocumentTypeIdentifier;
import com.helger.phoss.ap.validation.PhormDocumentVerifier.PhormRequest;
import com.helger.xml.serialize.read.DOMReader;

import org.junit.Test;

/**
 * Test class for class {@link PhormDocumentVerifier}.
 *
 * @author Philip Helger
 */
public final class PhormDocumentVerifierTest
{
  /** A real French Factur-X document type identifier - its payload is a PDF and not XML */
  private static final String DOCTYPE_FACTURX = "urn:peppol:doctype:pdf+xml##urn:cen.eu:en16931:2017#conformant#urn:peppol:france:billing:Factur-X:1.0::D22B";
  /** A regular Peppol BIS Billing UBL Invoice document type identifier - its payload is XML */
  private static final String DOCTYPE_BIS3 = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";
  private static final String DOC_PATH = "/storage/inbound/2026/08/31/dummy.sbd";
  private static final byte [] PDF_BYTES = "%PDF-1.7\nnot really a PDF".getBytes (StandardCharsets.ISO_8859_1);

  private static IDocumentTypeIdentifier _docTypeID (final String sValue)
  {
    return new SimpleDocumentTypeIdentifier ("busdox-docid-qns", sValue);
  }

  private static PeppolSBDHData _sbdhData ()
  {
    return new PeppolSBDHData (SimpleIdentifierFactory.INSTANCE);
  }

  @Test
  public void testIsNonXMLDocumentType ()
  {
    assertTrue (PhormDocumentVerifier.isNonXMLDocumentType (_docTypeID (DOCTYPE_FACTURX)));
    assertFalse (PhormDocumentVerifier.isNonXMLDocumentType (_docTypeID (DOCTYPE_BIS3)));
    // Not an OpenPeppol document type identifier layout at all
    assertFalse (PhormDocumentVerifier.isNonXMLDocumentType (_docTypeID ("bla")));
  }

  @Test
  public void testResolveBinaryContentPdf ()
  {
    final PeppolSBDHData aData = _sbdhData ();
    aData.setBusinessMessageBinaryOnly (PDF_BYTES, CMimeType.APPLICATION_PDF, null);
    aData.setCountryC1 ("FR");

    final PhormRequest aRequest = PhormDocumentVerifier.resolveRequestFromSBDHData (aData,
                                                                                    DOC_PATH,
                                                                                    _docTypeID (DOCTYPE_FACTURX));
    assertEquals ("/api/hybrid_validate", aRequest.apiPath ());
    assertEquals ("application/pdf", aRequest.contentType ().getMimeType ());
    assertArrayEquals (PDF_BYTES, aRequest.payloadBytes ());
    assertEquals ("FR", aRequest.countryC1 ());
  }

  @Test
  public void testResolveBinaryContentPdfWithoutCountry ()
  {
    final PeppolSBDHData aData = _sbdhData ();
    aData.setBusinessMessageBinaryOnly (PDF_BYTES, CMimeType.APPLICATION_PDF, null);

    // No C1 country code at all
    assertNull (PhormDocumentVerifier.resolveRequestFromSBDHData (aData, DOC_PATH, _docTypeID (DOCTYPE_FACTURX))
                                     .countryC1 ());

    // A syntactically invalid country code is not forwarded to phorm
    aData.setCountryC1 ("France");
    assertNull (PhormDocumentVerifier.resolveRequestFromSBDHData (aData, DOC_PATH, _docTypeID (DOCTYPE_FACTURX))
                                     .countryC1 ());
  }

  @Test
  public void testResolveBinaryContentNonPdf ()
  {
    final PeppolSBDHData aData = _sbdhData ();
    aData.setBusinessMessageBinaryOnly (PDF_BYTES, CMimeType.APPLICATION_OCTET_STREAM, null);

    // A binary payload that is not a PDF cannot be validated as a hybrid invoice
    final PhormRequest aRequest = PhormDocumentVerifier.resolveRequestFromSBDHData (aData,
                                                                                    DOC_PATH,
                                                                                    _docTypeID (DOCTYPE_FACTURX));
    assertEquals ("/api/dd_and_validate/", aRequest.apiPath ());
    assertNull (aRequest.payloadBytes ());
    assertNull (aRequest.countryC1 ());
  }

  @Test
  public void testResolveXmlBusinessMessage ()
  {
    final PeppolSBDHData aData = _sbdhData ();
    aData.setBusinessMessage (DOMReader.readXMLDOM ("<Invoice/>").getDocumentElement ());

    // Not a "BinaryContent" element - stay with the regular validation
    assertEquals ("/api/dd_and_validate/",
                  PhormDocumentVerifier.resolveRequestFromSBDHData (aData, DOC_PATH, _docTypeID (DOCTYPE_FACTURX))
                                       .apiPath ());
  }
}
