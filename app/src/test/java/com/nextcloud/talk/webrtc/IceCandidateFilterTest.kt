/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IceCandidateFilterTest {

    @Test
    fun detectsLoopbackCandidates() {
        assertTrue(IceCandidateFilter.isLoopbackCandidate("candidate:1 1 udp 2122260223 127.0.0.1 50000 typ host"))
        assertTrue(IceCandidateFilter.isLoopbackCandidate("candidate:1 1 udp 2122260223 127.1.2.3 50000 typ host"))
        assertTrue(IceCandidateFilter.isLoopbackCandidate("candidate:2 1 udp 2122194687 ::1 50001 typ host"))
        assertTrue(IceCandidateFilter.isLoopbackCandidate("candidate:3 1 tcp 1518280447 ::ffff:127.0.0.1 9 typ host"))
    }

    @Test
    fun keepsOtherCandidates() {
        assertFalse(IceCandidateFilter.isLoopbackCandidate("candidate:1 1 udp 2122260223 10.0.0.2 50000 typ host"))
        assertFalse(IceCandidateFilter.isLoopbackCandidate("candidate:1 1 udp 1 203.0.113.5 40000 typ srflx"))
        assertFalse(IceCandidateFilter.isLoopbackCandidate("candidate:1 1 udp 1 2001:db8::1 40000 typ host"))
        assertFalse(IceCandidateFilter.isLoopbackCandidate("candidate:1 1 udp 1 1127.0.0.1 40000 typ host"))
        assertFalse(IceCandidateFilter.isLoopbackCandidate(null))
        assertFalse(IceCandidateFilter.isLoopbackCandidate("candidate:1 1 udp 1"))
    }
}
