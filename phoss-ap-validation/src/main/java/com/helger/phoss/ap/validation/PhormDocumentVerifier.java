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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.StatusLine;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.annotation.Nonempty;
import com.helger.annotation.style.IsSPIImplementation;
import com.helger.annotation.style.VisibleForTesting;
import com.helger.base.numeric.mutable.MutableInt;
import com.helger.base.string.StringHelper;
import com.helger.base.url.URLHelper;
import com.helger.cache.regex.RegExHelper;
import com.helger.collection.commons.ICommonsList;
import com.helger.config.IConfig;
import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.helger.http.CHttpHeader;
import com.helger.httpclient.HttpClientManager;
import com.helger.httpclient.HttpClientSettings;
import com.helger.httpclient.response.ExtendedHttpResponseException;
import com.helger.json.IJsonObject;
import com.helger.json.serialize.JsonReader;
import com.helger.mime.CMimeType;
import com.helger.mime.IMimeType;
import com.helger.mime.parse.MimeTypeParser;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.sbdh.PeppolSBDHDataReader;
import com.helger.peppol.sbdh.spec12.BinaryContentType;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.peppol.doctype.PeppolDocumentTypeIdentifierParts;
import com.helger.peppolid.peppol.doctype.PeppolGenericDocumentTypeIdentifierParts;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.result.json.PhiveJsonHelper;
import com.helger.phoss.ap.api.CPhossAP;
import com.helger.phoss.ap.api.config.APConfigProvider;
import com.helger.phoss.ap.api.config.APConfigurationProperties;
import com.helger.phoss.ap.api.mgr.IDocumentPayloadManager;
import com.helger.phoss.ap.api.model.VerificationIssue;
import com.helger.phoss.ap.api.model.VerificationOutcome;
import com.helger.phoss.ap.api.spi.IInboundDocumentVerifierSPI;
import com.helger.phoss.ap.api.spi.IOutboundDocumentVerifierSPI;
import com.helger.phoss.ap.basic.APBasicConfig;
import com.helger.phoss.ap.basic.APBasicMetaManager;

/**
 * Document verifier implementation that calls the phorm Validation Service to validate documents.
 * The validation service automatically detects the document type and validates it against the
 * appropriate rules. This class implements both inbound and outbound verification SPIs.<br>
 * A document with a non-XML payload - a French Factur-X PDF is the typical case - cannot be
 * validated that way, because the document type detection needs XML. Such a document is therefore
 * sent to the hybrid validation API of phorm instead, which validates the PDF carrier and the XML
 * embedded in it.
 *
 * @author Philip Helger
 */
@IsSPIImplementation
public class PhormDocumentVerifier implements IInboundDocumentVerifierSPI, IOutboundDocumentVerifierSPI
{
  /** The ID of this verifier, as used in the telemetry and for the uniqueness check */
  public static final String VERIFIER_ID = "phorm";

  private static final String HTTP_HEADER_X_TOKEN = "X-Token";
  /** The phorm API that determines the document type of an XML payload and validates it */
  private static final String API_PATH_DD_AND_VALIDATE = "/api/dd_and_validate/";
  /** The phorm API that validates a hybrid ZUGFeRD / Factur-X PDF and the XML embedded in it */
  private static final String API_PATH_HYBRID_VALIDATE = "/api/hybrid_validate";
  /** The optional query parameter of the hybrid API that drives the country specific rules */
  private static final String QUERY_PARAM_COUNTRY = "country";
  /** The bytes every PDF document starts with - see ISO 32000-1 chapter 7.5.2 */
  private static final byte [] PDF_HEADER_BYTES = { '%', 'P', 'D', 'F', '-' };

  private static final Logger LOGGER = LoggerFactory.getLogger (PhormDocumentVerifier.class);

  private enum EPhormCallState
  {
    /** Phorm is not configured - skip verification */
    SKIPPED,
    /**
     * The request could not be created or sent at all - Phorm is misconfigured or the document
     * could not be read
     */
    REQUEST_ERROR,
    /** Phorm could not be reached or reported itself as unavailable */
    SERVICE_UNAVAILABLE,
    /** Phorm answered, but the response could not be used */
    RESPONSE_ERROR,
    // Note: REQUEST_ERROR, SERVICE_UNAVAILABLE and RESPONSE_ERROR all mean that the call did not
    // produce a verdict about the document. They are therefore all mapped to
    // VerificationOutcome.serviceUnavailable (...) and only differ in the message
    /** Phorm call completed - {@link PhormCallResult#results} is non-null */
    COMPLETED
  }

