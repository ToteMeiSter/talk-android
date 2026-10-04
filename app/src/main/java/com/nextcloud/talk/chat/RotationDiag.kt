/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat

import android.app.Activity
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.window.layout.WindowMetricsCalculator

/**
 * Temporary diagnostics for the rotation / unfold width problem: logs the configuration seen by
 * [ChatActivity] and the pixel widths of its main views. Read with `adb logcat -s RotationDiag`.
 */
object RotationDiag {
    private const val TAG = "RotationDiag"

    @Suppress("DEPRECATION")
    fun logConfiguration(activity: Activity, stage: String, savedInstanceState: Bundle?, paneEnabled: Boolean?) {
        val cfg = activity.resources.configuration
        val calculator = WindowMetricsCalculator.getOrCreate()
        val current = calculator.computeCurrentWindowMetrics(activity).bounds
        val maximum = calculator.computeMaximumWindowMetrics(activity).bounds
        val realSize = Point()
        activity.windowManager.defaultDisplay.getRealSize(realSize)
        Log.d(
            TAG,
            "$stage activity=${System.identityHashCode(activity)} savedState=${savedInstanceState != null} " +
                "orientation=${cfg.orientation} screenWidthDp=${cfg.screenWidthDp} " +
                "screenHeightDp=${cfg.screenHeightDp} smallestWidthDp=${cfg.smallestScreenWidthDp} " +
                "density=${cfg.densityDpi} currentBounds=$current maximumBounds=$maximum " +
                "realSize=${realSize.x}x${realSize.y} multiWindow=${activity.isInMultiWindowMode} " +
                "paneEnabled=$paneEnabled"
        )
        // Configuration.toString() contains the window configuration (bounds, app bounds, windowing mode) on API 30+
        Log.d(TAG, "$stage configuration=$cfg")
    }

    /** Logs the display cutout the window gets: with the default cutout mode the window is shifted away from it. */
    fun watchCutout(name: String, view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val cutout = insets.displayCutout
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            Log.d(
                TAG,
                "insets $name cutoutRects=${cutout?.boundingRects} safe=" +
                    "${cutout?.safeInsetLeft},${cutout?.safeInsetTop},${cutout?.safeInsetRight}," +
                    "${cutout?.safeInsetBottom} systemBars=$bars " +
                    "viewLocation=${IntArray(2).also { v.getLocationOnScreen(it) }.toList()} " +
                    "viewSize=${v.width}x${v.height}"
            )
            ViewCompat.onApplyWindowInsets(v, insets)
        }
    }

    fun watchWidth(name: String, view: View) {
        view.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                Log.d(
                    TAG,
                    "layout $name width=${right - left}px height=${bottom - top}px " +
                        "(was ${oldRight - oldLeft}x${oldBottom - oldTop}) measuredWidth=${v.measuredWidth}"
                )
            }
        }
    }
}
