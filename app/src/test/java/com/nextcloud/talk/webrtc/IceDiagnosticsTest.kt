/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.webrtc.PeerConnection

class IceDiagnosticsTest {

    @Test
    fun describesTypeAndProtocolOfCandidates() {
        assertEquals(
            "type=relay protocol=udp",
            IceDiagnostics.describeCandidate(
                "candidate:1 1 udp 41885439 203.0.113.5 50000 typ relay raddr 10.0.0.2 rport 40000 generation 0"
            )
        )
        assertEquals(
            "type=host protocol=tcp",
            IceDiagnostics.describeCandidate("candidate:2 1 TCP 1518280447 10.0.0.2 9 typ host tcptype active")
        )
        assertEquals(
            "type=srflx protocol=udp",
            IceDiagnostics.describeCandidate("candidate:3 1 udp 1686052607 203.0.113.5 40000 typ srflx raddr 0.0.0.0")
        )
    }

    @Test
    fun candidateWithoutTypeIsUnknown() {
        assertEquals("type=? protocol=?", IceDiagnostics.describeCandidate(null))
        assertEquals("type=? protocol=udp", IceDiagnostics.describeCandidate("candidate:1 1 udp 1"))
    }

    @Test
    fun describesServersWithoutCredentials() {
        val servers = listOf(
            PeerConnection.IceServer("stun:stun.example:3478"),
            PeerConnection.IceServer("turns:turn.example:443?transport=tcp", "user-name", "secret-credential")
        )

        val text = IceDiagnostics.describeServers(servers)

        assertEquals("[stun:stun.example:3478 turns:turn.example:443?transport=tcp(auth)]", text)
        assertFalse(text.contains("user-name"))
        assertFalse(text.contains("secret-credential"))
    }

    @Test
    fun describesNoServers() {
        assertEquals("[]", IceDiagnostics.describeServers(null))
    }
}
