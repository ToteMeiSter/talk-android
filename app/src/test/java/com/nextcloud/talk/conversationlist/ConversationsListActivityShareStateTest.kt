/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.conversationlist

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ConversationsListActivityShareStateTest {

    private lateinit var activity: ConversationsListActivity

    @Before
    fun setUp() {
        // not created: onCreate needs Dagger; only the share state of the instance is used
        activity = Robolectric.buildActivity(ConversationsListActivity::class.java).get()
    }

    @Test
    fun `text of an earlier share does not survive into the next collect`() {
        activity.intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "first share")
        }
        call("collectDataFromIntent")
        assertEquals("first share", getField("textToPaste"))

        // the list and the chat are side by side: no onResume, the next tap sees an intent without action
        call("clearIntentAction")
        call("collectDataFromIntent")

        assertEquals("", getField("textToPaste"))
    }

    @Test
    fun `clearing the intent action leaves the share mode`() {
        activity.intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        shareScreenState().value = true

        call("clearIntentAction")

        assertFalse(shareScreenState().value)
    }

    private fun shareScreenState(): MutableStateFlow<Boolean> {
        @Suppress("UNCHECKED_CAST")
        return getField("showShareToScreenState") as MutableStateFlow<Boolean>
    }

    private fun call(name: String) {
        val method = ConversationsListActivity::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(activity)
    }

    private fun getField(name: String): Any? {
        val field = ConversationsListActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(activity)
    }
}
