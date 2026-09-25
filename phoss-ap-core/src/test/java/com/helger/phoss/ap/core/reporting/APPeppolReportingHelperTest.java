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
package com.helger.phoss.ap.core.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;

import com.helger.collection.commons.CommonsArrayList;
import com.helger.config.ConfigFactory;
import com.helger.config.fallback.ConfigWithFallback;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.factory.IIdentifierFactory;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.phoss.ap.api.config.APConfigProvider;
import com.helger.phoss.ap.api.config.APConfigurationProperties;
import com.helger.scope.mock.ScopeTestRule;

/**
 * Test class for {@link APPeppolReportingHelper}.
 *
 * @author Philip Helger
 */
public final class APPeppolReportingHelperTest
{
  private static final IIdentifierFactory IF = PeppolIdentifierFactory.INSTANCE;

  @Rule
  public final ScopeTestRule m_aRule = new ScopeTestRule ();

  private static void _setExcludedParticipantIDs (final String sValue)
  {
    if (sValue == null)
      System.clearProperty (APConfigurationProperties.PEPPOL_REPORTING_EXCLUDE_PARTICIPANT_IDS);
    else
      System.setProperty (APConfigurationProperties.PEPPOL_REPORTING_EXCLUDE_PARTICIPANT_IDS, sValue);
    APConfigProvider.setConfig (new ConfigWithFallback (ConfigFactory.createDefaultValueProvider ()));
  }

  @Test
  public void testGetEffectiveEndUserIDUnified ()
  {
    // No mapping, but the value is lower cased
    assertEquals ("iso6523-actorid-upis::0088:abc12345",
                  APPeppolReportingHelper.getEffectiveEndUserID (IF.createParticipantIdentifierWithDefaultScheme ("0088:ABC12345")));
  }

  @Test
  public void testGetEffectiveEndUserIDMapped ()
  {
    // Belgian VAT number is mapped to the Belgian enterprise number
    assertEquals ("iso6523-actorid-upis::0208:0123456789",
                  APPeppolReportingHelper.getEffectiveEndUserID (IF.createParticipantIdentifierWithDefaultScheme ("9925:BE0123456789")));
    // Same End User as above
    assertEquals ("iso6523-actorid-upis::0208:0123456789",
                  APPeppolReportingHelper.getEffectiveEndUserID (IF.createParticipantIdentifierWithDefaultScheme ("0208:0123456789")));

    // German VAT number is mapped to the German Electronic Business Address number
    assertEquals ("iso6523-actorid-upis::0246:de123456789",
                  APPeppolReportingHelper.getEffectiveEndUserID (IF.createParticipantIdentifierWithDefaultScheme ("9930:DE123456789")));

    // Finnish OVT identifier is mapped to the Finnish OVT code
    assertEquals ("iso6523-actorid-upis::0216:00371234567800001",
                  APPeppolReportingHelper.getEffectiveEndUserID (IF.createParticipantIdentifierWithDefaultScheme ("0037:1234567800001")));
  }

  @Test
  public void testGetAllExcludedParticipantIDs ()
  {
    final IConfigWithFallback aOldConfig = APConfigProvider.getConfig ();
    final String sOldValue = System.getProperty (APConfigurationProperties.PEPPOL_REPORTING_EXCLUDE_PARTICIPANT_IDS);
    try
    {
      // Nothing configured
      _setExcludedParticipantIDs (null);
      assertTrue (APPeppolReportingHelper.getAllExcludedParticipantIDs ().isEmpty ());

      // Both the URI encoded and the default scheme notation are supported, invalid values are
      // silently ignored
      _setExcludedParticipantIDs ("iso6523-actorid-upis::9915:test,0088:1234567890128,not-a-participant-id");
      assertEquals (new CommonsArrayList <> (IF.createParticipantIdentifierWithDefaultScheme ("9915:test"),
                                             IF.createParticipantIdentifierWithDefaultScheme ("0088:1234567890128")),
                    APPeppolReportingHelper.getAllExcludedParticipantIDs ());
    }
    finally
    {
      _setExcludedParticipantIDs (sOldValue);
      APConfigProvider.setConfig (aOldConfig);
    }
  }

  @Test
  public void testIsExcludedFromReporting ()
  {
    final IConfigWithFallback aOldConfig = APConfigProvider.getConfig ();
    final String sOldValue = System.getProperty (APConfigurationProperties.PEPPOL_REPORTING_EXCLUDE_PARTICIPANT_IDS);
    try
    {
      final IParticipantIdentifier aOther = IF.createParticipantIdentifierWithDefaultScheme ("0088:1234567890128");

      // Nothing configured - nothing is excluded
      _setExcludedParticipantIDs (null);
      assertFalse (APPeppolReportingHelper.isExcludedFromReporting ("iso6523-actorid-upis::9915:test",
                                                                    aOther.getURIEncoded ()));

      _setExcludedParticipantIDs ("9915:test");
      // Excluded as the sender as well as as the receiver
      assertTrue (APPeppolReportingHelper.isExcludedFromReporting ("iso6523-actorid-upis::9915:test",
                                                                   aOther.getURIEncoded ()));
      assertTrue (APPeppolReportingHelper.isExcludedFromReporting (aOther.getURIEncoded (),
                                                                   "iso6523-actorid-upis::9915:test"));
      // Participant identifier values are case insensitive
      assertTrue (APPeppolReportingHelper.isExcludedFromReporting ("iso6523-actorid-upis::9915:TEST",
                                                                   aOther.getURIEncoded ()));
      // Neither side matches
      assertFalse (APPeppolReportingHelper.isExcludedFromReporting (aOther.getURIEncoded (), aOther.getURIEncoded ()));
      assertFalse (APPeppolReportingHelper.isExcludedFromReporting (null, null));
      // Different scheme, same value
      assertFalse (APPeppolReportingHelper.isExcludedFromReporting ("iso6523-actorid-upis::9916:test",
                                                                    aOther.getURIEncoded ()));
    }
    finally
    {
      _setExcludedParticipantIDs (sOldValue);
      APConfigProvider.setConfig (aOldConfig);
    }
  }
}
