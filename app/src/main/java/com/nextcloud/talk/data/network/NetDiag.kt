/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.data.network

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.nextcloud.talk.logger.AppLog as Log

/**
 * Fork-only diagnostics of the network the system gives to the app process (`adb logcat -s NetDiag`, or the log in
 * the settings). It tells which policy closes the sockets of a backgrounded app: a blocked UID
 * (`onBlockedStatusChanged blocked=true`) or a frozen process without a block (ticks with a gap, no block line).
 * Numbers and booleans only: no addresses, no names.
 */
object NetDiag {
    const val TAG = "NetDiag"
    const val TICK_INTERVAL_MS = 30_000L

    fun event(event: String, context: Context) {
        Log.i(TAG, "$event, ${describeProcess(context)}")
    }

    /** `netId` of a [android.net.Network]: its handle is not stable text, `toString` is the netId. */
    fun netId(network: Any?): String = network?.toString() ?: "none"

    /** Importance of the process (100 foreground, 125 foreground service, 400 cached) and data saver state. */
    @Suppress("TooGenericExceptionCaught")
    fun describeProcess(context: Context): String =
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val info = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            "importance=${info.importance} restrictBackground=${cm?.restrictBackgroundStatus}"
        } catch (e: RuntimeException) {
            "process state unavailable: ${e.javaClass.simpleName}"
        }

    /**
     * One heartbeat line. `sinceLastTick` far above [TICK_INTERVAL_MS] means the process did not run (frozen or the
     * device slept); `deepSleep` is the time the CPU was off since boot (elapsedRealtime minus uptime), its growth
     * between ticks separates a device sleep from a frozen process of a running device.
     */
    fun formatTick(uptimeMs: Long, elapsedMs: Long, previousElapsedMs: Long?, previousSleepMs: Long?): String {
        val sleep = elapsedMs - uptimeMs
        val sinceLast = previousElapsedMs?.let { elapsedMs - it } ?: -1
        val sleptSinceLast = previousSleepMs?.let { sleep - it } ?: -1
        return "tick uptime=$uptimeMs elapsed=$elapsedMs sinceLastTick=$sinceLast ms " +
            "deepSleep=$sleep ms deepSleepSinceLastTick=$sleptSinceLast ms"
    }

    /** Keeps the previous tick; [next] returns the line for the tick now. */
    class Ticker {
        private var previousElapsed: Long? = null
        private var previousSleep: Long? = null

        fun next(uptimeMs: Long = SystemClock.uptimeMillis(), elapsedMs: Long = SystemClock.elapsedRealtime()): String {
            val line = formatTick(uptimeMs, elapsedMs, previousElapsed, previousSleep)
            previousElapsed = elapsedMs
            previousSleep = elapsedMs - uptimeMs
            return line
        }
    }

    /**
     * Tells whether the validated/suspended state of a network changed: the system repeats
     * `onCapabilitiesChanged` for every signal strength change, and these lines would drown the log.
     */
    class CapabilitiesChangeFilter {
        private val last = HashMap<String, Pair<Boolean, Boolean>>()

        @Synchronized
        fun changed(netId: String, validated: Boolean, notSuspended: Boolean): Boolean {
            val state = validated to notSuspended
            if (last[netId] == state) return false
            last[netId] = state
            return true
        }

        @Synchronized
        fun forget(netId: String) {
            last.remove(netId)
        }
    }
}
