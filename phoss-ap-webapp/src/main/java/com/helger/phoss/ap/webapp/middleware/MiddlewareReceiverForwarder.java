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
package com.helger.phoss.ap.webapp.middleware;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.helger.base.enforce.ValueEnforcer;
import com.helger.base.state.ESuccess;
import com.helger.base.string.StringHelper;
import com.helger.base.tostring.ToStringGenerator;
import com.helger.config.fallback.IConfigWithFallback;
import com.helger.http.header.HttpHeaderMap;
import com.helger.phoss.ap.api.mgr.IDocumentForwarder;
import com.helger.phoss.ap.api.model.ForwardingResult;
import com.helger.phoss.ap.api.model.IInboundTransaction;
import com.helger.phoss.ap.basic.APBasicMetaManager;
import com.helger.phoss.ap.core.inbound.InboundHttpHeaderContext;
import com.helger.xml.XMLHelper;
import com.helger.xml.serialize.read.DOMReader;

/**
 * A deployment-provided {@link IDocumentForwarder} that replicates the legacy Middleware
 * <code>receiver</code> webservice contract used by the previous phase4-peppol-standalone
 * implementation:
 * <ul>
 * <li>Builds an <code>InboundPeppolRequest</code> XML envelope containing the AS4 HTTP request
 * headers, the Peppol metadata and the Base64-encoded StandardBusinessDocument.</li>
 * <li>POSTs it as <code>application/xml</code> to the configured endpoint.</li>
 * <li>Parses the <code>ProcessResult</code> XML response and maps it to a
 * {@link ForwardingResult}.</li>
 * </ul>
 * Selected via <code>forwarding.mode=spi</code> and <code>forwarding.spi.id=middleware-data</code>.
 *
 * @author phoss-ap fork
 */
public class MiddlewareReceiverForwarder implements IDocumentForwarder
{
  /** Provider ID as referenced by <code>forwarding.spi.id</code>. */
  public static final String PROVIDER_ID = "middleware-data";

  private static final Logger LOGGER = LoggerFactory.getLogger (MiddlewareReceiverForwarder.class);

  private String m_sUrl;
  private boolean m_bInsecureTls;

  /**
   * Trust-all TrustManager, used only when <code>forwarding.middleware.insecure-tls=true</code> is
   * configured (e.g. for internal endpoints with self-signed certificates). Do not enable in
   * production against untrusted networks.
   */
  private static final class TrustAllManager extends X509ExtendedTrustManager
  {
    @Override
    public X509Certificate [] getAcceptedIssuers ()
    {
      return new X509Certificate [0];
    }

    @Override
    public void checkServerTrusted (final X509Certificate [] chain, final String authType) throws CertificateException
    {}

    @Override
    public void checkClientTrusted (final X509Certificate [] chain, final String authType) throws CertificateException
    {}

    @Override
    public void checkServerTrusted (final X509Certificate [] chain,
                                    final String authType,
                                    final SSLEngine engine) throws CertificateException
    {}

    @Override
    public void checkServerTrusted (final X509Certificate [] chain,
                                    final String authType,
                                    final Socket socket) throws CertificateException
    {}

    @Override
    public void checkClientTrusted (final X509Certificate [] chain,
                                    final String authType,
                                    final SSLEngine engine) throws CertificateException
    {}

    @Override
    public void checkClientTrusted (final X509Certificate [] chain,
                                    final String authType,
                                    final Socket socket) throws CertificateException
    {}
  }

  /** {@inheritDoc} */
  @Override
  public boolean isWithDeliveryConfirmation ()
  {
    // The receiver call synchronously confirms whether the document was accepted by C4,
    // so an inbound MLS may be answered with acceptance.
    return true;
  }

  /** {@inheritDoc} */
  @Override
  @NonNull
  public ESuccess initFromConfiguration (@NonNull final IConfigWithFallback aConfig, @NonNull final String sKeyPrefix)
  {
    ValueEnforcer.notNull (aConfig, "Config");
    ValueEnforcer.notNull (sKeyPrefix, "KeyPrefix");

    final String sUrlKey = sKeyPrefix + "middleware.url";
    m_sUrl = aConfig.getAsString (sUrlKey);
    if (StringHelper.isEmpty (m_sUrl))
    {
      LOGGER.error ("The Middleware receiver forwarding endpoint at '" + sUrlKey + "' is missing");
      return ESuccess.FAILURE;
    }

    m_bInsecureTls = "true".equalsIgnoreCase (aConfig.getAsString (sKeyPrefix + "middleware.insecure-tls"));

    LOGGER.info ("Initialized Middleware receiver forwarder with endpoint '" +
                 m_sUrl +
                 "'" +
                 (m_bInsecureTls ? " (insecure TLS enabled)" : ""));
    return ESuccess.SUCCESS;
  }

