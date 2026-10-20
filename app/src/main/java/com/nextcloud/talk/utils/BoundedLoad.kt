/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A blocking load with its own time limit. The coroutine is cancelled at the limit, the thread is never
 * interrupted: an interrupted `runBlocking` throws InterruptedException, which no caller expects.
 */
object BoundedLoad {
    /** A picture of a notification: without it the notification is shown without the picture. */
    const val PICTURE_TIMEOUT_MS = 2_000L

    /** The result of [block], or null if it did not finish within [timeoutMs]. */
    fun <T : Any> within(timeoutMs: Long, block: suspend () -> T?): T? =
        runBlocking { withTimeoutOrNull(timeoutMs) { block() } }
}