  private static record PhormCallResult (@NonNull EPhormCallState state, @Nullable ValidationResultList results)
  {
    @NonNull
    static final PhormCallResult SKIPPED = new PhormCallResult (EPhormCallState.SKIPPED, null);
    @NonNull
    static final PhormCallResult REQUEST_ERROR = new PhormCallResult (EPhormCallState.REQUEST_ERROR, null);
    @NonNull
    static final PhormCallResult SERVICE_UNAVAILABLE = new PhormCallResult (EPhormCallState.SERVICE_UNAVAILABLE, null);
    @NonNull
    static final PhormCallResult RESPONSE_ERROR = new PhormCallResult (EPhormCallState.RESPONSE_ERROR, null);

    @NonNull
    static PhormCallResult completed (@NonNull final ValidationResultList aResults)
    {
      return new PhormCallResult (EPhormCallState.COMPLETED, aResults);
    }
  }

  /**
   * The resolved phorm request: which API to call, with which content type and with which payload.
   *
   * @param apiPath
   *        The path of the phorm API to call, relative to the configured base URL.
   * @param contentType
   *        The content type of the request entity.
   * @param payloadBytes
   *        The payload to send, or <code>null</code> to stream the stored document as-is. Only the
   *        PDF that was extracted from an SBDH "BinaryContent" element needs to be materialized.
   * @param countryC1
   *        The C1 country code to send as the "country" query parameter, or <code>null</code> for
   *        none.
   */
  @VisibleForTesting
  static record PhormRequest (@NonNull String apiPath,
                              @NonNull ContentType contentType,
                              byte @Nullable [] payloadBytes,
                              @Nullable String countryC1)
  {
    @NonNull
    static PhormRequest ddAndValidate ()
    {
      return new PhormRequest (API_PATH_DD_AND_VALIDATE, ContentType.APPLICATION_XML, null, null);
    }

    @NonNull
    static PhormRequest hybridValidate (final byte @Nullable [] aPDFBytes, @Nullable final String sCountryC1)
    {
      return new PhormRequest (API_PATH_HYBRID_VALIDATE, ContentType.APPLICATION_PDF, aPDFBytes, sCountryC1);
    }
  }

  /**
   * Check if the provided document type identifier uses a non-XML syntax specific ID - like
   * <code>urn:peppol:doctype:pdf+xml</code> of the French Factur-X document types. Only such a
   * document type can carry a PDF payload.
   *
   * @param aDocTypeID
   *        The document type identifier to check. May not be <code>null</code>.
   * @return <code>true</code> if the payload of that document type is not XML.
   */
  @VisibleForTesting
  static boolean isNonXMLDocumentType (@NonNull final IDocumentTypeIdentifier aDocTypeID)
  {
    final String sSyntaxSpecificID;
    try
    {
      sSyntaxSpecificID = PeppolGenericDocumentTypeIdentifierParts.extractFromIdentifier (aDocTypeID)
                                                                  .getSyntaxSpecificID ();
    }
    catch (final IllegalArgumentException ex)
    {
      // Not an OpenPeppol document type identifier layout - nothing indicates a non-XML payload
      return false;
    }
    return !PeppolDocumentTypeIdentifierParts.isSyntaxSpecificIDLookingLikeXML (sSyntaxSpecificID);
  }

  /**
   * Check if the stored document itself is a PDF. That is the case for an outbound transaction that
   * was submitted as a payload-only PDF - there the stored document is the bare PDF, because the
   * SBD around it is only created by phase4 during the AS4 transmission.
   *
   * @param aDocPayloadMgr
   *        The document payload manager to read from. May not be <code>null</code>.
   * @param sDocumentPath
   *        The path of the stored document. May not be <code>null</code>.
   * @return <code>true</code> if the stored document starts with the PDF header bytes.
   */
  private static boolean _isStoredDocumentAPDF (@NonNull final IDocumentPayloadManager aDocPayloadMgr,
                                                @NonNull @Nonempty final String sDocumentPath)
  {
    try (final InputStream aIS = aDocPayloadMgr.openDocumentStreamForRead (sDocumentPath))
    {
      return Arrays.equals (aIS.readNBytes (PDF_HEADER_BYTES.length), PDF_HEADER_BYTES);
    }
    catch (final Exception ex)
    {
      // Also catches the IllegalStateException of a document payload manager that cannot open the
      // document at all - the regular API then runs into the same problem and reports it as usual
      LOGGER.error ("Failed to read the start of document '" + sDocumentPath + "': " + ex.getMessage ());
      return false;
    }
  }