  /** {@inheritDoc} */
  @Override
  @NonNull
  public ForwardingResult forwardDocument (@NonNull final IInboundTransaction aTx)
  {
    try
    {
      // Read the stored StandardBusinessDocument bytes (== the AS4 aSBDBytes)
      final byte [] aSbdBytes = APBasicMetaManager.getDocPayloadMgr ().readDocument (aTx.getDocumentPath ());

      // Build the InboundPeppolRequest envelope
      final Document aReqDoc = buildInboundPeppolRequest (aTx, aSbdBytes);

      LOGGER.info ("Forwarding inbound transaction '" +
                   aTx.getID () +
                   "' (SBDH ID '" +
                   aTx.getSbdhInstanceID () +
                   "') to Middleware receiver at '" +
                   m_sUrl +
                   "'");

      // POST and read the response
      final Document aResDoc;
      try (final InputStream aIS = performHttpRequest (aReqDoc, m_sUrl, false))
      {
        aResDoc = DOMReader.readXMLDOM (aIS);
      }

      final Node aPR = XMLHelper.getFirstChildElementOfName (aResDoc, "ProcessResult");
      if (aPR == null)
      {
        LOGGER.error ("No parseable ProcessResult received from receiver for transaction '" + aTx.getID () + "'");
        // Transport-level problem -> allow retry
        return ForwardingResult.failure ("middleware_no_process_result", "No parseable result received from receiver");
      }

      final String sStatus = _childText (aPR, "Status");
      final String sErrorMessage = _childText (aPR, "ErrorMessage");
      final String sC4CountryCode = _childText (aPR, "C4CountryCode");

      LOGGER.info ("Middleware receiver processing for transaction '" +
                   aTx.getID () +
                   "' finished with status '" +
                   sStatus +
                   "'");

      if (!"success".equals (sStatus))
      {
        LOGGER.error ("Middleware receiver rejected transaction '" +
                      aTx.getID () +
                      "': " +
                      sStatus +
                      "/" +
                      sErrorMessage);
        // Definitive business rejection from the backend -> do not retry (a retry would also lose
        // the AS4 HTTP headers, see InboundHttpHeaderContext).
        return ForwardingResult.failureNoRetry ("middleware_receiver_rejected",
                                                sErrorMessage != null ? sErrorMessage : "Rejected by receiver");
      }

      // Success - hand the C4 country code to the built-in Peppol Reporting
      return ForwardingResult.success (sC4CountryCode);
    }
    catch (final IOException ex)
    {
      // Transport/IO problem -> allow retry (a fresh attempt may succeed)
      LOGGER.error ("Middleware receiver forwarding failed for transaction '" +
                    aTx.getID () +
                    "': " +
                    ex.getMessage () +
                    " (" +
                    ex.getClass ().getName () +
                    ")");
      return ForwardingResult.failure ("middleware_io_error", ex.getMessage () + " (" + ex.getClass ().getName () + ")");
    }
    catch (final Exception ex)
    {
      LOGGER.error ("Middleware receiver forwarding failed for transaction '" + aTx.getID () + "'", ex);
      return ForwardingResult.failure ("middleware_error", ex.getMessage () + " (" + ex.getClass ().getName () + ")");
    }
  }

  @NonNull
  private static Document buildInboundPeppolRequest (@NonNull final IInboundTransaction aTx,
                                                     @NonNull final byte [] aSbdBytes) throws Exception
  {
    final Document doc = DocumentBuilderFactory.newDefaultInstance ().newDocumentBuilder ().newDocument ();
    final Node root = doc.appendChild (doc.createElement ("InboundPeppolRequest"));

    // AS4 HTTP request headers (only available on the synchronous reception thread)
    final Node htHeaders = root.appendChild (doc.createElement ("HttpRequestHeaders"));
    final HttpHeaderMap aHeaders = InboundHttpHeaderContext.get ();
    if (aHeaders != null)
    {
      aHeaders.forEachSingleHeader ( (key, val) -> {
        final Element req = (Element) htHeaders.appendChild (doc.createElement ("HttpRequestHeader"));
        req.setAttribute ("key", key);
        req.setTextContent (val);
      }, true);
    }
    else
    {
      // Happens on retry attempts (RetryScheduler thread). Kept as a warning so it is visible.
      LOGGER.warn ("No AS4 HTTP request headers available for transaction '" +
                   aTx.getID () +
                   "' - forwarding without <HttpRequestHeaders> content (likely a retry attempt)");
    }

    _appendText (doc, root, "Sender", aTx.getSenderID ());
    _appendText (doc, root, "Receiver", aTx.getReceiverID ());
    _appendText (doc, root, "CountryC1", aTx.getC1CountryCode ());
    _appendText (doc, root, "InstanceIdentifier", aTx.getSbdhInstanceID ());
    _appendText (doc, root, "DocumentTypeInstanceIdentifier", aTx.getDocTypeID ());
    _appendText (doc, root, "EBMSMessageID", aTx.getAS4MessageID ());

    final Node recDoc = root.appendChild (doc.createElement ("ReceivedBusinessDocument"));
    recDoc.setTextContent (new String (Base64.getEncoder ().encode (aSbdBytes), StandardCharsets.ISO_8859_1));

    return doc;
  }

