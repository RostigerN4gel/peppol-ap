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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import com.helger.phorm.client.PhormClient;
import com.helger.scope.mock.ScopeTestRule;

/**
 * Test class for class {@link PhormClientHolder}.
 *
 * @author Philip Helger
 */
public final class PhormClientHolderTest
{
  private static final String URL1 = "http://localhost:8080";
  private static final String URL2 = "http://localhost:9090";
  private static final String TOKEN1 = "token-1";
  private static final String TOKEN2 = "token-2";

  @Rule
  public final ScopeTestRule m_aScopeRule = new ScopeTestRule ();

  @After
  public void after ()
  {
    PhormClientHolder.shutdown ();
  }

  @Test
  public void testClientIsReused ()
  {
    final PhormClient aClient = PhormClientHolder.getClient (URL1, TOKEN1);
    assertEquals (URL1, aClient.getBaseURL ());
    // The connection pool is only worth something if the same client is handed out again
    assertSame (aClient, PhormClientHolder.getClient (URL1, TOKEN1));
  }

  @Test
  public void testChangedConfigurationRebuildsTheClient ()
  {
    final PhormClient aClient = PhormClientHolder.getClient (URL1, TOKEN1);

    // A changed URL must not be masked by the cached client
    final PhormClient aOtherURL = PhormClientHolder.getClient (URL2, TOKEN1);
    assertNotSame (aClient, aOtherURL);
    assertEquals (URL2, aOtherURL.getBaseURL ());

    // ... and neither must a changed token, even though it is not visible on the client
    assertNotSame (aOtherURL, PhormClientHolder.getClient (URL2, TOKEN2));
  }

  @Test
  public void testShutdownIsRepeatable ()
  {
    final PhormClient aClient = PhormClientHolder.getClient (URL1, TOKEN1);

    // The verifier is registered as an inbound and as an outbound SPI, so close() arrives twice
    PhormClientHolder.shutdown ();
    PhormClientHolder.shutdown ();

    // The next caller gets a working client again
    assertNotSame (aClient, PhormClientHolder.getClient (URL1, TOKEN1));
  }
}
