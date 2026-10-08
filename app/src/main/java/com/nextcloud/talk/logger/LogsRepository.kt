/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

interface LogsRepository {
    val lostEntries: Boolean
    var minimumLevel: Level

    /** State of the logcat capture, for the diagnosis report. */
    val captureStatus: String

    fun load(onLoaded: (entries: List<LogEntry>, totalLogSize: Long) -> Unit)
    fun deleteAll()
}
