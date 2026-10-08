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
     * Fails the request with a [java.util.concurrent.TimeoutException] after the timeout and
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
     * The enrichment of a notification that is already shown: the answer of [fetch] and the work of [prepare]
     * on it (avatars, preview) together must finish within [timeoutMs], else the result is a
     * [java.util.concurrent.TimeoutException]. [prepare] runs on [workScheduler] and is cancelled at the deadline.
     */
    fun <T : Any, R : Any> enrichment(
        fetch: Observable<T>,
        prepare: (T) -> R,
        timeoutMs: Long = NC_NOTIFICATION_TIMEOUT_MS,
        scheduler: Scheduler = Schedulers.computation(),
        workScheduler: Scheduler = Schedulers.io()
    ): Observable<R> =
        fetch
            .flatMap { answer -> Observable.fromCallable { prepare(answer) }.subscribeOn(workScheduler) }
            .compose(deadline<R>(scheduler, timeoutMs))

    /**
     * Runs [showFirst] before any network call, then waits for the [enrichment]. The result goes to
     * [onPrepared], every failure (including the deadline) to [onFailed]. The request never decides whether the
     * first notification is shown, it only enriches it.
     */
    @Suppress("LongParameterList")
    fun <T : Any, R : Any> showFirstThenEnrich(
        showFirst: () -> Unit,
        fetch: Observable<T>,
        prepare: (T) -> R,
        onPrepared: (R) -> Unit,
        onFailed: (Throwable) -> Unit,
        timeoutMs: Long = NC_NOTIFICATION_TIMEOUT_MS,
        scheduler: Scheduler = Schedulers.computation(),
        workScheduler: Scheduler = Schedulers.io()
    ) {
        showFirst()
        enrichment(fetch, prepare, timeoutMs, scheduler, workScheduler)
            .blockingSubscribe(onPrepared, onFailed)
    }
}