  @NonNull
  private InputStream performHttpRequest (@NonNull final Document aDoc,
                                          @NonNull final String sUrl,
                                          final boolean bIsRedirect) throws Exception
  {
    final HttpURLConnection huc = (HttpURLConnection) new URI (sUrl).toURL ().openConnection ();
    huc.setInstanceFollowRedirects (true);
    if (huc instanceof final HttpsURLConnection hsuc && m_bInsecureTls)
    {
      final KeyManager [] km = null;
      final X509TrustManager tm = new TrustAllManager ();
      final TrustManager [] tma = { tm };
      final SSLContext sc = SSLContext.getInstance ("TLS");
      sc.init (km, tma, new SecureRandom ());
      sc.getClientSessionContext ().setSessionTimeout (1);
      sc.getClientSessionContext ().setSessionCacheSize (1);
      hsuc.setSSLSocketFactory (sc.getSocketFactory ());
    }
    huc.setRequestMethod ("POST");
    huc.setRequestProperty ("Content-Type", "application/xml");
    huc.setDoOutput (true);
    huc.setChunkedStreamingMode (512);

    try (final OutputStream os = huc.getOutputStream ())
    {
      writeXML (aDoc, os);
    }

    int resCode = -1;
    InputStream is = null;
    try
    {
      is = huc.getInputStream ();
      resCode = huc.getResponseCode ();
      if (resCode == 307)
      {
        final String newLoc = huc.getHeaderField ("Location");
        LOGGER.info ("Got a redirection to " + newLoc);
        if (bIsRedirect)
          LOGGER.info ("Already got a redirection, treating as error");
        else
          return performHttpRequest (aDoc, newLoc, true);
      }
    }
    catch (final IOException ex)
    {
      // fall through to error handling below
    }

    if (resCode / 100 != 2)
    {
      LOGGER.error ("Middleware receiver returned HTTP " + resCode + "/" + huc.getResponseMessage ());
      final String sErrBody = _getErrorBody (huc.getErrorStream ());
      if (sErrBody != null)
        LOGGER.error ("Received error body from receiver: " + sErrBody);
      throw new IOException ("Middleware receiver returned HTTP status " + resCode);
    }
    return is;
  }

  private static void writeXML (@NonNull final Document aDoc, @NonNull final OutputStream aOS) throws Exception
  {
    final TransformerFactory tf = TransformerFactory.newInstance ();
    final Transformer transformer = tf.newTransformer ();
    transformer.transform (new DOMSource (aDoc), new StreamResult (aOS));
    aOS.flush ();
  }

  private static String _getErrorBody (final InputStream aIS)
  {
    if (aIS == null)
      return null;
    try
    {
      final StringBuilder sb = new StringBuilder ();
      final byte [] data = new byte [1024];
      int read;
      while ((read = aIS.read (data)) != -1)
        sb.append (new String (data, 0, read, StandardCharsets.ISO_8859_1));
      return sb.toString ();
    }
    catch (final IOException ex)
    {
      return null;
    }
  }

  private static void _appendText (@NonNull final Document aDoc,
                                   @NonNull final Node aParent,
                                   @NonNull final String sName,
                                   final String sValue)
  {
    aParent.appendChild (aDoc.createElement (sName)).setTextContent (sValue != null ? sValue : "");
  }

  private static String _childText (@NonNull final Node aParent, @NonNull final String sName)
  {
    final Element aElem = XMLHelper.getFirstChildElementOfName (aParent, sName);
    return aElem == null ? null : XMLHelper.getFirstChildText (aElem);
  }

  @Override
  public String toString ()
  {
    return new ToStringGenerator (this).append ("URL", m_sUrl).append ("InsecureTls", m_bInsecureTls).getToString ();
  }
}
