/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The log of a file must not carry its name or its path, only the kind of file.
 */
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
}
