/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

/**
 * The call is on hold while an accepted phone call is going on. The microphone and the camera of the user are
 * switched off, the voices of the others are not played. The state of the microphone and of the camera before the
 * hold is kept, so they are switched on again after the phone call only if they were on before.
 */
internal class CallHoldState {

    data class LocalMedia(val microphoneOn: Boolean, val videoOn: Boolean)

    private var saved: LocalMedia? = null

    val isOnHold: Boolean
        get() = saved != null

    /**
     * @return the media state to switch off, or null if the call is on hold already
     */
    fun enter(microphoneOn: Boolean, videoOn: Boolean): LocalMedia? {
        if (saved != null) {
            return null
        }
        val state = LocalMedia(microphoneOn, videoOn)
        saved = state
        return state
    }

    /**
     * @return the media state to switch on again, or null if the call was not on hold
     */
    fun exit(): LocalMedia? {
        val state = saved
        saved = null
        return state
    }
}
