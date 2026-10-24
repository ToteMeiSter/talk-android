/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.activities.CallStatus
import com.nextcloud.talk.webrtc.CallRecoveryPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.PeerConnection.IceConnectionState

class CallRecoveryPolicyTest {

    @Test
    fun `failed ice connection rejoins when the call is not interrupted`() {
        assertEquals(Action.REJOIN, CallRecoveryPolicy.onIceFailed(interrupted = false))
    }

    @Test
    fun `failed ice connection while a rejoin is on its way starts no second one`() {
        assertEquals(Action.IGNORE, CallRecoveryPolicy.onIceFailed(interrupted = false, rejoinInProgress = true))
    }

    @Test
    fun `phone call still defers a failure while a rejoin is on its way`() {
        assertEquals(Action.DEFER, CallRecoveryPolicy.onIceFailed(interrupted = true, rejoinInProgress = true))
    }

    @Test
    fun `failed ice connection during a phone call is kept for later`() {
        assertEquals(Action.DEFER, CallRecoveryPolicy.onIceFailed(interrupted = true))
    }

    @Test
    fun `network switch with a connected publisher only watches the connection`() {
        assertEquals(
            Action.RECHECK,
            CallRecoveryPolicy.onNetworkSwitched(false, IceConnectionState.CONNECTED)
        )
        assertEquals(
            Action.RECHECK,
            CallRecoveryPolicy.onNetworkSwitched(false, IceConnectionState.COMPLETED)
        )
    }

    @Test
    fun `network switch with a disconnected publisher waits for the ice state to settle`() {
        assertEquals(
            Action.RECHECK,
            CallRecoveryPolicy.onNetworkSwitched(false, IceConnectionState.DISCONNECTED)
        )
    }

    @Test
    fun `network switch with a lost publisher rejoins at once`() {
        assertEquals(Action.REJOIN, CallRecoveryPolicy.onNetworkSwitched(false, IceConnectionState.FAILED))
        assertEquals(Action.REJOIN, CallRecoveryPolicy.onNetworkSwitched(false, IceConnectionState.CLOSED))
        assertEquals(Action.REJOIN, CallRecoveryPolicy.onNetworkSwitched(false, null))
    }

    @Test
    fun `network switch during a phone call is kept for later`() {
        assertEquals(Action.DEFER, CallRecoveryPolicy.onNetworkSwitched(true, IceConnectionState.FAILED))
        assertEquals(Action.DEFER, CallRecoveryPolicy.onNetworkSwitched(true, IceConnectionState.CONNECTED))
    }

    @Test
    fun `recheck keeps a connection which came back`() {
        assertEquals(Action.KEEP, CallRecoveryPolicy.onRecheck(false, IceConnectionState.CONNECTED, 0))
        assertEquals(Action.KEEP, CallRecoveryPolicy.onRecheck(false, IceConnectionState.COMPLETED, 2))
    }

    @Test
    fun `recheck rejoins a lost connection at once`() {
        assertEquals(Action.REJOIN, CallRecoveryPolicy.onRecheck(false, IceConnectionState.FAILED, 0))
        assertEquals(Action.REJOIN, CallRecoveryPolicy.onRecheck(false, null, 0))
    }

    @Test
    fun `recheck gives a disconnected connection a few more looks and then rejoins`() {
        val states = (0 until CallRecoveryPolicy.MAX_RECHECKS).map {
            CallRecoveryPolicy.onRecheck(false, IceConnectionState.DISCONNECTED, it)
        }

        assertEquals(List(CallRecoveryPolicy.MAX_RECHECKS) { Action.RECHECK }, states)
        assertEquals(
            Action.REJOIN,
            CallRecoveryPolicy.onRecheck(false, IceConnectionState.DISCONNECTED, CallRecoveryPolicy.MAX_RECHECKS)
        )
    }

    @Test
    fun `recheck waits while a phone call goes on`() {
        assertEquals(Action.DEFER, CallRecoveryPolicy.onRecheck(true, IceConnectionState.FAILED, 0))
    }

    @Test
    fun `the give-up deadline does not end a call that is left or timed out`() {
        assertEquals(false, CallRecoveryPolicy.giveUpApplies(CallStatus.LEAVING))
        assertEquals(false, CallRecoveryPolicy.giveUpApplies(CallStatus.CALLING_TIMEOUT))
        assertEquals(true, CallRecoveryPolicy.giveUpApplies(CallStatus.PUBLISHER_FAILED))
        assertEquals(true, CallRecoveryPolicy.giveUpApplies(CallStatus.RECONNECTING))
    }

    @Test
    fun `the ring time ends the dial only while nobody answered`() {
        val applies = listOf(
            CallStatus.CONNECTING,
            CallStatus.JOINED,
            CallStatus.RECONNECTING,
            CallStatus.PUBLISHER_FAILED,
            CallStatus.OFFLINE
        )
        applies.forEach { assertEquals(it.name, true, CallRecoveryPolicy.ringTimeoutApplies(it)) }
        assertEquals(false, CallRecoveryPolicy.ringTimeoutApplies(CallStatus.IN_CONVERSATION))
        assertEquals(false, CallRecoveryPolicy.ringTimeoutApplies(CallStatus.LEAVING))
        assertEquals(false, CallRecoveryPolicy.ringTimeoutApplies(CallStatus.CALLING_TIMEOUT))
        assertEquals(false, CallRecoveryPolicy.ringTimeoutApplies(null))
    }
}
