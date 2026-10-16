/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.mediaviewer.model

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A file the user is forwarding, kept in the memory of this process while the conversation is picked. The conversation
 * list is exported, so the path of the file must never come in through an intent: the intent only carries the random
 * key of an entry made here. A key without an entry (foreign intent, dead process) forwards nothing.
 */
object PendingFileForward {

    /**
     * [fileId], [fileName] and [fileSize] describe the file for the copy that is made when the user may not share it
     * again (a file of another user). [fileId] and [fileName] are blank when the message does not tell them; then
     * no copy is possible. [permissions] are the permissions of the file in the message (bit 2 update, bit 16
     * share), null when the message does not tell them; they decide between share and copy before the request.
     */
    data class Entry(
        val userId: Long,
        val remotePath: String,
        val caption: String,
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val permissions: Int? = null
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun put(entry: Entry): String {
        val key = UUID.randomUUID().toString()
        entries[key] = entry
        return key
    }

    /** Reads the entry without removing it, e.g. to show the confirmation (a rotation must not lose it). */
    fun peek(key: String?): Entry? = key?.let { entries[it] }

    /** Reads the entry and removes it, so that a key works once. */
    fun take(key: String?): Entry? = key?.let { entries.remove(it) }
}
