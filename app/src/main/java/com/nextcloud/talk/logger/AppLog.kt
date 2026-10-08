/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import android.util.Log

/**
 * Static access to the app's [Logger] for the classes that have no dependency injection. It has the calls of
 * `android.util.Log` that the app uses, so a Kotlin file can write `import com.nextcloud.talk.logger.AppLog as Log`
 * and keep its calls.
 *
 * The lines go through the [Logger] (and so to the log file in the settings) whether or not the logcat capture
 * works. Until [install] is called, and in unit tests, the calls fall back to `android.util.Log`.
 */
object AppLog {
    @Volatile
    private var logger: Logger? = null

    /** Called once by the module that creates the logger. */
    @JvmStatic
    fun install(logger: Logger?) {
        this.logger = logger
    }

    @JvmStatic
    fun d(tag: String?, msg: String?): Int {
        val target = logger
        if (target == null) return Log.d(tag, msg.orEmpty())
        target.d(tag.orEmpty(), msg.orEmpty())
        return 0
    }

    @JvmStatic
    fun d(tag: String?, msg: String?, tr: Throwable?): Int {
        val target = logger
        return when {
            target == null -> Log.d(tag, msg, tr)
            tr == null -> d(tag, msg)
            else -> {
                target.d(tag.orEmpty(), msg.orEmpty(), tr)
                0
            }
        }
    }

    @JvmStatic
    fun i(tag: String?, msg: String?): Int {
        val target = logger
        if (target == null) return Log.i(tag, msg.orEmpty())
        target.i(tag.orEmpty(), msg.orEmpty())
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String?): Int {
        val target = logger
        if (target == null) return Log.w(tag, msg.orEmpty())
        target.w(tag.orEmpty(), msg.orEmpty())
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int {
        val target = logger
        return when {
            target == null -> Log.w(tag, msg, tr)
            tr == null -> w(tag, msg)
            else -> {
                target.w(tag.orEmpty(), msg.orEmpty(), tr)
                0
            }
        }
    }

    @JvmStatic
    fun w(tag: String?, tr: Throwable?): Int = w(tag, tr?.toString(), tr)

    @JvmStatic
    fun e(tag: String?, msg: String?): Int {
        val target = logger
        if (target == null) return Log.e(tag, msg.orEmpty())
        target.e(tag.orEmpty(), msg.orEmpty())
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int {
        val target = logger
        return when {
            target == null -> Log.e(tag, msg, tr)
            tr == null -> e(tag, msg)
            else -> {
                target.e(tag.orEmpty(), msg.orEmpty(), tr)
                0
            }
        }
    }

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String = Log.getStackTraceString(tr)
}
