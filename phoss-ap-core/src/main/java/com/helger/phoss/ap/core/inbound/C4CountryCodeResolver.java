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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.annotation.concurrent.Immutable;
import com.helger.collection.commons.ICommonsOrderedSet;
import com.helger.peppol.apsupport.BusinessCardCache;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.factory.IIdentifierFactory;
import com.helger.peppolid.peppol.pidscheme.IPeppolParticipantIdentifierScheme;
import com.helger.peppolid.peppol.pidscheme.PeppolParticipantIdentifierSchemeManager;
import com.helger.phoss.ap.api.codelist.EC4CountryCodeMode;
import com.helger.phoss.ap.api.model.IInboundTransaction;
import com.helger.phoss.ap.basic.APBasicMetaManager;
import com.helger.phoss.ap.core.APCoreConfig;
import com.helger.phoss.ap.core.APCoreMetaManager;

/**
 * Resolves the C4 country code for an inbound transaction by trying the configured modes in order.
 * Falls through to the next mode if the current one does not yield a valid 2-letter country code.
 *
 * @author Philip Helger
 * @since v0.1.3
 */
@Immutable
public final class C4CountryCodeResolver
{
  private static final Logger LOGGER = LoggerFactory.getLogger (C4CountryCodeResolver.class);

  private C4CountryCodeResolver ()
  {}

  /**
   * Check if the provided string is a valid 2-letter country code (not "international" or similar).
   *
   * @param sCountryCode
   *        The country code to check. May be <code>null</code>.
   * @return <code>true</code> if it is a valid 2-letter uppercase country code.
   */
  private static boolean _isValidCountryCode (@Nullable final String sCountryCode)
  {
    return sCountryCode != null &&
           sCountryCode.length () == 2 &&
           Character.isUpperCase (sCountryCode.charAt (0)) &&
           Character.isUpperCase (sCountryCode.charAt (1));
  }

  /**
   * Try to determine the C4 country code from the receiver's participant identifier scheme. The ISO
   * 6523 code prefix of the receiver value is mapped to a country code via the predefined scheme
   * registry.
   *
   * @param aTx
   *        The inbound transaction. May not be <code>null</code>.
   * @return The country code or <code>null</code> if it cannot be determined.
   */
  @Nullable
  private static String _resolveFromReceiverParticipantID (@NonNull final IInboundTransaction aTx)
  {
    final IIdentifierFactory aIF = APBasicMetaManager.getIdentifierFactory ();
    final IParticipantIdentifier aReceiverID = aIF.parseParticipantIdentifier (aTx.getReceiverID ());
    if (aReceiverID == null)
    {
      LOGGER.warn ("Failed to parse receiver participant identifier '" + aTx.getReceiverID () + "'");
      return null;
    }

    final IPeppolParticipantIdentifierScheme aScheme = PeppolParticipantIdentifierSchemeManager.getSchemeOfIdentifier (aReceiverID);
    if (aScheme == null)
      return null;

    final String sCountryCode = aScheme.getCountryCode ();
    if (_isValidCountryCode (sCountryCode))
      return sCountryCode;

    return null;
  }

  /**
   * Try to determine the C4 country code from the Business Card of the receiver participant.
   *
   * @param aTx
   *        The inbound transaction. May not be <code>null</code>.
   * @return The country code or <code>null</code> if it cannot be determined.
   */
  @Nullable
  private static String _resolveFromBusinessCardCache (@NonNull final IInboundTransaction aTx)
  {
    final BusinessCardCache aCache = APCoreMetaManager.getBusinessCardCache ();
    if (aCache == null)
    {
      LOGGER.warn ("BusinessCardCache is not initialized but mode 'business_card' is configured");
      return null;
    }

    final IIdentifierFactory aIF = APBasicMetaManager.getIdentifierFactory ();
    final IParticipantIdentifier aReceiverID = aIF.parseParticipantIdentifier (aTx.getReceiverID ());
    if (aReceiverID == null)
    {
      LOGGER.warn ("Failed to parse receiver participant identifier '" + aTx.getReceiverID () + "'");
      return null;
    }

    final String sCountryCode = aCache.getCountryCode (aReceiverID);
    if (_isValidCountryCode (sCountryCode))
      return sCountryCode;

    return null;
  }

  /**
   * Try to resolve the C4 country code for the given inbound transaction using the configured
   * determination modes in order.
   *
   * @param aTx
   *        The inbound transaction. May not be <code>null</code>.
   * @return The resolved C4 country code, or <code>null</code> if none of the configured modes
   *         could determine it.
   */
  @Nullable
  public static String resolve (@NonNull final IInboundTransaction aTx)
  {
    final ICommonsOrderedSet <EC4CountryCodeMode> aModes = APCoreConfig.getC4CountryCodeModes ();
    for (final EC4CountryCodeMode eMode : aModes)
    {
      final String sCountryCode = switch (eMode)
      {
        case RECEIVER_PARTICIPANT_ID -> _resolveFromReceiverParticipantID (aTx);
        case BUSINESS_CARD_CACHE -> _resolveFromBusinessCardCache (aTx);
      };

      if (sCountryCode != null)
      {
        LOGGER.info ("Resolved C4 country code '" +
                     sCountryCode +
                     "' for transaction '" +
                     aTx.getID () +
                     "' via mode '" +
                     eMode.getID () +
                     "'");
        return sCountryCode;
      }
    }

    return null;
  }
}
