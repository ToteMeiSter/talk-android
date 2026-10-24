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
import com.nextcloud.talk.models.json.capabilities.CapabilitiesDto
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.signaling.SignalingMessageReceiver
import com.nextcloud.talk.signaling.SignalingMessageSender
import com.nextcloud.talk.webrtc.PublisherRejoinPolicy
import com.nextcloud.talk.webrtc.WebSocketInstance
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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
        val spreed: SpreedCapabilityDto = mock()
        whenever(spreed.features).thenReturn(listOf("signaling-v3"))
        val capabilities: CapabilitiesDto = mock()
        whenever(capabilities.spreedCapability).thenReturn(spreed)
        whenever(user.capabilities).thenReturn(capabilities)
        setField("conversationUser", user)
        setField("roomToken", "abc123")
        setField("baseUrl", "https://test.example.com")
        activity.logger = mock()
        setField("ncApi", ncApi)
        setField("webSocketClient", webSocketClient)
        val callViewModel: CallViewModel = mock()
        whenever(callViewModel.participants).thenReturn(MutableStateFlow(emptyList()))
        setField("callViewModel", callViewModel)
        setField("signalingMessageSender", mock<SignalingMessageSender>())
        setField("signalingMessageReceiver", mock<SignalingMessageReceiver>())
        setField("isCallSetUp", true)
        setField("handler", mock<Handler>())
        setField("publisherRejoinPolicy", PublisherRejoinPolicy { SystemClock.uptimeMillis() })
        whenever(ncApi.leaveCall(anyOrNull(), any(), anyOrNull())).thenReturn(Observable.never<GenericOverall>())
        // the start of a join (initiateCall) only asks for the signaling settings: never answered here
        whenever(ncApi.getSignalingSettings(anyOrNull(), any())).thenReturn(Observable.never())
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()
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

    @Test
    fun aHelloAfterNoAnswerRefreshesTheSenderButDoesNotJoinOrChangeTheState() {
        whenever(webSocketClient.hasMCU()).thenReturn(false)
        invoke("setCallState", CallStatus.CALLING_TIMEOUT)
        assertNull(field("messageSender"))

        sendHello(WebSocketCommunicationEvent("hello", hashMapOf("version" to "1")))

        assertNotNull("\"call again\" reuses the sender of the reconnected signaling", field("messageSender"))
        assertEquals(CallStatus.CALLING_TIMEOUT, status())
        verify(webSocketClient, never()).clearResumeId()
        verify(ncApi, never()).getSignalingSettings(anyOrNull(), any())
        verify(ncApi, never()).joinCall(anyOrNull(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun aJoinAnsweredAfterNoAnswerLeavesTheCallAgainAndDoesNotAskForTheRoom() {
        val join = PublishSubject.create<GenericOverall>()
        whenever(ncApi.joinCall(anyOrNull(), any(), any(), any(), anyOrNull())).thenReturn(join)
        prepareForPerformCall()
        invoke("setCallState", CallStatus.PUBLISHER_FAILED)
        invoke("performCall")

        invoke("setCallState", CallStatus.CALLING_TIMEOUT)
        join.onNext(GenericOverall())

        verify(ncApi).leaveCall(anyOrNull(), any(), anyOrNull())
        verify(ncApi, never()).getRoom(anyOrNull(), any())
        assertEquals(CallStatus.CALLING_TIMEOUT, status())
    }

    @Test
    fun aJoinThatFailsAfterNoAnswerKeepsTheScreenOfCallAgain() {
        val join = PublishSubject.create<GenericOverall>()
        whenever(ncApi.joinCall(anyOrNull(), any(), any(), any(), anyOrNull())).thenReturn(join)
        prepareForPerformCall()
        invoke("setCallState", CallStatus.PUBLISHER_FAILED)
        invoke("performCall")

        invoke("setCallState", CallStatus.CALLING_TIMEOUT)
        join.onError(java.io.IOException("late"))

        assertEquals(CallStatus.CALLING_TIMEOUT, status())
        assertTrue("the activity is not finished", !activity.isFinishing)
        verify(ncApi, never()).getRoom(anyOrNull(), any())
    }

    private fun prepareForPerformCall() {
        setField("messageSender", com.nextcloud.talk.call.MessageSenderNoMcu(mock<SignalingMessageSender>(), emptySet(), mutableListOf()))
    }

    private fun sendHello(hello: WebSocketCommunicationEvent) {
        activity.onMessageEvent(hello)
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
