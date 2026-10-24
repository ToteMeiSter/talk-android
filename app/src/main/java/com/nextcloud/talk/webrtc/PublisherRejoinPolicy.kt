/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.utils.NotificationUtils

/**
 * The time and state rules of one dial: when the call is joined again after the ICE connection of the own publisher
 * failed, when to stop trying, how long it rings, and whether a rejoin may ring the others again.
 *
 * - Pause: the first rejoin is at once (a glitch heals), then 2 s, 4 s, 8 s, 15 s. The count only limits the rate of
 *   the tries; it never ends the call. Fast errors of the join (404, no network) would use up a count in seconds.
 * - Give up: the call is ended when it was not connected [GIVE_UP_AFTER_MILLIS] after the first failure. This is a
 *   deadline, not a check at the next failure: the caller arms a timer with [remainingUntilGiveUp], also when the
 *   attempts only log errors and no new failure comes. [onConnected] closes the window.
 * - Ring: [onJoined] returns what is left of the 55 s the callee has to answer. A rejoin does not start the ring
 *   time again. Once the other side was in the call ([onPeerJoined]), a rejoin is not a ring any more: the time
 *   starts over, as the other side has to be seen again.
 * - Leave and silent: a rejoin leaves the call on the server only when the other side joined it. Alone in the call
 *   the user stays in it (`CallController::leaveCall` ends a call without active sessions, which would take the
 *   ringing away from the callee); the old session ends by itself. The join is silent only after the other side
 *   joined, since the call is on and nobody is to be rung again.
 *
 * Not thread-safe, call it from one thread (the main thread).
 */
internal class PublisherRejoinPolicy(private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) {

    private var failuresSinceConnected = 0
    private var windowOpenedAt = NONE
    private var firstJoinedAt = NONE
    private var peerJoined = false

    /**
     * Registers a failure.
     *
     * @return pause in milliseconds before the rejoin (0 means at once), or [GIVE_UP]
     */
    fun onPublisherFailed(): Long {
        if (remainingUntilGiveUp() == 0L) {
            return GIVE_UP
        }
        val failure = failuresSinceConnected++
        return if (failure == 0) 0L else minOf(FIRST_PAUSE_MILLIS shl minOf(failure - 1, MAX_SHIFT), MAX_PAUSE_MILLIS)
    }

    /** Opens the give-up window if it is closed; returns the time left until the call is given up. */
    fun remainingUntilGiveUp(): Long {
        val now = clockMillis()
        if (windowOpenedAt == NONE) {
            windowOpenedAt = now
        }
        return (GIVE_UP_AFTER_MILLIS - (now - windowOpenedAt)).coerceAtLeast(0L)
    }

    /** A new network starts the pauses over; the give-up window stays. */
    fun reset() {
        failuresSinceConnected = 0
    }

    /** The publisher connected: the failures are over, the give-up window is closed. */
    fun onConnected() {
        failuresSinceConnected = 0
        windowOpenedAt = NONE
    }

    /** The user dials again by hand: a new dial, everything starts over. */
    fun onRedial() {
        onConnected()
        firstJoinedAt = NONE
        peerJoined = false
    }

    fun onPeerJoined() {
        peerJoined = true
    }

    /** Joined (again): the time the callee has left to answer. */
    fun onJoined(): Long {
        val now = clockMillis()
        if (firstJoinedAt == NONE || peerJoined) {
            firstJoinedAt = now
        }
        return (NotificationUtils.CALL_NOTIFICATION_TIMEOUT_MS - (now - firstJoinedAt)).coerceAtLeast(0L)
    }

    /** A rejoin leaves the call first only if somebody else is in it, else the call would end on the server. */
    fun leavesCallOnRejoin(): Boolean = peerJoined

    /**
     * A join after the other side was in the call must not ring it again: the server starts a call again when both
     * sides were out of it at once and then sends the call notification (`Notification/Listener.php`,
     * `afterCallStarted`) unless the join is silent (`RoomService::setActiveSince`, `DETAIL_IN_CALL_SILENT`).
     */
    fun joinsSilently(initiallySilent: Boolean): Boolean = initiallySilent || peerJoined

    companion object {
        /** Returned instead of a pause when no further rejoin is made. */
        const val GIVE_UP = -1L

        /** The same 55 s as the ring time of a call: one try for a call to be connected. */
        const val GIVE_UP_AFTER_MILLIS = 55_000L
        private const val FIRST_PAUSE_MILLIS = 2_000L
        private const val MAX_PAUSE_MILLIS = 15_000L
        private const val MAX_SHIFT = 4
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NONE = Long.MIN_VALUE
    }
}
