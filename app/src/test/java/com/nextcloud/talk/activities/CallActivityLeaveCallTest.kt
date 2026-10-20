/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import android.app.Application
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.models.json.generic.GenericOverall
import io.reactivex.Observable
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hanging up with "end call for everyone" must not end the call of the others unless this activity instance
 * has requested the join itself (e.g. the button is pressed before POST /call, or the activity was recreated).
 * The DELETE /call itself is always sent, otherwise a recreated activity would leave a ghost participant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallActivityLeaveCallTest {

    private val ncApi: NcApi = mock()
    private lateinit var activity: CallActivity

    @Before
    fun setUp() {
        whenever(ncApi.leaveCall(anyOrNull(), any(), anyOrNull())).thenReturn(Observable.never<GenericOverall>())
        activity = Robolectric.buildActivity(CallActivity::class.java).get()
        setField("ncApi", ncApi)
        setField("baseUrl", "https://test.example.com")
        setField("roomToken", "testRoom")
    }

    @Test
    fun endForAllIsDroppedWhenJoinWasNeverRequested() {
        setField("callJoinRequested", false)

        invokeSendLeaveCall(endCall = true)

        verify(ncApi).leaveCall(anyOrNull(), any(), isNull())
    }

    @Test
    fun endForAllIsKeptAfterJoinWasRequested() {
        setField("callJoinRequested", true)

        invokeSendLeaveCall(endCall = true)

        verify(ncApi).leaveCall(anyOrNull(), any(), eq(true))
    }

    @Test
    fun leaveWithoutEndForAllIsSentEvenWithoutJoin() {
        setField("callJoinRequested", false)

        invokeSendLeaveCall(endCall = null)

        verify(ncApi).leaveCall(anyOrNull(), any(), isNull())
    }

    private fun invokeSendLeaveCall(endCall: Boolean?) {
        val method = CallActivity::class.java.getDeclaredMethod(
            "sendLeaveCall",
            Int::class.javaPrimitiveType,
            Boolean::class.javaObjectType
        )
        method.isAccessible = true
        method.invoke(activity, 4, endCall)
    }

    private fun setField(fieldName: String, value: Any?) {
        var current: Class<*>? = CallActivity::class.java
        while (current != null) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(activity, value)
                return
            } catch (e: NoSuchFieldException) {
                current = current.superclass
            }
        }
        error("Field '$fieldName' not found in CallActivity hierarchy")
    }
}
