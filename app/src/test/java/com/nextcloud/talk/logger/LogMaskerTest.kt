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

class LogMaskerTest {
    private fun assertMasked(input: String, secret: String) {
        val masked = LogMasker.mask(input)
        assertFalse("secret still in: $masked", masked.contains(secret))
        assertTrue("no mask in: $masked", masked.contains(LogMasker.MASK))
        assertEquals("masking must be idempotent", masked, LogMasker.mask(masked))
    }

    @Test
    fun `authorization header is masked whatever its value`() {
        assertMasked("10-07 12:00:00.000  1  2 D HTTP: Authorization: Basic dXNlcjphcHAtcGFzc3dvcmQ=", "dXNlcjph")
        assertMasked("D HTTP: authorization: Bearer abcdef.ghijkl", "abcdef")
        assertMasked("D HTTP: Proxy-Authorization: Whatever secret42", "secret42")
        assertEquals("D HTTP: Authorization: ***", LogMasker.mask("D HTTP: Authorization: ██"))
    }

    @Test
    fun `cookies are masked`() {
        assertMasked("D HTTP: Cookie: nc_session_id=abc123; oc_sessionPassphrase=zzz999", "zzz999")
        assertMasked("D HTTP: Set-Cookie: oc12345=sessionvalue7; path=/; secure", "sessionvalue7")
    }

    @Test
    fun `bearer and basic tokens outside a header are masked`() {
        assertMasked("sending with Bearer 0123456789abcdef", "0123456789abcdef")
        assertMasked("header was Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", "QWxhZGRpbjpvcGVuIHNlc2FtZQ")
    }

    @Test
    fun `ordinary words after Basic and Bearer are kept`() {
        val line = "D Settings: Basic notifications are on, Bearer of news"
        assertEquals(line, LogMasker.mask(line))
    }

    @Test
    fun `jwt is masked`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJlMTIz"
        assertMasked("D Auth: got $jwt from server", jwt)
    }

    @Test
    fun `json bodies of the http log are masked`() {
        assertMasked("""{"ocs":{"data":{"apppassword":"Sx7Kq-9mQ2pLd"}}}""", "Sx7Kq")
        assertMasked("""{"server":"https://c.example","loginName":"u","appPassword":"abcDEF123456"}""", "abcDEF123456")
        assertMasked("""{"password":"hunter2 with space and \"quote\""}""", "hunter2")
        assertMasked("""{"pushTokenHash":"deadbeef0123","devicePublicKey":"x"}""", "deadbeef0123")
    }

    @Test
    fun `form bodies and queries are masked`() {
        assertMasked("password=hunter2&roomName=test", "hunter2")
        assertMasked("GET /ocs/v2.php/x?token=SecretTok3n&format=json", "SecretTok3n")
        assertMasked("pushToken: fcm-token-0123456789", "fcm-token-0123456789")
        assertMasked("pushRegistrationToServer will be done with pushToken: ABC:APA91bHxyz", "APA91bHxyz")
    }

    @Test
    fun `login flow url is masked`() {
        assertMasked("nc://login/user:alice&password:Sup3rS3cret&server:https://cloud.example", "Sup3rS3cret")
        assertMasked("opening https://alice:Sup3rS3cret@cloud.example/index.php", "Sup3rS3cret")
    }
}
