/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A room join that fails after the dial ended ("no answer") must not start a new join or leave the screen of
 * "call again".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallActivityRoomJoinFailedTest {

    private lateinit var activity: CallActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(CallActivity::class.java).get()
        activity.logger = mock()
    }

    @Test
    fun aLateRoomJoinFailureAfterNoAnswerNeitherJoinsNorLeavesTheScreen() {
        invoke("setCallState", CallStatus.CALLING_TIMEOUT)

        invoke("handleRoomJoinFailed")

        assertEquals(CallStatus.CALLING_TIMEOUT, field("currentCallStatus"))
        assertEquals("no new join was started", false, field("joinRoomInitiated"))
        assertEquals("no refresh of the room session was counted", 0, field("roomJoinRefreshes"))
        assertNull("no \"join failed\" toast on the screen of \"call again\"", ShadowToast.getLatestToast())
        assertFalse("the screen stays", activity.isFinishing)
    }

    private fun invoke(name: String, vararg args: Any) {
        val method = CallActivity::class.java.declaredMethods.first {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        method.invoke(activity, *args)
    }

    private fun field(name: String): Any? =
        CallActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
