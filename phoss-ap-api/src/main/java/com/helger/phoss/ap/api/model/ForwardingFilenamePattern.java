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

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.annotation.Nonempty;
import com.helger.annotation.Nonnegative;
import com.helger.annotation.style.VisibleForTesting;
import com.helger.base.enforce.ValueEnforcer;
import com.helger.base.state.ESuccess;
import com.helger.base.string.StringHelper;
import com.helger.base.string.StringImplode;
import com.helger.base.tostring.ToStringGenerator;
import com.helger.collection.commons.CommonsHashSet;
import com.helger.collection.commons.CommonsLinkedHashSet;
import com.helger.collection.commons.ICommonsOrderedSet;
import com.helger.collection.commons.ICommonsSet;
import com.helger.config.IConfig;
import com.helger.io.file.FilenameHelper;

/**
 * The configurable name under which a document forwarder stores a forwarded document - the base
 * name of a file or the object key of a blob store, so without the ".xml" of the document and
 * without the ".json" of an optional metadata sidecar.
 * <p>
 * A downstream routing service that picks the documents up from a shared directory usually routes
 * them by the receiver participant ID. A fixed layout forces it to open every payload or to read
 * the metadata sidecar for that, which is why the layout is configurable per forwarder since
 * v0.12.0. The supported placeholders are the IDs of {@link EForwardingFilenamePlaceholder},
 * written as <code>{name}</code>. The <code>${name}</code> syntax is deliberately <b>not</b>
 * supported: the configuration replaces variables of that form in every value itself, so such a
 * pattern never reaches this class intact.
 * </p>
 * <p>
 * Every placeholder value comes from the received document and is therefore sanitized: everything
 * that is not an ASCII letter, an ASCII digit, '.', '-' or '_' is replaced with '_'. That is
 * deliberately stricter than {@link FilenameHelper#getAsSecureValidASCIIFilename(String)}, because
 * the path separators must not survive - the resolved name is appended to an upload directory, so a
 * sender provided value like the SBDH Instance Identifier must never be able to point outside of it
 * - and because ':' is illegal on Windows only, which would name the very same document differently
 * depending on the operating system the AP runs on.
 * </p>
 *
 * @author Philip Helger
 * @since 0.12.0
 */
public class ForwardingFilenamePattern
{
  /**
   * Maximum length of a resolved filename, leaving room for the extension and for the ".tmp" of an
   * atomic write within the usual limit of 255 bytes per filename
   */
  public static final int MAX_LENGTH_FILENAME = 200;
  /** Maximum length of a resolved object key, leaving room for the key prefix and the extension */
  public static final int MAX_LENGTH_OBJECT_KEY = 900;

  private static final Logger LOGGER = LoggerFactory.getLogger (ForwardingFilenamePattern.class);

  private final String m_sPattern;
  private final boolean m_bAllowPathSeparator;
  private final int m_nMaxLength;

  /**
   * Constructor. Use {@link #checkPattern(String, String, boolean)} to verify the pattern before,
   * or create the instance from the configuration with
   * {@link #createFilenameFromConfig(IConfig, String, String)} or with
   * {@link #createObjectKeyFromConfig(IConfig, String, String)}.
   *
   * @param sPattern
   *        The pattern to be resolved. May neither be <code>null</code> nor empty.
   * @param bAllowPathSeparator
   *        <code>true</code> if the literal part of the pattern may contain path separators, so
   *        that it can span "directories" - suitable for an object key but not for a filename. The
   *        placeholder values never contain a path separator, no matter what is passed here.
   * @param nMaxLength
   *        The maximum length of the resolved name. Must be &gt; 0.
   */
  @VisibleForTesting
  ForwardingFilenamePattern (@NonNull @Nonempty final String sPattern,
                             final boolean bAllowPathSeparator,
                             @Nonnegative final int nMaxLength)
  {
    ValueEnforcer.notEmpty (sPattern, "Pattern");
    ValueEnforcer.isGT0 (nMaxLength, "MaxLength");
    m_sPattern = sPattern;
    m_bAllowPathSeparator = bAllowPathSeparator;
    m_nMaxLength = nMaxLength;
  }