  /**
   * Get the country code to be sent to the hybrid validation API. phorm knows <code>DE</code>,
   * <code>FR</code> and <code>OTHER</code> today and falls back to its default for anything else,
   * so the code is deliberately not filtered against that list here - a country that is added to
   * the hybrid rules later works without a change in here. Only the Peppol syntax of the country
   * code is checked, so that nothing unexpected ends up in the URL.
   *
   * @param sCountryC1
   *        The C1 country code from the SBDH. May be <code>null</code>.
   * @return <code>null</code> if no usable country code is present.
   */
  @Nullable
  private static String _getHybridCountry (@Nullable final String sCountryC1)
  {
    if (StringHelper.isNotEmpty (sCountryC1) &&
        RegExHelper.stringMatchesPattern (PeppolSBDHDataReader.DEFAULT_COUNTRY_CODE_REGEX, sCountryC1))
      return sCountryC1;
    return null;
  }

  /**
   * Determine which phorm API the stored document must be sent to. Everything with an XML payload
   * goes to the regular document detection and validation API, as before. A document type with a
   * non-XML syntax specific ID however cannot be handled there - the document type detection needs
   * XML and answers with an HTTP 400 - so its PDF payload is sent to the hybrid validation API
   * instead. The PDF is either the stored document itself (an outbound payload-only submission) or
   * base64 encoded in the SBDH "BinaryContent" element (an inbound document as well as an outbound
   * prebuilt SBD).<br>
   * Anything that is not understood falls back to the regular API, so that a document is never
   * silently validated with fewer rules than before.
   *
   * @param aDocPayloadMgr
   *        The document payload manager to read from. May not be <code>null</code>.
   * @param sDocumentPath
   *        The path of the stored document. May not be <code>null</code>.
   * @param aDocTypeID
   *        The Peppol document type identifier. May not be <code>null</code>.
   * @return Never <code>null</code>.
   */
  @NonNull
  private static PhormRequest _resolveRequest (@NonNull final IDocumentPayloadManager aDocPayloadMgr,
                                               @NonNull @Nonempty final String sDocumentPath,
                                               @NonNull final IDocumentTypeIdentifier aDocTypeID)
  {
    if (!isNonXMLDocumentType (aDocTypeID))
      return PhormRequest.ddAndValidate ();

    // The bare PDF of a payload-only outbound submission - there is no SBDH and therefore no C1
    // country code to derive the country specific hybrid rules from
    if (_isStoredDocumentAPDF (aDocPayloadMgr, sDocumentPath))
    {
      LOGGER.info ("The stored document '" + sDocumentPath + "' is a PDF - using the hybrid validation");
      return PhormRequest.hybridValidate (null, null);
    }

    // It must be an SBD with a "BinaryContent" business message
    final PeppolSBDHData aSbdData;
    try (final InputStream aIS = aDocPayloadMgr.openDocumentStreamForRead (sDocumentPath))
    {
      // The value checks are deliberately disabled: the SBDH was already checked when the document
      // was received respectively submitted, and this verifier must not turn an envelope detail
      // into a validation error of its own
      aSbdData = new PeppolSBDHDataReader (APBasicMetaManager.getIdentifierFactory ()).setPerformValueChecks (false)
                                                                                      .extractData (aIS);
    }
    catch (final Exception ex)
    {
      LOGGER.error ("Failed to read the stored document '" +
                    sDocumentPath +
                    "' as a Standard Business Document: " +
                    ex.getMessage ());
      return PhormRequest.ddAndValidate ();
    }

    return resolveRequestFromSBDHData (aSbdData, sDocumentPath, aDocTypeID);
  }

