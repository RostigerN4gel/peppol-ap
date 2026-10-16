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

import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.helger.annotation.concurrent.Immutable;
import com.helger.base.enforce.ValueEnforcer;
import com.helger.base.string.StringHelper;
import com.helger.collection.commons.ICommonsList;
import com.helger.io.resource.inmemory.ReadableResourceString;
import com.helger.peppol.sk.tdd.codelist.ESKTDDDocumentScope;
import com.helger.peppol.sk.tdd.codelist.ESKTDDReporterRole;
import com.helger.peppol.sk.tdd.codelist.ESKTDDTaxDataTypeCode;
import com.helger.peppol.sk.tdd.jaxb.PeppolSKTDD100Marshaller;
import com.helger.peppol.sk.tdd.v100.PeppolSKTDD100Builder;
import com.helger.peppol.sk.tdd.v100.PeppolSKTDD100ReportedTransactionBuilder;
import com.helger.peppol.sk.tdd.v2026_04_14.TaxDataType;
import com.helger.peppol.sk.tdd.validate.PeppolSKTDDValidator;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.factory.IIdentifierFactory;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phoss.ap.api.model.VerificationIssue;
import com.helger.phoss.ap.validation.PhiveToVerificationMapper;
import com.helger.ubl21.UBL21Marshaller;
import com.helger.xml.serialize.read.DOMReader;

import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyType;
import oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.EndpointIDType;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;

/**
 * FORK: Creates and validates Peppol SK Tax Data Documents (TDD) from UBL 2.1 Invoices and Credit
 * Notes, based on <code>com.helger.peppol:peppol-sk-tdd</code>. This class contains no Spring or
 * database dependencies - the REST part is in {@link SKTDDController}.
 *
 * @author phoss-ap fork
 */
@Immutable
public final class SKTDDService
{
  /** Document type of the SK TDD (SRA 9.1) */
  public static final String TDD_DOCTYPE_SCHEME = "busdox-docid-qns";
  public static final String TDD_DOCTYPE_VALUE = "urn:peppol:schema:sk-taxdata:1.0::TaxData##urn:peppol:taxdata:sk-1::1.0";
  /** Process of the SK TDD (SRA 9.1) */
  public static final String TDD_PROCESS_SCHEME = "cenbii-procid-ubl";
  public static final String TDD_PROCESS_VALUE = "urn:peppol:taxreporting";

  private static final String NS_UBL_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
  private static final String NS_UBL_CREDITNOTE = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
  private static final String PARTICIPANT_SCHEME = "iso6523-actorid-upis";

  /**
   * A parsed UBL 2.1 Invoice or Credit Note - exactly one of the two is set.
   *
   * @param invoice
   *        The Invoice. May be <code>null</code> if this is a Credit Note.
   * @param creditNote
   *        The Credit Note. May be <code>null</code> if this is an Invoice.
   */
  public record ReportedDocument (@Nullable InvoiceType invoice, @Nullable CreditNoteType creditNote)
  {
    /**
     * @return The party that has to report in the given role: the seller for C2, the buyer for C3.
     *         May be <code>null</code>.
     */
    @Nullable
    PartyType getReportingParty (@NonNull final ESKTDDReporterRole eRole)
    {
      if (eRole == ESKTDDReporterRole.SENDER)
      {
        if (invoice != null)
          return invoice.getAccountingSupplierParty () == null ? null
                                                              : invoice.getAccountingSupplierParty ().getParty ();
        return creditNote.getAccountingSupplierParty () == null ? null
                                                               : creditNote.getAccountingSupplierParty ().getParty ();
      }
      if (invoice != null)
        return invoice.getAccountingCustomerParty () == null ? null : invoice.getAccountingCustomerParty ().getParty ();
      return creditNote.getAccountingCustomerParty () == null ? null
                                                             : creditNote.getAccountingCustomerParty ().getParty ();
    }

    void initReportedTransaction (@NonNull final PeppolSKTDD100ReportedTransactionBuilder aRT)
    {
      if (invoice != null)
        aRT.initFromInvoice (invoice);
      else
        aRT.initFromCreditNote (creditNote);
    }
  }

  private SKTDDService ()
  {}

  @NonNull
  public static IDocumentTypeIdentifier createTDDDocumentTypeID (@NonNull final IIdentifierFactory aIF)
  {
    return aIF.createDocumentTypeIdentifier (TDD_DOCTYPE_SCHEME, TDD_DOCTYPE_VALUE);
  }

  @NonNull
  public static IProcessIdentifier createTDDProcessID (@NonNull final IIdentifierFactory aIF)
  {
    return aIF.createProcessIdentifier (TDD_PROCESS_SCHEME, TDD_PROCESS_VALUE);
  }

  /**
   * Parse a UBL 2.1 Invoice or Credit Note.
   *
   * @param aBytes
   *        The XML bytes. May not be <code>null</code>.
   * @return <code>null</code> if the bytes are neither a parsable UBL 2.1 Invoice nor Credit Note.
   */
  @Nullable
  public static ReportedDocument parseReportedDocument (final byte @NonNull [] aBytes)
  {
    ValueEnforcer.notNull (aBytes, "Bytes");

    final Document aDoc = DOMReader.readXMLDOM (aBytes);
    if (aDoc == null)
      return null;
    final Element aRoot = aDoc.getDocumentElement ();
    if (NS_UBL_INVOICE.equals (aRoot.getNamespaceURI ()) && "Invoice".equals (aRoot.getLocalName ()))
    {
      final InvoiceType aInvoice = UBL21Marshaller.invoice ().read (aDoc);
      return aInvoice == null ? null : new ReportedDocument (aInvoice, null);
    }
    if (NS_UBL_CREDITNOTE.equals (aRoot.getNamespaceURI ()) && "CreditNote".equals (aRoot.getLocalName ()))
    {
      final CreditNoteType aCreditNote = UBL21Marshaller.creditNote ().read (aDoc);
      return aCreditNote == null ? null : new ReportedDocument (null, aCreditNote);
    }
    return null;
  }

