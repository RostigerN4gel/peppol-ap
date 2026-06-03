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

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helger.json.IJsonObject;
import com.helger.json.serialize.JsonWriter;
import com.helger.json.serialize.JsonWriterSettings;
import com.helger.phoss.ap.core.APCoreConfig;
import com.helger.phoss.ap.core.status.APStatusProvider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Management status endpoint providing non-sensitive configuration and version information as JSON.
 *
 * @author Philip Helger
 */
@RestController
@RequestMapping ("/management")
@Tag (name = "Management",
      description = "Operational endpoints (not protected by the API token; restrict at a reverse proxy if needed).")
public class StatusController
{
  /**
   * @return JSON object with status data, or a minimal disabled indicator if the status endpoint is
   *         disabled via configuration.
   */
  @GetMapping (path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation (summary = "Status, version and configuration overview",
              description = "Returns non-sensitive configuration values, version information and runtime metadata. " +
                            "When 'management.status.enabled=false' a minimal disabled indicator is returned instead.")
  @ApiResponse (responseCode = "200", description = "Status JSON document")
  public ResponseEntity <String> getStatus ()
  {
    final IJsonObject aStatusData;
    if (APCoreConfig.isManagementStatusEnabled ())
      aStatusData = APStatusProvider.getDefaultStatusData ();
    else
      aStatusData = APStatusProvider.getStatusDisabledData ();

    return ResponseEntity.ok (new JsonWriter (JsonWriterSettings.DEFAULT_SETTINGS_FORMATTED).writeAsString (aStatusData));
  }
}
