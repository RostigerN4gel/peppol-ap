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
package com.helger.phoss.ap.core;

import java.util.ServiceLoader;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.annotation.style.ReturnsMutableCopy;
import com.helger.annotation.style.VisibleForTesting;
import com.helger.base.exception.InitializationException;
import com.helger.base.string.StringHelper;
import com.helger.collection.commons.CommonsArrayList;
import com.helger.collection.commons.CommonsHashMap;
import com.helger.collection.commons.ICommonsMap;
import com.helger.collection.commons.ICommonsList;
import com.helger.collection.commons.ICommonsOrderedSet;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.peppol.apsupport.BusinessCardCache;
import com.helger.peppol.servicedomain.EPeppolNetwork;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.phoss.ap.api.codelist.EC4CountryCodeMode;
import com.helger.phoss.ap.api.codelist.EForwardingMode;
import com.helger.phoss.ap.api.config.APConfigProvider;
import com.helger.phoss.ap.api.config.APConfigurationProperties;
import com.helger.phoss.ap.api.mgr.IDocumentForwarder;
import com.helger.phoss.ap.api.spi.IAPLifecycleEventSPI;
import com.helger.phoss.ap.api.spi.IAPNotificationHandlerSPI;
import com.helger.phoss.ap.api.spi.IDocumentForwarderProviderSPI;
import com.helger.phoss.ap.api.spi.IDocumentVerifier;
import com.helger.phoss.ap.api.spi.IInboundDocumentVerifierSPI;
import com.helger.phoss.ap.api.spi.IOutboundDocumentVerifierSPI;
import com.helger.phoss.ap.api.spi.IPeppolReceiverCheckSPI;
import com.helger.phoss.ap.basic.APBasicConfig;
import com.helger.phoss.ap.core.forwarding.DocumentForwarderFactory;
import com.helger.phoss.ap.core.notification.LifecycleEventManager;
import com.helger.phoss.ap.core.reporting.APPeppolReportingHelper;
import com.helger.phoss.ap.core.notification.NotificationHandlerManager;
import com.helger.smpclient.httpclient.SMPHttpClientSettings;

/**
 * Central manager for core AP components including the document forwarder, inbound/outbound
 * document verifiers, receiver checks, and notification handlers. Components are initialized from
 * configuration and via {@link ServiceLoader} SPI.
 *
 * @author Philip Helger
 */
public final class APCoreMetaManager
{
  public static final int MAX_SECONDARY_FORWARDERS = 10;
  private static final Logger LOGGER = LoggerFactory.getLogger (APCoreMetaManager.class);

  private static EForwardingMode s_eForwardingMode;
  private static IDocumentForwarder s_aForwarder;
  private static final ICommonsList <IDocumentForwarder> s_aSecondaryForwarders = new CommonsArrayList <> ();
  private static IDocumentForwarder s_aMlsCopyForwarder;
  private static BusinessCardCache s_aBusinessCardCache;
  private static final ICommonsList <IInboundDocumentVerifierSPI> s_aInboundVerifiers = new CommonsArrayList <> ();
  private static final ICommonsList <IOutboundDocumentVerifierSPI> s_aOutboundVerifiers = new CommonsArrayList <> ();
  private static final ICommonsList <IPeppolReceiverCheckSPI> s_aReceiverChecks = new CommonsArrayList <> ();

  private APCoreMetaManager ()
  {}

