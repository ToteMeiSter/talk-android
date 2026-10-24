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
    private val ring = com.nextcloud.talk.utils.NotificationUtils.CALL_NOTIFICATION_TIMEOUT_MS

    @Test
    fun firstRejoinIsImmediateAndPausesGrowToACap() {
        val pauses = (1..8).map { policy.onPublisherFailed() }

        assertEquals(listOf(0L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L, 15_000L), pauses)
    }

    @Test
    fun fastFailuresDoNotEndTheCall() {
        // 404 / IOException of the join: many failures within seconds
        repeat(20) {
            now += 300
            assertTrue(policy.onPublisherFailed() != PublisherRejoinPolicy.GIVE_UP)
        }
    }

    @Test
    fun theCallIsGivenUpByTheDeadlineAndStaysGivenUp() {
        assertEquals(0L, policy.onPublisherFailed())

        now = PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS - 1
        assertEquals(2_000L, policy.onPublisherFailed())

        now = PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS
        repeat(5) { assertEquals(PublisherRejoinPolicy.GIVE_UP, policy.onPublisherFailed()) }
    }

    @Test
    fun theDeadlineRunsWithoutFurtherFailures() {
        policy.remainingUntilGiveUp()

        now = 20_000
        assertEquals(PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS - 20_000, policy.remainingUntilGiveUp())
        now = PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS + 5_000
        assertEquals(0L, policy.remainingUntilGiveUp())
    }

    @Test
    fun connectedPublisherClosesTheWindowAndStartsTheCountOver() {
        repeat(3) { policy.onPublisherFailed() }
        now = 10 * PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS

        policy.onConnected()

        assertEquals(0L, policy.onPublisherFailed())
        assertEquals(2_000L, policy.onPublisherFailed())
        assertEquals(PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS, policy.remainingUntilGiveUp())
    }

    @Test
    fun newNetworkResetsThePausesButNotTheDeadline() {
        policy.onPublisherFailed()
        now = 30_000
        policy.reset()

        assertEquals(0L, policy.onPublisherFailed())
        assertEquals(PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS - 30_000, policy.remainingUntilGiveUp())
    }

    @Test
    fun redialStartsEverythingOver() {
        policy.onPublisherFailed()
        policy.onPublisherFailed()
        policy.onJoined()
        policy.onPeerJoined()
        now = 40_000

        policy.onRedial()

        assertEquals(0L, policy.onPublisherFailed())
        assertEquals(PublisherRejoinPolicy.GIVE_UP_AFTER_MILLIS, policy.remainingUntilGiveUp())
        assertEquals(ring, policy.onJoined())
        assertFalse(policy.leavesCallOnRejoin())
        assertFalse(policy.joinsSilently(initiallySilent = false))
    }

    @Test
    fun ringTimeRunsFromTheFirstJoinNotFromEveryRejoin() {
        assertEquals(ring, policy.onJoined())

        now = 20_000
        assertEquals(ring - 20_000, policy.onJoined())

        now = ring + 1_000
        assertEquals(0L, policy.onJoined())
    }

    @Test
    fun ringTimeStartsOverOnceTheOtherSideWasInTheCall() {
        policy.onJoined()
        policy.onPeerJoined()

        now = 50_000
        assertEquals(ring, policy.onJoined())
    }

    @Test
    fun aloneInTheCallARejoinDoesNotLeaveAndIsNotSilent() {
        assertFalse(policy.leavesCallOnRejoin())
        assertFalse(policy.joinsSilently(initiallySilent = false))
        assertTrue(policy.joinsSilently(initiallySilent = true))
    }

    @Test
    fun afterTheOtherSideJoinedARejoinLeavesAndJoinsSilently() {
        policy.onPeerJoined()

        assertTrue(policy.leavesCallOnRejoin())
        assertTrue(policy.joinsSilently(initiallySilent = false))
    }
}
