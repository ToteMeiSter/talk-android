/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class PublisherRejoinPolicyTest {

    private val policy = PublisherRejoinPolicy()

    @Test
    fun firstRejoinIsImmediate() {
        assertEquals(0L, policy.onPublisherFailed())
    }

    @Test
    fun pausesGrowAndStopAtCeiling() {
        val pauses = (1..7).map { policy.onPublisherFailed() }

        assertEquals(listOf(0L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), pauses)
    }

    @Test
    fun neverGivesUpAndKeepsCeilingPause() {
        repeat(10) { policy.onPublisherFailed() }

        repeat(1_000) { assertEquals(15_000L, policy.onPublisherFailed()) }
    }

    @Test
    fun connectedPublisherStartsTheCountOver() {
        repeat(6) { policy.onPublisherFailed() }

        policy.onPublisherConnected()

        assertEquals(0L, policy.onPublisherFailed())
        assertEquals(2_000L, policy.onPublisherFailed())
    }
}
