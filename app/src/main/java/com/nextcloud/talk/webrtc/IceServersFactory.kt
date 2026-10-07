/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.models.json.signaling.settings.SignalingSettingsDto
import com.nextcloud.talk.utils.ApiUtils
import org.webrtc.PeerConnection

/**
 * Builds the ICE servers of a call from the signaling settings.
 *
 * The settings are fetched again each time the call is rejoined. The servers of the new settings replace the old
 * ones: the TURN credentials are temporary, and a list that only grows would make every new peer connection
 * allocate on each server once per earlier join.
 */
object IceServersFactory {

    @JvmStatic
    fun fromSettings(settings: SignalingSettingsDto, apiVersion: Int): MutableList<PeerConnection.IceServer> {
        val iceServers = ArrayList<PeerConnection.IceServer>()

        settings.stunServers?.forEach { stunServer ->
            val urls = if (apiVersion == ApiUtils.API_V3) stunServer.urls.orEmpty() else listOfNotNull(stunServer.url)
            urls.forEach { iceServers.add(PeerConnection.IceServer(it)) }
        }

        settings.turnServers?.forEach { turnServer ->
            turnServer.urls?.forEach {
                iceServers.add(PeerConnection.IceServer(it, turnServer.username, turnServer.credential))
            }
        }

        return iceServers
    }
}
