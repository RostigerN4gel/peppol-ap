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

import org.jspecify.annotations.Nullable;

import com.helger.http.header.HttpHeaderMap;

/**
 * Thread-local carrier for the original AS4 HTTP request headers of an inbound message.
 * <p>
 * The phoss-ap {@link com.helger.phoss.ap.api.model.IInboundTransaction} model intentionally does
 * not expose the raw AS4 HTTP request headers. Some deployment-provided document forwarders (see
 * {@link com.helger.phoss.ap.api.spi.IDocumentForwarderProviderSPI}) need them, e.g. to replicate a
 * legacy backend contract. This holder is populated by
 * {@link Phase4InboundMessageProcessorSPI#handleIncomingSBD} at reception time and read by such a
 * forwarder during the <b>synchronous</b> first forwarding attempt (which runs in the same thread).
 * <p>
 * IMPORTANT: The value is only available on the reception thread. Retried forwarding attempts run
 * on the {@code RetryScheduler} thread, where {@link #get()} returns <code>null</code>. Forwarders
 * relying on these headers should therefore be used with synchronous forwarding and must tolerate a
 * <code>null</code> result.
 *
 * @author phoss-ap fork
 */
public final class InboundHttpHeaderContext
{
  private static final ThreadLocal <HttpHeaderMap> TL = new ThreadLocal <> ();

  private InboundHttpHeaderContext ()
  {}

  /**
   * Store the AS4 HTTP request headers for the current thread.
   *
   * @param aHeaders
   *        The headers to store. May be <code>null</code>.
   */
  public static void set (@Nullable final HttpHeaderMap aHeaders)
  {
    TL.set (aHeaders);
  }

  /**
   * @return The AS4 HTTP request headers stored for the current thread, or <code>null</code> if
   *         none are available (e.g. on a retry thread).
   */
  @Nullable
  public static HttpHeaderMap get ()
  {
    return TL.get ();
  }

  /**
   * Remove any headers stored for the current thread. Must be called in a {@code finally} block to
   * avoid leaking references across pooled threads.
   */
  public static void clear ()
  {
    TL.remove ();
  }
}
