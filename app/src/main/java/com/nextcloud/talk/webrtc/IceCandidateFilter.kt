/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

/**
 * Filters the local ICE candidates before they are sent to the remote peer.
 */
object IceCandidateFilter {

    // "candidate:<foundation> <component> <protocol> <priority> <address> <port> typ <type> ..."
    private const val ADDRESS_INDEX = 4

    /**
     * True for a candidate whose address is loopback (127.x.x.x or ::1). Such a candidate is useless to the remote
     * peer, so it should not be sent through signaling.
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