  /**
   * Initialize the core meta manager by creating the document forwarder from configuration and
   * loading all SPI-based verifiers, receiver checks, and notification handlers.
   */
  public static void init ()
  {
    LOGGER.info ("Initializing APMetaManager");

    final IConfigWithFallback aConfig = APConfigProvider.getConfig ();

    // Create primary forwarder based on configuration
    {
      final String sForwardingMode = aConfig.getAsString (APConfigurationProperties.FORWARDING_MODE);
      final EForwardingMode eForwardingMode = EForwardingMode.getFromIDOrNull (sForwardingMode);
      if (eForwardingMode == null)
        throw new InitializationException ("The configured Forwarding Mode '" + sForwardingMode + "' is invalid");

      final IDocumentForwarder aForwarder = DocumentForwarderFactory.create (eForwardingMode,
                                                                             aConfig,
                                                                             IDocumentForwarder.DEFAULT_CONFIG_KEY_PREFIX);
      if (aForwarder.initFromConfiguration (aConfig, IDocumentForwarder.DEFAULT_CONFIG_KEY_PREFIX).isFailure ())
        throw new InitializationException ("Failed to init forwarder configuration - see logs for details");

      s_eForwardingMode = eForwardingMode;
      s_aForwarder = aForwarder;
      LOGGER.info ("Loaded document forwarder: " + aForwarder.toString ());
    }

    // Create secondary forwarders (fire-and-forget, no retry, no SLA) based on configuration
    {
      for (int nIndex = 1;; nIndex++)
      {
        final String sSecondaryPrefix = APConfigurationProperties.FORWARDING_SECONDARY_PREFIX + nIndex + ".";
        final String sSecondaryMode = aConfig.getAsString (sSecondaryPrefix +
                                                           APConfigurationProperties.FORWARDING_SECONDARY_MODE_SUFFIX);
        if (sSecondaryMode == null)
          break;

        final EForwardingMode eSecondaryMode = EForwardingMode.getFromIDOrNull (sSecondaryMode);
        if (eSecondaryMode == null)
          throw new InitializationException ("The configured Forwarding Mode for secondary forwarder at index " +
                                             nIndex +
                                             " ('" +
                                             sSecondaryMode +
                                             "') is invalid");

        if (nIndex > MAX_SECONDARY_FORWARDERS)
          throw new InitializationException ("No more than " +
                                             MAX_SECONDARY_FORWARDERS +
                                             " secondary Forwarders are allowed.");

        final IDocumentForwarder aSecondary = DocumentForwarderFactory.create (eSecondaryMode,
                                                                               aConfig,
                                                                               sSecondaryPrefix);
        if (aSecondary.initFromConfiguration (aConfig, sSecondaryPrefix).isFailure ())
          throw new InitializationException ("Failed to init secondary forwarder #" +
                                             nIndex +
                                             " configuration - see logs for details");

        s_aSecondaryForwarders.add (aSecondary);
        LOGGER.info ("Loaded secondary document forwarder #" + nIndex + ": " + aSecondary.toString ());
      }
      if (s_aSecondaryForwarders.isEmpty ())
        LOGGER.info ("No secondary document forwarders configured");
      else
        LOGGER.info ("Loaded " + s_aSecondaryForwarders.size () + " secondary document forwarder(s)");
    }

    // Create the sink for the copies of the self-generated MLS documents (fire-and-forget, no
    // retry, no SLA). Disabled by default - stay completely silent in that case.
    if (aConfig.getAsBoolean (APConfigurationProperties.FORWARDING_MLS_COPY_ENABLED,
                              APConfigurationProperties.FORWARDING_MLS_COPY_ENABLED_DEFAULT))
    {
      // Without an own mode the whole primary forwarder configuration is reused
      final String sOwnMode = aConfig.getAsString (APConfigurationProperties.FORWARDING_MLS_COPY_MODE);
      final boolean bOwnConfig = StringHelper.isNotEmpty (sOwnMode);
      final String sMlsCopyPrefix = bOwnConfig ? APConfigurationProperties.FORWARDING_MLS_COPY_PREFIX
                                               : IDocumentForwarder.DEFAULT_CONFIG_KEY_PREFIX;

      final EForwardingMode eMlsCopyMode = bOwnConfig ? EForwardingMode.getFromIDOrNull (sOwnMode) : s_eForwardingMode;
      if (eMlsCopyMode == null)
        throw new InitializationException ("The configured Forwarding Mode for the MLS copy ('" +
                                           sOwnMode +
                                           "') is invalid");

      final IDocumentForwarder aMlsCopy = DocumentForwarderFactory.create (eMlsCopyMode, aConfig, sMlsCopyPrefix);
      if (aMlsCopy.initFromConfiguration (aConfig, sMlsCopyPrefix).isFailure ())
        throw new InitializationException ("Failed to init the MLS copy forwarder configuration - see logs for details");

      s_aMlsCopyForwarder = aMlsCopy;
      LOGGER.info ("Loaded MLS copy document forwarder" +
                   (bOwnConfig ? "" : " (reusing the primary forwarder configuration)") +
                   ": " +
                   aMlsCopy.toString ());
    }

    // Initialize Business Card Cache if configured
    {
      final ICommonsOrderedSet <EC4CountryCodeMode> aModes = APCoreConfig.getC4CountryCodeModes ();
      if (aModes.contains (EC4CountryCodeMode.BUSINESS_CARD_CACHE))
      {
        final EPeppolNetwork ePeppolStage = APCoreConfig.getPeppolStage ();
        if (ePeppolStage == null)
          throw new InitializationException ("Peppol stage must be configured when using C4 country code mode 'business_card'");

        final SMPHttpClientSettings aHCS = new SMPHttpClientSettings ();
        APBasicConfig.applyHttpProxySettings (aHCS);
        s_aBusinessCardCache = new BusinessCardCache (ePeppolStage.getSMLInfo (), aHCS);
        LOGGER.info ("Initialized BusinessCardCache for C4 country code determination");
      }
      if (aModes.isNotEmpty ())
        LOGGER.info ("C4 country code determination modes: " + aModes);
    }

    // Log the effective Peppol Reporting exclusions, so that configuration errors are visible on
    // startup and not only when the first matching transaction is handled
    {
      final ICommonsList <IParticipantIdentifier> aExcludedPIDs = APPeppolReportingHelper.getAllExcludedParticipantIDs ();
      if (aExcludedPIDs.isNotEmpty ())
        LOGGER.info ("Excluding " +
                     aExcludedPIDs.size () +
                     " participant ID(s) from Peppol Reporting: " +
                     aExcludedPIDs.getAllMapped (IParticipantIdentifier::getURIEncoded));
    }

    for (final IInboundDocumentVerifierSPI aVerifier : ServiceLoader.load (IInboundDocumentVerifierSPI.class))
    {
      s_aInboundVerifiers.add (aVerifier);
      LOGGER.info ("Loaded inbound document verifier '" +
                   aVerifier.getID () +
                   "': " +
                   aVerifier.getClass ().getName ());
    }

    for (final IOutboundDocumentVerifierSPI aVerifier : ServiceLoader.load (IOutboundDocumentVerifierSPI.class))
    {
      s_aOutboundVerifiers.add (aVerifier);
      LOGGER.info ("Loaded outbound document verifier '" +
                   aVerifier.getID () +
                   "': " +
                   aVerifier.getClass ().getName ());
    }

    // The IDs must be unique over all verifiers, so that a verifier can be identified by its ID
    final ICommonsList <IDocumentVerifier> aAllVerifiers = new CommonsArrayList <> ();
    aAllVerifiers.addAll (s_aInboundVerifiers);
    aAllVerifiers.addAll (s_aOutboundVerifiers);
    checkVerifierIDsAreUnique (aAllVerifiers);

    // The IDs of the forwarder providers must be unique as well, because the configuration selects
    // a provider by its ID
    final ICommonsList <IDocumentForwarderProviderSPI> aAllProviders = new CommonsArrayList <> ();
    for (final IDocumentForwarderProviderSPI aProvider : ServiceLoader.load (IDocumentForwarderProviderSPI.class))
    {
      aAllProviders.add (aProvider);
      LOGGER.info ("Loaded document forwarder provider '" +
                   aProvider.getID () +
                   "': " +
                   aProvider.getClass ().getName ());
    }
    checkForwarderProviderIDsAreUnique (aAllProviders);

    for (final IPeppolReceiverCheckSPI aCheck : ServiceLoader.load (IPeppolReceiverCheckSPI.class))
    {
      s_aReceiverChecks.add (aCheck);
      LOGGER.info ("Loaded receiver check: " + aCheck.getClass ().getName ());
    }

    NotificationHandlerManager.initSPI ();
    LifecycleEventManager.initSPI ();

    LOGGER.info ("APMetaManager initialized successfully");
  }

