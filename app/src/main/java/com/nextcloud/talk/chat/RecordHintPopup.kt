/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Krainov Gleb <krajnov.g@kontentplus.ru>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.color.MaterialColors
import com.nextcloud.talk.R

/**
 * Hint bubble above the record button, with an arrow pointing at the button. It does not take touches, so the
 * button stays usable while the hint is visible.
 */
class RecordHintPopup(private val anchor: View) {

    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }
    private var popup: PopupWindow? = null

    fun show(@StringRes messageRes: Int) {
        dismiss()
        if (!anchor.isAttachedToWindow) return
        val context = anchor.context
        val content = LayoutInflater.from(context).inflate(R.layout.view_record_hint, null)
        val bubbleColor = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceInverse)
        val textColor = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurfaceInverse)
        val text = content.findViewById<TextView>(R.id.recordHintText)
        text.setText(messageRes)
        text.setTextColor(textColor)
        (text.background.mutate() as GradientDrawable).setColor(bubbleColor)
        val arrow = content.findViewById<ImageView>(R.id.recordHintArrow)
        arrow.imageTintList = ColorStateList.valueOf(bubbleColor)

        val metrics = context.resources.displayMetrics
        val margin = (SCREEN_MARGIN_DP * metrics.density).toInt()
        val windowWidth = anchor.rootView.width
        content.measure(
            View.MeasureSpec.makeMeasureSpec(windowWidth - 2 * margin, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        // showAtLocation positions relative to the window of the anchor, so the anchor is measured in its window:
        // the screen position is shifted by the status bar, a display cutout or the pane of a split layout
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val placement = hintPlacement(
            anchorLeft = location[0],
            anchorTop = location[1],
            anchorWidth = anchor.width,
            windowWidth = windowWidth,
            hintWidth = content.measuredWidth,
            hintHeight = content.measuredHeight,
            margin = margin,
            gap = (GAP_DP * metrics.density).toInt(),
            arrowWidth = (ARROW_WIDTH_DP * metrics.density).toInt()
        )

        val arrowParams = arrow.layoutParams as LinearLayout.LayoutParams
        arrowParams.leftMargin = placement.arrowLeftMargin
        arrow.layoutParams = arrowParams

        popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
            .apply {
                isTouchable = false
                isFocusable = false
                isOutsideTouchable = false
                showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, placement.x, placement.y)
            }
        handler.postDelayed(dismissRunnable, SHOW_DURATION_MS)
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        popup?.dismiss()
        popup = null
    }

    /**
     * Where the hint goes, in the coordinates of the window of the anchor.
     */
    data class Placement(val x: Int, val y: Int, val arrowLeftMargin: Int)

    companion object {
        const val SHOW_DURATION_MS = 1500L
        private const val SCREEN_MARGIN_DP = 8
        private const val GAP_DP = 4
        private const val ARROW_WIDTH_DP = 16

        /**
         * The hint sits above the anchor with [gap] between them, centred on it and kept [margin] away from the edges
         * of the window; the arrow points at the centre of the anchor.
         */
        @Suppress("LongParameterList")
        fun hintPlacement(
            anchorLeft: Int,
            anchorTop: Int,
            anchorWidth: Int,
            windowWidth: Int,
            hintWidth: Int,
            hintHeight: Int,
            margin: Int,
            gap: Int,
            arrowWidth: Int
        ): Placement {
            val anchorCenterX = anchorLeft + anchorWidth / 2
            val x = (anchorCenterX - hintWidth / 2).coerceIn(margin, maxOf(margin, windowWidth - margin - hintWidth))
            val y = anchorTop - hintHeight - gap
            val arrow = (anchorCenterX - x - arrowWidth / 2).coerceIn(0, maxOf(0, hintWidth - arrowWidth))
            return Placement(x, y, arrow)
        }
    }
}
