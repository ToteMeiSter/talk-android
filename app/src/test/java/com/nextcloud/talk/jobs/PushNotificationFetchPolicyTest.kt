/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import com.nextcloud.talk.utils.BoundedLoad
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PushNotificationFetchPolicyTest {

    private val scheduler = TestScheduler()

    private fun <T : Any> Observable<T>.withDeadline() =
        compose(PushNotificationFetchPolicy.deadline<T>(scheduler)).test()

    @Test
    fun deadlineIsShorterThanTheWakeWindowOfThePush() {
        assertTrue(PushNotificationFetchPolicy.NC_NOTIFICATION_TIMEOUT_MS in 1_000L..8_000L)
    }

    @Test
    fun hangingRequestFailsWithTimeoutAtTheDeadline() {
        val observer = Observable.never<String>().withDeadline()

        scheduler.advanceTimeBy(PushNotificationFetchPolicy.NC_NOTIFICATION_TIMEOUT_MS - 1, TimeUnit.MILLISECONDS)
        observer.assertNoErrors().assertNotTerminated()

        scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        observer.assertError(TimeoutException::class.java)
    }

    @Test
    fun hangingRequestIsCancelled() {
        var cancelled = false
        val observer = Observable.never<String>().doOnDispose { cancelled = true }.withDeadline()

        scheduler.advanceTimeBy(PushNotificationFetchPolicy.NC_NOTIFICATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        observer.assertError(TimeoutException::class.java)
        assertTrue(cancelled)
    }

    @Test
    fun answerInTimePassesUnchanged() {
        val observer = Observable.just("notification")
            .delay(PushNotificationFetchPolicy.NC_NOTIFICATION_TIMEOUT_MS - 1, TimeUnit.MILLISECONDS, scheduler)
            .withDeadline()

        scheduler.advanceTimeBy(PushNotificationFetchPolicy.NC_NOTIFICATION_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)

        observer.assertValue("notification").assertComplete().assertNoErrors()
    }

    @Test
    fun failureBeforeTheDeadlineKeepsItsCause() {
        val failure = IOException("Failed to connect")
        val observer = Observable.error<String>(failure).withDeadline()

        observer.assertError(failure)
        assertEquals(1, observer.errorCount())
        assertFalse(observer.errors().single() is TimeoutException)
    }

    private class Calls {
        val events = mutableListOf<String>()
    }

    private fun run(fetch: Observable<String>, calls: Calls, onFetched: (String) -> Unit = {}) {
        PushNotificationFetchPolicy.showFirstThenEnrich(
            showFirst = { calls.events.add("first") },
            fetch = fetch.doOnSubscribe { calls.events.add("subscribed") },
            onFetched = {
                onFetched(it)
                calls.events.add("enriched:$it")
            },
            onFailed = { calls.events.add("failed:${it.javaClass.simpleName}") },
            timeoutMs = 200L
        )
    }

    @Test
    fun hangingRequestShowsOnceBeforeTheRequestAndNeverEnriches() {
        val calls = Calls()

        run(Observable.never(), calls)

        assertEquals(listOf("first", "subscribed", "failed:TimeoutException"), calls.events)
    }

    @Test
    fun answerInTimeShowsFirstAndThenEnriches() {
        val calls = Calls()

        run(Observable.just("server"), calls)

        assertEquals(listOf("first", "subscribed", "enriched:server"), calls.events)
    }

    @Test
    fun failedRequestKeepsTheFirstShowAndShowsNothingMore() {
        val calls = Calls()

        run(Observable.error(IOException("Failed to connect")), calls)

        assertEquals(listOf("first", "subscribed", "failed:IOException"), calls.events)
    }

    @Test
    fun failureInTheEnrichmentGoesToTheFailureHandlerOnly() {
        val calls = Calls()

        run(Observable.just("server"), calls) { throw IllegalStateException("broken") }

        assertEquals(listOf("first", "subscribed", "failed:IllegalStateException"), calls.events)
    }

    @Test
    fun slowPictureAfterTheAnswerDoesNotDropTheAnswerAndDoesNotInterruptTheThread() {
        val errors = CopyOnWriteArrayList<Throwable>()
        RxJavaPlugins.setErrorHandler { errors.add(it) }
        try {
            val calls = Calls()
            var picture: String? = "not loaded"
            var interrupted = true

            // the load takes longer than the deadline of the request and runs into its own limit
            run(Observable.just("server"), calls) {
                picture = BoundedLoad.within(PICTURE_LIMIT_MS) {
                    delay(10_000L)
                    "picture"
                }
                interrupted = Thread.currentThread().isInterrupted
            }

            assertEquals(listOf("first", "subscribed", "enriched:server"), calls.events)
            assertNull(picture)
            assertFalse(interrupted)
            // let a possible late error reach the handler
            Thread.sleep(300L)
            assertTrue("RxJava error handler got $errors", errors.isEmpty())
        } finally {
            RxJavaPlugins.setErrorHandler(null)
        }
    }

    @Test
    fun answerIsAppliedEvenIfTheWorkOutlastsTheDeadline() {
        val calls = Calls()

        run(Observable.just("server"), calls) { Thread.sleep(400L) }

        assertEquals(listOf("first", "subscribed", "enriched:server"), calls.events)
    }

    @Test
    fun deadlineLimitsTheRequestOnTestScheduler() {
        val observer = Observable.never<String>().withDeadline()

        scheduler.advanceTimeBy(PushNotificationFetchPolicy.NC_NOTIFICATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        observer.assertError(TimeoutException::class.java)
    }

    private companion object {
        const val PICTURE_LIMIT_MS = 100L
    }
}
