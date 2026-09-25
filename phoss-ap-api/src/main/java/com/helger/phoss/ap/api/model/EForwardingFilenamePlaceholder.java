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
package com.helger.phoss.ap.api.model;

import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.helger.annotation.Nonempty;
import com.helger.base.enforce.ValueEnforcer;
import com.helger.base.id.IHasID;
import com.helger.base.lang.EnumHelper;

/**
 * A single placeholder of a {@link ForwardingFilenamePattern}, together with the value it takes
 * from the document to be forwarded. The ID is the name as it is written in the pattern, without
 * the surrounding braces - so the ID of <code>{datetime}</code> is <code>datetime</code>.
 * <p>
 * This enum is deliberately in the <code>model</code> package and not in the <code>codelist</code>
 * package: its value provider operates on an {@link IForwardableDocument}, and the code lists must
 * not depend on the model.
 * </p>
 *
 * @author Philip Helger
 * @since 0.12.0
 */
public enum EForwardingFilenamePlaceholder implements IHasID <String>
{
  /** The reception timestamp, formatted according to {@link #DATETIME_PATTERN}. */
  DATETIME ("datetime", EForwardingFilenamePlaceholder::_getFormattedTimestamp, false),
  /** The phase4 Incoming ID; the transaction ID for a self-generated document. */
  INCOMING_ID ("incoming-id", IForwardableDocument::localID, true),
  /** The SBDH Instance Identifier. */
  SBDH_INSTANCE_ID ("sbdh-instance-id", IForwardableDocument::sbdhInstanceID, true),
  /** The receiver participant ID (C4) including the identifier scheme. */
  RECEIVER_ID ("receiver-id", IForwardableDocument::receiverID, false),
  /** The receiver participant ID (C4) without the identifier scheme. */
  RECEIVER_VALUE ("receiver-value", x -> _getValueWithoutScheme (x.receiverID ()), false),
  /** The sender participant ID (C1) including the identifier scheme. */
  SENDER_ID ("sender-id", IForwardableDocument::senderID, false),
  /** The sender participant ID (C1) without the identifier scheme. */
  SENDER_VALUE ("sender-value", x -> _getValueWithoutScheme (x.senderID ()), false),
  /** The Peppol Document Type ID. */
  DOCTYPE_ID ("doctype-id", IForwardableDocument::docTypeID, false),
  /** The Peppol Process ID. */
  PROCESS_ID ("process-id", IForwardableDocument::processID, false);

  /** The format of the {@link #DATETIME} value */
  public static final String DATETIME_PATTERN = "yyyyMMddHHmmss";

  private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern (DATETIME_PATTERN);

  private final String m_sID;
  private final Function <IForwardableDocument, String> m_aValueProvider;
  private final boolean m_bUniquePerDocument;

  EForwardingFilenamePlaceholder (@NonNull @Nonempty final String sID,
                                  @NonNull final Function <IForwardableDocument, String> aValueProvider,
                                  final boolean bUniquePerDocument)
  {
    m_sID = sID;
    m_aValueProvider = aValueProvider;
    m_bUniquePerDocument = bUniquePerDocument;
  }

  @NonNull
  @Nonempty
  private static String _getFormattedTimestamp (@NonNull final IForwardableDocument aDocument)
  {
    return DATETIME_FORMATTER.format (aDocument.timestamp ());
  }

  /**
   * Get the value part of a URI encoded Peppol identifier, meaning everything after the "::" that
   * separates the identifier scheme from the identifier value. So
   * "iso6523-actorid-upis::0088:123456" becomes "0088:123456" - the ICD stays part of the value,
   * because the same number in a different ICD is a different participant.
   *
   * @param sURIEncodedID
   *        The URI encoded identifier. May not be <code>null</code>.
   * @return The identifier value, or the unchanged input if it contains no scheme separator. Never
   *         <code>null</code>.
   */
  @NonNull
  private static String _getValueWithoutScheme (@NonNull final String sURIEncodedID)
  {
    final int nIndex = sURIEncodedID.indexOf ("::");
    return nIndex < 0 ? sURIEncodedID : sURIEncodedID.substring (nIndex + 2);
  }

  /** {@inheritDoc} */
  @NonNull
  @Nonempty
  public String getID ()
  {
    return m_sID;
  }

  /**
   * @return <code>true</code> if this placeholder alone makes the resolved name unique per
   *         document. A pattern that uses none of these may name two documents identically.
   */
  public boolean isUniquePerDocument ()
  {
    return m_bUniquePerDocument;
  }

  /**
   * Get the unsanitized value of this placeholder for a single document.
   *
   * @param aDocument
   *        The document to take the value from. May not be <code>null</code>.
   * @return The value. Never <code>null</code> but maybe empty.
   */
  @NonNull
  public String getValue (@NonNull final IForwardableDocument aDocument)
  {
    ValueEnforcer.notNull (aDocument, "Document");
    return m_aValueProvider.apply (aDocument);
  }

  /**
   * Find the enum constant matching the given ID.
   *
   * @param sID
   *        The ID to look up. May be <code>null</code>.
   * @return The matching enum constant, or <code>null</code> if not found.
   */
  @Nullable
  public static EForwardingFilenamePlaceholder getFromIDOrNull (@Nullable final String sID)
  {
    return EnumHelper.getFromIDOrNull (EForwardingFilenamePlaceholder.class, sID);
  }
}