  /**
   * Determine the phorm API for a document whose SBD was already parsed. Everything that is not a
   * non-empty PDF in a "BinaryContent" business message falls back to the regular API.
   *
   * @param aSbdData
   *        The parsed Standard Business Document. May not be <code>null</code>.
   * @param sDocumentPath
   *        The path of the stored document - for logging only. May not be <code>null</code>.
   * @param aDocTypeID
   *        The Peppol document type identifier - for logging only. May not be <code>null</code>.
   * @return Never <code>null</code>.
   */
  @NonNull
  @VisibleForTesting
  static PhormRequest resolveRequestFromSBDHData (@NonNull final PeppolSBDHData aSbdData,
                                                  @NonNull @Nonempty final String sDocumentPath,
                                                  @NonNull final IDocumentTypeIdentifier aDocTypeID)
  {
    final BinaryContentType aBinaryContent = aSbdData.getBusinessMessageAsBinaryContent ();
    if (aBinaryContent == null)
    {
      LOGGER.warn ("The document type '" +
                   aDocTypeID.getURIEncoded () +
                   "' uses a non-XML syntax, but the business message of '" +
                   sDocumentPath +
                   "' is not a 'BinaryContent' element");
      return PhormRequest.ddAndValidate ();
    }

    final IMimeType aMimeType = MimeTypeParser.safeParseMimeType (aBinaryContent.getMimeType ());
    if (aMimeType == null ||
        !CMimeType.APPLICATION_PDF.getAsStringWithoutParameters ()
                                  .equalsIgnoreCase (aMimeType.getAsStringWithoutParameters ()))
    {
      LOGGER.warn ("The 'BinaryContent' of '" +
                   sDocumentPath +
                   "' has the MIME type '" +
                   aBinaryContent.getMimeType () +
                   "' and not '" +
                   CMimeType.APPLICATION_PDF.getAsStringWithoutParameters () +
                   "' - the hybrid validation is not applicable");
      return PhormRequest.ddAndValidate ();
    }

    final byte [] aPDFBytes = aBinaryContent.getValue ();
    if (aPDFBytes == null || aPDFBytes.length == 0)
    {
      LOGGER.warn ("The 'BinaryContent' of '" + sDocumentPath + "' contains no data");
      return PhormRequest.ddAndValidate ();
    }

    final String sCountry = _getHybridCountry (aSbdData.getCountryC1 ());
    LOGGER.info ("Extracted a PDF of " +
                 aPDFBytes.length +
                 " bytes from the 'BinaryContent' of '" +
                 sDocumentPath +
                 "' - using the hybrid validation" +
                 (sCountry == null ? "" : " for country '" + sCountry + "'"));
    return PhormRequest.hybridValidate (aPDFBytes, sCountry);
  }

  /**
   * Send the prepared request to phorm and turn the response into a {@link PhormCallResult}.
   *
   * @param aHttpClientMgr
   *        The HTTP client manager to use. May not be <code>null</code>.
   * @param aPost
   *        The prepared POST request, including its entity. May not be <code>null</code>.
   * @param sDocumentPath
   *        The path of the stored document - for logging only. May not be <code>null</code>.
   * @return Never <code>null</code>.
   * @throws IOException
   *         In case of an HTTP error
   */
  @NonNull
  private static PhormCallResult _executeAndParse (@NonNull final HttpClientManager aHttpClientMgr,
                                                   @NonNull final HttpPost aPost,
                                                   @NonNull @Nonempty final String sDocumentPath) throws IOException
  {
    final MutableInt aStatusCode = new MutableInt (0);
    final byte [] aResponseBytes = aHttpClientMgr.execute (aPost, aHttpResponse -> {
      final StatusLine aStatusLine = new StatusLine (aHttpResponse);
      aStatusCode.set (aStatusLine.getStatusCode ());
      // Skip all server side errors
      if (aStatusLine.getStatusCode () >= 500)
        return null;

      // Phorm return 400 in case of invalid validations
      final HttpEntity aEntity = aHttpResponse.getEntity ();
      return EntityUtils.toByteArray (aEntity);
    });
    if (aResponseBytes == null)
    {
      // Server side error (HTTP >= 500) or an empty response entity
      LOGGER.error ("Phorm returned null response for '" + sDocumentPath + "' with code " + aStatusCode.intValue ());
      return PhormCallResult.SERVICE_UNAVAILABLE;
    }

    final IJsonObject aJson = JsonReader.builder ().source (aResponseBytes).readAsObject ();
    if (aJson == null)
    {
      // Phorm answered, but not with something usable
      LOGGER.error ("Failed to parse Phorm response as JSON for '" +
                    sDocumentPath +
                    "' with code " +
                    aStatusCode.intValue ());
      return PhormCallResult.RESPONSE_ERROR;
    }

    // Parse JSON back to data structure
    final ValidationResultList aResultList = PhiveJsonHelper.getAsValidationResultList (aJson);
    if (aResultList == null)
    {
      LOGGER.error ("Failed to extract validation results from Phorm response for '" +
                    sDocumentPath +
                    "' with code " +
                    aStatusCode.intValue ());
      return PhormCallResult.RESPONSE_ERROR;
    }

    if (aResultList.containsAtLeastOneError ())
    {
      final int nErrors = aResultList.getAllCount (IError::isError);
      final int nWarns = aResultList.getAllCount (x -> x.getErrorLevel ().isEQ (EErrorLevel.WARN));
      LOGGER.warn ("Document '" +
                   sDocumentPath +
                   "' failed validation. " +
                   nErrors +
                   (nErrors == 1 ? " error" : " errors") +
                   (nWarns == 0 ? "" : " and " + nWarns + (nWarns == 1 ? " warning" : " warnings")) +
                   " found");
      if (LOGGER.isDebugEnabled ())
      {
        aResultList.getAllErrors ()
                   .forEach (e -> LOGGER.debug ("  Validation error: " + e.getErrorText (CPhossAP.DEFAULT_LOCALE)));
      }
    }
    else
    {
      LOGGER.info ("Document '" +
                   sDocumentPath +
                   "' passed validation (validity=" +
                   aResultList.getOverallValidity () +
                   ")");
    }
    return PhormCallResult.completed (aResultList);
  }

