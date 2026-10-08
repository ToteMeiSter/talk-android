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
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

/**
 * How long the notification of a message push waits for the server. The push wakes the device for about ten seconds
 * only, and in the background the system may block the network of the app (Huawei, Doze) so that a request hangs
 * until the app is opened again. A message must be shown before that, from the text of the push itself, so the
 * request only enriches the notification that is already on screen.
 */
object PushNotificationFetchPolicy {
    /** Longest wait for `GET notifications/<id>` before the notification is shown from the push subject. */
    const val NC_NOTIFICATION_TIMEOUT_MS = 5_000L

    /**
     * Fails the request with a [java.util.concurrent.TimeoutException] after [NC_NOTIFICATION_TIMEOUT_MS] and
     * cancels it. The error takes the same path as any other failure of the request.
     */
    fun <T : Any> deadline(
        scheduler: Scheduler = Schedulers.computation(),
        timeoutMs: Long = NC_NOTIFICATION_TIMEOUT_MS
    ): ObservableTransformer<T, T> =
        ObservableTransformer { upstream ->
            upstream.timeout(timeoutMs, TimeUnit.MILLISECONDS, scheduler)
        }

    /**
     * Runs [showFirst] before any network call, then waits for [fetch] for at most [timeoutMs].
     * The answer goes to [onFetched], every failure (including the deadline) to [onFetchFailed].
     * The result of the request never decides whether the first notification is shown, it only enriches it.
     */
    @Suppress("LongParameterList")
    fun <T : Any> showFirstThenEnrich(
        showFirst: () -> Unit,
        fetch: Observable<T>,
        onFetched: (T) -> Unit,
        onFetchFailed: (Throwable) -> Unit,
        timeoutMs: Long = NC_NOTIFICATION_TIMEOUT_MS,
        scheduler: Scheduler = Schedulers.computation()
    ) {
        showFirst()
        fetch
            .compose(deadline<T>(scheduler, timeoutMs))
            .blockingSubscribe(onFetched, onFetchFailed)
    }
}
