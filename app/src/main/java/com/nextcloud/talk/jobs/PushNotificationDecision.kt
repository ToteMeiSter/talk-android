/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import androidx.core.app.NotificationCompat.MessagingStyle.Message

/** What a push does to the notification of its conversation. */
object PushNotificationDecision {
    enum class Alert {
        /** A new message: sound and vibration. */
        ALERT,

        /** The server data of a message that is already shown: update, no sound. */
        SILENT,

        /** The message of this push is no longer in the notification: write nothing. */
        SKIP
    }

    /**
     * @param isEnrichment the push was already shown from its subject
     * @param activeMessages messages of the notification of the conversation that is in the status bar now,
     *  null if there is none (dismissed, cancelled)
     */
    fun decide(isEnrichment: Boolean, activeMessages: List<Message>?, pushNotificationId: Long?): Alert =
        when {
            !isEnrichment -> Alert.ALERT
            activeMessages?.any { PushMessageHistory.isMessageOfPush(it, pushNotificationId) } == true -> Alert.SILENT
            else -> Alert.SKIP
        }

    /** The subject is shown after a failed request only when nothing was shown for this push yet. */
    fun showFromSubjectAfterFailure(alreadyNotified: Boolean): Boolean = !alreadyNotified
}