  @NonNull
  private PhormCallResult _callPhorm (@NonNull @Nonempty final String sDocumentPath,
                                      @NonNull final IDocumentTypeIdentifier aDocTypeID)
  {
    final IDocumentPayloadManager aDocPayloadMgr = APBasicMetaManager.getDocPayloadMgr ();
    final IConfig aConfig = APConfigProvider.getConfig ();
    final String sPhormBaseURL = aConfig.getAsString (APConfigurationProperties.VERIFICATION_PHORM_URL);
    final String sPhormToken = aConfig.getAsString (APConfigurationProperties.VERIFICATION_PHORM_TOKEN);

    if (StringHelper.isEmpty (sPhormBaseURL))
    {
      if (LOGGER.isDebugEnabled ())
        LOGGER.debug ("Phorm URL is not configured ('" + APConfigurationProperties.VERIFICATION_PHORM_URL + "')");

      // Don't break document processing if Phorm is not used
      return PhormCallResult.SKIPPED;
    }

    if (URLHelper.getAsURL (sPhormBaseURL) == null)
    {
      LOGGER.error ("Phorm URL '" + sPhormBaseURL + "' is not a valid URL");
      return PhormCallResult.REQUEST_ERROR;
    }

    if (StringHelper.isEmpty (sPhormToken))
    {
      LOGGER.error ("Phorm URL '" + sPhormBaseURL + "' looks okay but the Token is not configured");
      return PhormCallResult.REQUEST_ERROR;
    }

    if (!aDocPayloadMgr.existsDocument (sDocumentPath))
    {
      LOGGER.error ("Document path '" + sDocumentPath + "' does not exist");
      return PhormCallResult.REQUEST_ERROR;
    }

    final PhormRequest aRequest = _resolveRequest (aDocPayloadMgr, sDocumentPath, aDocTypeID);

    final StringBuilder aURL = new StringBuilder (StringHelper.trimEnd (sPhormBaseURL, '/'));
    aURL.append (aRequest.apiPath ());
    if (aRequest.countryC1 () != null)
      aURL.append ('?').append (QUERY_PARAM_COUNTRY).append ('=').append (aRequest.countryC1 ());
    final String sURL = aURL.toString ();

    final HttpClientSettings aHCS = new HttpClientSettings ();
    APBasicConfig.applyHttpProxySettings (aHCS);

    try (final HttpClientManager aHttpClientMgr = HttpClientManager.create (aHCS))
    {
      final HttpPost aPost = new HttpPost (sURL);
      aPost.setHeader (CHttpHeader.ACCEPT, ContentType.APPLICATION_JSON.getMimeType ());
      if (StringHelper.isNotEmpty (sPhormToken))
        aPost.setHeader (HTTP_HEADER_X_TOKEN, sPhormToken);

      LOGGER.info ("Calling Phorm at '" + sURL + "' for document '" + sDocumentPath + "'");

      final byte [] aPayloadBytes = aRequest.payloadBytes ();
      if (aPayloadBytes != null)
      {
        // The PDF that was extracted from the SBDH - it had to be materialized anyway
        aPost.setEntity (new ByteArrayEntity (aPayloadBytes, aRequest.contentType ()));
        return _executeAndParse (aHttpClientMgr, aPost, sDocumentPath);
      }

      // Provide as InputStream to be able to handle larger payloads
      try (final InputStream aDocumentIS = aDocPayloadMgr.openDocumentStreamForRead (sDocumentPath))
      {
        aPost.setEntity (new InputStreamEntity (aDocumentIS, aRequest.contentType ()));
        return _executeAndParse (aHttpClientMgr, aPost, sDocumentPath);
      }
    }
    catch (final ExtendedHttpResponseException ex)
    {
      // A response was received, but with an error status code
      LOGGER.error ("Phorm returned HTTP error for '" + sDocumentPath + "': " + ex.getMessage ());
      return PhormCallResult.RESPONSE_ERROR;
    }
    catch (final IOException ex)
    {
      LOGGER.error ("Failed to call Phorm for '" +
                    sDocumentPath +
                    "': " +
                    ex.getMessage () +
                    " (" +
                    ex.getClass ().getName () +
                    ")");
      return PhormCallResult.SERVICE_UNAVAILABLE;
    }
    catch (final Exception ex)
    {
      // We don't know whether the request or the response was at fault
      LOGGER.error ("Unexpected error calling Phorm for '" + sDocumentPath + "'", ex);
      return PhormCallResult.SERVICE_UNAVAILABLE;
    }
  }

