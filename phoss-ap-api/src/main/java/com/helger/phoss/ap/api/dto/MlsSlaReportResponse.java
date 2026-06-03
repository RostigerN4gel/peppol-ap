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
package com.helger.phoss.ap.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON response DTO representing an MLS SLA compliance report with individual measurement entries
 * and aggregated statistics. Usable both for server-side serialization and client-side
 * deserialization.
 * <p>
 * Note: The server-side {@code fromDomain(MlsSlaReport)} factory method remains in the webapp
 * module because it depends on {@code phoss-ap-db} types.
 *
 * @author Philip Helger
 */
@Schema (description = "MLS SLA compliance report — aggregated statistics plus the underlying " +
                       "individual measurements. Used for both MLS-1 (receiving side, 20-minute threshold) " +
                       "and MLS-2 (sending side, 25-minute threshold) per Peppol Network Policy.")
public class MlsSlaReportResponse
{
  @Schema (description = "Total number of measured transactions", example = "1240")
  private int totalCount;

  @Schema (description = "Number of measurements within the SLA threshold", example = "1238")
  private int withinSlaCount;

  @Schema (description = "Actual compliance percentage", example = "99.84")
  private double compliancePercent;

  @Schema (description = "Required target percentage per Peppol Network Policy", example = "99.5")
  private double targetPercent;

  @Schema (description = "SLA threshold in seconds (1200 for MLS-1, 1500 for MLS-2)", example = "1200")
  private long thresholdSeconds;

  @Schema (description = "Whether the target is currently met")
  private boolean meetingSla;

  @Schema (description = "Individual SLA measurement entries")
  private List <MlsSlaEntryResponse> entries;

  /**
   * Default constructor for JSON deserialization.
   */
  public MlsSlaReportResponse ()
  {}

  /** @return the total number of measured transactions */
  public int getTotalCount ()
  {
    return totalCount;
  }

  /**
   * @param n
   *        The total count to set.
   */
  public void setTotalCount (final int n)
  {
    totalCount = n;
  }

  /** @return the number of transactions within the SLA threshold */
  public int getWithinSlaCount ()
  {
    return withinSlaCount;
  }

  /**
   * @param n
   *        The within-SLA count to set.
   */
  public void setWithinSlaCount (final int n)
  {
    withinSlaCount = n;
  }

  /** @return the SLA compliance percentage */
  public double getCompliancePercent ()
  {
    return compliancePercent;
  }

  /**
   * @param d
   *        The compliance percentage to set.
   */
  public void setCompliancePercent (final double d)
  {
    compliancePercent = d;
  }

  /** @return the target SLA percentage */
  public double getTargetPercent ()
  {
    return targetPercent;
  }

  /**
   * @param d
   *        The target percentage to set.
   */
  public void setTargetPercent (final double d)
  {
    targetPercent = d;
  }

  /** @return the SLA threshold in seconds */
  public long getThresholdSeconds ()
  {
    return thresholdSeconds;
  }

  /**
   * @param n
   *        The threshold in seconds to set.
   */
  public void setThresholdSeconds (final long n)
  {
    thresholdSeconds = n;
  }

  /** @return <code>true</code> if the SLA target is being met */
  public boolean isMeetingSla ()
  {
    return meetingSla;
  }

  /**
   * @param b
   *        <code>true</code> if meeting the SLA target.
   */
  public void setMeetingSla (final boolean b)
  {
    meetingSla = b;
  }

  /** @return the list of individual SLA measurement entries */
  public List <MlsSlaEntryResponse> getEntries ()
  {
    return entries;
  }

  /**
   * @param aEntries
   *        The SLA measurement entries to set.
   */
  public void setEntries (final List <MlsSlaEntryResponse> aEntries)
  {
    entries = aEntries;
  }
}
