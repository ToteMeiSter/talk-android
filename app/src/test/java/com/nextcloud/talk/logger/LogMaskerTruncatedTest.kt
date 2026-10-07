/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMaskerTruncatedTest {
    private fun assertCutMasked(input: String, secret: String) {
        val masked = LogMasker.mask(input)
        assertFalse("secret still in: " + masked, masked.contains(secret))
        assertTrue(masked.contains(LogMasker.MASK))
        assertEquals(masked, LogMasker.mask(masked))
    }

    @Test
    fun `a secret cut by logcat without the closing quote is masked to the end of the line`() {
        assertCutMasked("""D HTTP: {"appPassword":"abcDEF123456789xyz""", "abcDEF123456789xyz")
        assertCutMasked("""D HTTP: {"ocs":{"data":{"apppassword":"Sx7Kq-9mQ2pLd\""", "Sx7Kq")
        assertCutMasked("""D WS: {"auth":{"params":{"ticket":"1:tick3tvalue-part""", "tick3tvalue")
    }

    @Test
    fun `a long token and an escaped credential cut by logcat are masked`() {
        val token = "b".repeat(LOGIN_TOKEN_LENGTH)
        assertCutMasked("""D HTTP: {"poll":{"token":"$token""", token)
        assertCutMasked("""D WS: {"message":"{\"credential\":\"s3cr3tc4ed-and-more""", "s3cr3tc4ed")
    }

    @Test
    fun `a value with its closing quote keeps what follows`() {
        assertEquals("""{"password":"***","next":1}""", LogMasker.mask("""{"password":"abc","next":1}"""))
    }

    private companion object {
        const val LOGIN_TOKEN_LENGTH = 128
    }
}
