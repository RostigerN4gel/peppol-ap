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
package com.helger.phoss.ap.core.outbound;

import org.jspecify.annotations.NonNull;

import com.helger.base.enforce.ValueEnforcer;
import com.helger.base.tostring.ToStringGenerator;
import com.helger.peppolid.IParticipantIdentifier;

/**
 * Describes the MLS specific SMP lookup fallback behaviour according to MLS SPOG section 5.4. When
 * a custom <code>MLS_TO</code> receiver cannot be resolved via SMP lookup, the outbound processing
 * falls back to the default SPID receiver (derived from the sending C2's Peppol AP certificate),
 * after notifying registered handlers via
 * {@link com.helger.phoss.ap.api.spi.IAPNotificationHandlerSPI#onSpecialMlsToNotReachable}.
 *
 * @author Philip Helger
 * @since 0.11.0
 */
public final class MlsSmpFallback
{
  private final IParticipantIdentifier m_aFallbackReceiverID;
  private final String m_sReferencedSbdhInstanceID;

  /**
   * Constructor.
   *
   * @param aFallbackReceiverID
   *        The default SPID receiver participant identifier to use when the primary MLS receiver is
   *        not reachable. May not be <code>null</code>.
   * @param sReferencedSbdhInstanceID
   *        The SBDH Instance Identifier of the original business document the MLS refers to, used
   *        for notification purposes. May not be <code>null</code>.
   */
  public MlsSmpFallback (@NonNull final IParticipantIdentifier aFallbackReceiverID,
                         @NonNull final String sReferencedSbdhInstanceID)
  {
    ValueEnforcer.notNull (aFallbackReceiverID, "FallbackReceiverID");
    ValueEnforcer.notNull (sReferencedSbdhInstanceID, "ReferencedSbdhInstanceID");
    m_aFallbackReceiverID = aFallbackReceiverID;
    m_sReferencedSbdhInstanceID = sReferencedSbdhInstanceID;
  }

  /**
   * @return The default SPID receiver participant identifier to fall back to. Never
   *         <code>null</code>.
   */
  @NonNull
  public IParticipantIdentifier getFallbackReceiverID ()
  {
    return m_aFallbackReceiverID;
  }

  /**
   * @return The SBDH Instance Identifier of the original business document. Never
   *         <code>null</code>.
   */
  @NonNull
  public String getReferencedSbdhInstanceID ()
  {
    return m_sReferencedSbdhInstanceID;
  }

  @Override
  public String toString ()
  {
    return new ToStringGenerator (this).append ("FallbackReceiverID", m_aFallbackReceiverID)
                                       .append ("ReferencedSbdhInstanceID", m_sReferencedSbdhInstanceID)
                                       .getToString ();
  }
}
