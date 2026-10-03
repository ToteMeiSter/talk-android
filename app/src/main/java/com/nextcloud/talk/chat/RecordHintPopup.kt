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
        content.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels - 2 * margin, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = content.measuredWidth
        val height = content.measuredHeight

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val anchorCenterX = location[0] + anchor.width / 2
        val x = (anchorCenterX - width / 2).coerceIn(margin, maxOf(margin, metrics.widthPixels - margin - width))
        val y = location[1] - height - (GAP_DP * metrics.density).toInt()

        val arrowParams = arrow.layoutParams as LinearLayout.LayoutParams
        val arrowMax = width - (ARROW_WIDTH_DP * metrics.density).toInt()
        arrowParams.leftMargin = (anchorCenterX - x - (ARROW_WIDTH_DP * metrics.density).toInt() / 2)
            .coerceIn(0, maxOf(0, arrowMax))
        arrow.layoutParams = arrowParams

        popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
            .apply {
                isTouchable = false
                isFocusable = false
                isOutsideTouchable = false
                showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
            }
        handler.postDelayed(dismissRunnable, SHOW_DURATION_MS)
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        popup?.dismiss()
        popup = null
    }

    companion object {
        const val SHOW_DURATION_MS = 1500L
        private const val SCREEN_MARGIN_DP = 8
        private const val GAP_DP = 4
        private const val ARROW_WIDTH_DP = 16
    }
}
