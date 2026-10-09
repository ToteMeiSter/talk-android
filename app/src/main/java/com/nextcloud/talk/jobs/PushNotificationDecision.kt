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

    const val TYPE_CHAT = "chat"
    const val TYPE_ROOM = "room"
    const val TYPE_CALL = "call"
    const val TYPE_RECORDING = "recording"
    const val TYPE_REMOTE_TALK_SHARE = "remote_talk_share"
    const val TYPE_REMINDER = "reminder"

    /**
     * A push without chat history: the notification of the same server notification that is already in the status
     * bar is written again without sound (stage 2 started again after the system stopped it).
     */
    fun decideNonChat(activeServerNotificationId: Long?, pushNotificationId: Long?): Alert =
        if (pushNotificationId != null && activeServerNotificationId == pushNotificationId) {
            Alert.SILENT
        } else {
            Alert.ALERT
        }

    /** What stage 1 of a Talk push does, see [stage1Route]. */
    enum class Stage1Route {
        /** Show the notification from the push subject, then hand the server data to stage 2. */
        SUBJECT_THEN_SERVER,

        /** Nothing to show without the server: hand everything to stage 2. */
        SERVER,

        /**
         * An incoming call: shown from the push subject, the room token and the cached conversation, then stage 2
         * only asks the server whether the call is still going on.
         */
        CALL_FROM_PUSH,

        /** A type nobody handles. */
        NONE
    }

    /**
     * Stage 1 needs no network. A chat message has a text in the push subject that can be shown at once, a call
     * rings from the push subject and the room token.
     */
    fun stage1Route(type: String?): Stage1Route =
        when (type) {
            TYPE_CHAT -> Stage1Route.SUBJECT_THEN_SERVER
            TYPE_ROOM, TYPE_RECORDING, TYPE_REMINDER, TYPE_REMOTE_TALK_SHARE -> Stage1Route.SERVER
            TYPE_CALL -> Stage1Route.CALL_FROM_PUSH
            else -> Stage1Route.NONE
        }

    /** What stage 2 runs for a type. Every type that stage 1 hands over has one. */
    enum class Stage2Handler { NOTIFICATION, REMOTE_TALK_SHARE, CALL, NONE }

    fun stage2Handler(type: String?): Stage2Handler =
        when (type) {
            TYPE_CHAT, TYPE_ROOM, TYPE_RECORDING, TYPE_REMINDER -> Stage2Handler.NOTIFICATION
            TYPE_REMOTE_TALK_SHARE -> Stage2Handler.REMOTE_TALK_SHARE
            TYPE_CALL -> Stage2Handler.CALL
            else -> Stage2Handler.NONE
        }

    /** What stage 2 does when the request for the server notification failed. */
    enum class Stage2Failure {
        /** The system stopped the stage: it runs again, nothing is written now. */
        RERUN,

        /** Nothing is shown, the room is only caught up (the server dropped the notification, or it is shown). */
        NO_SHOW,

        /** Show the notification from the push subject. */
        SUBJECT_FALLBACK
    }

    /**
     * @param stopped the system stopped the stage
     * @param alreadyNotified a notification was written for the push (a chat push in stage 1)
     * @param httpCode HTTP code of the answer, null for an error without one
     */
    fun onStage2Failure(stopped: Boolean, alreadyNotified: Boolean, httpCode: Int?): Stage2Failure =
        when {
            stopped -> Stage2Failure.RERUN
            httpCode == HTTP_NOT_FOUND || alreadyNotified -> Stage2Failure.NO_SHOW
            else -> Stage2Failure.SUBJECT_FALLBACK
        }

    /**
     * GreedyScheduler starts stage 2 in the process at once, because its network callback does not report that the
     * system closed the network of the app (Huawei, screen off: BLOCKED). The expedited job that JobScheduler holds
     * for the same work gets the network, but WorkManager cancels it as soon as the run in the process ends. So a run
     * that JobScheduler did not start waits while the network is closed. A run of JobScheduler has the network.
     */
    fun shouldWaitForNetwork(startedByJobScheduler: Boolean, networkBlocked: Boolean): Boolean =
        !startedByJobScheduler && networkBlocked

    /**
     * Result of [waitForOpenNetwork].
     *
     * @param waitedMs time spent in the wait
     * @param opened the network was open when the wait ended
     */
    data class NetworkWait(val waitedMs: Long, val opened: Boolean)

    /**
     * Waits until [isBlocked] says no, the stage is stopped or [maxMs] pass. Never waits longer than [maxMs]: after
     * it stage 2 goes on as without the wait.
     */
    @Suppress("LongParameterList")
    fun waitForOpenNetwork(
        isBlocked: () -> Boolean,
        isStopped: () -> Boolean,
        now: () -> Long,
        sleep: (Long) -> Unit,
        maxMs: Long = NETWORK_WAIT_MAX_MS,
        stepMs: Long = NETWORK_WAIT_STEP_MS
    ): NetworkWait {
        val start = now()
        var waited = 0L
        while (isBlocked()) {
            if (isStopped() || waited >= maxMs) return NetworkWait(waited, opened = false)
            sleep(minOf(stepMs, maxMs - waited))
            waited = now() - start
        }
        return NetworkWait(waited, opened = true)
    }

    const val NETWORK_WAIT_MAX_MS = 10_000L
    const val NETWORK_WAIT_STEP_MS = 250L

    /** Time of the notification: the one stage 1 took, so that every run of stage 2 writes the same notification. */
    fun pushTimestamp(stage1Time: Long, now: Long): Long = if (stage1Time > 0L) stage1Time else now

    private const val HTTP_NOT_FOUND = 404
}
