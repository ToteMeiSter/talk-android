/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.webrtc.PeerConnection.IceConnectionState

/**
 * Decides whether a call with the MCU is left and joined again with a new session, or kept.
 *
 * Joining again is the last resort: the other participants see the user leave. It is not done
 * - while the call is interrupted (a phone call took the audio focus): the connection is paused, not lost. The
 *   decision is made again when the interruption ends;
 * - because the network handle changed (VPN switched, LTE/3G change): the path may be the same. The own publisher
 *   connection is watched for a few seconds, and only a connection which does not come back is replaced.
 */
internal object CallRecoveryPolicy {

    enum class Action {
        /** Leave the call and join it again with a new session. */
        REJOIN,

        /** Keep the call and look at the publisher connection again after [RECHECK_DELAY_MILLIS]. */
        RECHECK,

        /** The call is interrupted: keep everything, decide when the interruption ends. */
        DEFER,

        /** The connection is fine. */
        KEEP
    }

    const val RECHECK_DELAY_MILLIS = 3_000L
    const val MAX_RECHECKS = 3

    fun onIceFailed(interrupted: Boolean): Action = if (interrupted) Action.DEFER else Action.REJOIN

    fun onNetworkSwitched(interrupted: Boolean, publisher: IceConnectionState?): Action =
        when {
            interrupted -> Action.DEFER
            isLost(publisher) -> Action.REJOIN
            else -> Action.RECHECK
        }

    /**
     * @param recheck number of looks done before this one, starting with 0
     */
    fun onRecheck(interrupted: Boolean, publisher: IceConnectionState?, recheck: Int): Action =
        when {
            interrupted -> Action.DEFER
            isConnected(publisher) -> Action.KEEP
            isLost(publisher) || recheck >= MAX_RECHECKS -> Action.REJOIN
            else -> Action.RECHECK
        }

    private fun isConnected(state: IceConnectionState?) =
        state == IceConnectionState.CONNECTED || state == IceConnectionState.COMPLETED

    private fun isLost(state: IceConnectionState?) =
        state == null || state == IceConnectionState.FAILED || state == IceConnectionState.CLOSED
}
