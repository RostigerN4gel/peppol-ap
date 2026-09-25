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
package com.helger.phoss.ap.core.inbound;

import org.jspecify.annotations.NonNull;

import com.helger.phoss.ap.api.model.ForwardingResult;

/**
 * Optional extension of a {@link com.helger.phoss.ap.api.mgr.IDocumentForwarder}: a forwarder that
 * implements this interface can request that a failed forwarding is answered to C2 with an AS4/EBMS
 * error instead of an AS4 Receipt.
 * <p>
 * This only works for the <b>synchronous first</b> forwarding attempt, which runs while C2 is still
 * waiting for the AS4 response. If {@link #isRejectViaAS4(ForwardingResult)} returns
 * <code>true</code> there, {@link InboundOrchestrator} marks the transaction as
 * {@link com.helger.phoss.ap.api.codelist.EInboundStatus#AS4_REJECTED}, schedules no retry and sends
 * no MLS - C2 is expected to retransmit. Transactions in that status are ignored by the duplicate
 * detection, so the retransmission is processed like a first delivery. Retries by the
 * {@code RetryScheduler} never consult this interface, because the AS4 response was already sent.
 * <p>
 * While the forwarding circuit breaker is open, no forwarding is attempted at all. For a forwarder
 * implementing this interface the synchronous first attempt is then rejected via AS4 as well,
 * instead of being accepted for a later retry.
 * <p>
 * Deliberate deviation from the Peppol AS4 profile, which expects failures behind C3 to be reported
 * via MLS only.
 *
 * @author phoss-ap fork
 */
public interface IAS4RejectingDocumentForwarder
{
  /**
   * Decide whether a failed forwarding should be rejected on the AS4 level.
   *
   * @param aResult
   *        The failed forwarding result, as returned by this forwarder. Never <code>null</code>.
   * @return <code>true</code> to answer C2 with an AS4/EBMS error, <code>false</code> to keep the
   *         default handling (Receipt, retry or MLS).
   */
  boolean isRejectViaAS4 (@NonNull ForwardingResult aResult);
}
