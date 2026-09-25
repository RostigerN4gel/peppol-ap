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

import static org.junit.Assert.fail;

import org.jspecify.annotations.NonNull;
import org.junit.Test;

import com.helger.annotation.Nonempty;
import com.helger.base.exception.InitializationException;
import com.helger.collection.commons.CommonsArrayList;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.phoss.ap.api.mgr.IDocumentForwarder;
import com.helger.phoss.ap.api.model.VerificationOutcome;
import com.helger.phoss.ap.api.spi.IDocumentForwarderProviderSPI;
import com.helger.phoss.ap.api.spi.IInboundDocumentVerifierSPI;
import com.helger.phoss.ap.api.spi.IOutboundDocumentVerifierSPI;

/**
 * Test class for the ID uniqueness checks of {@link APCoreMetaManager}. See
 * <a href="https://github.com/phax/phoss-ap/issues/80">issue 80</a>.
 *
 * @author Philip Helger
 */
public final class APCoreMetaManagerIDUniquenessTest
{
  /** A verifier that only carries an ID - the verification itself is irrelevant here */
  private static class MockInboundVerifier implements IInboundDocumentVerifierSPI
  {
    private final String m_sID;

    MockInboundVerifier (@NonNull @Nonempty final String sID)
    {
      m_sID = sID;
    }

    @NonNull
    @Nonempty
    public String getID ()
    {
      return m_sID;
    }

    @NonNull
    public VerificationOutcome verifyInboundDocument (@NonNull @Nonempty final String sDocumentPath,
                                                      @NonNull final IDocumentTypeIdentifier aDocTypeID,
                                                      @NonNull final IProcessIdentifier aProcessID)
    {
      return VerificationOutcome.passed ();
    }
  }

  /** A second, unrelated implementation class using the same kind of ID */
  private static final class OtherInboundVerifier extends MockInboundVerifier
  {
    OtherInboundVerifier (@NonNull @Nonempty final String sID)
    {
      super (sID);
    }
  }

  /** A verifier that serves the inbound and the outbound direction, like the bundled one */
  private static final class MockBothDirectionsVerifier extends MockInboundVerifier implements
                                                        IOutboundDocumentVerifierSPI
  {
    MockBothDirectionsVerifier (@NonNull @Nonempty final String sID)
    {
      super (sID);
    }

    @NonNull
    public VerificationOutcome verifyOutboundDocument (@NonNull @Nonempty final String sDocumentPath,
                                                       @NonNull final IDocumentTypeIdentifier aDocTypeID,
                                                       @NonNull final IProcessIdentifier aProcessID)
    {
      return VerificationOutcome.passed ();
    }
  }

  private static class MockForwarderProvider implements IDocumentForwarderProviderSPI
  {
    private final String m_sID;

    MockForwarderProvider (@NonNull @Nonempty final String sID)
    {
      m_sID = sID;
    }

    @NonNull
    @Nonempty
    public String getID ()
    {
      return m_sID;
    }

    @NonNull
    public IDocumentForwarder createDocumentForwarder ()
    {
      throw new UnsupportedOperationException ();
    }
  }

  private static final class OtherForwarderProvider extends MockForwarderProvider
  {
    OtherForwarderProvider (@NonNull @Nonempty final String sID)
    {
      super (sID);
    }
  }

  @Test
  public void testUniqueVerifierIDsAreAccepted ()
  {
    APCoreMetaManager.checkVerifierIDsAreUnique (new CommonsArrayList <> (new MockInboundVerifier ("a"),
                                                                          new OtherInboundVerifier ("b")));

    // No verifier at all is fine as well
    APCoreMetaManager.checkVerifierIDsAreUnique (new CommonsArrayList <> ());
  }

  @Test
  public void testSameVerifierClassInBothDirectionsIsNoDuplicate ()
  {
    // A verifier implementing both SPIs is loaded once per SPI, so the very same ID shows up twice
    // - that is not a duplicate
    APCoreMetaManager.checkVerifierIDsAreUnique (new CommonsArrayList <> (new MockBothDirectionsVerifier ("phorm"),
                                                                          new MockBothDirectionsVerifier ("phorm")));
  }

  @Test
  public void testDuplicateVerifierIDIsRejected ()
  {
    try
    {
      APCoreMetaManager.checkVerifierIDsAreUnique (new CommonsArrayList <> (new MockInboundVerifier ("dup"),
                                                                            new OtherInboundVerifier ("dup")));
      fail ("Two different verifier classes with the same ID must be rejected");
    }
    catch (final InitializationException ex)
    {
      // expected
    }
  }

  @Test
  public void testEmptyVerifierIDIsRejected ()
  {
    try
    {
      APCoreMetaManager.checkVerifierIDsAreUnique (new CommonsArrayList <> (new MockInboundVerifier ("")));
      fail ("A verifier without an ID must be rejected");
    }
    catch (final InitializationException ex)
    {
      // expected
    }
  }

  @Test
  public void testUniqueForwarderProviderIDsAreAccepted ()
  {
    APCoreMetaManager.checkForwarderProviderIDsAreUnique (new CommonsArrayList <> (new MockForwarderProvider ("a"),
                                                                                   new OtherForwarderProvider ("b")));
    APCoreMetaManager.checkForwarderProviderIDsAreUnique (new CommonsArrayList <> ());
  }

  @Test
  public void testDuplicateForwarderProviderIDIsRejected ()
  {
    try
    {
      APCoreMetaManager.checkForwarderProviderIDsAreUnique (new CommonsArrayList <> (new MockForwarderProvider ("dup"),
                                                                                     new OtherForwarderProvider ("dup")));
      fail ("Two forwarder providers with the same ID must be rejected");
    }
    catch (final InitializationException ex)
    {
      // expected
    }
  }

  @Test
  public void testEmptyForwarderProviderIDIsRejected ()
  {
    try
    {
      APCoreMetaManager.checkForwarderProviderIDsAreUnique (new CommonsArrayList <> (new MockForwarderProvider ("")));
      fail ("A forwarder provider without an ID must be rejected");
    }
    catch (final InitializationException ex)
    {
      // expected
    }
  }
}
