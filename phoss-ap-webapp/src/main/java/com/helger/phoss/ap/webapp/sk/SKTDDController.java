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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helger.base.io.stream.StreamHelper;
import com.helger.base.string.StringHelper;
import com.helger.collection.commons.ICommonsList;
import com.helger.config.IConfig;
import com.helger.json.IJsonObject;
import com.helger.json.JsonArray;
import com.helger.json.JsonObject;
import com.helger.peppol.sbdh.EPeppolMLSType;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.sk.tdd.codelist.ESKTDDDocumentScope;
import com.helger.peppol.sk.tdd.codelist.ESKTDDReporterRole;
import com.helger.peppol.sk.tdd.codelist.ESKTDDTaxDataTypeCode;
import com.helger.peppol.sk.tdd.v2026_04_14.TaxDataType;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.factory.IIdentifierFactory;
import com.helger.phase4.peppol.Phase4PeppolSendingReport;
import com.helger.phoss.ap.api.config.APConfigProvider;
import com.helger.phoss.ap.api.model.IOutboundTransaction;
import com.helger.phoss.ap.api.model.OutboundSubmitResult;
import com.helger.phoss.ap.api.model.VerificationIssue;
import com.helger.phoss.ap.api.model.VerificationOutcome;
import com.helger.phoss.ap.basic.APBasicMetaManager;
import com.helger.phoss.ap.core.APCoreConfig;
import com.helger.phoss.ap.core.outbound.OutboundOrchestrator;
import com.helger.phoss.ap.webapp.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * FORK: REST endpoint that creates a Peppol SK Tax Data Document (TDD) from a UBL 2.1 Invoice or
 * Credit Note, validates it and sends it as a regular outbound transaction to the Slovak tax
 * authority (C5). Deciding <em>when</em> a TDD (S, R or D) is needed and correlating the MLS of
 * invoice and TDD is the job of the caller (the Middleware) - see CUSTOMIZATIONS.md.
 *
 * @author phoss-ap fork
 */
