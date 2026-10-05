/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.upload

import androidx.work.WorkInfo
import at.bitfire.dav4jvm.exception.HttpException as DavHttpException
import com.nextcloud.talk.upload.UploadRetryPolicy.Decision
import com.nextcloud.talk.upload.UploadRetryPolicy.FailureKind
import com.nextcloud.talk.upload.UploadRetryPolicy.StopAction
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException as RetrofitHttpException
import retrofit2.Response
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

@Suppress("TooManyFunctions")
class UploadRetryPolicyTest {

    @Test
    fun `connection problems are network errors`() {
        assertEquals(FailureKind.NETWORK, UploadRetryPolicy.classify(SocketTimeoutException()))
        assertEquals(FailureKind.NETWORK, UploadRetryPolicy.classify(UnknownHostException()))
        assertEquals(FailureKind.NETWORK, UploadRetryPolicy.classify(IOException("Canceled")))
    }

    @Test
    fun `http errors that may stay are server errors`() {
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(DavHttpException(403, "no")))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(DavHttpException(400, "bad")))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(DavHttpException(507, "full")))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(retrofit(404)))
    }

    @Test
    fun `a server that is unavailable is waited for like a lost network`() {
        listOf(500, 502, 503, 504, 408, 429).forEach {
            assertEquals("code $it", FailureKind.NETWORK, UploadRetryPolicy.classify(DavHttpException(it, "x")))
        }
        assertEquals(FailureKind.NETWORK, UploadRetryPolicy.classify(retrofit(503)))
    }

    @Test
    fun `http error wrapped in an IOException is still a server error`() {
        val wrapped = IOException("failed to create folder", DavHttpException(403, "no"))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(wrapped))
    }

    @Test
    fun `an IOException wrapped by RxJava is a network error`() {
        assertEquals(FailureKind.NETWORK, UploadRetryPolicy.classify(RuntimeException(IOException("reset"))))
        assertEquals(
            FailureKind.NETWORK,
            UploadRetryPolicy.classify(RuntimeException(RuntimeException(UnknownHostException())))
        )
    }

    @Test
    fun `an http error wrapped by RxJava is classified by its code`() {
        assertEquals(FailureKind.NETWORK, UploadRetryPolicy.classify(RuntimeException(retrofit(503))))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(RuntimeException(retrofit(403))))
    }

    @Test
    fun `an http error wins over an IOException in the chain`() {
        val error = IOException("outer", RuntimeException(DavHttpException(403, "no")))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(error))
    }

    @Test
    fun `tls problems count against the limit`() {
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(SSLHandshakeException("bad certificate")))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(SSLPeerUnverifiedException("who")))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(UnknownServiceException("cleartext")))
        assertEquals(FailureKind.SERVER, UploadRetryPolicy.classify(RuntimeException(SSLHandshakeException("x"))))
    }

    @Test
    fun `local problems are neither network nor server errors`() {
        assertEquals(FailureKind.OTHER, UploadRetryPolicy.classify(FileNotFoundException()))
        assertEquals(FailureKind.OTHER, UploadRetryPolicy.classify(RuntimeException(FileNotFoundException())))
        assertEquals(FailureKind.OTHER, UploadRetryPolicy.classify(IllegalArgumentException()))
    }

    @Test
    fun `network errors never use up attempts`() {
        assertEquals(Decision.RETRY, UploadRetryPolicy.decide(FailureKind.NETWORK, 0))
    }

    @Test
    fun `server errors are retried until the limit`() {
        for (count in 1 until UploadRetryPolicy.MAX_SERVER_ERRORS) {
            assertEquals(Decision.RETRY, UploadRetryPolicy.decide(FailureKind.SERVER, count))
        }
        assertEquals(
            Decision.FAIL,
            UploadRetryPolicy.decide(FailureKind.SERVER, UploadRetryPolicy.MAX_SERVER_ERRORS)
        )
    }

    @Test
    fun `other errors fail at once`() {
        assertEquals(Decision.FAIL, UploadRetryPolicy.decide(FailureKind.OTHER, 0))
    }

    @Test
    fun `user cancel aborts whatever the stop reason is`() {
        assertEquals(StopAction.ABORT, UploadRetryPolicy.stopAction(WorkInfo.STOP_REASON_NOT_STOPPED, true))
        assertEquals(
            StopAction.ABORT,
            UploadRetryPolicy.stopAction(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, true)
        )
    }

    @Test
    fun `cancel by the app aborts`() {
        assertEquals(StopAction.ABORT, UploadRetryPolicy.stopAction(WorkInfo.STOP_REASON_CANCELLED_BY_APP, false))
    }

    @Test
    fun `stops by the system keep the upload`() {
        val systemReasons = listOf(
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            WorkInfo.STOP_REASON_TIMEOUT,
            WorkInfo.STOP_REASON_DEVICE_STATE,
            WorkInfo.STOP_REASON_PREEMPT,
            WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_APP_STANDBY,
            WorkInfo.STOP_REASON_USER,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT,
            WorkInfo.STOP_REASON_UNKNOWN,
            WorkInfo.STOP_REASON_NOT_STOPPED
        )
        systemReasons.forEach {
            assertEquals("reason ", StopAction.KEEP, UploadRetryPolicy.stopAction(it, false))
        }
    }

    private fun retrofit(code: Int) = RetrofitHttpException(Response.error<Any>(code, "".toResponseBody()))
}
