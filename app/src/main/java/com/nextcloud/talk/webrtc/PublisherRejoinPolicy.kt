/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

/**
 * Tells how long to wait before the call is rejoined with a new signaling session after the ICE connection of the
 * own publisher failed, and when to stop trying.
 *
 * Without a pause a permanently failing ICE connection (e.g. TURNS does not work) rejoins every few seconds and
 * piles up signaling sessions. A single glitch heals at once, because the first rejoin has no pause. The second
 * rejoin waits 2 s, the third 4 s. A call which never connected after [MAX_REJOINS] rejoins, or which is still not
 * connected [GIVE_UP_AFTER_MILLIS] after the first failure, is given up: the caller ends the call with an error.
 * Redialling for ever is what the owner ruled out: one try, then cancel. The count starts over ([reset]) as soon as
 * the publisher connected again.
 *
 * Not thread-safe, call it from one thread (the main thread).
 */
internal class PublisherRejoinPolicy(private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) {

    private var failuresSinceConnected = 0
    private var firstFailureAt = 0L

    /**
     * Registers a failure.
     *
     * @return pause in milliseconds before the rejoin (0 means at once), or [GIVE_UP]
     */
    fun onPublisherFailed(): Long {
        val now = clockMillis()
        val failure = failuresSinceConnected
        if (failure == 0) {
            firstFailureAt = now
        } else if (failure >= MAX_REJOINS || now - firstFailureAt >= GIVE_UP_AFTER_MILLIS) {
            return GIVE_UP
        }
        failuresSinceConnected++
        return if (failure == 0) 0L else FIRST_PAUSE_MILLIS shl (failure - 1)
    }

    fun reset() {
        failuresSinceConnected = 0
    }

    companion object {
        /** Returned instead of a pause when no further rejoin is made. */
        const val GIVE_UP = -1L

        const val MAX_REJOINS = 3

        /** The same 55 s as the ring time of a call: one try for a call to be connected. */
        const val GIVE_UP_AFTER_MILLIS = 55_000L
        private const val FIRST_PAUSE_MILLIS = 2_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * A rejoin after a failure is a part of the call which is on, not a new call: it must not ring the others
         * again. The server starts a call again when both sides were out of it at once
         * (`CallController::leaveCall` ends it without active sessions), and sends the call notification to the
         * others (`Notification/Listener.php`, `afterCallStarted`) unless the join is silent
         * (`RoomService::setActiveSince`, `DETAIL_IN_CALL_SILENT`).
         */
        fun joinsSilently(initiallySilent: Boolean, isRejoin: Boolean): Boolean = initiallySilent || isRejoin
    }
}
