/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.account

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Temporary diagnostics for the "CSRF check failed" browser login: counts the login requests, browser launches,
 * polls and recreations of [BrowserLoginActivity]. URLs are logged without host, tokens and query, only whether
 * scheme, host and port match the server address. Read with `adb logcat -s LoginDiag`.
 */
object LoginDiag {
    private const val TAG = "LoginDiag"
    private const val MIN_TOKEN_LENGTH = 16

    private val activityCreates = AtomicInteger()
    private val loginRequests = AtomicInteger()
    private val browserLaunches = AtomicInteger()
    private val polls = AtomicInteger()

    @Volatile
    private var baseUrl: String? = null

    fun activityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Log.i(
            TAG,
            "activity onCreate #${activityCreates.incrementAndGet()} activity=${System.identityHashCode(activity)} " +
                "savedState=${savedInstanceState != null} " +
                "orientation=${activity.resources.configuration.orientation} " +
                "multiWindow=${activity.isInMultiWindowMode} taskId=${activity.taskId}"
        )
    }

    fun activityEvent(activity: Activity, event: String) {
        Log.i(
            TAG,
            "activity $event activity=${System.identityHashCode(activity)} " +
                "changingConfigurations=${activity.isChangingConfigurations} finishing=${activity.isFinishing}"
        )
    }

    fun event(message: String) {
        Log.i(TAG, message)
    }

    fun loginRequestStarted(url: String) {
        baseUrl = url
        Log.i(TAG, "POST login/v2 #${loginRequests.incrementAndGet()} base=${describe(url)}")
    }

    fun loginRequestSkipped() {
        Log.i(TAG, "login already started, no new POST login/v2 (requests so far ${loginRequests.get()})")
    }

    fun loginRequestFinished(loginUrl: String?, pollUrl: String?) {
        if (loginUrl == null || pollUrl == null) {
            Log.i(TAG, "POST login/v2 failed")
        } else {
            Log.i(TAG, "POST login/v2 ok loginUrl=${describe(loginUrl)} pollUrl=${describe(pollUrl)}")
        }
    }

    fun browserLaunched(url: String) {
        Log.i(TAG, "browser launch #${browserLaunches.incrementAndGet()} url=${describe(url)}")
    }

    fun pollStarted(): Int {
        val number = polls.incrementAndGet()
        Log.i(TAG, "poll #$number started")
        return number
    }

    fun pollFinished(number: Int, success: Boolean) {
        Log.i(TAG, "poll #$number finished success=$success")
    }

    private fun describe(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "<unparsable url>"
        val base = baseUrl?.let { runCatching { URI(it) }.getOrNull() }
        val sameScheme = base != null && uri.scheme == base.scheme
        val sameHost = base != null && uri.host.equals(base.host, ignoreCase = true)
        val samePort = base != null && uri.port == base.port
        val path = uri.rawPath.orEmpty().split('/').joinToString("/") {
            if (it.length >= MIN_TOKEN_LENGTH) "<${it.length} chars>" else it
        }
        val query = if (uri.rawQuery.isNullOrEmpty()) "" else "?<query>"
        return "${uri.scheme}://<host>$path$query (sameSchemeAsBase=$sameScheme sameHostAsBase=$sameHost " +
            "samePortAsBase=$samePort)"
    }
}
