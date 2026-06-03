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

import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helger.collection.commons.ICommonsList;
import com.helger.phoss.ap.api.IInboundTransactionManager;
import com.helger.phoss.ap.api.dto.InboundTransactionResponse;
import com.helger.phoss.ap.api.dto.MlsSlaEntryResponse;
import com.helger.phoss.ap.api.dto.MlsSlaReportResponse;
import com.helger.phoss.ap.db.APJdbcMetaManager;
import com.helger.phoss.ap.db.MlsMetricsManagerJdbc;
import com.helger.phoss.ap.db.MlsMetricsManagerJdbc.MlsSlaReport;
import com.helger.phoss.ap.webapp.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for MLS (Message Level Status) related operations including querying transactions
 * with missing MLS responses and retrieving MLS SLA compliance reports per Peppol Network Policy.
 *
 * @author Philip Helger
 */
@RestController
@RequestMapping ("/api/mls")
@Tag (name = "MLS", description = "Message Level Status — missing responses and SLA reports per Peppol Network Policy")
@SecurityRequirement (name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class MlsController
{
  /**
   * Get all inbound business document transactions for which no MLS response has been sent yet.
   *
   * @return List of inbound transactions without MLS response.
   */
  @GetMapping ("/missing")
  @Operation (summary = "List inbound transactions missing an MLS response",
              description = "Returns all inbound business document transactions for which no MLS response has been sent yet " +
                            "(mls_response_code IS NULL). Excludes incoming MLS messages themselves.")
  @ApiResponses ({ @ApiResponse (responseCode = "200",
                                 description = "List of inbound transactions without an MLS response"),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content) })
  public ResponseEntity <List <InboundTransactionResponse>> getMissingMls ()
  {
    final IInboundTransactionManager aTxMgr = APJdbcMetaManager.getInboundTransactionMgr ();

    final var aTxs = aTxMgr.getAllWithoutMlsResponse ();
    final ICommonsList <InboundTransactionResponse> aResult = aTxs.getAllMapped (InboundTransactionResponse::fromDomain);
    return ResponseEntity.ok (aResult);
  }

  /**
   * Create a response DTO from a domain model MLS SLA report. This factory method depends on
   * {@code phoss-ap-db} types and therefore stays in the webapp module.
   *
   * @param aReport
   *        The MLS SLA report. May not be <code>null</code>.
   * @return A new response DTO. Never <code>null</code>.
   */
  @NonNull
  private static MlsSlaReportResponse _fromDomain (@NonNull final MlsSlaReport aReport)
  {
    final MlsSlaReportResponse ret = new MlsSlaReportResponse ();
    ret.setTotalCount (aReport.totalCount ());
    ret.setWithinSlaCount (aReport.withinSlaCount ());
    ret.setCompliancePercent (aReport.compliancePercent ());
    ret.setTargetPercent (aReport.targetPercent ());
    ret.setThresholdSeconds (aReport.thresholdSeconds ());
    ret.setMeetingSla (aReport.isMeetingSla ());
    ret.setEntries (aReport.entries ()
                           .getAllMapped (e -> new MlsSlaEntryResponse (e.sbdhInstanceID (),
                                                                        e.m1 ().toString (),
                                                                        e.m2OrM3 ().toString (),
                                                                        e.durationSeconds (),
                                                                        e.withinSla ())));
    return ret;
  }

  /**
   * Get MLS-1 SLA report (receiving side). Measures M2 - M1: time between receiving the original
   * business document (M1) and successfully sending back the MLS response (M2). SLR: 99.5% within
   * 20 minutes.
   *
   * @return The MLS-1 SLA report.
   */
  @GetMapping ("/sla/mls1")
  @Operation (summary = "MLS-1 SLA report (receiving side)",
              description = "Measures M2 - M1: time between receiving the original business document (M1) and successfully " +
                            "sending back the MLS response (M2). Per Peppol Network Policy: 99.5% must be within 20 minutes.")
  @ApiResponses ({ @ApiResponse (responseCode = "200", description = "MLS-1 SLA report"),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content) })
  public ResponseEntity <MlsSlaReportResponse> getMls1Sla ()
  {
    final MlsMetricsManagerJdbc aMetricsMgr = APJdbcMetaManager.getMlsMetricsMgr ();

    final var aReport = aMetricsMgr.getMls1Report ();
    return ResponseEntity.ok (_fromDomain (aReport));
  }

  /**
   * Get MLS-2 SLA report (sending side). Measures M3 - M1: time between successfully sending the
   * business document (M1) and receiving the MLS response from C3 (M3). SLR: 99.5% within 25
   * minutes.
   *
   * @return The MLS-2 SLA report.
   */
  @GetMapping ("/sla/mls2")
  @Operation (summary = "MLS-2 SLA report (sending side)",
              description = "Measures M3 - M1: time between successfully sending the business document (M1) and receiving " +
                            "the MLS response from C3 (M3). Per Peppol Network Policy: 99.5% must be within 25 minutes.")
  @ApiResponses ({ @ApiResponse (responseCode = "200", description = "MLS-2 SLA report"),
                   @ApiResponse (responseCode = "401",
                                 description = "Missing or invalid API token",
                                 content = @Content) })
  public ResponseEntity <MlsSlaReportResponse> getMls2Sla ()
  {
    final MlsMetricsManagerJdbc aMetricsMgr = APJdbcMetaManager.getMlsMetricsMgr ();

    final var aReport = aMetricsMgr.getMls2Report ();
    return ResponseEntity.ok (_fromDomain (aReport));
  }
}
