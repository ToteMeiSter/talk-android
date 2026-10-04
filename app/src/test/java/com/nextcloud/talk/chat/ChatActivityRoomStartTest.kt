/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat

import android.app.Application
import android.content.Intent
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

/**
 * Opening the chat starts the room session. Its first step, handleIntent, needs the user before any room session
 * (and so any ChatViewModel) exists. It must use the user the activity was created with and not fail with
 * "no room is shown yet".
 *
 * A full onCreate is not run: it needs Dagger (NextcloudTalkApplication.sharedApplication.componentApplication),
 * the stored user, view binding and dozens of collaborators. As in CallActivityAddParticipantTest the activity is
 * built without onCreate and the fields are set by reflection. startRoomSession is not run either: after
 * handleIntent it needs the layout and the observers of the whole chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ChatActivityRoomStartTest {

    private val user = User(id = 1L, userId = "alice", username = "alice", baseUrl = "https://example.com", token = "t")
    private lateinit var activity: ChatActivity

    @Before
    fun setUp() {
        val intent = Intent().putExtra(KEY_ROOM_TOKEN, "room1")
        activity = Robolectric.buildActivity(ChatActivity::class.java, intent).get()
        setField("initialUser", user)
    }

    @Test
    fun handleIntentBeforeRoomSessionUsesInitialUser() {
        invokeHandleIntent(activity.intent)

        assertSame(user, activity.conversationUser)
        assertEquals(ApiUtils.getCredentials(user.username, user.token), activity.credentials)
        assertNotNull(activity.credentials)
    }

    private fun setField(name: String, value: Any?) {
        val field = ChatActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(activity, value)
    }

    private fun invokeHandleIntent(intent: Intent) {
        val method = ChatActivity::class.java.getDeclaredMethod("handleIntent", Intent::class.java)
        method.isAccessible = true
        try {
            method.invoke(activity, intent)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
