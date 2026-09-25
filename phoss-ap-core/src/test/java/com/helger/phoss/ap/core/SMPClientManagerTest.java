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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.Duration;

import org.junit.Test;

import com.helger.config.ConfigFactory;
import com.helger.config.fallback.ConfigWithFallback;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.phoss.ap.api.config.APConfigProvider;
import com.helger.phoss.ap.api.config.APConfigurationProperties;
import com.helger.smpclient.peppol.CachingSMPClientReadOnly;
import com.helger.smpclient.peppol.SMPClientCache;
import com.helger.smpclient.peppol.SMPClientReadOnly;

/**
 * Test class for class {@link SMPClientManager}.
 *
 * @author Philip Helger
 */
public final class SMPClientManagerTest
{
  private static final URI SMP_URI = URI.create ("http://smp.example.org");

  @Test
  public void testDefaultConfiguration ()
  {
    assertTrue (APCoreConfig.isPeppolSmpCacheEnabled ());
    assertEquals (Duration.ofMinutes (15), APCoreConfig.getPeppolSmpCacheTTL ());
    assertEquals (1_000, APCoreConfig.getPeppolSmpCacheMaxSize ());
  }

  @Test
  public void testInitAndShutdown ()
  {
    try
    {
      SMPClientManager.init ();
      assertTrue (SMPClientManager.isCacheEnabled ());
      assertNotNull (SMPClientManager.getSharedHttpClientManager ());

      final SMPClientCache aCache = SMPClientManager.getCache ();
      assertNotNull (aCache);
      assertEquals (APConfigurationProperties.PEPPOL_SMP_CACHE_TTL_DEFAULT, aCache.getCacheTTL ());
      assertEquals (APConfigurationProperties.PEPPOL_SMP_CACHE_MAX_SIZE_DEFAULT, aCache.getMaxSize ());

      // The created client is a caching one, using the shared cache and the shared HTTP client
      // manager
      final SMPClientReadOnly aSMPClient = SMPClientManager.createSMPClient (SMP_URI);
      assertTrue (aSMPClient instanceof CachingSMPClientReadOnly);
      assertSame (aCache, ((CachingSMPClientReadOnly) aSMPClient).getCache ());
      assertSame (SMPClientManager.getSharedHttpClientManager (), aSMPClient.getSharedHttpClientManager ());
    }
    finally
    {
      SMPClientManager.shutdown ();
    }
    assertNull (SMPClientManager.getSharedHttpClientManager ());
  }

  @Test
  public void testCacheDisabled ()
  {
    final IConfigWithFallback aOldConfig = APConfigProvider.getConfig ();
    final String sOldValue = System.getProperty (APConfigurationProperties.PEPPOL_SMP_CACHE_ENABLED);
    try
    {
      System.setProperty (APConfigurationProperties.PEPPOL_SMP_CACHE_ENABLED, "false");
      APConfigProvider.setConfig (new ConfigWithFallback (ConfigFactory.createDefaultValueProvider ()));
      assertFalse (APCoreConfig.isPeppolSmpCacheEnabled ());

      SMPClientManager.init ();
      assertFalse (SMPClientManager.isCacheEnabled ());

      // Without caching, a plain SMP client is created
      final SMPClientReadOnly aSMPClient = SMPClientManager.createSMPClient (SMP_URI);
      assertFalse (aSMPClient instanceof CachingSMPClientReadOnly);
      assertSame (SMPClientManager.getSharedHttpClientManager (), aSMPClient.getSharedHttpClientManager ());
    }
    finally
    {
      SMPClientManager.shutdown ();

      if (sOldValue == null)
        System.clearProperty (APConfigurationProperties.PEPPOL_SMP_CACHE_ENABLED);
      else
        System.setProperty (APConfigurationProperties.PEPPOL_SMP_CACHE_ENABLED, sOldValue);
      APConfigProvider.setConfig (aOldConfig);
    }
  }
}
