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
 * piles up signaling sessions. A single glitch heals at once, because the first rejoin has no pause. Every
 * further consecutive failure waits longer: 2, 4, 8 and then 15 seconds, which is also the ceiling. There is no
 * limit of attempts; the user ends the call. The count starts over ([reset]) as soon as the publisher connected
 * again.
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
        return if (failure == 0) {
            0L
        } else {
            minOf(FIRST_PAUSE_MILLIS shl minOf(failure - 1, MAX_SHIFT), MAX_PAUSE_MILLIS)
        }
    }

    fun reset() {
        failuresSinceConnected = 0
    }

    companion object {
        const val MAX_PAUSE_MILLIS = 15_000L
        private const val FIRST_PAUSE_MILLIS = 2_000L
        private const val MAX_SHIFT = 3
    }
}
