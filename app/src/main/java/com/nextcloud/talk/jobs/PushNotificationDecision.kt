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

    /** What stage 1 of a Talk push does, see [stage1Route]. */
    enum class Stage1Route {
        /** Show the notification from the push subject, then hand the server data to stage 2. */
        SUBJECT_THEN_SERVER,

        /** Nothing to show without the server: hand everything to stage 2. */
        SERVER,

        /** A type nobody handles. */
        NONE
    }

    /** Stage 1 needs no network. Only a chat message has a text in the push subject that can be shown at once. */
    fun stage1Route(type: String?): Stage1Route =
        when (type) {
            NotificationWorker.TYPE_CHAT -> Stage1Route.SUBJECT_THEN_SERVER

            NotificationWorker.TYPE_ROOM,
            NotificationWorker.TYPE_RECORDING,
            NotificationWorker.TYPE_REMINDER,
            NotificationWorker.TYPE_REMOTE_TALK_SHARE,
            NotificationWorker.TYPE_CALL -> Stage1Route.SERVER

            else -> Stage1Route.NONE
        }

    /**
     * The subject is shown after a failed request of stage 2 only when nothing was shown for this push yet:
     * a chat push was shown from its subject in stage 1.
     */
    fun showFromSubjectAfterFailure(alreadyNotified: Boolean): Boolean = !alreadyNotified
}
