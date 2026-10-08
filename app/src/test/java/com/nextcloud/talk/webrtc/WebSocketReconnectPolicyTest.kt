/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketReconnectPolicyTest {

    @Test
    fun firstRetryIsAsQuickAsBefore() {
        assertEquals(1_000L, WebSocketReconnectPolicy.delayMs(1))
    }

    @Test
    fun pauseDoublesWithEveryFailureInARow() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L),
            (1..5).map { WebSocketReconnectPolicy.delayMs(it) }
        )
    }

    @Test
    fun pauseStopsGrowingAtTheMaximum() {
        assertEquals(WebSocketReconnectPolicy.MAX_DELAY_MS, WebSocketReconnectPolicy.delayMs(6))
        assertEquals(WebSocketReconnectPolicy.MAX_DELAY_MS, WebSocketReconnectPolicy.delayMs(1_000))
        assertEquals(WebSocketReconnectPolicy.MAX_DELAY_MS, WebSocketReconnectPolicy.delayMs(Int.MAX_VALUE))
    }

    @Test
    fun neverShorterThanTheFirstPause() {
        assertEquals(WebSocketReconnectPolicy.FIRST_DELAY_MS, WebSocketReconnectPolicy.delayMs(0))
        assertEquals(WebSocketReconnectPolicy.FIRST_DELAY_MS, WebSocketReconnectPolicy.delayMs(-3))
    }

    @Test
    fun stormOfTenFailuresMakesFewerAttemptsThanOnePerSecond() {
        // 10 s of failures used to be 10 attempts at one per second
        var elapsed = 0L
        var attempts = 0
        while (elapsed < 10_000L) {
            attempts++
            elapsed += WebSocketReconnectPolicy.delayMs(attempts)
        }
        assertTrue("attempts=$attempts", attempts <= 4)
    }
}
