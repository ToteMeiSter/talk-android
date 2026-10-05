/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.upload

import androidx.work.WorkInfo
import at.bitfire.dav4jvm.exception.HttpException as DavHttpException
import retrofit2.HttpException as RetrofitHttpException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Decides what an upload does after an error or after WorkManager stopped it.
 *
 * Network problems and stops by the system never use up attempts: the upload resumes once the network is back.
 * Only errors answered by the server count against [MAX_SERVER_ERRORS].
 */
object UploadRetryPolicy {

    /** Server errors one upload may hit before it is marked as failed. */
    const val MAX_SERVER_ERRORS = 4

    private const val MAX_CAUSE_DEPTH = 8

    enum class FailureKind { NETWORK, SERVER, OTHER }

    enum class Decision { RETRY, FAIL }

    enum class StopAction {
        /** The user cancelled the upload: remove the parts from the server and the prepared file. */
        ABORT,

        /** The system stopped the upload: keep the parts and the prepared file for the next run. */
        KEEP
    }

    fun classify(error: Throwable): FailureKind {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is DavHttpException || cause is RetrofitHttpException) {
                return FailureKind.SERVER
            }
            cause = cause.cause
            depth++
        }
        return if (error is IOException && error !is FileNotFoundException) FailureKind.NETWORK else FailureKind.OTHER
    }

    /**
     * @param serverErrorCount server errors of this upload so far, including the one being decided on
     */
    fun decide(kind: FailureKind, serverErrorCount: Int): Decision =
        when {
            kind == FailureKind.NETWORK -> Decision.RETRY
            kind == FailureKind.SERVER && serverErrorCount < MAX_SERVER_ERRORS -> Decision.RETRY
            else -> Decision.FAIL
        }

    /**
     * @param stopReason [androidx.work.ListenableWorker.getStopReason]
     * @param userCancelled the user cancelled this upload from the chat or from the notification
     */
    fun stopAction(stopReason: Int, userCancelled: Boolean): StopAction =
        if (userCancelled || stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
            StopAction.ABORT
        } else {
            StopAction.KEEP
        }
}
