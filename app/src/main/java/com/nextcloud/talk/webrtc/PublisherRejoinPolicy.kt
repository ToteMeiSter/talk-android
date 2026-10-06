/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

/**
 * Tells how long to wait before the call is rejoined with a new signaling session after the ICE connection of the
 * own publisher failed.
 *
 * Without a pause a permanently failing ICE connection (e.g. TURNS does not work) rejoins every few seconds and
 * piles up signaling sessions. The first rejoin happens at once, so a single glitch heals as fast as before. Every
 * further consecutive failure waits longer: 2, 4, 8 and then 15 seconds, which is also the ceiling. There is no
 * limit of attempts; the user ends the call. The count starts over as soon as the publisher connected again
 * ([onPublisherConnected]).
 *
 * Not thread-safe, call it from one thread (the main thread).
 */
internal class PublisherRejoinPolicy {

    private var failuresSinceConnected = 0

    /**
     * Registers a failure.
     *
     * @return pause in milliseconds before the rejoin (0 means at once)
     */
    fun onPublisherFailed(): Long {
        val failure = failuresSinceConnected
        if (failure < Int.MAX_VALUE) {
            failuresSinceConnected++
        }
        return when {
            failure == 0 -> 0L
            failure > MAX_DOUBLINGS -> MAX_PAUSE_MILLIS
            else -> minOf(FIRST_PAUSE_MILLIS shl (failure - 1), MAX_PAUSE_MILLIS)
        }
    }

    fun onPublisherConnected() {
        failuresSinceConnected = 0
    }

    companion object {
        const val MAX_PAUSE_MILLIS = 15_000L
        private const val FIRST_PAUSE_MILLIS = 2_000L
        private const val MAX_DOUBLINGS = 10
    }
}
