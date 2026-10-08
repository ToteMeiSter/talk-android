/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Text for the log lines of the push chain. Never pass tokens, keys, signatures or the data of a push. */
object PushLogUtils {
    private const val MAX_FCM_VALUE = 32

    /** Keys of the FCM extras whose values are written to the log. */
    val FCM_LOGGED_KEYS: List<String> = listOf(
        "google.delivered_priority",
        "google.original_priority",
        "google.priority",
        "google.priority_reduced",
        "google.ttl",
        "google.sent_time",
        "message_type"
    )

    /**
     * Priority, lifetime and type of an FCM message for the log, and only the names of its other `google.*` keys.
     * The values of all other keys are never read: they hold the subject, the signature, identifiers and tokens.
     *
     * @param keys the keys of the extras of the message
     * @param valueOf the value of a key; asked for the keys of [FCM_LOGGED_KEYS] only
     */
    fun describeFcmExtras(keys: Set<String>, valueOf: (String) -> Any?): String {
        val values = FCM_LOGGED_KEYS.joinToString(" ") { key ->
            val value = if (key in keys) valueOf(key)?.toString()?.take(MAX_FCM_VALUE) else null
            "$key=$value"
        }
        val otherGoogleKeys = keys.filter { it.startsWith("google.") && it !in FCM_LOGGED_KEYS }.sorted()
        return "$values otherGoogleKeys=$otherGoogleKeys"
    }

    /** Whether the system lets the app use the network right now. Booleans only, no addresses. */
    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    fun describeNetwork(context: Context): String =
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            "activeNetwork=${network != null} " +
                "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                "networkInfoState=${cm?.activeNetworkInfo?.detailedState}"
        } catch (e: RuntimeException) {
            "network state unavailable: ${e.javaClass.simpleName}"
        }
}
