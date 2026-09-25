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
package com.helger.phoss.ap.webapp.forwarding;

import org.jspecify.annotations.NonNull;

import com.helger.annotation.Nonempty;
import com.helger.base.state.ESuccess;
import com.helger.base.tostring.ToStringGenerator;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.phoss.ap.api.codelist.EForwardingMode;
import com.helger.phoss.ap.api.mgr.IDocumentForwarder;
import com.helger.phoss.ap.api.model.ForwardingResult;
import com.helger.phoss.ap.api.model.IForwardableDocument;
import com.helger.phoss.ap.core.inbound.IAS4RejectingDocumentForwarder;
import com.helger.phoss.ap.forwarding.http.HttpDocumentForwarder;

/**
 * The built-in synchronous HTTP forwarder (<code>forwarding.mode=http_post_sync</code>) with one
 * difference: if the receiver backend answers with an HTTP error status, C2 gets an AS4/EBMS error
 * instead of an AS4 Receipt, and phoss-ap neither retries nor sends an MLS.
 * <p>
 * Everything else - configuration (<code>forwarding.http.*</code>), request, response contract,
 * <code>{"retry":"none"}</code>, IO errors and timeouts - is delegated unchanged to
 * {@link HttpDocumentForwarder}. Only the synchronous first attempt can be rejected via AS4, see
 * {@link IAS4RejectingDocumentForwarder}.
 * <p>
 * Selected via <code>forwarding.mode=spi</code> and
 * <code>forwarding.spi.id=http-sync-as4-reject</code>.
 *
 * @author phoss-ap fork
 */
public class AS4RejectingHttpForwarder implements IDocumentForwarder, IAS4RejectingDocumentForwarder
{
  /** Provider ID as referenced by <code>forwarding.spi.id</code>. */
  public static final String PROVIDER_ID = "http-sync-as4-reject";

  /**
   * The error code {@link HttpDocumentForwarder} uses for an HTTP response status &ge; 300.
   */
  static final String ERROR_CODE_HTTP_STATUS = "http_status";

  private final HttpDocumentForwarder m_aDelegate = new HttpDocumentForwarder (EForwardingMode.HTTP_POST_SYNC);

  /** {@inheritDoc} */
  @Override
  @NonNull
  @Nonempty
  public String getID ()
  {
    return PROVIDER_ID;
  }

  /** {@inheritDoc} */
  @Override
  @NonNull
  public ESuccess initFromConfiguration (@NonNull final IConfigWithFallback aConfig, @NonNull final String sKeyPrefix)
  {
    return m_aDelegate.initFromConfiguration (aConfig, sKeyPrefix);
  }

  /** {@inheritDoc} */
  @Override
  @NonNull
  public ForwardingResult forwardDocument (@NonNull final IForwardableDocument aDocument)
  {
    return m_aDelegate.forwardDocument (aDocument);
  }

  /** {@inheritDoc} */
  @Override
  public boolean isWithDeliveryConfirmation ()
  {
    return m_aDelegate.isWithDeliveryConfirmation ();
  }

  /** {@inheritDoc} */
  @Override
  public boolean isRejectViaAS4 (@NonNull final ForwardingResult aResult)
  {
    // Only an HTTP error status - not IO errors or timeouts, and not "retry":"none"
    return aResult.isFailure () && ERROR_CODE_HTTP_STATUS.equals (aResult.getErrorCode ());
  }

  /** {@inheritDoc} */
  @Override
  public String toString ()
  {
    return new ToStringGenerator (this).append ("Delegate", m_aDelegate).getToString ();
  }
}
