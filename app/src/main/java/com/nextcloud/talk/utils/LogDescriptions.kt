/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.net.Uri

/**
 * Shapes of user data for the log, which the user can export and send: no file names, no paths, no user ids.
 */
object LogDescriptions {
    private const val NONE = "none"
    private const val MAX_EXTENSION_LENGTH = 5

    /**
     * Shape of a uri for the log: scheme, authority without a user id and whether there was one. Neither
     * path nor query, as they hold file names.
     */
    fun describeUri(uri: Uri?): String {
        if (uri == null) return NONE
        val authority = uri.authority
        val host = authority?.substringAfterLast('@') ?: NONE
        val hasUserInfo = authority?.contains('@') == true
        return "${uri.scheme ?: NONE}://$host userInfo=$hasUserInfo"
    }

    /**
     * Shape of a file name or path for the log: the extension only, as the name itself is user data.
     */
    fun describeFile(nameOrPath: String?): String {
        val extension = nameOrPath?.substringAfterLast('/')?.substringAfterLast('.', "").orEmpty()
        val isPlain = extension.length in 1..MAX_EXTENSION_LENGTH && extension.all { it.isLetterOrDigit() }
        return "extension=" + if (isPlain) extension.lowercase() else NONE
    }
}
