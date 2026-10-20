/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

/**
 * The pause before the signaling socket is opened again after a failure. It grows with every failure in a row, so
 * a device without network (the system blocks the network of the app in the background) does not open a socket
 * every second; the first retry stays as quick as before, as a call has to recover from a short network gap.
 */
object WebSocketReconnectPolicy {
    const val FIRST_DELAY_MS = 1_000L

    /** Upper bound of the pause: a failing connection is tried again at least this often. */
    const val MAX_DELAY_MS = 16_000L

    private const val MAX_SHIFT = 10

    /** @param consecutiveFailures failures since the last hello, this one included (1 for the first). */
    fun delayMs(consecutiveFailures: Int): Long {
        val shift = (consecutiveFailures - 1).coerceIn(0, MAX_SHIFT)
        return minOf(FIRST_DELAY_MS shl shift, MAX_DELAY_MS)
    }
}
