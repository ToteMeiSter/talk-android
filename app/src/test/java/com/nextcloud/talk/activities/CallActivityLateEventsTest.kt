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
import com.nextcloud.talk.events.WebSocketCommunicationEvent
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.signaling.SignalingMessageSender
import com.nextcloud.talk.webrtc.PublisherRejoinPolicy
import com.nextcloud.talk.webrtc.WebSocketInstance
import io.reactivex.Observable
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

/**
 * Events that arrive after the dial ended ("no answer") or while a rejoin is under way: only "call again" may leave
 * the screen of "no answer", and a rejoin leaves the call on the server only when the other side was in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallActivityLateEventsTest {

    private lateinit var activity: CallActivity
    private val ncApi: NcApi = mock()
    private val webSocketClient: WebSocketInstance = mock()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(CallActivity::class.java).get()
        val user: User = mock()
        whenever(user.id).thenReturn(7L)
        whenever(user.hasSpreedFeatureCapability(any())).thenReturn(true)
        setField("conversationUser", user)
        setField("roomToken", "abc123")
        setField("baseUrl", "https://test.example.com")
        setField("ncApi", ncApi)
        setField("webSocketClient", webSocketClient)
        val callViewModel: CallViewModel = mock()
        whenever(callViewModel.participants).thenReturn(MutableStateFlow(emptyList()))
        setField("callViewModel", callViewModel)
        setField("signalingMessageSender", mock<SignalingMessageSender>())
        setField("isCallSetUp", true)
        setField("handler", mock<Handler>())
        setField("publisherRejoinPolicy", PublisherRejoinPolicy { SystemClock.uptimeMillis() })
        whenever(ncApi.leaveCall(anyOrNull(), any(), anyOrNull())).thenReturn(Observable.never<GenericOverall>())
    }

    @Test
    fun noAnswerEndsTheDialInThePublisherFailedStateAndTheGiveUpTimerKeepsTheScreen() {
        invoke("setCallState", CallStatus.JOINED)
        idle(15)
        (field("publisherRejoinPolicy") as PublisherRejoinPolicy).onPublisherFailed()
        // the real rejoin: arms the give-up timer (deadline t=70 s) and the pause before the next join
        invoke("rejoinCallWithNewSession", 60_000L)

        idle(40)
        assertEquals(CallStatus.CALLING_TIMEOUT, status())
        idle(30)

        assertEquals(CallStatus.CALLING_TIMEOUT, status())
        assertNull("no \"could not connect\" toast on the screen of \"call again\"", ShadowToast.getLatestToast())
    }

    @Test
    fun lateEventsAfterNoAnswerDoNotDialAgain() {
        invoke("setCallState", CallStatus.CALLING_TIMEOUT)

        // a late "hello" of the rejoin chain, a late join, a late ICE failure
        val hello = WebSocketCommunicationEvent("hello", hashMapOf("version" to "1"))
        sendHello(hello)
        invoke("performCall")
        invoke("rejoinAfterFailure")

        assertEquals(CallStatus.CALLING_TIMEOUT, status())
        verify(ncApi, never()).joinCall(anyOrNull(), any(), any(), any(), anyOrNull())
        verify(webSocketClient, never()).clearResumeId()
    }

    @Test
    fun aRejoinLeavesTheCallOnTheServerOnlyAfterTheOtherSideWasInIt() {
        val policy = field("publisherRejoinPolicy") as PublisherRejoinPolicy
        invoke("setCallState", CallStatus.JOINED)

        invoke("rejoinCallWithNewSession", 5_000L)
        verify(ncApi, never()).leaveCall(anyOrNull(), any(), anyOrNull())

        policy.onPeerJoined()
        invoke("setCallState", CallStatus.JOINED)
        invoke("rejoinCallWithNewSession", 5_000L)
        verify(ncApi).leaveCall(anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun aHelloWithANewSessionWhileReconnectingLeavesTheCallOnlyAfterTheOtherSideWasInIt() {
        val policy = field("publisherRejoinPolicy") as PublisherRejoinPolicy
        whenever(webSocketClient.hasMCU()).thenReturn(false)
        val hello = WebSocketCommunicationEvent("hello", hashMapOf("version" to "1"))

        invoke("setCallState", CallStatus.RECONNECTING)
        sendHello(hello)
        verify(ncApi, never()).leaveCall(anyOrNull(), any(), anyOrNull())

        policy.onPeerJoined()
        invoke("setCallState", CallStatus.RECONNECTING)
        sendHello(hello)
        verify(ncApi).leaveCall(anyOrNull(), any(), anyOrNull())
    }

    // After the leave the branch starts the join (initiateCall), which needs the whole call screen: not set up here.
    private fun sendHello(hello: WebSocketCommunicationEvent) {
        runCatching { activity.onMessageEvent(hello) }
    }

    private fun status(): CallStatus? = field("currentCallStatus") as CallStatus?

    private fun idle(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
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

    private fun setField(name: String, value: Any?) {
        CallActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
