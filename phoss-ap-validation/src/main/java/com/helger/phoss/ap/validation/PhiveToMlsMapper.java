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

import java.util.Locale;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.helger.annotation.concurrent.Immutable;
import com.helger.base.location.ILocation;
import com.helger.base.string.StringHelper;
import com.helger.collection.commons.CommonsArrayList;
import com.helger.collection.commons.ICommonsList;
import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.helger.peppol.mls.CPeppolMLS;
import com.helger.peppol.mls.EPeppolMLSStatusReasonCode;
import com.helger.phive.api.EValidationBaseType;
import com.helger.phive.api.result.ValidationResult;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phoss.ap.api.CPhossAP;
import com.helger.phoss.ap.api.model.MlsOutcome;
import com.helger.phoss.ap.api.model.MlsOutcomeIssue;

/**
 * Maps a phive {@link ValidationResultList} to an {@link MlsOutcome} suitable for Peppol Message
 * Level Status (MLS) responses. The mapping uses the validation artefact's base type to choose
 * between {@link EPeppolMLSStatusReasonCode#SYNTAX_VIOLATION} (for XSD) and
 * {@link EPeppolMLSStatusReasonCode#BUSINESS_RULE_VIOLATION_FATAL} /
 * {@link EPeppolMLSStatusReasonCode#BUSINESS_RULE_VIOLATION_WARNING} (for Schematron and others),
 * and uses the phive error field name or error location as the MLS error field. Entries below WARN
 * are dropped. When no errors are present the mapping yields {@link MlsOutcome#acceptance()} and
 * warnings are discarded, because acceptance responses do not carry line issues.
 *
 * @author Philip Helger
 * @since 0.10.0
 */
@Immutable
public final class PhiveToMlsMapper
{
  private PhiveToMlsMapper ()
  {}

  @NonNull
  private static String _description (@NonNull final IError aError, @NonNull final Locale aDisplayLocale)
  {
    final String sErrorID = aError.getErrorID ();
    final String sText = aError.getErrorText (aDisplayLocale);

    final boolean bHasID = StringHelper.isNotEmpty (sErrorID);
    final boolean bHasText = StringHelper.isNotEmpty (sText);

    if (bHasID && bHasText)
      return "[" + sErrorID + "] " + sText;
    if (bHasText)
      return sText;
    if (bHasID)
      return "[" + sErrorID + "]";

    // MlsOutcomeIssue requires a non-empty description
    return "Validation failure";
  }

  @NonNull
  private static String _errorField (@NonNull final IError aError)
  {
    // Phive Schematron populates errorFieldName with the failed assertion's XPath context
    final String sFieldName = aError.getErrorFieldName ();
    if (StringHelper.isNotEmpty (sFieldName))
      return sFieldName;

    // Fall back to formatted location (resource:line:column) if any info is present
    final ILocation aLocation = aError.getErrorLocation ();
    if (aLocation != null && aLocation.isAnyInformationPresent ())
    {
      final String sLocation = aLocation.getAsString ();
      if (StringHelper.isNotEmpty (sLocation))
        return sLocation;
    }

    // Return "NA"
    return CPeppolMLS.LINE_ID_NOT_AVAILABLE;
  }

  @NonNull
  private static MlsOutcomeIssue _toIssue (@NonNull final IError aError,
                                           @NonNull final EValidationBaseType eBaseType,
                                           @NonNull final Locale aDisplayLocale)
  {
    final EPeppolMLSStatusReasonCode eReason;
    if (eBaseType.isXSD ())
    {
      // XSD: every failure is a syntax violation
      eReason = EPeppolMLSStatusReasonCode.SYNTAX_VIOLATION;
    }
    else
    {
      // Schematron and others: distinguish fatal vs warning by phive level
      eReason = aError.getErrorLevel ().isError () ? EPeppolMLSStatusReasonCode.BUSINESS_RULE_VIOLATION_FATAL
                                                   : EPeppolMLSStatusReasonCode.BUSINESS_RULE_VIOLATION_WARNING;
    }

    final String sErrorField = _errorField (aError);
    final String sDescription = _description (aError, aDisplayLocale);
    return new MlsOutcomeIssue (sErrorField, eReason, sDescription);
  }

  private static boolean _isSaxonTransformationWarning (@NonNull final IError aError)
  {
    return aError.getErrorLevel ().isEQ (EErrorLevel.WARN) &&
           "Transformation warning".equals (aError.getErrorText (CPhossAP.DEFAULT_LOCALE)) &&
           aError.getLinkedException () != null;
  }

  /**
   * Map a phive {@link ValidationResultList} to an {@link MlsOutcome}. When the result list
   * contains no errors the outcome is {@link MlsOutcome#acceptance()} (any warnings are dropped).
   * Otherwise the outcome is {@link MlsOutcome#rejection(String, java.util.List)} containing all
   * WARN-and-above entries.
   *
   * @param aResultList
   *        The phive validation result list to map. May not be <code>null</code>.
   * @param aDisplayLocale
   *        The locale used to render error texts. May be <code>null</code> in which case
   *        {@link CPhossAP#DEFAULT_LOCALE} is used.
   * @param sResponseText
   *        Optional human-readable response text added to a rejection outcome. May be
   *        <code>null</code>.
   * @return Never <code>null</code>.
   */
  @NonNull
  public static MlsOutcome toMlsOutcome (@NonNull final ValidationResultList aResultList,
                                         @Nullable final Locale aDisplayLocale,
                                         @Nullable final String sResponseText)
  {
    if (aResultList.containsNoError ())
    {
      // AP - verified and okay
      return MlsOutcome.acceptance ();
    }

    final Locale aEffectiveLocale = aDisplayLocale != null ? aDisplayLocale : CPhossAP.DEFAULT_LOCALE;
    final ICommonsList <MlsOutcomeIssue> aIssues = new CommonsArrayList <> ();
    for (final ValidationResult aResult : aResultList)
    {
      // Ignore skipped layer
      if (aResult.getValidity ().isSkipped ())
        continue;

      final EValidationBaseType eBaseType = aResult.getValidationArtefact ().getValidationType ().getBaseType ();
      for (final IError aError : aResult.getErrorList ())
      {
        // We only care about warning or higher
        if (aError.getErrorLevel ().isLT (EErrorLevel.WARN))
          continue;

        if (_isSaxonTransformationWarning (aError))
          continue;

        aIssues.add (_toIssue (aError, eBaseType, aEffectiveLocale));
      }
    }

    // Result list said there is at least one error, so the loop above must have produced issues
    return MlsOutcome.rejection (sResponseText, aIssues);
  }
}