  /**
   * Ensure that the IDs of all inbound and outbound verifiers are unique. A verifier class that
   * implements both SPIs is loaded twice - once per SPI - and therefore contributes the same ID
   * twice, which is not a duplicate.
   *
   * @param aAllVerifiers
   *        All loaded inbound and outbound verifiers. May not be <code>null</code>.
   * @throws InitializationException
   *         If two different verifier classes use the same ID.
   */
  @VisibleForTesting
  static void checkVerifierIDsAreUnique (@NonNull final Iterable <? extends IDocumentVerifier> aAllVerifiers)
  {
    // Maps the verifier ID to the implementation class that uses it
    final ICommonsMap <String, Class <?>> aIDToClass = new CommonsHashMap <> ();

    for (final IDocumentVerifier aVerifier : aAllVerifiers)
    {
      final String sID = aVerifier.getID ();
      if (StringHelper.isEmpty (sID))
        throw new InitializationException ("The document verifier " +
                                           aVerifier.getClass ().getName () +
                                           " provides an empty ID");

      final Class <?> aExistingClass = aIDToClass.get (sID);
      if (aExistingClass == null)
        aIDToClass.put (sID, aVerifier.getClass ());
      else
        if (!aExistingClass.equals (aVerifier.getClass ()))
        {
          // The same ID is used by two different implementations
          throw new InitializationException ("The document verifier ID '" +
                                             sID +
                                             "' is used by " +
                                             aExistingClass.getName () +
                                             " and by " +
                                             aVerifier.getClass ().getName () +
                                             ". Verifier IDs must be unique over all inbound and outbound verifiers.");
        }
    }
  }

