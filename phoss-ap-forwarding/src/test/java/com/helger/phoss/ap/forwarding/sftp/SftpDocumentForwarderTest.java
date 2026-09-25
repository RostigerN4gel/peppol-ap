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
package com.helger.phoss.ap.forwarding.sftp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.jspecify.annotations.NonNull;
import org.junit.Test;

import com.helger.base.state.ESuccess;
import com.helger.collection.commons.CommonsHashMap;
import com.helger.collection.commons.ICommonsMap;
import com.helger.config.ConfigFactory;
import com.helger.config.fallback.ConfigWithFallback;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.config.source.MultiConfigurationValueProvider;
import com.helger.config.source.appl.ConfigurationSourceFunction;
import com.helger.phoss.ap.api.config.APConfigurationProperties;

/**
 * Test class for class {@link SftpDocumentForwarder}, focusing on the configuration of the filename
 * pattern. The resolution itself is tested in <code>ForwardingFilenamePatternTest</code>.
 *
 * @author Philip Helger
 */
public final class SftpDocumentForwarderTest
{
  @NonNull
  private static SftpDocumentForwarder _createForwarder ()
  {
    return new SftpDocumentForwarder ();
  }

  @NonNull
  private static ESuccess _init (@NonNull final SftpDocumentForwarder aForwarder,
                                 @NonNull final String sFilenamePattern)
  {
    final ICommonsMap <String, String> aValues = new CommonsHashMap <> ();
    aValues.put ("forwarding.sftp.host", "localhost");
    aValues.put ("forwarding.sftp.user", "user");
    aValues.put ("forwarding.sftp.password", "password");
    aValues.put ("forwarding.sftp.uploaddir", "/upload");
    aValues.put ("forwarding.sftp.filename-pattern", sFilenamePattern);

    final MultiConfigurationValueProvider aVP = ConfigFactory.createDefaultValueProvider ();
    // Highest priority wins over the values from application.properties
    aVP.addConfigurationSource (new ConfigurationSourceFunction (aValues::get), Integer.MAX_VALUE);
    final IConfigWithFallback aConfig = new ConfigWithFallback (aVP);

    return aForwarder.initFromConfiguration (aConfig, "forwarding.");
  }

  @Test
  public void testDefaultPattern ()
  {
    final SftpDocumentForwarder aForwarder = _createForwarder ();
    final ICommonsMap <String, String> aValues = new CommonsHashMap <> ();
    aValues.put ("forwarding.sftp.host", "localhost");

    final MultiConfigurationValueProvider aVP = ConfigFactory.createDefaultValueProvider ();
    aVP.addConfigurationSource (new ConfigurationSourceFunction (aValues::get), Integer.MAX_VALUE);
    assertEquals (ESuccess.SUCCESS, aForwarder.initFromConfiguration (new ConfigWithFallback (aVP), "forwarding."));
    assertEquals (APConfigurationProperties.FORWARDING_SFTP_FILENAME_PATTERN_DEFAULT,
                  aForwarder.getFilenamePattern ().getPattern ());
    // A filename may not span directories
    assertFalse (aForwarder.getFilenamePattern ().isAllowPathSeparator ());
  }

  @Test
  public void testInitAcceptsAValidPattern ()
  {
    final SftpDocumentForwarder aForwarder = _createForwarder ();
    assertEquals (ESuccess.SUCCESS, _init (aForwarder, "{receiver-value}_{datetime}_{incoming-id}"));
    assertEquals ("{receiver-value}_{datetime}_{incoming-id}", aForwarder.getFilenamePattern ().getPattern ());
    // No unique part - a warning is logged, but the pattern is accepted
    assertEquals (ESuccess.SUCCESS, _init (_createForwarder (), "{receiver-value}_{datetime}"));
  }

  @Test
  public void testInitRejectsAnInvalidPattern ()
  {
    // Typo in a placeholder name
    assertEquals (ESuccess.FAILURE, _init (_createForwarder (), "{datetime}_{reciever-id}"));
    // Empty pattern
    assertEquals (ESuccess.FAILURE, _init (_createForwarder (), ""));
    // Subdirectories are not supported
    assertEquals (ESuccess.FAILURE, _init (_createForwarder (), "{receiver-value}/{datetime}"));
    assertEquals (ESuccess.FAILURE, _init (_createForwarder (), "..\\{datetime}"));
    // Unbalanced braces
    assertEquals (ESuccess.FAILURE, _init (_createForwarder (), "{datetime_{incoming-id}"));
  }
}