@RestController
@RequestMapping ("/api/sk/tdd")
@Tag (name = "Peppol SK TDD", description = "Create and send Slovak Tax Data Documents (fork)")
@SecurityRequirement (name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class SKTDDController
{
  /** <code>true</code> to enable the endpoint. Disabled by default. */
  public static final String CONFIG_ENABLED = "sk.tdd.enabled";
  /** Peppol participant ID of the tax authority AP (C5) - receiver of the TDD */
  public static final String CONFIG_TAX_AUTHORITY_PARTICIPANT_ID = "sk.tdd.tax-authority.participant-id";
  public static final String DEFAULT_TAX_AUTHORITY_PARTICIPANT_ID = "iso6523-actorid-upis::0242:000357-TaxAuthority.SK";
  /** Value of TaxAuthority/ID in the TDD */
  public static final String CONFIG_TAX_AUTHORITY_ID = "sk.tdd.tax-authority.id";
  public static final String DEFAULT_TAX_AUTHORITY_ID = "SK";
  /** Optional value of TaxAuthority/Name in the TDD */
  public static final String CONFIG_TAX_AUTHORITY_NAME = "sk.tdd.tax-authority.name";
  /** Peppol participant ID of the reporter's representative (this AP, e.g. 0242:...) - mandatory */
  public static final String CONFIG_REPORTERS_REPRESENTATIVE_ID = "sk.tdd.reporters-representative.participant-id";
  /** C1 country code put into the SBDH of the TDD transmission */
  public static final String CONFIG_C1_COUNTRY_CODE = "sk.tdd.c1-country-code";
  public static final String DEFAULT_C1_COUNTRY_CODE = "SK";

  private static final Logger LOGGER = LoggerFactory.getLogger (SKTDDController.class);
  private static final String LOG_PREFIX = "[SK-TDD] ";
  private static final int MAX_CUSTOM_FIELD_LENGTH = 255;

  @NonNull
  private static ResponseEntity <String> _error (@NonNull final HttpStatus eStatus,
                                                 @NonNull final String sMessage,
                                                 @Nullable final ICommonsList <VerificationIssue> aIssues)
  {
    final IJsonObject ret = new JsonObject ().add ("errorMessage", sMessage);
    if (aIssues != null && aIssues.isNotEmpty ())
      ret.add ("issues", _toJson (aIssues));
    return ResponseEntity.status (eStatus).contentType (MediaType.APPLICATION_JSON).body (ret.getAsJsonString ());
  }

  @NonNull
  private static JsonArray _toJson (@NonNull final Iterable <VerificationIssue> aIssues)
  {
    final JsonArray ret = new JsonArray ();
    for (final VerificationIssue aIssue : aIssues)
      ret.add (aIssue.getAsJson ());
    return ret;
  }

  @Nullable
  private static IParticipantIdentifier _parseParticipantID (@NonNull final IIdentifierFactory aIF,
                                                             @Nullable final String s)
  {
    if (StringHelper.isEmpty (s))
      return null;
    final IParticipantIdentifier ret = aIF.parseParticipantIdentifier (s);
    return ret != null ? ret : aIF.createParticipantIdentifierWithDefaultScheme (s);
  }

  @PostMapping (value = "/{reporterRole}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation (summary = "Create a Peppol SK TDD from a UBL Invoice / Credit Note and send it to the tax authority",
              description = "The request body is a UBL 2.1 Invoice or Credit Note. The AP builds the SK TDD " +
                            "(incl. the Invoice UUID v5), validates it with the SK TDD Schematron and sends it as a " +
                            "regular outbound transaction to the tax authority AP (C5). With send=false the TDD is " +
                            "only built and validated and returned as XML (dry run).")
  @ApiResponses ({ @ApiResponse (responseCode = "200", description = "TDD created (and sent, unless send=false)"),
                   @ApiResponse (responseCode = "400",
                                 description = "Invalid parameter, or the body is no UBL 2.1 Invoice / Credit Note"),
                   @ApiResponse (responseCode = "401", description = "Missing or invalid API token"),
                   @ApiResponse (responseCode = "404",
                                 description = "SK TDD support (sk.tdd.enabled) or sending is disabled"),
                   @ApiResponse (responseCode = "422",
                                 description = "The TDD could not be built, failed the Schematron validation, was rejected by the outbound verification or could not be sent") })
  public ResponseEntity <String> createTDD (@Parameter (description = "Reporter role: C2 (the AP of the seller) or C3 (the AP of the buyer)",
                                                        required = true,
                                                        example = "C2") @PathVariable ("reporterRole") final String sReporterRole,
                                            @Parameter (description = "Tax data type code: S (submit), R (resubmit) or D (disregard)",
                                                        example = "S") @RequestParam (value = "type",
                                                                                      defaultValue = "S") final String sType,
                                            @Parameter (description = "Document scope: D (domestic), IC (intra-community) or INTL (international)",
                                                        example = "D") @RequestParam (value = "scope",
                                                                                      defaultValue = "D") final String sScope,
                                            @Parameter (description = "Peppol participant ID of the reporting party. Derived from the EndpointID of the seller (C2) or buyer (C3) when omitted.",
                                                        example = "iso6523-actorid-upis::0245:2020123456") @RequestParam (value = "reportingParty",
                                                                                                                         required = false) final String sReportingParty,
                                            @Parameter (description = "UUID of the TDD. A random UUID v4 is generated when omitted.") @RequestParam (value = "tddUUID",
                                                                                                                                                       required = false) final String sTDDUUID,
                                            @Parameter (description = "Custom SBDH Instance Identifier of the TDD transmission. Generated when omitted.") @RequestParam (value = "sbdhInstanceID",
                                                                                                                                                                         required = false) final String sSbdhInstanceID,
                                            @Parameter (description = "false = only build and validate the TDD and return it (dry run)") @RequestParam (value = "send",
                                                                                                                                                         defaultValue = "true") final boolean bSend,
                                            @Parameter (description = "Optional custom field 1 (max 255 characters), stored with the outbound transaction") @RequestParam (value = "custom1",
                                                                                                                                                                            required = false) final String sCustom1,
                                            @Parameter (description = "Optional custom field 2 (max 255 characters)") @RequestParam (value = "custom2",
                                                                                                                                       required = false) final String sCustom2,
                                            @Parameter (description = "Optional custom field 3 (max 255 characters)") @RequestParam (value = "custom3",
                                                                                                                                       required = false) final String sCustom3,
                                            @Parameter (hidden = true) @NonNull final HttpServletRequest aServletRequest) throws Exception
  {
    final IConfig aConfig = APConfigProvider.getConfig ();
    if (!aConfig.getAsBoolean (CONFIG_ENABLED, false))
    {
      LOGGER.info (LOG_PREFIX + "SK TDD support is disabled ('" + CONFIG_ENABLED + "')");
      return ResponseEntity.notFound ().build ();
    }
    if (bSend && !APCoreConfig.isSendingEnabled ())
    {
      LOGGER.info (LOG_PREFIX + "Peppol AP sending is disabled");
      return ResponseEntity.notFound ().build ();
    }

    // Parameters
    final ESKTDDReporterRole eRole = ESKTDDReporterRole.getFromIDOrNull (sReporterRole);
    if (eRole == null)
      return _error (HttpStatus.BAD_REQUEST, "Invalid reporter role '" + sReporterRole + "' - use C2 or C3", null);
    final ESKTDDTaxDataTypeCode eType = ESKTDDTaxDataTypeCode.getFromIDOrNull (sType);
    if (eType == null)
      return _error (HttpStatus.BAD_REQUEST, "Invalid tax data type code '" + sType + "' - use S, R or D", null);
    final ESKTDDDocumentScope eScope = ESKTDDDocumentScope.getFromIDOrNull (sScope);
    if (eScope == null)
      return _error (HttpStatus.BAD_REQUEST, "Invalid document scope '" + sScope + "' - use D, IC or INTL", null);
    for (final String sCustom : new String [] { sCustom1, sCustom2, sCustom3 })
      if (sCustom != null && sCustom.length () > MAX_CUSTOM_FIELD_LENGTH)
        return _error (HttpStatus.BAD_REQUEST,
                       "A custom field exceeds the maximum length of " + MAX_CUSTOM_FIELD_LENGTH + " characters",
                       null);

    final IIdentifierFactory aIF = APBasicMetaManager.getIdentifierFactory ();
    final String sTaxAuthorityParticipantID = aConfig.getAsString (CONFIG_TAX_AUTHORITY_PARTICIPANT_ID,
                                                                   DEFAULT_TAX_AUTHORITY_PARTICIPANT_ID);
    final IParticipantIdentifier aTaxAuthority = _parseParticipantID (aIF, sTaxAuthorityParticipantID);
    if (aTaxAuthority == null)
      return _error (HttpStatus.INTERNAL_SERVER_ERROR,
                     "The configured tax authority participant ID '" + sTaxAuthorityParticipantID + "' is invalid",
                     null);
    final String sRepresentative = aConfig.getAsString (CONFIG_REPORTERS_REPRESENTATIVE_ID);
    final IParticipantIdentifier aRepresentative = _parseParticipantID (aIF, sRepresentative);
    if (aRepresentative == null)
      return _error (HttpStatus.INTERNAL_SERVER_ERROR,
                     StringHelper.isEmpty (sRepresentative) ? "The reporter's representative participant ID (this AP) is not configured ('" +
                                                              CONFIG_REPORTERS_REPRESENTATIVE_ID +
                                                              "')"
                                                            : "The configured reporter's representative participant ID '" +
                                                              sRepresentative +
                                                              "' is invalid",
                     null);

    // Read and parse the reported document
    final byte [] aPayload;
    try (final InputStream aIS = aServletRequest.getInputStream ())
    {
      aPayload = StreamHelper.getAllBytes (aIS);
    }
    if (aPayload == null || aPayload.length == 0)
      return _error (HttpStatus.BAD_REQUEST, "The request body is empty", null);
    final SKTDDService.ReportedDocument aDoc = SKTDDService.parseReportedDocument (aPayload);
    if (aDoc == null)
      return _error (HttpStatus.BAD_REQUEST, "The request body is no valid UBL 2.1 Invoice or Credit Note", null);

    final IParticipantIdentifier aReportingParty;
    if (StringHelper.isNotEmpty (sReportingParty))
    {
      aReportingParty = _parseParticipantID (aIF, sReportingParty);
      if (aReportingParty == null)
        return _error (HttpStatus.BAD_REQUEST, "Invalid reporting party '" + sReportingParty + "'", null);
    }
    else
    {
      aReportingParty = SKTDDService.getReportingParticipantID (aDoc, eRole, aIF);
      if (aReportingParty == null)
        return _error (HttpStatus.BAD_REQUEST,
                       "The " +
                                               (eRole == ESKTDDReporterRole.SENDER ? "seller" : "buyer") +
                                               " has no EndpointID - pass the reportingParty parameter",
                       null);
    }

    // Build and validate the TDD
    final TaxDataType aTDD = SKTDDService.createTDD (aDoc,
                                                     eType,
                                                     eScope,
                                                     eRole,
                                                     aReportingParty,
                                                     aTaxAuthority,
                                                     aRepresentative,
                                                     aConfig.getAsString (CONFIG_TAX_AUTHORITY_ID,
                                                                          DEFAULT_TAX_AUTHORITY_ID),
                                                     aConfig.getAsString (CONFIG_TAX_AUTHORITY_NAME),
                                                     sTDDUUID);
    if (aTDD == null)
      return _error (HttpStatus.UNPROCESSABLE_CONTENT,
                     "The TDD could not be built, because a mandatory field is missing - see the server log for details",
                     null);
    final String sTDDXML = SKTDDService.getAsXML (aTDD);
    final ICommonsList <VerificationIssue> aIssues = SKTDDService.validateTDD (sTDDXML);
    final ICommonsList <VerificationIssue> aErrors = aIssues.getAll (VerificationIssue::isError);
    if (aErrors.isNotEmpty ())
    {
      LOGGER.warn (LOG_PREFIX + "The created TDD failed the SK TDD Schematron with " + aErrors.size () + " error(s)");
      return _error (HttpStatus.UNPROCESSABLE_CONTENT, "The created TDD failed the SK TDD validation", aIssues);
    }

    final IJsonObject aResponse = new JsonObject ().add ("tddUUID", SKTDDService.getTDDUUID (aTDD))
                                                   .add ("reportedDocumentUUID",
                                                         SKTDDService.getReportedDocumentUUID (aTDD))
                                                   .add ("taxDataTypeCode", eType.getID ())
                                                   .add ("documentScope", eScope.getID ())
                                                   .add ("reporterRole", eRole.getID ())
                                                   .add ("reportingParty", aReportingParty.getURIEncoded ())
                                                   .add ("receiver", aTaxAuthority.getURIEncoded ());
    if (aIssues.isNotEmpty ())
      aResponse.add ("validationWarnings", _toJson (aIssues));

    // The SRA requires MLS_TYPE=ALWAYS_SEND for every TDD - the AP puts the global mls.type into the
    // SBDH of every outbound document, so it can only be checked here
    if (APCoreConfig.getMlsType () != EPeppolMLSType.ALWAYS_SEND)
    {
      final String sWarning = "mls.type is '" +
                              APCoreConfig.getMlsType ().getID () +
                              "', but a SK TDD must be sent with MLS_TYPE " +
                              EPeppolMLSType.ALWAYS_SEND.getID ();
      LOGGER.warn (LOG_PREFIX + sWarning);
      aResponse.add ("warning", sWarning);
    }

    if (!bSend)
    {
      aResponse.add ("tddXML", sTDDXML);
      return ResponseEntity.ok (aResponse.getAsJsonString ());
    }

    // Send it as a regular outbound document
    final String sEffectiveSbdhInstanceID = StringHelper.isNotEmpty (sSbdhInstanceID) ? sSbdhInstanceID
                                                                                      : PeppolSBDHData.createRandomSBDHInstanceIdentifier ();
    aResponse.add ("sbdhInstanceID", sEffectiveSbdhInstanceID);
    LOGGER.info (LOG_PREFIX +
                 "Sending TDD '" +
                 SKTDDService.getTDDUUID (aTDD) +
                 "' (" +
                 eType.getID () +
                 ", " +
                 eRole.getID () +
                 ") for reported document '" +
                 SKTDDService.getReportedDocumentUUID (aTDD) +
                 "' as SBDH '" +
                 sEffectiveSbdhInstanceID +
                 "'");

    final OutboundSubmitResult aSubmitResult = OutboundOrchestrator.submitRawDocument (LOG_PREFIX,
                                                                                       aReportingParty,
                                                                                       aTaxAuthority,
                                                                                       SKTDDService.createTDDDocumentTypeID (aIF),
                                                                                       SKTDDService.createTDDProcessID (aIF),
                                                                                       sEffectiveSbdhInstanceID,
                                                                                       aConfig.getAsString (CONFIG_C1_COUNTRY_CODE,
                                                                                                            DEFAULT_C1_COUNTRY_CODE),
                                                                                       new ByteArrayInputStream (sTDDXML.getBytes (StandardCharsets.UTF_8)),
                                                                                       null,
                                                                                       null,
                                                                                       null,
                                                                                       null,
                                                                                       null,
                                                                                       sCustom1,
                                                                                       sCustom2,
                                                                                       sCustom3);
    if (aSubmitResult.isFailure ())
    {
      if (aSubmitResult.isVerificationRejected ())
      {
        final VerificationOutcome aOutcome = aSubmitResult.getVerifierResult ().outcome ();
        return _error (HttpStatus.UNPROCESSABLE_CONTENT,
                       "The TDD was rejected by the outbound verification: " +
                                                         StringHelper.getNotNull (aOutcome.getMessage (), "-"),
                       aOutcome.getAllIssues ());
      }
      return _error (HttpStatus.UNPROCESSABLE_CONTENT,
                     StringHelper.getNotNull (aSubmitResult.getErrorMessage (),
                                              "Failed to submit the TDD as outbound transaction"),
                     null);
    }

    final IOutboundTransaction aTx = aSubmitResult.getTransaction ();
    aResponse.add ("outboundTransactionID", aTx.getID ());
    final Phase4PeppolSendingReport aSendingReport = OutboundOrchestrator.processPendingOutbound (LOG_PREFIX, aTx);
    aResponse.add ("sendingReport", aSendingReport.getAsJsonObject ());
    return aSendingReport.isOverallSuccess () ? ResponseEntity.ok (aResponse.getAsJsonString ())
                                              : ResponseEntity.unprocessableContent ().body (aResponse.getAsJsonString ());
  }
}
