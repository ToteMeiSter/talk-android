/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Julius Linus <juliuslinus1@gmail.com>
 * SPDX-FileCopyrightText: 2024 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.data.network

import androidx.lifecycle.LiveData
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Utility for reporting app connectivity status.
 */
interface NetworkMonitor {
    /**
     * Returns the device's current connectivity status.
     */
    val isOnline: StateFlow<Boolean>

    /**
     * Returns the device's current connectivity status as LiveData for better interop with Java code.
     */
    val isOnlineLiveData: LiveData<Boolean>

    /**
     * Emits when the device moved to another network (e.g. Wi-Fi to mobile data, or Wi-Fi switched off and
     * on again, which gives a new network). Sockets and ICE candidates bound to the previous network are dead
     * after that, even though [isOnline] stays true. Does not emit for the network that was already active when
     * monitoring started.
     */
    val networkSwitches: SharedFlow<Unit>
}
