/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import androidx.core.app.NotificationCompat.MessagingStyle.Message

/**
 * Messages of a conversation notification. A message is marked with the server id of its push, so that the
 * message shown first from the subject of the push is replaced by the one with the data of the server.
 */
object PushMessageHistory {
    private const val KEY_PUSH_NOTIFICATION_ID = "push_notification_id"

    fun isMessageOfPush(message: Message, pushNotificationId: Long?): Boolean =
        pushNotificationId != null &&
            message.extras.containsKey(KEY_PUSH_NOTIFICATION_ID) &&
            message.extras.getLong(KEY_PUSH_NOTIFICATION_ID) == pushNotificationId

    /**
     * The [existing] messages with [replacement] at the place of the message of the same push. A push without a
     * message in [existing] adds [replacement] at the end.
     */
    fun merge(existing: List<Message>, pushNotificationId: Long?, replacement: Message): List<Message> {
        if (pushNotificationId != null) {
            replacement.extras.putLong(KEY_PUSH_NOTIFICATION_ID, pushNotificationId)
        }
        val result = mutableListOf<Message>()
        var replaced = false
        for (message in existing) {
            if (isMessageOfPush(message, pushNotificationId)) {
                if (!replaced) result.add(replacement)
                replaced = true
            } else {
                result.add(copyOf(message))
            }
        }
        if (!replaced) result.add(replacement)
        return result
    }

    private fun copyOf(message: Message): Message =
        Message(message.text, message.timestamp, message.person).also { it.extras.putAll(message.extras) }
}
