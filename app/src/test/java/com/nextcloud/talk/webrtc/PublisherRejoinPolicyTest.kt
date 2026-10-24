/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherRejoinPolicyTest {

    private var now = 0L
    private val policy = PublisherRejoinPolicy { now }

    @Test
    fun firstRejoinIsImmediate() {
        assertEquals(0L, policy.onPublisherFailed())
    }

    @Test
    fun pausesGrowAndThenTheCallIsGivenUp() {
        val pauses = (1..4).map { policy.onPublisherFailed() }

        assertEquals(listOf(0L, 2_000L, 4_000L, PublisherRejoinPolicy.GIVE_UP), pauses)
    }

    @Test
    fun givenUpStaysGivenUp() {
        repeat(PublisherRejoinPolicy.MAX_REJOINS + 1) { policy.onPublisherFailed() }

        repeat(100) { assertEquals(PublisherRejoinPolicy.GIVE_UP, policy.onPublisherFailed()) }
    }

    @Test
    fun slowTriesAreGivenUpAfterTheRingTimeEvenWithRejoinsLeft() {
        assertEquals(0L, policy.onPublisherFailed())

        now = PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS - 1
        assertEquals(2_000L, policy.onPublisherFailed())

        now = PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS
        assertEquals(PublisherRejoinPolicy.GIVE_UP, policy.onPublisherFailed())
    }

    @Test
    fun connectedPublisherStartsTheCountOver() {
        repeat(3) { policy.onPublisherFailed() }
        now = 10 * PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS

        policy.reset()

        assertEquals(0L, policy.onPublisherFailed())
        assertEquals(2_000L, policy.onPublisherFailed())
    }

    @Test
    fun rejoinAfterAFailureJoinsSilentlyButTheFirstJoinDoesNot() {
        assertFalse(PublisherRejoinPolicy.joinsSilently(initiallySilent = false, isRejoin = false))
        assertTrue(PublisherRejoinPolicy.joinsSilently(initiallySilent = false, isRejoin = true))
        assertTrue(PublisherRejoinPolicy.joinsSilently(initiallySilent = true, isRejoin = false))
    }
}
