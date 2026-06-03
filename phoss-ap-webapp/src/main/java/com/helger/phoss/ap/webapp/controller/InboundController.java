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
package com.helger.phoss.ap.webapp.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helger.base.string.StringHelper;
import com.helger.collection.commons.ICommonsList;
import com.helger.phoss.ap.api.IInboundTransactionManager;
import com.helger.phoss.ap.api.dto.InboundTransactionResponse;
import com.helger.phoss.ap.api.dto.ReportResponse;
import com.helger.phoss.ap.api.model.IInboundTransaction;
import com.helger.phoss.ap.core.reporting.APPeppolReportingHelper;
import com.helger.phoss.ap.db.APJdbcMetaManager;
import com.helger.phoss.ap.webapp.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for inbound transaction operations including reporting the C4 country code,
 * querying transaction status by SBDH Instance ID, and listing all transactions currently in
 * processing.
 *
 * @author Philip Helger
 */
@RestController
@RequestMapping ("/api/inbound")
@Tag (name = "Inbound", description = "Inbound transaction reporting and status queries")
@SecurityRequirement (name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class InboundController
{
  private static final Logger LOGGER = LoggerFactory.getLogger (InboundController.class);

  /**
   * Store the C4 country code for a received inbound transaction and create the corresponding
   * Peppol Reporting entry.
   *
   * @param sSbdhInstanceID
   *        The SBDH Instance ID of the transaction. May not be <code>null</code>.
   * @param sC4CountryCode
   *        The C4 country code to store. May not be <code>null</code>.
   * @return A {@link ReportResponse} on success, 404 if the transaction does not exist, or 400 if
   *         the C4 country code was already set.
   */
  @PostMapping ("/report")
  @Operation (summary = "Report C4 country code for an inbound transaction",
              description = "Triggers the creation of a Peppol Reporting record for a previously received inbound message. " +
                            "Called by the Receiver Backend after it has successfully processed the document. " +
                            "Stores the C4 country code on the transaction and updates the reporting status to 'reported'.")
  @ApiResponses ({ @ApiResponse (responseCode = "200", description = "Country code stored and reporting record created"),
                   @ApiResponse (responseCode = "400",
                                 description = "The transaction already has a C4 country code stored",
                                 content = @Content),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content),
                   @ApiResponse (responseCode = "404",
                                 description = "No inbound transaction with the given SBDH Instance ID",
                                 content = @Content) })
  public ResponseEntity <ReportResponse> reportInbound (@Parameter (description = "Peppol SBDH Instance Identifier of the inbound message",
                                                                    required = true,
                                                                    example = "550e8400-e29b-41d4-a716-446655440000") @RequestParam ("sbdhInstanceID") final String sSbdhInstanceID,
                                                        @Parameter (description = "ISO 3166-1 alpha-2 country code of the final receiver (C4)",
                                                                    required = true,
                                                                    example = "AT") @RequestParam ("c4CountryCode") final String sC4CountryCode)
  {
    final IInboundTransactionManager aTxMgr = APJdbcMetaManager.getInboundTransactionMgr ();

    // Does a transaction exist for the provided SBDH Instance ID?
    final IInboundTransaction aTx = aTxMgr.getBySbdhInstanceID (sSbdhInstanceID);
    if (aTx == null)
      return ResponseEntity.notFound ().build ();

    // Does the transaction already have a C4 Country Code?
    if (StringHelper.isNotEmpty (aTx.getC4CountryCode ()))
      return ResponseEntity.badRequest ().build ();

    LOGGER.info ("Storing C4 Country Code '" +
                 sC4CountryCode +
                 "' to inbound transaction '" +
                 aTx.getID () +
                 "' with SBDH ID '" +
                 sSbdhInstanceID +
                 "'");

    // Store the country code for C4 and create the reporting entry
    aTxMgr.updateC4CountryCode (aTx.getID (), sC4CountryCode);
    APPeppolReportingHelper.createInboundPeppolReportingItem (aTx.getID ());

    return ResponseEntity.ok (new ReportResponse (aTx.getID (),
                                                  "updated",
                                                  "C4 country code set to '" + sC4CountryCode + "'"));
  }

  /**
   * Get the current status of an inbound transaction by its SBDH Instance ID.
   *
   * @param sbdhInstanceID
   *        The SBDH Instance ID to look up.
   * @param bIncludeArchive
   *        When <code>true</code>, the archive table is searched if the transaction is no longer
   *        present in the active table. Default is <code>false</code> (active table only). Added in
   *        0.9.0.
   * @return The transaction details, or 404 if not found.
   */
  @GetMapping ("/status/{sbdhInstanceID}")
  @Operation (summary = "Query inbound transaction status",
              description = "Returns the current status of a specific inbound transaction. By default only the active " +
                            "inbound_transaction table is searched. Pass includeArchive=true to also consider archived transactions.")
  @ApiResponses ({ @ApiResponse (responseCode = "200", description = "Transaction found"),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content),
                   @ApiResponse (responseCode = "404",
                                 description = "No inbound transaction with the given SBDH Instance ID",
                                 content = @Content) })
  public ResponseEntity <InboundTransactionResponse> getStatus (@Parameter (description = "Peppol SBDH Instance Identifier of the inbound message",
                                                                            required = true,
                                                                            example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable final String sbdhInstanceID,
                                                                @Parameter (description = "When true, the archive table is consulted if the transaction is not in the active table. Since 0.9.0.") @RequestParam (name = "includeArchive",
                                                                                                                                                                                                                  defaultValue = "false") final boolean bIncludeArchive)
  {
    final IInboundTransactionManager aTxMgr = APJdbcMetaManager.getInboundTransactionMgr ();
    final IInboundTransaction aTx = bIncludeArchive ? aTxMgr.getBySbdhInstanceIDIncludingArchive (sbdhInstanceID)
                                                    : aTxMgr.getBySbdhInstanceID (sbdhInstanceID);
    if (aTx == null)
      return ResponseEntity.notFound ().build ();

    return ResponseEntity.ok (InboundTransactionResponse.fromDomain (aTx));
  }

  /**
   * Get all inbound transactions that are currently being processed (not yet completed or
   * permanently failed).
   *
   * @return A list of in-processing inbound transactions.
   */
  @GetMapping ("/in-processing")
  @Operation (summary = "List inbound transactions in processing",
              description = "Returns all inbound transactions that are not yet in a final state — includes status received, " +
                            "forwarding, forward_failed (awaiting retry). Excludes rejected, forwarded, permanently_failed.")
  @ApiResponses ({ @ApiResponse (responseCode = "200", description = "List of inbound transactions"),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content) })
  public ResponseEntity <List <InboundTransactionResponse>> getInProcessing ()
  {
    final IInboundTransactionManager aTxMgr = APJdbcMetaManager.getInboundTransactionMgr ();
    final var aTxs = aTxMgr.getAllInProcessing ();

    final ICommonsList <InboundTransactionResponse> aResult = aTxs.getAllMapped (InboundTransactionResponse::fromDomain);
    return ResponseEntity.ok (aResult);
  }

  /**
   * Get all forwarded inbound transactions that are still missing a C4 country code.
   *
   * @return A list of inbound transactions without C4 country code.
   * @since v0.1.3
   */
  @GetMapping ("/missing-c4-country-code")
  @Operation (summary = "List inbound transactions missing C4 country code",
              description = "Returns all forwarded inbound transactions for which the C4 country code has not yet been determined. " +
                            "Only includes transactions in status forwarded where reporting is still pending. Since v0.1.3.")
  @ApiResponses ({ @ApiResponse (responseCode = "200",
                                 description = "List of inbound transactions without a C4 country code"),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content) })
  public ResponseEntity <List <InboundTransactionResponse>> getMissingC4CountryCode ()
  {
    final IInboundTransactionManager aTxMgr = APJdbcMetaManager.getInboundTransactionMgr ();
    final var aTxs = aTxMgr.getAllWithoutC4CountryCode ();

    final ICommonsList <InboundTransactionResponse> aResult = aTxs.getAllMapped (InboundTransactionResponse::fromDomain);
    return ResponseEntity.ok (aResult);
  }

  /**
   * Check if a specific inbound transaction is still missing a C4 country code.
   *
   * @param sbdhInstanceID
   *        The SBDH Instance ID to check.
   * @return 200 with the transaction details if the C4 country code is still missing, 204 if the
   *         transaction exists but the C4 country code is already set, or 404 if the transaction
   *         does not exist.
   * @since v0.1.3
   */
  @GetMapping ("/missing-c4-country-code/{sbdhInstanceID}")
  @Operation (summary = "Check a specific transaction for missing C4 country code",
              description = "Checks whether a specific inbound transaction is still missing a C4 country code. Since v0.1.3.")
  @ApiResponses ({ @ApiResponse (responseCode = "200",
                                 description = "The C4 country code is still missing — returns the full transaction details"),
                   @ApiResponse (responseCode = "204",
                                 description = "The transaction exists and the C4 country code is already set",
                                 content = @Content),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content),
                   @ApiResponse (responseCode = "404",
                                 description = "No inbound transaction with the given SBDH Instance ID",
                                 content = @Content) })
  public ResponseEntity <InboundTransactionResponse> getMissingC4CountryCodeForTransaction (@Parameter (description = "Peppol SBDH Instance Identifier of the inbound message",
                                                                                                        required = true,
                                                                                                        example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable final String sbdhInstanceID)
  {
    final IInboundTransactionManager aTxMgr = APJdbcMetaManager.getInboundTransactionMgr ();
    final IInboundTransaction aTx = aTxMgr.getBySbdhInstanceID (sbdhInstanceID);
    if (aTx == null)
      return ResponseEntity.notFound ().build ();

    if (StringHelper.isNotEmpty (aTx.getC4CountryCode ()))
      return ResponseEntity.noContent ().build ();

    return ResponseEntity.ok (InboundTransactionResponse.fromDomain (aTx));
  }
}
