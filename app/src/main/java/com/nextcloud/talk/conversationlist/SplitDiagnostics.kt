/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.conversationlist

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.window.WindowSdkExtensions
import androidx.window.embedding.ActivityEmbeddingController
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitController
import androidx.window.layout.WindowMetricsCalculator

/**
 * Temporary test-build diagnostics: shows why the two-pane mode may not start. Not for upstream.
 */
object SplitDiagnostics {
    private const val TAG = "SplitDiagnostics"
    private var shown = false

    @Suppress("TooGenericExceptionCaught")
    fun show(activity: Activity) {
        val text = try {
            describe(activity)
        } catch (e: Throwable) {
            "diagnostics failed: $e"
        }
        Log.i(TAG, text)
        if (shown) return
        shown = true
        AlertDialog.Builder(activity)
            .setTitle("Split diagnostics")
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun describe(activity: Activity): String {
        val density = activity.resources.displayMetrics.density
        val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
        val cfg = activity.resources.configuration
        val status = SplitController.getInstance(activity).splitSupportStatus
        val statusName = when (status) {
            SplitController.SplitSupportStatus.SPLIT_AVAILABLE -> "SPLIT_AVAILABLE"
            SplitController.SplitSupportStatus.SPLIT_UNAVAILABLE -> "SPLIT_UNAVAILABLE (no OEM support)"
            SplitController.SplitSupportStatus.SPLIT_ERROR_PROPERTY_NOT_DECLARED -> "PROPERTY_NOT_DECLARED"
            else -> status.toString()
        }
        val rules = RuleController.getInstance(activity).getRules().size
        val embedded = ActivityEmbeddingController.getInstance(activity).isActivityEmbedded(activity)
        return buildString {
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
            appendLine("splitSupportStatus: $statusName")
            appendLine("window extensions version: ${WindowSdkExtensions.getInstance().extensionVersion}")
            appendLine("rules registered: $rules")
            appendLine("window: ${(bounds.width() / density).toInt()} x ${(bounds.height() / density).toInt()} dp")
            appendLine("screenWidthDp x screenHeightDp: ${cfg.screenWidthDp} x ${cfg.screenHeightDp}")
            appendLine("smallestScreenWidthDp: ${cfg.smallestScreenWidthDp}")
            appendLine("activity embedded now: $embedded")
            append("need: width >= 600, height >= 600, smallest >= 600 (dp)")
        }
    }
}
