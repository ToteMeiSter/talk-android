/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The log of a file or a uri must not carry its name or its path, only the kind of file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LogDescriptionsTest {

    @Test
    fun logDescriptionOfNameHasOnlyTheExtension() {
        val text = LogDescriptions.describeFile("Holiday Secret.PDF")

        assertEquals("extension=pdf", text)
        assertFalse(text.contains("Holiday"))
        assertFalse(text.contains("Secret"))
    }

    @Test
    fun logDescriptionOfPathHasNoDirectoryAndNoName() {
        val text = LogDescriptions.describeFile("/storage/emulated/0/DCIM/Private/passport.jpg")

        assertEquals("extension=jpg", text)
        assertFalse(text.contains("DCIM"))
        assertFalse(text.contains("passport"))
    }

    @Test
    fun logDescriptionWithoutPlainExtensionIsNone() {
        assertEquals("extension=none", LogDescriptions.describeFile(null))
        assertEquals("extension=none", LogDescriptions.describeFile(""))
        assertEquals("extension=none", LogDescriptions.describeFile("secret-name"))
        assertEquals("extension=none", LogDescriptions.describeFile(".secretconfig"))
        assertEquals("extension=none", LogDescriptions.describeFile("report.my secret"))
        assertEquals("extension=none", LogDescriptions.describeFile("trailing."))
    }

    @Test
    fun logDescriptionHasNoPathAndNoQuery() {
        val text = LogDescriptions.describeUri(Uri.parse("content://media/external/Holiday.jpg?name=Secret.pdf"))

        assertEquals("content://media userInfo=false", text)
        assertFalse(text.contains("Holiday"))
        assertFalse(text.contains("Secret"))
    }

    @Test
    fun logDescriptionHasNoUserId() {
        val text = LogDescriptions.describeUri(Uri.parse("content://7@com.example.provider/logs/secret-name.json"))

        assertEquals("content://com.example.provider userInfo=true", text)
        assertFalse(text.contains("secret-name"))
        assertFalse(text.contains("7@"))
    }

    @Test
    fun logDescriptionOfNoUriAndOfEmptyUri() {
        assertEquals("none", LogDescriptions.describeUri(null))
        assertEquals("none://none userInfo=false", LogDescriptions.describeUri(Uri.EMPTY))
    }
}
