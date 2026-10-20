/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * A failed join of the call ends the call, except while it is rejoined ([CallActivity.shouldRetryRejoin]).
 */
class CallActivityRejoinRetryTest {

    private fun http(code: Int): HttpException {
        val body = "".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun `network error during a rejoin is retried`() {
        assertTrue(CallActivity.shouldRetryRejoin(CallStatus.PUBLISHER_FAILED, IOException("timeout")))
        assertTrue(CallActivity.shouldRetryRejoin(CallStatus.RECONNECTING, IOException("timeout")))
    }

    @Test
    fun `gone session and server errors during a rejoin are retried`() {
        assertTrue(CallActivity.shouldRetryRejoin(CallStatus.PUBLISHER_FAILED, http(404)))
        assertTrue(CallActivity.shouldRetryRejoin(CallStatus.PUBLISHER_FAILED, http(408)))
        assertTrue(CallActivity.shouldRetryRejoin(CallStatus.PUBLISHER_FAILED, http(429)))
        assertTrue(CallActivity.shouldRetryRejoin(CallStatus.PUBLISHER_FAILED, http(503)))
    }

    @Test
    fun `refusal of the server ends the call`() {
        assertFalse(CallActivity.shouldRetryRejoin(CallStatus.PUBLISHER_FAILED, http(403)))
        assertFalse(CallActivity.shouldRetryRejoin(CallStatus.RECONNECTING, http(401)))
    }

    @Test
    fun `first join of a call is not retried`() {
        assertFalse(CallActivity.shouldRetryRejoin(CallStatus.CONNECTING, IOException("timeout")))
        assertFalse(CallActivity.shouldRetryRejoin(CallStatus.IN_CONVERSATION, IOException("timeout")))
        assertFalse(CallActivity.shouldRetryRejoin(null, IOException("timeout")))
    }
}
