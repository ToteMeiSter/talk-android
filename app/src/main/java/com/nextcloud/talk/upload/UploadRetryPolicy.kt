/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.upload

import at.bitfire.dav4jvm.exception.HttpException as DavHttpException
import retrofit2.HttpException as RetrofitHttpException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

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
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500
    private const val HTTP_INSUFFICIENT_STORAGE = 507

    /**
     * NETWORK: no connection or a server that is temporarily unavailable, retried without a limit.
     * SERVER: an answer or a TLS problem that may not go away, retried up to [MAX_SERVER_ERRORS] times.
     * OTHER: a local problem, fails at once.
     */
    enum class FailureKind { NETWORK, SERVER, OTHER }

    enum class Decision { RETRY, FAIL }

    /**
     * Looks through the whole chain of causes, because RxJava wraps a checked [IOException] in a RuntimeException.
     * An HTTP error wins over everything else. Errors of an overloaded or restarting server (5xx except 507, 408,
     * 429) are treated like a lost network: the upload waits and goes on without a limit. Other HTTP errors and
     * TLS problems count against [MAX_SERVER_ERRORS].
     */
    fun classify(error: Throwable): FailureKind {
        var httpCode: Int? = null
        var tlsProblem = false
        var ioProblem = false
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (httpCode == null) {
                httpCode = httpCodeOf(cause)
            }
            tlsProblem = tlsProblem || cause is SSLException || cause is UnknownServiceException
            ioProblem = ioProblem || (cause is IOException && cause !is FileNotFoundException)
            cause = cause.cause
            depth++
        }
        return when {
            httpCode != null -> if (isTransientHttpCode(httpCode)) FailureKind.NETWORK else FailureKind.SERVER
            tlsProblem -> FailureKind.SERVER
            ioProblem -> FailureKind.NETWORK
            else -> FailureKind.OTHER
        }
    }

    private fun httpCodeOf(error: Throwable): Int? =
        when (error) {
            is DavHttpException -> error.code
            is RetrofitHttpException -> error.code()
            else -> null
        }

    private fun isTransientHttpCode(code: Int): Boolean =
        (code >= HTTP_SERVER_ERROR && code != HTTP_INSUFFICIENT_STORAGE) ||
            code == HTTP_REQUEST_TIMEOUT ||
            code == HTTP_TOO_MANY_REQUESTS

    /**
     * @param serverErrorCount server errors of this upload so far, including the one being decided on
     */
    fun decide(kind: FailureKind, serverErrorCount: Int): Decision =
        when {
            kind == FailureKind.NETWORK -> Decision.RETRY
            kind == FailureKind.SERVER && serverErrorCount < MAX_SERVER_ERRORS -> Decision.RETRY
            else -> Decision.FAIL
        }
}
