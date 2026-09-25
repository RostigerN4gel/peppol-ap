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
import com.helger.annotation.style.IsSPIImplementation;
import com.helger.phoss.ap.api.mgr.IDocumentForwarder;
import com.helger.phoss.ap.api.spi.IDocumentForwarderProviderSPI;

/**
 * SPI provider that exposes the {@link AS4RejectingHttpForwarder} to the phoss-ap forwarding
 * factory. Activate via <code>forwarding.mode=spi</code> and
 * <code>forwarding.spi.id=http-sync-as4-reject</code>.
 *
 * @author phoss-ap fork
 */
@IsSPIImplementation
public class AS4RejectingHttpForwarderProvider implements IDocumentForwarderProviderSPI
{
  @Override
  @NonNull
  @Nonempty
  public String getID ()
  {
    return AS4RejectingHttpForwarder.PROVIDER_ID;
  }

  @Override
  @NonNull
  public IDocumentForwarder createDocumentForwarder ()
  {
    return new AS4RejectingHttpForwarder ();
  }
}
