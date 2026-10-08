/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.nextcloud.talk.logger.AppLog as Log
import retrofit2.HttpException

/**
 * Fork-only diagnostics of the push chain (`adb logcat -s PushDiag`).
 * Never pass push tokens, keys, signatures, device identifiers or credentials here.
 */
object PushDiag {
    const val TAG = "PushDiag"
    private const val MAX_TEXT = 200
    private const val MAX_CAUSES = 5
    private const val MAX_FCM_VALUE = 32

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, if (error == null) message else "$message: ${describe(error)}")
    }

    /**
     * State of the network and of the app at the moment of the push: whether the system lets the app use the
     * network (active network, VALIDATED, blocked state, background data restriction), power state and the
     * importance of the process, with [withPower] also what [describePowerRestrictions] reads (several binder calls:
     * once per stage). No tokens, no addresses.
     */
    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    fun describeNetworkAndProcess(context: Context, withPower: Boolean = false): String =
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val info = cm?.activeNetworkInfo
            val pm = context.getSystemService(PowerManager::class.java)
            val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            "activeNetwork=${network != null} " +
                "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                "internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                "notSuspended=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)} " +
                "networkInfoState=${info?.detailedState} " +
                "restrictBackground=${cm?.restrictBackgroundStatus} " +
                "deviceIdle=${pm?.isDeviceIdleMode} interactive=${pm?.isInteractive} " +
                "processImportance=${process.importance}" +
                if (withPower) " ${describePowerRestrictions(context)}" else ""
        } catch (e: RuntimeException) {
            "network state unavailable: ${describe(e)}"
        }

    /**
     * What may close the network to the app besides Doze: standby bucket (10 active, 20 working set, 30 frequent,
     * 40 rare, 45 restricted), battery saver, battery optimization, background restriction, light Doze and low power
     * standby. A value the API level does not have is left out. Numbers and booleans only.
     */
    @Suppress("TooGenericExceptionCaught")
    fun describePowerRestrictions(context: Context): String =
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            val am = context.getSystemService(ActivityManager::class.java)
            val parts = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                parts += "standbyBucket=${context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket}"
            }
            parts += "powerSave=${pm?.isPowerSaveMode}"
            parts += "ignoringBatteryOpt=${pm?.isIgnoringBatteryOptimizations(context.packageName)}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                parts += "backgroundRestricted=${am?.isBackgroundRestricted}"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                parts += "lightIdle=${pm?.isDeviceLightIdleMode}"
                parts += "lowPowerStandby=${pm?.isLowPowerStandbyEnabled}"
            }
            parts.joinToString(" ")
        } catch (e: RuntimeException) {
            "power restrictions unavailable: ${e.javaClass.simpleName}"
        }

    /** `ok`, or the class of the error with the HTTP code: no body, no message, no address. */
    fun describeOutcome(error: Throwable?): String =
        when (error) {
            null -> "ok"
            is HttpException -> "HttpException code=${error.code()}"
            else -> error.javaClass.name
        }

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

    /** Exception class and message including the cause chain, truncated. */
    fun describe(error: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) sb.append(" <- ")
            sb.append(current.javaClass.name).append(": ").append(current.message?.take(MAX_TEXT))
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return sb.toString()
    }

    /** Like [describe], plus HTTP code and truncated error body; [secrets] are removed from the body. */
    @Suppress("TooGenericExceptionCaught")
    fun describeHttp(error: Throwable, vararg secrets: String?): String {
        if (error !is HttpException) return describe(error)
        val body = try {
            error.response()?.errorBody()?.string()
        } catch (e: Exception) {
            "<unreadable: ${e.javaClass.simpleName}>"
        }
        var text = body.orEmpty()
        secrets.filterNot { it.isNullOrEmpty() }.forEach { text = text.replace(it!!, "<redacted>") }
        return "HttpException code=${error.code()} body=${text.take(MAX_TEXT)}"
    }
}
