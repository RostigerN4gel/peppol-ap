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
package com.helger.phoss.ap.webapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.helger.phoss.ap.api.CPhossAPVersion;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

/**
 * Top-level OpenAPI document metadata. The OpenAPI specification itself is produced at runtime by
 * springdoc-openapi (no UI is bundled — only the raw spec endpoints {@code /v3/api-docs} and
 * {@code /v3/api-docs.yaml} are exposed).
 *
 * @author Philip Helger
 */
@Configuration
public class OpenApiConfig
{
  /**
   * @return The {@link OpenAPI} bean carrying title, version, contact and license metadata.
   */
  @Bean
  public OpenAPI apOpenAPI ()
  {
    return new OpenAPI ().info (new Info ().title ("phoss Peppol Access Point API")
                                           .description ("HTTP API exposed by the phoss Peppol Access Point — " +
                                                         "inbound reporting, outbound submission, MLS, and Peppol Reporting.")
                                           .version (CPhossAPVersion.BUILD_VERSION)
                                           .contact (new Contact ().name ("Philip Helger")
                                                                   .email ("philip@helger.com")
                                                                   .url ("https://github.com/phax/phoss-ap"))
                                           .license (new License ().name ("Apache License, Version 2.0")
                                                                   .url ("https://www.apache.org/licenses/LICENSE-2.0")));
  }
}
