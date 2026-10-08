/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import com.nextcloud.talk.jobs.CallPushRetryPolicy.PollFailureOutcome
import io.reactivex.Observable
import io.reactivex.schedulers.TestScheduler
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

@Suppress("TooManyFunctions")
class CallPushRetryPolicyTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun networkErrorsAreTransient() {
        assertTrue(CallPushRetryPolicy.isTransient(UnknownHostException("no dns")))
        assertTrue(CallPushRetryPolicy.isTransient(SocketTimeoutException()))
        assertTrue(CallPushRetryPolicy.isTransient(IOException("Software caused connection abort")))
        assertTrue(CallPushRetryPolicy.isTransient(SSLException("Read error: ssl=0x1: I/O error during system call")))
    }

    @Test
    fun certificateProblemsArePermanent() {
        val e = SSLHandshakeException("bad cert").apply { initCause(CertificateException("expired")) }
        assertFalse(CallPushRetryPolicy.isTransient(e))
    }

    @Test
    fun certPathValidatorExceptionInCauseChainIsPermanent() {
        val e = SSLException("trust failure", CertPathValidatorException("no anchor"))
        assertFalse(CallPushRetryPolicy.isTransient(e))
    }

    @Test
    fun requestTimeoutIsTransient() {
        assertTrue(CallPushRetryPolicy.isTransient(TimeoutException()))
    }

    @Test
    fun httpStatusClassification() {
        assertTrue(CallPushRetryPolicy.isTransient(http(500)))
        assertTrue(CallPushRetryPolicy.isTransient(http(503)))
        assertTrue(CallPushRetryPolicy.isTransient(http(408)))
        assertTrue(CallPushRetryPolicy.isTransient(http(429)))
        assertFalse(CallPushRetryPolicy.isTransient(http(401)))
        assertFalse(CallPushRetryPolicy.isTransient(http(403)))
        assertFalse(CallPushRetryPolicy.isTransient(http(404)))
    }

    @Test
    fun nonIoErrorsArePermanent() {
        assertFalse(CallPushRetryPolicy.isTransient(NullPointerException()))
        assertFalse(CallPushRetryPolicy.isTransient(IllegalStateException()))
    }

    @Test
    fun roomFetchRetriesOnlyInsideWindow() {
        val e = UnknownHostException()
        assertTrue(CallPushRetryPolicy.shouldRetry(e, 0, CallPushRetryPolicy.ROOM_FETCH_WINDOW_MS))
        assertTrue(CallPushRetryPolicy.shouldRetry(e, 44_999, CallPushRetryPolicy.ROOM_FETCH_WINDOW_MS))
        assertFalse(CallPushRetryPolicy.shouldRetry(e, 45_000, CallPushRetryPolicy.ROOM_FETCH_WINDOW_MS))
        assertFalse(CallPushRetryPolicy.shouldRetry(http(404), 0, CallPushRetryPolicy.ROOM_FETCH_WINDOW_MS))
    }

    @Test
    fun lateRoomWithoutCallDoesNotRing() {
        assertTrue(CallPushRetryPolicy.shouldRingAfterRoomFetch(hasCall = true, elapsedMs = 20_000))
        assertFalse(CallPushRetryPolicy.shouldRingAfterRoomFetch(hasCall = false, elapsedMs = 20_000))
        assertFalse(CallPushRetryPolicy.shouldRingAfterRoomFetch(hasCall = true, elapsedMs = 45_000))
    }

    @Test
    fun firstPollNetworkErrorKeepsTheCall() {
        assertEquals(PollFailureOutcome.RETRY, CallPushRetryPolicy.pollFailureOutcome(UnknownHostException(), 0))
        assertEquals(
            PollFailureOutcome.RETRY,
            CallPushRetryPolicy.pollFailureOutcome(SSLException("Read error"), 59_999)
        )
    }

    @Test
    fun exhaustedWindowIsReportedAsMissed() {
        assertEquals(
            PollFailureOutcome.WINDOW_EXHAUSTED,
            CallPushRetryPolicy.pollFailureOutcome(UnknownHostException(), CallPushRetryPolicy.POLL_WINDOW_MS)
        )
    }

    @Test
    fun permanentPollErrorDropsSilentlyWithoutMissedCall() {
        assertEquals(PollFailureOutcome.DROP_SILENTLY, CallPushRetryPolicy.pollFailureOutcome(http(404), 0))
        assertEquals(PollFailureOutcome.DROP_SILENTLY, CallPushRetryPolicy.pollFailureOutcome(http(403), 100_000))
    }

    // ---- room fetch loop, virtual time ----

    private class FakeRoom(val hasCall: Boolean = true)

    @Test
    fun hangingFirstAttemptIsCutAndRetried() =
        runTest {
            var calls = 0
            val room = CallPushRetryPolicy.fetchRoomWithRetry<FakeRoom>(
                elapsedMs = { currentTime },
                isStopped = { false },
                hasCall = { it.hasCall }
            ) {
                calls++
                if (calls == 1) awaitCancellation() else FakeRoom()
            }
            assertTrue(room != null)
            assertEquals(2, calls)
            // 10 s attempt timeout + 3 s pause, not the whole 45 s window
            assertEquals(13_000L, currentTime)
        }

    @Test
    fun roomFetchGivesUpWhenWindowIsUsedUp() =
        runTest {
            var calls = 0
            val room = CallPushRetryPolicy.fetchRoomWithRetry<FakeRoom>(
                elapsedMs = { currentTime },
                isStopped = { false },
                hasCall = { it.hasCall }
            ) {
                calls++
                throw UnknownHostException()
            }
            assertNull(room)
            assertTrue(calls > 5)
            assertTrue(currentTime <= CallPushRetryPolicy.ROOM_FETCH_WINDOW_MS + CallPushRetryPolicy.RETRY_DELAY_MS)
        }

    @Test
    fun roomFetchDoesNotRetryPermanentError() =
        runTest {
            var calls = 0
            val room = CallPushRetryPolicy.fetchRoomWithRetry<FakeRoom>(
                elapsedMs = { currentTime },
                isStopped = { false },
                hasCall = { it.hasCall }
            ) {
                calls++
                throw http(404)
            }
            assertNull(room)
            assertEquals(1, calls)
        }

    @Test
    fun delayedRoomWithoutCallIsDropped() =
        runTest {
            var calls = 0
            val room = CallPushRetryPolicy.fetchRoomWithRetry<FakeRoom>(
                elapsedMs = { currentTime },
                isStopped = { false },
                hasCall = { it.hasCall }
            ) {
                calls++
                if (calls == 1) throw UnknownHostException() else FakeRoom(hasCall = false)
            }
            assertNull(room)
        }

    @Test
    fun firstTryRoomWithoutCallIsDropped() =
        runTest {
            val room = CallPushRetryPolicy.fetchRoomWithRetry<FakeRoom>(
                elapsedMs = { currentTime },
                isStopped = { false },
                hasCall = { it.hasCall }
            ) { FakeRoom(hasCall = false) }
            assertNull(room)
        }

    @Test
    fun firstTryRoomWithCallIsReturned() =
        runTest {
            val room = CallPushRetryPolicy.fetchRoomWithRetry<FakeRoom>(
                elapsedMs = { currentTime },
                isStopped = { false },
                hasCall = { it.hasCall }
            ) { FakeRoom(hasCall = true) }
            assertTrue(room != null)
        }

    @Test
    fun ringingNeedsARunningCallOnEveryAnswer() {
        assertTrue(CallPushRetryPolicy.shouldRingForRoom(hasCall = true, retried = false, elapsedMs = 3_600_000))
        assertFalse(CallPushRetryPolicy.shouldRingForRoom(hasCall = false, retried = false, elapsedMs = 0))
        assertTrue(CallPushRetryPolicy.shouldRingForRoom(hasCall = true, retried = true, elapsedMs = 20_000))
        assertFalse(CallPushRetryPolicy.shouldRingForRoom(hasCall = true, retried = true, elapsedMs = 45_000))
        assertFalse(CallPushRetryPolicy.shouldRingForRoom(hasCall = false, retried = true, elapsedMs = 1_000))
    }

    @Test
    fun stoppedWorkerStopsTheLoop() =
        runTest {
            var calls = 0
            val room = CallPushRetryPolicy.fetchRoomWithRetry<FakeRoom>(
                elapsedMs = { currentTime },
                isStopped = { calls >= 1 },
                hasCall = { it.hasCall }
            ) {
                calls++
                throw UnknownHostException()
            }
            assertNull(room)
            assertEquals(1, calls)
        }

    // ---- poll transformer, TestScheduler ----

    private class PollRun(
        val scheduler: TestScheduler = TestScheduler(),
        var subscriptions: Int = 0,
        var visible: Boolean = true
    ) {
        val values = mutableListOf<Int>()
        var error: Throwable? = null
        var completed = false

        fun start(source: (Int) -> Observable<Int>) {
            Observable.defer { source(++subscriptions) }
                .compose(
                    CallPushRetryPolicy.pollTransformer<Int>(
                        elapsedMs = { scheduler.now(TimeUnit.MILLISECONDS) },
                        isCallVisible = { visible },
                        shouldKeepPolling = { true },
                        scheduler = scheduler
                    )
                )
                .subscribe({ values.add(it) }, { error = it }, { completed = true })
        }

        fun advanceTo(ms: Long) = scheduler.advanceTimeTo(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun pollErrorAtStartIsRetriedAfterThreeSecondsWithoutFailing() {
        val run = PollRun()
        run.start { n -> if (n == 1) Observable.error(UnknownHostException()) else Observable.just(n) }
        run.advanceTo(0)
        assertEquals(1, run.subscriptions)
        assertEquals(null, run.error)
        run.advanceTo(2_999)
        assertEquals(1, run.subscriptions)
        run.advanceTo(3_000)
        assertEquals(2, run.subscriptions)
        assertEquals(listOf(2), run.values.take(1))
        assertEquals(null, run.error)
    }

    @Test
    fun pollRetryDoesNotUseUpTheRepeatCounter() {
        val run = PollRun()
        // failed subscriptions must not take repeats away: the success polling still runs to the window
        run.start { n -> if (n <= 2) Observable.error(UnknownHostException()) else Observable.just(n) }
        run.advanceTo(100_000)
        assertTrue(run.completed)
        assertEquals(null, run.error)
        assertTrue(run.values.size >= 10)
    }

    @Test
    fun persistentPollErrorEndsAtWindowAsExhausted() {
        val run = PollRun()
        run.start { Observable.error(UnknownHostException()) }
        run.advanceTo(59_999)
        assertEquals(null, run.error)
        run.advanceTo(60_000)
        val e = run.error!!
        assertEquals(
            CallPushRetryPolicy.PollFailureOutcome.WINDOW_EXHAUSTED,
            CallPushRetryPolicy.pollFailureOutcome(e, run.scheduler.now(TimeUnit.MILLISECONDS))
        )
    }

    @Test
    fun permanentPollErrorFailsAtOnce() {
        val run = PollRun()
        run.start { Observable.error(http(404)) }
        run.advanceTo(0)
        assertEquals(1, run.subscriptions)
        assertEquals(
            CallPushRetryPolicy.PollFailureOutcome.DROP_SILENTLY,
            CallPushRetryPolicy.pollFailureOutcome(run.error!!, 0)
        )
    }

    @Test
    fun hangingPollRequestTimesOutAndIsRetried() {
        val run = PollRun()
        run.start { n -> if (n == 1) Observable.never() else Observable.just(n) }
        run.advanceTo(9_999)
        assertEquals(1, run.subscriptions)
        run.advanceTo(13_000)
        assertEquals(2, run.subscriptions)
        assertEquals(null, run.error)
    }

    @Test
    fun successfulPollingStopsAtWindow() {
        val run = PollRun()
        run.start { n -> Observable.just(n) }
        run.advanceTo(200_000)
        assertTrue(run.completed)
        // polls every 5 s, nothing after the 60 s window
        assertTrue(run.values.size in 12..13)
    }

    @Test
    fun retriesStopWhenCallNotificationIsGone() {
        val run = PollRun()
        run.start { Observable.error(UnknownHostException()) }
        run.advanceTo(0)
        run.visible = false
        run.advanceTo(3_000)
        // the second poll still ran, its failure is not retried any more
        assertEquals(2, run.subscriptions)
        assertTrue(run.error is CallPushRetryPolicy.CallNotificationGoneException)
        assertEquals(
            CallPushRetryPolicy.PollFailureOutcome.DROP_SILENTLY,
            CallPushRetryPolicy.pollFailureOutcome(run.error!!, 3_000)
        )
    }
}
