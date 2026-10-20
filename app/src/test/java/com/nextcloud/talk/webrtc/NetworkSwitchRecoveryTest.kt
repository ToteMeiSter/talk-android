/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class NetworkSwitchRecoveryTest {
    private val first: PeerConnectionWrapper = Mockito.mock(PeerConnectionWrapper::class.java)
    private val second: PeerConnectionWrapper = Mockito.mock(PeerConnectionWrapper::class.java)
    private val calls = mutableListOf<String>()

    @Test
    fun withoutMcuSignalingIsReconnectedAndIceRestartedOnEveryConnection() {
        NetworkSwitchRecovery.recover(
            hasMcu = false,
            rejoinWithNewSession = { calls.add("rejoin") },
            reconnectSignaling = { calls.add("signaling") },
            peerConnectionWrappers = listOf(first, second)
        )

        assertEquals(listOf("signaling"), calls)
        verify(first).restartIce()
        verify(second).restartIce()
    }

    @Test
    fun withMcuTheCallIsJoinedAgainAndIceIsNotRestarted() {
        NetworkSwitchRecovery.recover(
            hasMcu = true,
            rejoinWithNewSession = { calls.add("rejoin") },
            reconnectSignaling = { calls.add("signaling") },
            peerConnectionWrappers = listOf(first, second)
        )

        assertEquals(listOf("rejoin"), calls)
        verify(first, never()).restartIce()
        verify(second, never()).restartIce()
    }

    @Test
    fun withoutConnectionsOnlySignalingIsReconnected() {
        NetworkSwitchRecovery.recover(
            hasMcu = false,
            rejoinWithNewSession = { calls.add("rejoin") },
            reconnectSignaling = { calls.add("signaling") },
            peerConnectionWrappers = emptyList()
        )

        assertEquals(listOf("signaling"), calls)
    }
}