  /**
   * Turn a completed Phorm call into a verification outcome. The issues are the same in both
   * directions - only how they are reported to the outside world differs.
   *
   * @param aCall
   *        The completed Phorm call. May not be <code>null</code>.
   * @return Never <code>null</code>.
   */
  @NonNull
  private static VerificationOutcome _toOutcome (@NonNull final PhormCallResult aCall)
  {
    final ICommonsList <VerificationIssue> aIssues = PhiveToVerificationMapper.toVerificationIssues (aCall.results (),
                                                                                                     CPhossAP.DEFAULT_LOCALE);
    if (aCall.results ().containsNoError ())
    {
      // Valid - any remaining issues are warnings
      return VerificationOutcome.passed (aIssues);
    }

    // The result list said there is at least one error, so the mapping must have produced issues
    return VerificationOutcome.rejected ("Document validation failed", aIssues);
  }

  /** {@inheritDoc} */

  @NonNull
  @Nonempty
  public String getID ()
  {
    return VERIFIER_ID;
  }

  @NonNull
  public VerificationOutcome verifyInboundDocument (@NonNull @Nonempty final String sDocumentPath,
                                                    @NonNull final IDocumentTypeIdentifier aDocTypeID,
                                                    @NonNull final IProcessIdentifier aProcessID)
  {
    final PhormCallResult aCall = _callPhorm (sDocumentPath, aDocTypeID);
    return switch (aCall.state ())
    {
      case SKIPPED -> VerificationOutcome.passed ();
      // The document was not validated at all, so this is no rejection of the document itself.
      // Depending on the configured EVerificationFailMode this leads to a deferral, a rejection or
      // an acceptance
      case REQUEST_ERROR -> VerificationOutcome.serviceUnavailable ("Phorm validation service call could not be sent - see server log for details");
      case SERVICE_UNAVAILABLE -> VerificationOutcome.serviceUnavailable ("Phorm validation service is not available - see server log for details");
      case RESPONSE_ERROR -> VerificationOutcome.serviceUnavailable ("Phorm validation service response could not be used - see server log for details");
      case COMPLETED -> _toOutcome (aCall);
    };
  }

  /** {@inheritDoc} */
  @NonNull
  public VerificationOutcome verifyOutboundDocument (@NonNull @Nonempty final String sDocumentPath,
                                                     @NonNull final IDocumentTypeIdentifier aDocTypeID,
                                                     @NonNull final IProcessIdentifier aProcessID)
  {
    final PhormCallResult aCall = _callPhorm (sDocumentPath, aDocTypeID);
    return switch (aCall.state ())
    {
      case SKIPPED -> VerificationOutcome.passed ();
      // Outbound verification has no fail mode - a verifier without a verdict stays fail closed.
      // The outcome is nevertheless "service unavailable" and not "rejected", so that the caller
      // can tell the submitter that the document was not actually found to be invalid
      case REQUEST_ERROR -> VerificationOutcome.serviceUnavailable ("Phorm validation service call could not be sent - see server log for details");
      case SERVICE_UNAVAILABLE -> VerificationOutcome.serviceUnavailable ("Phorm validation service is not available - see server log for details");
      case RESPONSE_ERROR -> VerificationOutcome.serviceUnavailable ("Phorm validation service response could not be used - see server log for details");
      case COMPLETED -> _toOutcome (aCall);
    };
  }
}
