/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.nextcloud.talk.logger.AppLog as Log

private const val TAG = "MainThreadStall"
private const val BEAT_INTERVAL_MS = 500L
private const val STALL_THRESHOLD_MS = 2000L
private const val DUMP_INTERVAL_MS = 2000L
private const val MAX_DUMPS = 4
private const val STACK_LINES = 40

/**
 * Writes the main thread's stack to the in-app log while the main thread has not run a posted task for
 * [STALL_THRESHOLD_MS]; an ANR leaves no trace in the log otherwise. Meant to run while one screen is visible.
 */
internal class MainThreadStallWatchdog(private val screen: String) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null

    @Volatile private var lastBeat = SystemClock.uptimeMillis()
    private var lastDump = 0L
    private var dumps = 0
    private var stalledSince = 0L

    private val beat = object : Runnable {
        override fun run() {
            lastBeat = SystemClock.uptimeMillis()
            mainHandler.postDelayed(this, BEAT_INTERVAL_MS)
        }
    }

    fun start() {
        if (thread != null) return
        lastBeat = SystemClock.uptimeMillis()
        mainHandler.post(beat)
        val checker = HandlerThread("StallWatchdog").also { it.start() }
        thread = checker
        Handler(checker.looper).post(object : Runnable {
            override fun run() {
                check()
                Handler(checker.looper).postDelayed(this, BEAT_INTERVAL_MS)
            }
        })
    }

    fun stop() {
        mainHandler.removeCallbacks(beat)
        thread?.quitSafely()
        thread = null
    }

    private fun check() {
        val now = SystemClock.uptimeMillis()
        val stalledFor = now - lastBeat
        if (stalledFor < STALL_THRESHOLD_MS) {
            if (stalledSince != 0L) {
                Log.w(TAG, "$screen: main thread recovered after ${now - stalledSince} ms")
                stalledSince = 0L
                dumps = 0
            }
            return
        }
        if (stalledSince == 0L) stalledSince = lastBeat
        if (dumps < MAX_DUMPS && now - lastDump >= DUMP_INTERVAL_MS) {
            lastDump = now
            dumps++
            val stack = Looper.getMainLooper().thread.stackTrace.take(STACK_LINES).joinToString("\n  at ")
            Log.w(TAG, "$screen: main thread stalled for $stalledFor ms (dump $dumps), stack:\n  at $stack")
        }
    }
}