  /**
   * Ensure that the IDs of all document forwarder provider SPIs are unique, because the
   * configuration property selecting a forwarder refers to exactly one of them.
   *
   * @param aAllProviders
   *        All loaded forwarder providers. May not be <code>null</code>.
   * @throws InitializationException
   *         If two providers use the same ID.
   */
  @VisibleForTesting
  static void checkForwarderProviderIDsAreUnique (@NonNull final Iterable <? extends IDocumentForwarderProviderSPI> aAllProviders)
  {
    final ICommonsMap <String, Class <?>> aIDToClass = new CommonsHashMap <> ();
    for (final IDocumentForwarderProviderSPI aProvider : aAllProviders)
    {
      final String sID = aProvider.getID ();
      if (StringHelper.isEmpty (sID))
        throw new InitializationException ("The document forwarder provider " +
                                           aProvider.getClass ().getName () +
                                           " provides an empty ID");

      final Class <?> aExistingClass = aIDToClass.put (sID, aProvider.getClass ());
      if (aExistingClass != null)
        throw new InitializationException ("The document forwarder provider ID '" +
                                           sID +
                                           "' is used by " +
                                           aExistingClass.getName () +
                                           " and by " +
                                           aProvider.getClass ().getName () +
                                           ". Forwarder provider IDs must be unique over all providers.");
    }
  }

  /**
   * Shutdown the core meta manager and release all resources.
   */
  public static void shutdown ()
  {
    LOGGER.info ("Shutting down APMetaManager");
  }

  /**
   * @return The configured forwarding mode. Never <code>null</code>.
   */
  @NonNull
  public static EForwardingMode getForwardingMode ()
  {
    return s_eForwardingMode;
  }

  /**
   * @return The configured document forwarder instance. Never <code>null</code>.
   */
  @NonNull
  public static IDocumentForwarder getForwarder ()
  {
    return s_aForwarder;
  }

  /**
   * @return A mutable copy of all configured secondary document forwarders. Never <code>null</code>
   *         but may be empty. Secondary forwarders are dispatched on a fire-and-forget basis after
   *         the primary forwarder succeeded; they have no retries and their failure does not affect
   *         the inbound transaction status.
   * @since 0.9.0
   */
  @NonNull
  @ReturnsMutableCopy
  public static ICommonsList <IDocumentForwarder> getAllSecondaryForwarders ()
  {
    return s_aSecondaryForwarders.getClone ();
  }

  /**
   * @return The forwarder that receives a copy of every MLS this AP generates itself, or
   *         <code>null</code> if <code>forwarding.mls-copy.enabled</code> is not set. It is
   *         dispatched on a fire-and-forget basis; it has no retries and its failure affects
   *         neither the MLS sending to C2 nor the inbound transaction status.
   * @since 0.12.0
   */
  @Nullable
  public static IDocumentForwarder getMlsCopyForwarderOrNull ()
  {
    return s_aMlsCopyForwarder;
  }

  /**
   * @return The Business Card Cache for C4 country code lookup, or <code>null</code> if not
   *         configured.
   * @since v0.1.3
   */
  @Nullable
  public static BusinessCardCache getBusinessCardCache ()
  {
    return s_aBusinessCardCache;
  }

  /**
   * @return A mutable copy of all registered inbound document verifiers. Never <code>null</code>.
   */
  @NonNull
  @ReturnsMutableCopy
  public static ICommonsList <IInboundDocumentVerifierSPI> getAllInboundVerifiers ()
  {
    return s_aInboundVerifiers.getClone ();
  }

  /**
   * @return A mutable copy of all registered outbound document verifiers. Never <code>null</code>.
   */
  @NonNull
  @ReturnsMutableCopy
  public static ICommonsList <IOutboundDocumentVerifierSPI> getAllOutboundVerifiers ()
  {
    return s_aOutboundVerifiers.getClone ();
  }

  /**
   * @return A mutable copy of all registered Peppol receiver checks. Never <code>null</code>.
   */
  @NonNull
  @ReturnsMutableCopy
  public static ICommonsList <IPeppolReceiverCheckSPI> getAllPeppolReceiverChecks ()
  {
    return s_aReceiverChecks.getClone ();
  }

  /**
   * @return A mutable copy of all registered notification handlers. Never <code>null</code>.
   */
  @NonNull
  @ReturnsMutableCopy
  public static ICommonsList <IAPNotificationHandlerSPI> getAllNotificationHandlers ()
  {
    return NotificationHandlerManager.getAllNotificationHandlers ();
  }

  /**
   * @return A mutable copy of all registered lifecycle event handlers. Never <code>null</code>.
   * @since 0.9.0
   */
  @NonNull
  @ReturnsMutableCopy
  public static ICommonsList <IAPLifecycleEventSPI> getAllLifecycleHandlers ()
  {
    return LifecycleEventManager.getAllLifecycleHandlers ();
  }
}
