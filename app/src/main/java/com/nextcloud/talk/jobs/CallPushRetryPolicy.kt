/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Decides what the incoming-call push handling does when the network is not (yet) available, e.g. right after
 * Doze or on microG devices, where the connection comes up with a delay after the push has woken the device.
 */
object CallPushRetryPolicy {
    /** How long the room lookup of a call push is retried before the call is dropped. */
    const val ROOM_FETCH_WINDOW_MS = 45_000L

    /** Absolute bound of the participants poll, retries after errors included. */
    const val POLL_WINDOW_MS = 60_000L

    const val RETRY_DELAY_MS = 3_000L

    /** One attempt is cut off early: after Doze a pooled connection may be dead and only time out much later. */
    const val ATTEMPT_TIMEOUT_MS = 10_000L

    /** One participants poll request; with [POLL_WINDOW_MS] this bounds the whole poll to about 70 s. */
    const val POLL_REQUEST_TIMEOUT_MS = 10_000L

    private const val POLL_INTERVAL_MS = 5_000L
    private const val POLL_MAX_REPEATS = 12L
    private const val MAX_CAUSE_DEPTH = 8

    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR_MIN = 500

    /**
     * Failures that may disappear once the network is back. Only certificate problems are permanent for IO.
     * A garbage 200 response (captive portal) is an IOException too and is retried until the window ends.
     */
    fun isTransient(e: Throwable): Boolean =
        when (e) {
            is HttpException -> e.code() == HTTP_REQUEST_TIMEOUT ||
                e.code() == HTTP_TOO_MANY_REQUESTS ||
                e.code() >= HTTP_SERVER_ERROR_MIN
            // network loss over HTTPS arrives as SSLException, so it is an IOException and counts as transient
            is IOException -> !isCertificateProblem(e)
            is TimeoutException -> true
            else -> false
        }

    private fun isCertificateProblem(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is CertificateException ||
                cause is CertPathValidatorException ||
                cause is SSLPeerUnverifiedException
            ) {
                return true
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    fun shouldRetry(e: Throwable, elapsedMs: Long, windowMs: Long): Boolean = elapsedMs < windowMs && isTransient(e)

    /**
     * After a delayed room lookup a room without a running call means the call has already ended: ringing for it
     * would be a ghost call. Past the window the push is stale whatever the room says.
     */
    fun shouldRingAfterRoomFetch(hasCall: Boolean, elapsedMs: Long): Boolean =
        hasCall && elapsedMs < ROOM_FETCH_WINDOW_MS

    /**
     * Whether the call rings for the room the lookup returned. The room of a call that has ended never rings, also
     * on the first answer: stage 2 of a push can start long after the push, and the server knows if the call runs.
     * After a retry the window of the lookup must also be left.
     */
    fun shouldRingForRoom(hasCall: Boolean, retried: Boolean, elapsedMs: Long): Boolean =
        hasCall && (!retried || shouldRingAfterRoomFetch(hasCall, elapsedMs))

    /** Outcome of the participants poll that ended with an error. */
    enum class PollFailureOutcome {
        /** Keep the notification and poll again. */
        RETRY,

        /** Window used up without confirmation that the call ended: stop ringing, report it as missed. */
        WINDOW_EXHAUSTED,

        /** Permanent error (room gone, no access): stop ringing, call state unknown, no "missed call". */
        DROP_SILENTLY
    }

    fun pollFailureOutcome(e: Throwable, elapsedMs: Long): PollFailureOutcome =
        when {
            !isTransient(e) -> PollFailureOutcome.DROP_SILENTLY
            elapsedMs < POLL_WINDOW_MS -> PollFailureOutcome.RETRY
            else -> PollFailureOutcome.WINDOW_EXHAUSTED
        }

    /** Raised into the poll when the user already dismissed the call: stop asking the server. */
    class CallNotificationGoneException : RuntimeException("call notification is no longer visible")

    /**
     * Loads the room of a call push, retrying transient failures inside [ROOM_FETCH_WINDOW_MS]. Every attempt is
     * limited to [ATTEMPT_TIMEOUT_MS]; an attempt timeout is a transient failure while window is left.
     * Returns null when the call must not be shown (permanent error, window used up, worker stopped, or the room
     * has no running call).
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun <T : Any> fetchRoomWithRetry(
        elapsedMs: () -> Long,
        isStopped: () -> Boolean,
        hasCall: (T) -> Boolean,
        fetch: suspend () -> T?
    ): T? {
        var retried = false
        while (!isStopped()) {
            val remaining = ROOM_FETCH_WINDOW_MS - elapsedMs()
            if (remaining <= 0) return null
            try {
                val room = withTimeout(minOf(remaining, ATTEMPT_TIMEOUT_MS)) { fetch() }
                return if (room != null && !shouldRingForRoom(hasCall(room), retried, elapsedMs())) {
                    null
                } else {
                    room
                }
            } catch (_: TimeoutCancellationException) {
                // attempt timed out; the loop decides by the remaining window whether to try again
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!shouldRetry(e, elapsedMs(), ROOM_FETCH_WINDOW_MS)) return null
            }
            retried = true
            delay(RETRY_DELAY_MS)
        }
        return null
    }

    /**
     * Tail of the participants poll. A failed or hanging request is retried while the call is still shown and
     * [POLL_WINDOW_MS] is not used up; the retry does not use up the repeat counter. Successful polls repeat every
     * 5 s up to 12 times while [shouldKeepPolling] holds and the window lasts. The stream completes on the window's
     * end and fails with the last error otherwise: classify it with [pollFailureOutcome].
     */
    fun <T : Any> pollTransformer(
        elapsedMs: () -> Long,
        isCallVisible: () -> Boolean,
        shouldKeepPolling: () -> Boolean,
        scheduler: Scheduler,
        onRetry: (Throwable) -> Unit = {}
    ): ObservableTransformer<T, T> =
        ObservableTransformer { upstream ->
            upstream
                .timeout(POLL_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS, scheduler)
                .retryWhen { errors ->
                    errors.flatMap { e ->
                        when {
                            !isCallVisible() -> Observable.error(CallNotificationGoneException())
                            pollFailureOutcome(e, elapsedMs()) == PollFailureOutcome.RETRY -> {
                                onRetry(e)
                                Observable.timer(RETRY_DELAY_MS, TimeUnit.MILLISECONDS, scheduler)
                            }
                            else -> Observable.error(e)
                        }
                    }
                }
                .repeatWhen { completed ->
                    completed.zipWith(Observable.rangeLong(1, POLL_MAX_REPEATS)) { _, i -> i }
                        .flatMap { Observable.timer(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS, scheduler) }
                        .takeWhile { shouldKeepPolling() && elapsedMs() < POLL_WINDOW_MS }
                }
        }
}
