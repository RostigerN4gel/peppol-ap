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
package com.helger.phoss.ap.api.spi;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.helger.annotation.Nonempty;
import com.helger.annotation.style.IsSPIInterface;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.phoss.ap.api.model.MlsOutcome;

/**
 * SPI interface for optional document verification. Implementations are loaded via
 * {@link java.util.ServiceLoader}. Multiple verifiers may be registered and are evaluated in order
 * — all must pass for the document to be accepted.
 *
 * @author Philip Helger
 */
@IsSPIInterface
public interface IInboundDocumentVerifierSPI
{
  /**
   * Verify a document's content against the given document type and process identifiers.
   *
   * @param sDocumentPath
   *        The absolute path where the document is stored. Never <code>null</code>.
   * @param aDocTypeID
   *        The Peppol Document Type Identifier. Never <code>null</code>.
   * @param aProcessID
   *        The Peppol Process Identifier. Never <code>null</code>.
   * @return <code>null</code> or an {@link MlsOutcome} with a non-failing response code if the
   *         verifier has no objection. A non-<code>null</code> {@link MlsOutcome} with response
   *         code {@link com.helger.peppol.mls.EPeppolMLSResponseCode#REJECTION REJECTION} signals
   *         that the document is rejected; its issues are propagated into the MLS response.
   */
  @Nullable
  MlsOutcome verifyInboundDocument (@NonNull @Nonempty String sDocumentPath,
                                    @NonNull IDocumentTypeIdentifier aDocTypeID,
                                    @NonNull IProcessIdentifier aProcessID);
}
