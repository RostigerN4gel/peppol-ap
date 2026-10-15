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
package com.helger.phoss.ap.validation;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.annotation.Nonempty;
import com.helger.annotation.concurrent.GuardedBy;
import com.helger.annotation.concurrent.ThreadSafe;
import com.helger.base.concurrent.SimpleReadWriteLock;
import com.helger.base.enforce.ValueEnforcer;
import com.helger.httpclient.HttpClientSettings;
import com.helger.phorm.client.PhormClient;
import com.helger.phoss.ap.basic.APBasicConfig;
import com.helger.phoss.ap.basic.APBasicMetaManager;

/**
 * Holder of the process wide {@link PhormClient} used by {@link PhormDocumentVerifier}. A
 * {@link PhormClient} owns an HTTP connection pool, so creating one per document would establish a
 * new TLS connection for every single verification - which is what the hand written predecessor of
 * the verifier did.
 * <p>
 * The client is created lazily on the first call and rebuilt whenever the configured phorm URL or
 * token changes, so that a configuration change cannot be masked by a cached client.
 * {@link #shutdown()} closes the pool; it is reached from
 * {@link com.helger.phoss.ap.core.APCoreMetaManager#shutdown()} through
 * {@link PhormDocumentVerifier#close()}, because the verifier SPI itself has no lifecycle.
 *
 * @author Philip Helger
 * @since 0.13.1
 */
@ThreadSafe
final class PhormClientHolder
{
  private static final Logger LOGGER = LoggerFactory.getLogger (PhormClientHolder.class);

  private static final SimpleReadWriteLock RW_LOCK = new SimpleReadWriteLock ();
  @GuardedBy ("RW_LOCK")
  private static PhormClient s_aClient;
  @GuardedBy ("RW_LOCK")
  private static String s_sBaseURL;
  @GuardedBy ("RW_LOCK")
  private static String s_sToken;

  private PhormClientHolder ()
  {}

  /**
   * @param sBaseURL
   *        The requested base URL. May not be <code>null</code>.
   * @param sToken
   *        The requested token. May not be <code>null</code>.
   * @return <code>true</code> if a client is present and was built for exactly that configuration.
   */
  @GuardedBy ("RW_LOCK")
  private static boolean _isUsable (@NonNull final String sBaseURL, @NonNull final String sToken)
  {
    return s_aClient != null && sBaseURL.equals (s_sBaseURL) && sToken.equals (s_sToken);
  }

  /**
   * Close and forget the current client, if any. Must be called with the write lock held.
   */
  @GuardedBy ("RW_LOCK")
  private static void _closeClient ()
  {
    if (s_aClient != null)
    {
      try
      {
        s_aClient.close ();
        LOGGER.info ("Closed the shared phorm client of '" + s_sBaseURL + "'");
      }
      catch (final Exception ex)
      {
        LOGGER.error ("Failed to close the shared phorm client of '" + s_sBaseURL + "'", ex);
      }
      s_aClient = null;
      s_sBaseURL = null;
      s_sToken = null;
    }
  }

  @NonNull
  private static PhormClient _createClient (@NonNull @Nonempty final String sBaseURL,
                                            @NonNull @Nonempty final String sToken)
  {
    final HttpClientSettings aHCS = new HttpClientSettings ();
    APBasicConfig.applyHttpProxySettings (aHCS);
    return PhormClient.builder ()
                      .baseURL (sBaseURL)
                      .token (sToken)
                      // Only relevant if the determined document details are ever read - use the
                      // same factory as the rest of the AP, so that they mean the same thing
                      .identifierFactory (APBasicMetaManager.getIdentifierFactory ())
                      .httpClientSettings (aHCS)
                      .build ();
  }

  /**
   * Get the shared client for the provided phorm configuration, creating it if necessary.
   *
   * @param sBaseURL
   *        The configured phorm base URL. May neither be <code>null</code> nor empty.
   * @param sToken
   *        The configured phorm API token. May neither be <code>null</code> nor empty.
   * @return The shared client. Never <code>null</code>. It must not be closed by the caller.
   */
  @NonNull
  static PhormClient getClient (@NonNull @Nonempty final String sBaseURL, @NonNull @Nonempty final String sToken)
  {
    ValueEnforcer.notEmpty (sBaseURL, "BaseURL");
    ValueEnforcer.notEmpty (sToken, "Token");

    // The common case - the configuration is unchanged and a client is present
    final PhormClient aExisting = RW_LOCK.readLockedGet (() -> _isUsable (sBaseURL, sToken) ? s_aClient : null);
    if (aExisting != null)
      return aExisting;

    return RW_LOCK.writeLockedGet (() -> {
      // Another thread may have created a matching client in the meantime
      if (_isUsable (sBaseURL, sToken))
        return s_aClient;

      // A changed URL or token invalidates the pooled connections as well
      _closeClient ();
      s_aClient = _createClient (sBaseURL, sToken);
      s_sBaseURL = sBaseURL;
      s_sToken = sToken;
      LOGGER.info ("Created the shared phorm client for '" + sBaseURL + "'");
      return s_aClient;
    });
  }

  /**
   * Close the shared HTTP connection pool. Afterwards a new client is created on the next
   * {@link #getClient(String, String)}, so this is safe to call more than once.
   */
  static void shutdown ()
  {
    RW_LOCK.writeLocked (PhormClientHolder::_closeClient);
  }
}
