/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.ui

import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A ComposeView measured with an UNSPECIFIED width passes an infinite width constraint to its content,
 * and the TopAppBar of the chat crashes with "Size(2147483647 x 200) is out of range".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ChatConversationListPaneLayoutTest {

    private class RecordingView(context: Context) : View(context) {
        val widthModes = mutableListOf<Int>()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            widthModes.add(MeasureSpec.getMode(widthMeasureSpec))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    @Test
    fun panesAreNeverMeasuredWithUnspecifiedWidth() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pane = RecordingView(context)
        val chatRoot = RecordingView(context)
        val layout = createConversationListPaneLayout(context, pane, chatRoot)

        layout.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )

        assertFalse(pane.widthModes.contains(View.MeasureSpec.UNSPECIFIED))
        assertFalse(chatRoot.widthModes.contains(View.MeasureSpec.UNSPECIFIED))
        assertEquals(WIDTH * 2 / 5, pane.measuredWidth)
        assertEquals(WIDTH * 3 / 5, chatRoot.measuredWidth)
        assertEquals(HEIGHT, chatRoot.measuredHeight)
    }

    companion object {
        private const val WIDTH = 1000
        private const val HEIGHT = 800
    }
}
