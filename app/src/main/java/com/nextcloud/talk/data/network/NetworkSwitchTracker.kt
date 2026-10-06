/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.data.network

/**
 * Decides whether a report about the default network means that the device changed its network.
 *
 * Only a different network handle is a change. Android gives a new handle to every network connection, also when
 * the same Wi-Fi is switched off and on or after airplane mode, so these cases are covered. A network that only
 * loses and regains validation (captive portal check, data stall) keeps its handle and its connections, so it is
 * not a change. The first network ever reported is not a change either.
 */
class NetworkSwitchTracker {
    private var current: Long? = null

    /**
     * @return true if the validated network [id] differs from the previously used one
     */
    @Synchronized
    fun onValidated(id: Long): Boolean {
        val previous = current
        current = id
        return previous != null && previous != id
    }
}
