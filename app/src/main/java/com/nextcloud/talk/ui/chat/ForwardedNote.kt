/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2017-2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nextcloud.talk.R
import java.util.Locale

/** The "Forwarded from <name>" line of a forwarded message and the rest of its text. */
internal data class ForwardedNoteSplit(val note: String, val body: String)

/** Replaces the templates read from the resources; tests have no resources. */
internal val LocalForwardedNoteTemplates = compositionLocalOf<List<String>?> { null }

private const val NAME_PLACEHOLDER = "%1\$s"

/** The note is two steps smaller than the 16sp text of a message (bodyLarge to bodySmall), bold and italic. */
internal val forwardedNoteStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Bold,
    fontStyle = FontStyle.Italic
)

/**
 * Splits off the first line of [text] when it is the note a forward writes, i.e. when it fits one of [templates] (the
 * string `nc_chat_forwarded_from` with `%1$s` for the name). Other text is not touched: null.
 */
internal fun splitForwardedNote(text: String, templates: List<String>): ForwardedNoteSplit? {
    val firstLine = text.substringBefore('\n')
    val isNote = templates.any { template ->
        val at = template.indexOf(NAME_PLACEHOLDER)
        if (at < 0) {
            return@any false
        }
        val prefix = template.substring(0, at)
        val suffix = template.substring(at + NAME_PLACEHOLDER.length)
        firstLine.length > prefix.length + suffix.length &&
            firstLine.startsWith(prefix) &&
            firstLine.endsWith(suffix)
    }
    return if (isNote) ForwardedNoteSplit(firstLine, text.substringAfter('\n', "")) else null
}

/** The note in the language of the app and in English and Russian: a forward keeps the language of its sender. */
internal fun forwardedNoteTemplates(context: Context): List<String> =
    listOf(context, localized(context, Locale.ENGLISH), localized(context, Locale("ru")))
        .map { it.getString(R.string.nc_chat_forwarded_from, NAME_PLACEHOLDER) }
        .distinct()

private fun localized(context: Context, locale: Locale): Context =
    context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
