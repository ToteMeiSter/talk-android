/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

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

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, if (error == null) message else "$message: ${describe(error)}")
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