  /**
   * Get the Peppol participant ID of the party that has to report in the given role, from its
   * <code>cbc:EndpointID</code> (BT-34 for the seller, BT-49 for the buyer).
   *
   * @param aDoc
   *        The reported document. May not be <code>null</code>.
   * @param eRole
   *        The reporter role. May not be <code>null</code>.
   * @param aIF
   *        The identifier factory to use. May not be <code>null</code>.
   * @return <code>null</code> if the party has no usable endpoint ID.
   */
  @Nullable
  public static IParticipantIdentifier getReportingParticipantID (@NonNull final ReportedDocument aDoc,
                                                                  @NonNull final ESKTDDReporterRole eRole,
                                                                  @NonNull final IIdentifierFactory aIF)
  {
    final PartyType aParty = aDoc.getReportingParty (eRole);
    if (aParty == null)
      return null;
    final EndpointIDType aEndpointID = aParty.getEndpointID ();
    if (aEndpointID == null || StringHelper.isEmpty (aEndpointID.getValue ()) ||
        StringHelper.isEmpty (aEndpointID.getSchemeID ()))
      return null;
    return aIF.createParticipantIdentifier (PARTICIPANT_SCHEME,
                                            aEndpointID.getSchemeID ().trim () + ":" + aEndpointID.getValue ().trim ());
  }

  /**
   * Build a TDD. The Invoice UUID v5 of the reported document (SRA 9.2.3) is calculated by the
   * builder.
   *
   * @return <code>null</code> if a mandatory field is missing - the details are logged by the
   *         builder.
   */
  @Nullable
  public static TaxDataType createTDD (@NonNull final ReportedDocument aDoc,
                                       @NonNull final ESKTDDTaxDataTypeCode eType,
                                       @NonNull final ESKTDDDocumentScope eScope,
                                       @NonNull final ESKTDDReporterRole eRole,
                                       @NonNull final IParticipantIdentifier aReportingParty,
                                       @NonNull final IParticipantIdentifier aReceivingParty,
                                       @NonNull final IParticipantIdentifier aReportersRepresentative,
                                       @NonNull final String sTaxAuthorityID,
                                       @Nullable final String sTaxAuthorityName,
                                       @Nullable final String sTDDUUID)
  {
    ValueEnforcer.notNull (aDoc, "Doc");
    ValueEnforcer.notNull (eType, "Type");
    ValueEnforcer.notNull (eScope, "Scope");
    ValueEnforcer.notNull (eRole, "Role");

    // The TDD type must be known to the reported transaction builder upfront
    final PeppolSKTDD100ReportedTransactionBuilder aRT = new PeppolSKTDD100ReportedTransactionBuilder (eType);
    aDoc.initReportedTransaction (aRT);

    final PeppolSKTDD100Builder aBuilder = new PeppolSKTDD100Builder ().taxDataTypeCode (eType)
                                                                       .documentScope (eScope)
                                                                       .reporterRole (eRole)
                                                                       .reportingParty (aReportingParty)
                                                                       .receivingParty (aReceivingParty)
                                                                       .reportersRepresentative (aReportersRepresentative)
                                                                       .taxAuthorityID (sTaxAuthorityID)
                                                                       .taxAuthorityName (sTaxAuthorityName)
                                                                       .reportedTransaction (aRT);
    if (StringHelper.isNotEmpty (sTDDUUID))
      aBuilder.uuid (sTDDUUID);
    return aBuilder.build ();
  }

  @NonNull
  public static String getAsXML (@NonNull final TaxDataType aTDD)
  {
    return new PeppolSKTDD100Marshaller ().setFormattedOutput (true).getAsString (aTDD);
  }

  /**
   * Validate a serialized TDD against all Schematron layers of the SK TDD rules.
   *
   * @param sTDDXML
   *        The TDD XML. May not be <code>null</code>.
   * @return All issues of level warning or higher. Never <code>null</code>.
   */
  @NonNull
  public static ICommonsList <VerificationIssue> validateTDD (@NonNull final String sTDDXML)
  {
    final ValidationResultList aVRL = PeppolSKTDDValidator.validateSK_TDD_100 (new ReadableResourceString (sTDDXML,
                                                                                                           StandardCharsets.UTF_8));
    return PhiveToVerificationMapper.toVerificationIssues (aVRL, null);
  }

  @Nullable
  public static String getTDDUUID (@NonNull final TaxDataType aTDD)
  {
    return aTDD.getUUID () == null ? null : aTDD.getUUID ().getValue ();
  }

  /**
   * @return The Invoice UUID v5 of the reported document, or <code>null</code> if the TDD has no
   *         reported document (possible for a TDD of type D).
   */
  @Nullable
  public static String getReportedDocumentUUID (@NonNull final TaxDataType aTDD)
  {
    // The builder creates exactly one reported transaction
    if (!aTDD.hasReportedTransactionEntries ())
      return null;
    final var aRepDoc = aTDD.getReportedTransactionAtIndex (0).getReportedDocument ();
    if (aRepDoc == null || aRepDoc.getUUID () == null)
      return null;
    return aRepDoc.getUUID ().getValue ();
  }
}
