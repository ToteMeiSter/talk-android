/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.webrtc.PeerConnection

/**
 * Text for the "IceDiag" log of the ICE setup of a peer connection: which servers it got and which candidates it
 * found. Never contains TURN credentials or the addresses of the candidates.
 */
object IceDiagnostics {
    const val TAG = "IceDiag"

    private const val PROTOCOL_INDEX = 2
    private const val ADDRESS_INDEX = 4
    private const val UNKNOWN = "?"

    /** Server URLs only, as `[turn:host:443?transport=tcp(turn) ...]`; `(turn)` says that credentials are set. */
    @JvmStatic
    fun describeServers(servers: List<PeerConnection.IceServer>?): String =
        servers.orEmpty().joinToString(prefix = "[", postfix = "]", separator = " ") { server ->
            val auth = if (server.username.isNullOrEmpty()) "" else "(auth)"
            server.urls.joinToString(",") + auth
        }

    /** `type=relay protocol=udp` from the SDP line of a candidate. */
    @JvmStatic
    fun describeCandidate(sdp: String?): String {
        val parts = sdp.orEmpty().trim().split(' ').filter { it.isNotEmpty() }
        val type = parts.getOrNull(parts.indexOf("typ") + 1)?.takeIf { parts.contains("typ") } ?: UNKNOWN
        val protocol = parts.getOrNull(PROTOCOL_INDEX)?.lowercase() ?: UNKNOWN
        return "type=$type protocol=$protocol"
    }

    /**
     * True for a candidate whose address is loopback (127.x.x.x or ::1). Such a candidate is useless to the peer,
     * so it must not go to the signaling server.
     */
    @JvmStatic
    fun isLoopbackCandidate(sdp: String?): Boolean {
        val address = sdp.orEmpty().trim().split(' ').filter { it.isNotEmpty() }.getOrNull(ADDRESS_INDEX)
            ?.lowercase()
            ?.removePrefix("::ffff:")
            ?: return false
        return address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:0:1"
    }
}