  /**
   * Replace every character that is not safe within a name with an underscore.
   *
   * @param sValue
   *        The value to be sanitized. May be <code>null</code>.
   * @param bAllowPathSeparator
   *        <code>true</code> to keep the path separators.
   * @return The sanitized value. Never <code>null</code> but maybe empty.
   */
  @NonNull
  private static String _getSanitized (@Nullable final String sValue, final boolean bAllowPathSeparator)
  {
    if (StringHelper.isEmpty (sValue))
      return "";

    final StringBuilder ret = new StringBuilder (sValue.length ());
    for (final char c : sValue.toCharArray ())
      if ((c >= 'a' && c <= 'z') ||
          (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') ||
          c == '.' ||
          c == '-' ||
          c == '_' ||
          (bAllowPathSeparator && c == '/'))
        ret.append (c);
      else
        ret.append ('_');
    return ret.toString ();
  }

  /**
   * Walk the pattern and replace every placeholder with the value the resolver returns for it. An
   * opening brace that has no matching closing one - or that contains another opening brace - is
   * taken literally.
   *
   * @param sPattern
   *        The pattern to be resolved. May not be <code>null</code>.
   * @param aPlaceholderResolver
   *        The resolver invoked with the name of each placeholder found. May not be
   *        <code>null</code> and may not return <code>null</code>.
   * @param aLiteralResolver
   *        The resolver invoked with each literal part between the placeholders. May not be
   *        <code>null</code> and may not return <code>null</code>.
   * @return The resolved pattern. Never <code>null</code>.
   */
  @NonNull
  private static String _resolve (@NonNull final String sPattern,
                                  @NonNull final UnaryOperator <String> aPlaceholderResolver,
                                  @NonNull final UnaryOperator <String> aLiteralResolver)
  {
    final int nMax = sPattern.length ();
    final StringBuilder aResolved = new StringBuilder (nMax);
    final StringBuilder aLiteral = new StringBuilder ();
    int nIndex = 0;
    while (nIndex < nMax)
    {
      final char c = sPattern.charAt (nIndex);
      if (c == '{')
      {
        final int nEnd = sPattern.indexOf ('}', nIndex + 1);
        // A nested opening brace means that this one is not a placeholder start
        final int nNested = sPattern.indexOf ('{', nIndex + 1);
        if (nEnd >= 0 && (nNested < 0 || nNested > nEnd))
        {
          aResolved.append (aLiteralResolver.apply (aLiteral.toString ()));
          aLiteral.setLength (0);
          aResolved.append (aPlaceholderResolver.apply (sPattern.substring (nIndex + 1, nEnd)));
          nIndex = nEnd + 1;
          continue;
        }
      }
      aLiteral.append (c);
      nIndex++;
    }
    return aResolved.append (aLiteralResolver.apply (aLiteral.toString ())).toString ();
  }

  /**
   * Collect the IDs of the supported placeholders, for a log message.
   *
   * @param aFilter
   *        The filter to apply. May be <code>null</code> to get the IDs of all placeholders.
   * @return The matching IDs, in declaration order. Never <code>null</code>.
   */
  @NonNull
  private static ICommonsOrderedSet <String> _getAllPlaceholderIDs (@Nullable final Predicate <EForwardingFilenamePlaceholder> aFilter)
  {
    final ICommonsOrderedSet <String> ret = new CommonsLinkedHashSet <> ();
    for (final EForwardingFilenamePlaceholder e : EForwardingFilenamePlaceholder.values ())
      if (aFilter == null || aFilter.test (e))
        ret.add (e.getID ());
    return ret;
  }

  /**
   * Check a pattern before it is used the first time, so that a typo shows up as a configuration
   * error at startup and not as a directory full of files called "{reciever-id}.xml". All problems
   * are logged as errors, except the one that cannot guarantee unique names - that one is a
   * warning, because it may well be intended.
   *
   * @param sConfigKey
   *        The configuration key the pattern was read from, for logging only. May not be
   *        <code>null</code>.
   * @param sPattern
   *        The pattern to be checked. May be <code>null</code>.
   * @param bAllowPathSeparator
   *        <code>true</code> if the literal part of the pattern may contain path separators.
   * @return {@link ESuccess#SUCCESS} if the pattern is usable. Never <code>null</code>.
   */
  @NonNull
  public static ESuccess checkPattern (@NonNull final String sConfigKey,
                                       @Nullable final String sPattern,
                                       final boolean bAllowPathSeparator)
  {
    ValueEnforcer.notNull (sConfigKey, "ConfigKey");

    if (StringHelper.isEmpty (sPattern))
    {
      LOGGER.error ("The forwarding filename pattern in '" + sConfigKey + "' must not be empty");
      return ESuccess.FAILURE;
    }

    if (sPattern.contains ("${"))
    {
      // "${...}" is the variable syntax of the configuration itself - a pattern using it is
      // replaced before it ever reaches this class, so it must not silently look supported
      LOGGER.error ("The forwarding filename pattern in '" +
                    sConfigKey +
                    "' uses the '${...}' syntax, which is reserved for the variable replacement of the configuration itself. Use the '{name}' syntax instead");
      return ESuccess.FAILURE;
    }

    final ICommonsSet <EForwardingFilenamePlaceholder> aUsed = new CommonsHashSet <> ();
    final ICommonsOrderedSet <String> aUnknown = new CommonsLinkedHashSet <> ();
    // The result is the literal part of the pattern only - all placeholders resolve to nothing. But
    // we remember all unknown configuration patterns
    final String sLiteralPart = _resolve (sPattern, sName -> {
      final EForwardingFilenamePlaceholder ePlaceholder = EForwardingFilenamePlaceholder.getFromIDOrNull (sName);
      if (ePlaceholder == null)
        aUnknown.add (sName);
      else
        aUsed.add (ePlaceholder);
      return "";
    }, UnaryOperator.identity ());

    if (aUnknown.isNotEmpty ())
    {
      // At least one unknown pattern was used
      LOGGER.error ("The forwarding filename pattern in '" +
                    sConfigKey +
                    "' uses the unknown placeholder(s) " +
                    StringImplode.getImploded (", ", aUnknown) +
                    ". Supported are only " +
                    StringImplode.getImploded (", ", _getAllPlaceholderIDs (null)));
      return ESuccess.FAILURE;
    }

    if (sLiteralPart.indexOf ('{') >= 0 || sLiteralPart.indexOf ('}') >= 0)
    {
      LOGGER.error ("The forwarding filename pattern in '" + sConfigKey + "' contains unbalanced placeholder braces");
      return ESuccess.FAILURE;
    }

    if (!bAllowPathSeparator && StringHelper.containsAny (sLiteralPart, FilenameHelper::isPathSeparatorChar))
    {
      LOGGER.error ("The forwarding filename pattern in '" +
                    sConfigKey +
                    "' may not contain a path separator - every document is stored in the configured target directory");
      return ESuccess.FAILURE;
    }

    if (!aUsed.containsAny (EForwardingFilenamePlaceholder::isUniquePerDocument))
    {
      // From a practical perspective...
      LOGGER.warn ("The forwarding filename pattern in '" +
                   sConfigKey +
                   "' contains none of the placeholders that are unique per document (" +
                   StringImplode.getImploded (", ",
                                              _getAllPlaceholderIDs (EForwardingFilenamePlaceholder::isUniquePerDocument)) +
                   "), so two documents may resolve to the same name and overwrite each other");
    }

    return ESuccess.SUCCESS;
  }

  /**
   * @return The pattern as it was configured. Never <code>null</code> nor empty.
   */
  @NonNull
  @Nonempty
  public final String getPattern ()
  {
    return m_sPattern;
  }

  /**
   * @return <code>true</code> if the literal part of the pattern may contain path separators.
   */
  public final boolean isAllowPathSeparator ()
  {
    return m_bAllowPathSeparator;
  }

  /**
   * @return The maximum length of a resolved name. Always &gt; 0.
   */
  @Nonnegative
  public final int getMaxLength ()
  {
    return m_nMaxLength;
  }

  /**
   * Resolve the pattern for a single document, resulting in the base name of the stored document -
   * so without the ".xml" of the document and without the ".json" of an optional metadata sidecar.
   *
   * @param aDocument
   *        The document to be forwarded. May not be <code>null</code>.
   * @return The resolved base name. Never <code>null</code> nor empty.
   */
  @NonNull
  @Nonempty
  public String getResolvedBaseName (@NonNull final IForwardableDocument aDocument)
  {
    ValueEnforcer.notNull (aDocument, "Document");

    // An unknown placeholder is rejected by checkPattern already
    final String sResolved = _resolve (m_sPattern, x -> {
      final EForwardingFilenamePlaceholder ePlaceholder = EForwardingFilenamePlaceholder.getFromIDOrNull (x);
      return ePlaceholder == null ? "" : _getSanitized (ePlaceholder.getValue (aDocument), false);
    }, x -> _getSanitized (x, m_bAllowPathSeparator));
    // The leading dots are removed, so that no value can turn the result into a hidden file that a
    // downstream poller never picks up
    String sBaseName = StringHelper.trimStartRepeatedly (sResolved, '.');

    if (sBaseName.length () > m_nMaxLength)
    {
      LOGGER.warn ("The resolved forwarding filename of transaction '" +
                   aDocument.id () +
                   "' is " +
                   sBaseName.length () +
                   " characters long and is therefore truncated to " +
                   m_nMaxLength +
                   " characters");
      sBaseName = sBaseName.substring (0, m_nMaxLength);
    }

    // Takes care of the Windows reserved filenames and of the illegal suffixes
    final String sSecureBaseName = FilenameHelper.getAsSecureValidASCIIFilename (sBaseName);
    if (StringHelper.isEmpty (sSecureBaseName))
    {
      // E.g. a pattern of "{sbdh-instance-id}" and an Instance Identifier consisting of dots only
      LOGGER.warn ("The forwarding filename pattern resolved to an unusable name for transaction '" +
                   aDocument.id () +
                   "' - using the default layout instead");
      return EForwardingFilenamePlaceholder.DATETIME.getValue (aDocument) +
             "_" +
             _getSanitized (EForwardingFilenamePlaceholder.INCOMING_ID.getValue (aDocument), false);
    }

    return sSecureBaseName;
  }

  @Override
  public String toString ()
  {
    return new ToStringGenerator (this).append ("Pattern", m_sPattern)
                                       .append ("AllowPathSeparator", m_bAllowPathSeparator)
                                       .append ("MaxLength", m_nMaxLength)
                                       .getToString ();
  }

  /**
   * Read a pattern that results in a filename from the configuration. Path separators are not
   * allowed and the resolved name is limited to {@link #MAX_LENGTH_FILENAME} characters.
   *
   * @param aConfig
   *        The configuration to read from. May not be <code>null</code>.
   * @param sConfigKey
   *        The configuration key to read. May neither be <code>null</code> nor empty.
   * @param sDefaultPattern
   *        The pattern to be used if the configuration key is not set. May neither be
   *        <code>null</code> nor empty.
   * @return <code>null</code> if the configured pattern is unusable - in that case the reason was
   *         logged as an error.
   */
  @Nullable
  public static ForwardingFilenamePattern createFilenameFromConfig (@NonNull final IConfig aConfig,
                                                                    @NonNull @Nonempty final String sConfigKey,
                                                                    @NonNull @Nonempty final String sDefaultPattern)
  {
    ValueEnforcer.notNull (aConfig, "Config");
    ValueEnforcer.notEmpty (sConfigKey, "ConfigKey");
    ValueEnforcer.notEmpty (sDefaultPattern, "DefaultPattern");

    final String sPattern = aConfig.getAsString (sConfigKey, sDefaultPattern);
    if (checkPattern (sConfigKey, sPattern, false).isFailure ())
      return null;

    return new ForwardingFilenamePattern (sPattern, false, MAX_LENGTH_FILENAME);
  }

  /**
   * Read a pattern that results in the object key of a blob store from the configuration. Contrary
   * to a filename, the literal part may contain path separators - so that the key can span
   * "directories" - and the resolved key is limited to {@link #MAX_LENGTH_OBJECT_KEY} characters.
   *
   * @param aConfig
   *        The configuration to read from. May not be <code>null</code>.
   * @param sConfigKey
   *        The configuration key to read. May neither be <code>null</code> nor empty.
   * @param sDefaultPattern
   *        The pattern to be used if the configuration key is not set. May neither be
   *        <code>null</code> nor empty.
   * @return <code>null</code> if the configured pattern is unusable - in that case the reason was
   *         logged as an error.
   */
  @Nullable
  public static ForwardingFilenamePattern createObjectKeyFromConfig (@NonNull final IConfig aConfig,
                                                                     @NonNull @Nonempty final String sConfigKey,
                                                                     @NonNull @Nonempty final String sDefaultPattern)
  {
    ValueEnforcer.notNull (aConfig, "Config");
    ValueEnforcer.notEmpty (sConfigKey, "ConfigKey");
    ValueEnforcer.notEmpty (sDefaultPattern, "DefaultPattern");

    final String sPattern = aConfig.getAsString (sConfigKey, sDefaultPattern);
    if (checkPattern (sConfigKey, sPattern, true).isFailure ())
      return null;

    return new ForwardingFilenamePattern (sPattern, true, MAX_LENGTH_OBJECT_KEY);
  }
}
