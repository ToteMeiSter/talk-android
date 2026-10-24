/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.jobs.CallPushPayload
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.webrtc.PublisherRejoinPolicy
import io.reactivex.Observable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The wiring of the call rules in [CallActivity]: the ring time, the give-up deadline, the call held for the
 * "own call" filter and the leave of a rejoin. The activity is built without `onCreate` (no Dagger, no WebRTC); the
 * handler of the UI states is a mock, so only the timers on the own rejoin handler run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallActivityCallRulesTest {

    private lateinit var activity: CallActivity
    private val ncApi: NcApi = mock()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(CallActivity::class.java).get()
        val user: User = mock()
        whenever(user.id).thenReturn(USER_ID)
        whenever(user.hasSpreedFeatureCapability(any())).thenReturn(true)
        setField("conversationUser", user)
        setField("roomToken", TOKEN)
        setField("baseUrl", "https://test.example.com")
        setField("ncApi", ncApi)
        setField("isCallSetUp", true)
        // the UI states are not under test: their handler does nothing
        setField("handler", mock<Handler>())
        // the policy counts with the clock of the looper, which the test moves
        setField("publisherRejoinPolicy", PublisherRejoinPolicy { SystemClock.uptimeMillis() })
    }

    @After
    fun tearDown() {
        CallActivity.activeCall = null
    }

    @Test
    fun theRingTimeEndsAtFiftyFiveSecondsAlsoWhileTheJoinIsFailing() {
        setCallState(CallStatus.JOINED)
        idle(50)
        // the failure at t=50 s: no JOINED comes any more
        setCallState(CallStatus.PUBLISHER_FAILED)
        idle(4)
        assertEquals(CallStatus.PUBLISHER_FAILED, status())

        idle(2)

        assertEquals(CallStatus.CALLING_TIMEOUT, status())
    }

    @Test
    fun aRejoinDoesNotStartTheRingTimeAgain() {
        setCallState(CallStatus.JOINED)
        idle(30)
        setCallState(CallStatus.PUBLISHER_FAILED)
        idle(10)
        setCallState(CallStatus.JOINED)
        idle(14)
        assertEquals(CallStatus.JOINED, status())

        idle(2)

        assertEquals(CallStatus.CALLING_TIMEOUT, status())
    }

    @Test
    fun theRingTimeStopsWhenTheOtherSideIsInTheCall() {
        setCallState(CallStatus.JOINED)
        idle(20)
        setCallState(CallStatus.IN_CONVERSATION)

        idle(120)

        assertEquals(CallStatus.IN_CONVERSATION, status())
    }

    @Test
    fun theCallIsHeldWhileJoinedAndReleasedWhenTheRingTimeRunsOutOrTheCallEnds() {
        setCallState(CallStatus.JOINED)
        assertEquals(CallPushPayload.ActiveCall(USER_ID, TOKEN), CallActivity.activeCall)

        setCallState(CallStatus.CALLING_TIMEOUT)
        assertNull(CallActivity.activeCall)

        // "call again" puts the call back
        setCallState(CallStatus.RECONNECTING)
        assertNotNull(CallActivity.activeCall)

        setCallState(CallStatus.LEAVING)
        assertNull(CallActivity.activeCall)
    }

    @Test
    fun aRejoinAloneInTheCallDoesNotLeaveItOnTheServer() {
        whenever(ncApi.leaveCall(anyOrNull(), any(), anyOrNull())).thenReturn(Observable.never<GenericOverall>())

        hangupNetworkCalls(leaveCall = false)
        verify(ncApi, never()).leaveCall(anyOrNull(), any(), anyOrNull())

        hangupNetworkCalls(leaveCall = true)
        verify(ncApi).leaveCall(anyOrNull(), any(), anyOrNull())
    }

    private fun status(): CallStatus? = field("currentCallStatus") as CallStatus?

    private fun idle(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
    }

    private fun setCallState(callState: CallStatus) {
        val method = CallActivity::class.java.getDeclaredMethod("setCallState", CallStatus::class.java)
        method.isAccessible = true
        method.invoke(activity, callState)
    }

    private fun hangupNetworkCalls(leaveCall: Boolean) {
        val method = CallActivity::class.java.getDeclaredMethod(
            "hangupNetworkCalls",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(activity, false, false, 0L, leaveCall)
    }

    private fun field(name: String): Any? =
        CallActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun setField(name: String, value: Any?) {
        CallActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private companion object {
        const val USER_ID = 7L
        const val TOKEN = "abc123"
    }
}
