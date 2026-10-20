/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.webrtc

/**
 * What a call does right after the device switched networks. The connections are bound to the address of the old
 * network: with the MCU the call is joined again with a new session, without it the signaling is reconnected and ICE
 * is restarted on every peer connection, as the web client does for a failed connection.
 */
object NetworkSwitchRecovery {
    fun recover(
        hasMcu: Boolean,
        rejoinWithNewSession: () -> Unit,
        reconnectSignaling: () -> Unit,
        peerConnectionWrappers: List<PeerConnectionWrapper>
    ) {
        if (hasMcu) {
            rejoinWithNewSession()
            return
        }
        reconnectSignaling()
        peerConnectionWrappers.forEach { it.restartIce() }
    }
}
