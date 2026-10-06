/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.models.json.signaling.NCSignalingMessageDto
import com.nextcloud.talk.signaling.SignalingMessageReceiver
import com.nextcloud.talk.signaling.SignalingMessageSender
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionDependencies
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

@Suppress("TooManyFunctions")
class PeerConnectionWrapperIceRestartTest {
    private val peerConnection: PeerConnection = Mockito.mock(PeerConnection::class.java)
    private val factory: PeerConnectionFactory = Mockito.mock(PeerConnectionFactory::class.java)
    private val receiver: SignalingMessageReceiver = Mockito.mock(SignalingMessageReceiver::class.java)
    private val sender: SignalingMessageSender = Mockito.mock(SignalingMessageSender::class.java)
    private val constraints = MediaConstraints()
    private val offer = SessionDescription(SessionDescription.Type.OFFER, "v=0")
    private val answer = SessionDescription(SessionDescription.Type.ANSWER, "v=0")
    private var observer: PeerConnection.Observer? = null

    @Before
    fun setUp() {
        PeerConnectionWrapper.restartAfterRollbackJitterMs = 0
        Mockito.`when`(
            factory.createPeerConnection(
                any(PeerConnection.RTCConfiguration::class.java),
                any(PeerConnectionDependencies::class.java)
            )
        ).thenAnswer {
            // the observer is package-private in PeerConnectionDependencies
            observer = PeerConnectionDependencies::class.java.getDeclaredMethod("getObserver")
                .apply { isAccessible = true }
                .invoke(it.getArgument<PeerConnectionDependencies>(1)) as PeerConnection.Observer
            peerConnection
        }
        Mockito.`when`(peerConnection.createDataChannel(anyString(), any()))
            .thenReturn(Mockito.mock(DataChannel::class.java))
    }

    @After
    fun tearDown() {
        PeerConnectionWrapper.disconnectedRestartDelayMs = 5000
        PeerConnectionWrapper.unansweredOfferTimeoutMs = 10000
        PeerConnectionWrapper.restartAfterRollbackJitterMs = 2000
    }

    // "remote-session" < "zzz-local-session": the local side creates the first offer
    private fun wrapper(
        hasMCU: Boolean = false,
        type: String = "video",
        createdFromOffer: Boolean = false
    ): PeerConnectionWrapper =
        PeerConnectionWrapper(
            factory,
            ArrayList<PeerConnection.IceServer>(),
            constraints,
            "remote-session",
            "zzz-local-session",
            null,
            false,
            hasMCU,
            type,
            receiver,
            sender,
            createdFromOffer
        )

    private fun negotiated(state: SignalingState = SignalingState.STABLE, local: SessionDescription = offer) {
        Mockito.`when`(peerConnection.signalingState()).thenReturn(state)
        Mockito.`when`(peerConnection.localDescription).thenReturn(local)
        Mockito.`when`(peerConnection.remoteDescription).thenReturn(answer)
    }

    private fun PeerConnectionWrapper.restartAndWait() {
        restartIce()
        PeerConnectionWrapper.waitForTimerIdle()
    }

    private fun iceState(state: IceConnectionState) {
        observer!!.onIceConnectionChange(state)
        PeerConnectionWrapper.waitForTimerIdle()
    }

    private fun webRtcListener(type: String = "video"): SignalingMessageReceiver.WebRtcMessageListener {
        val listener = ArgumentCaptor.forClass(SignalingMessageReceiver.WebRtcMessageListener::class.java)
        verify(receiver).addListener(listener.capture(), eq("remote-session"), eq(type))
        return listener.value
    }

    private fun lastCreateOfferObserver(): SdpObserver {
        val captor = ArgumentCaptor.forClass(SdpObserver::class.java)
        verify(peerConnection, Mockito.atLeastOnce()).createOffer(captor.capture(), any(MediaConstraints::class.java))
        return captor.value
    }

    private fun remoteDescriptionObserver(times: Int = 1): SdpObserver {
        val captor = ArgumentCaptor.forClass(SdpObserver::class.java)
        verify(peerConnection, times(times)).setRemoteDescription(captor.capture(), any(SessionDescription::class.java))
        return captor.value
    }

    private fun sentMessages(): List<NCSignalingMessageDto> {
        val captor = ArgumentCaptor.forClass(NCSignalingMessageDto::class.java)
        verify(sender, Mockito.atLeast(0)).send(captor.capture())
        return captor.allValues
    }

    private fun isRollback() = argThat<SessionDescription> { it.type == SessionDescription.Type.ROLLBACK }

    private fun verifyNoRestart() = verify(peerConnection, never()).restartIce()

    // restartIce

    @Test
    fun restartIceCreatesOfferWithRestartedIce_whenConnectionIsNegotiated() {
        val wrapper = wrapper()
        negotiated()
        Mockito.clearInvocations(peerConnection)

        wrapper.restartAndWait()

        // restartIce() has to come first, otherwise the offer keeps the old ICE credentials
        val order = inOrder(peerConnection)
        order.verify(peerConnection).restartIce()
        order.verify(peerConnection).createOffer(any(SdpObserver::class.java), eq(constraints))
    }

    @Test
    fun restartIceDoesNothingWithMcu() {
        val wrapper = wrapper(hasMCU = true)
        negotiated()
        Mockito.clearInvocations(peerConnection)

        wrapper.restartAndWait()

        verifyNoRestart()
    }

    @Test
    fun restartIceDoesNothingForScreenConnection() {
        val wrapper = wrapper(type = "screen")
        negotiated()
        Mockito.clearInvocations(peerConnection)

        wrapper.restartAndWait()

        verifyNoRestart()
    }

    @Test
    fun restartIceDoesNothingWhileAnotherNegotiationIsInProgress() {
        val wrapper = wrapper()
        negotiated(SignalingState.HAVE_LOCAL_OFFER)
        Mockito.clearInvocations(peerConnection)

        wrapper.restartAndWait()

        verifyNoRestart()
    }

    @Test
    fun restartIceDoesNothingBeforeFirstNegotiationIsFinished() {
        val wrapper = wrapper()
        Mockito.`when`(peerConnection.signalingState()).thenReturn(SignalingState.STABLE)
        Mockito.clearInvocations(peerConnection)

        wrapper.restartAndWait()

        verifyNoRestart()
    }

    // sid

    @Test
    fun restartOfferCarriesSidOfTheRemoteOffer_whenRemoteStartedTheConnection() {
        val wrapper = wrapper()
        webRtcListener().onOffer("v=0", "nick", "remote-sid")
        negotiated()

        wrapper.restartAndWait()
        lastCreateOfferObserver().onCreateSuccess(offer)

        assertEquals("remote-sid", sentMessages().last { it.type == "offer" }.sid)
    }

    @Test
    fun restartOfferCarriesSameSidAsFirstOffer_whenLocalSideStartedTheConnection() {
        val wrapper = wrapper()
        lastCreateOfferObserver().onCreateSuccess(offer)
        negotiated()

        wrapper.restartAndWait()
        lastCreateOfferObserver().onCreateSuccess(offer)

        val offers = sentMessages().filter { it.type == "offer" }
        assertEquals(2, offers.size)
        assertNotNull(offers[0].sid)
        assertEquals(offers[0].sid, offers[1].sid)
    }

    @Test
    fun requestOfferDoesNotCarryOwnSid() {
        wrapper(hasMCU = true)

        assertNull(sentMessages().single { it.type == "requestoffer" }.sid)
    }

    @Test
    fun mcuVideoConnectionRequestsAnOffer_butNotWhenCreatedFromAnOffer() {
        wrapper(hasMCU = true)
        assertEquals(1, sentMessages().count { it.type == "requestoffer" })
        Mockito.clearInvocations(sender)

        wrapper(hasMCU = true, createdFromOffer = true)

        assertEquals(0, sentMessages().count { it.type == "requestoffer" })
    }

    @Test
    fun mcuSubscriptionTakesSidOfTheOfferForAnswerAndCandidates() {
        wrapper(hasMCU = true)
        webRtcListener().onOffer("v=0", null, "janus-handle-sid")
        val sdpObserver = remoteDescriptionObserver()

        sdpObserver.onCreateSuccess(answer)
        observer!!.onIceCandidate(IceCandidate("0", 0, "candidate:1"))

        val messages = sentMessages().filter { it.type != "requestoffer" }
        assertEquals(listOf("answer", "candidate"), messages.map { it.type })
        assertEquals(listOf("janus-handle-sid", "janus-handle-sid"), messages.map { it.sid })
    }

    @Test
    fun answerAndCandidateOfOtherConnectionAreIgnored_butWithoutSidOrWithSameSidAreApplied() {
        wrapper()
        lastCreateOfferObserver().onCreateSuccess(offer)
        val sid = sentMessages().single { it.type == "offer" }.sid
        negotiated(SignalingState.HAVE_LOCAL_OFFER)
        val listener = webRtcListener()

        listener.onAnswer("v=0", null, "other-sid")
        listener.onCandidate("0", 0, "candidate:1", "other-sid")
        verify(peerConnection, never()).setRemoteDescription(any(SdpObserver::class.java), any())
        verify(peerConnection, never()).addIceCandidate(any())

        listener.onAnswer("v=0", null, null)
        listener.onAnswer("v=0", null, "")
        listener.onAnswer("v=0", null, sid)
        listener.onCandidate("0", 0, "candidate:1", sid)
        verify(peerConnection, times(3)).setRemoteDescription(any(SdpObserver::class.java), any())
        verify(peerConnection).addIceCandidate(any())
    }

    @Test
    fun offerWithOtherSidReplacesOnlyANegotiatedConnection() {
        val wrapper = wrapper()
        // not negotiated yet (no remote description): the offer just sets the sid
        assertFalse(wrapper.isReplacedByOffer("new-sid"))

        negotiated()
        assertTrue(wrapper.isReplacedByOffer("new-sid"))
        assertFalse(wrapper.isReplacedByOffer(null))
        assertFalse(wrapper.isReplacedByOffer(""))
    }

    @Test
    fun offerWithOtherSidIsNotAppliedToANegotiatedConnection() {
        wrapper()
        webRtcListener().onOffer("v=0", null, "first-sid")
        negotiated()
        Mockito.clearInvocations(peerConnection)

        webRtcListener().onOffer("v=0", null, "other-sid")

        verify(peerConnection, never()).setRemoteDescription(any(SdpObserver::class.java), any())
    }

    // offer collision and answers without own offer

    @Test
    fun remoteOfferInHaveLocalOffer_isIgnoredAndKeepsSid() {
        wrapper()
        val createOfferObserver = lastCreateOfferObserver()
        createOfferObserver.onCreateSuccess(offer)
        val ownSid = sentMessages().single { it.type == "offer" }.sid
        Mockito.`when`(peerConnection.signalingState()).thenReturn(SignalingState.HAVE_LOCAL_OFFER)

        webRtcListener().onOffer("v=0", null, "remote-sid")

        verify(peerConnection, never()).setLocalDescription(any(SdpObserver::class.java), isRollback())
        verify(peerConnection, never()).setRemoteDescription(any(SdpObserver::class.java), any())
        createOfferObserver.onCreateSuccess(offer)
        assertEquals(ownSid, sentMessages().last { it.type == "offer" }.sid)
    }

    @Test
    fun answerWithoutOwnOffer_isNotAppliedAndRestartsIce() {
        wrapper()
        negotiated(SignalingState.STABLE, local = answer)
        Mockito.clearInvocations(peerConnection)

        webRtcListener().onAnswer("v=0", null, null)
        PeerConnectionWrapper.waitForTimerIdle()

        verify(peerConnection, never()).setRemoteDescription(any(SdpObserver::class.java), any())
        verify(peerConnection).restartIce()
        verify(peerConnection).createOffer(any(SdpObserver::class.java), eq(constraints))
    }

    @Test
    fun unansweredRestartOfferIsRolledBackAfterTimeout() {
        PeerConnectionWrapper.unansweredOfferTimeoutMs = 50
        val wrapper = wrapper()
        negotiated()
        wrapper.restartAndWait()
        negotiated(SignalingState.HAVE_LOCAL_OFFER)

        verify(peerConnection, timeout(2000)).setLocalDescription(any(SdpObserver::class.java), isRollback())
    }

    @Test
    fun restartOfferAnsweredInTimeIsNotRolledBack() {
        PeerConnectionWrapper.unansweredOfferTimeoutMs = 300
        val wrapper = wrapper()
        negotiated()
        wrapper.restartAndWait()
        negotiated(SignalingState.HAVE_LOCAL_OFFER)
        // the answer arrives before the timeout
        negotiated(SignalingState.STABLE)

        Thread.sleep(600)

        verify(peerConnection, never()).setLocalDescription(any(SdpObserver::class.java), any())
    }

    @Test
    fun iceIsRestartedAgainAfterRollback_whenStillBroken() {
        PeerConnectionWrapper.unansweredOfferTimeoutMs = 50
        wrapper()
        negotiated()
        iceState(IceConnectionState.FAILED)
        verify(peerConnection).restartIce()
        negotiated(SignalingState.HAVE_LOCAL_OFFER)

        val rollbackObserver = ArgumentCaptor.forClass(SdpObserver::class.java)
        verify(peerConnection, timeout(2000)).setLocalDescription(rollbackObserver.capture(), isRollback())
        negotiated()
        rollbackObserver.value.onSetSuccess()

        verify(peerConnection, timeout(2000).times(2)).restartIce()
    }

    @Test
    fun iceIsNotRestartedAgainAfterRollback_whenConnectedAgain() {
        PeerConnectionWrapper.unansweredOfferTimeoutMs = 50
        wrapper()
        negotiated()
        iceState(IceConnectionState.FAILED)
        negotiated(SignalingState.HAVE_LOCAL_OFFER)
        val rollbackObserver = ArgumentCaptor.forClass(SdpObserver::class.java)
        verify(peerConnection, timeout(2000)).setLocalDescription(rollbackObserver.capture(), isRollback())
        iceState(IceConnectionState.CONNECTED)
        negotiated()

        rollbackObserver.value.onSetSuccess()
        Thread.sleep(200)

        verify(peerConnection, times(1)).restartIce()
    }

    // timers

    @Test
    fun timersAreCancelledWhenThePeerConnectionIsRemoved() {
        PeerConnectionWrapper.unansweredOfferTimeoutMs = 100
        PeerConnectionWrapper.disconnectedRestartDelayMs = 100
        val wrapper = wrapper()
        negotiated()
        wrapper.restartAndWait()
        iceState(IceConnectionState.DISCONNECTED)
        Mockito.clearInvocations(peerConnection)

        wrapper.removePeerConnection()
        negotiated(SignalingState.HAVE_LOCAL_OFFER)
        Thread.sleep(400)

        verify(peerConnection, never()).setLocalDescription(any(SdpObserver::class.java), any())
        verifyNoRestart()
    }

    // automatic restart on ICE state

    @Test
    fun iceFailedRestartsIce_whenLastLocalDescriptionIsOffer() {
        wrapper()
        negotiated()
        Mockito.clearInvocations(peerConnection)

        iceState(IceConnectionState.FAILED)

        verify(peerConnection).restartIce()
    }

    @Test
    fun iceFailedDoesNotRestartIce_whenLastLocalDescriptionIsAnswer() {
        wrapper()
        negotiated(local = answer)
        Mockito.clearInvocations(peerConnection)

        iceState(IceConnectionState.FAILED)

        verifyNoRestart()
    }

    @Test
    fun iceFailedDoesNotRestartIceWithMcuOrForScreen() {
        wrapper(hasMCU = true)
        negotiated()
        iceState(IceConnectionState.FAILED)
        verifyNoRestart()

        wrapper(type = "screen")
        iceState(IceConnectionState.FAILED)
        verifyNoRestart()
    }

    @Test
    fun automaticRestartsAreLimitedToFive_andCountStartsAgainWhenConnected() {
        wrapper()
        negotiated()
        Mockito.clearInvocations(peerConnection)

        repeat(7) { iceState(IceConnectionState.FAILED) }
        verify(peerConnection, times(5)).restartIce()

        iceState(IceConnectionState.CONNECTED)
        iceState(IceConnectionState.FAILED)
        verify(peerConnection, times(6)).restartIce()
    }

    @Test
    fun iceDisconnectedRestartsIceOnlyIfItLastsLongEnough() {
        PeerConnectionWrapper.disconnectedRestartDelayMs = 100
        wrapper()
        negotiated()
        Mockito.clearInvocations(peerConnection)

        iceState(IceConnectionState.DISCONNECTED)
        iceState(IceConnectionState.CONNECTED)
        Thread.sleep(400)
        verifyNoRestart()

        iceState(IceConnectionState.DISCONNECTED)
        verify(peerConnection, timeout(2000)).restartIce()
    }

    // answers to repeated offers

    @Test
    fun remoteOfferOnNegotiatedConnectionIsAnswered() {
        wrapper()
        webRtcListener().onOffer("v=0", "nick", null)
        val sdpObserver = remoteDescriptionObserver()
        // already negotiated: local description exists, the remote offer is an ICE restart
        negotiated(SignalingState.HAVE_REMOTE_OFFER)
        Mockito.clearInvocations(peerConnection)

        sdpObserver.onSetSuccess()

        verify(peerConnection).createAnswer(any(SdpObserver::class.java), any(MediaConstraints::class.java))
    }

    @Test
    fun setSuccessAfterOwnLocalDescriptionIsNotAnswered() {
        wrapper()
        webRtcListener().onOffer("v=0", "nick", null)
        val sdpObserver = remoteDescriptionObserver()
        negotiated(SignalingState.STABLE)
        Mockito.clearInvocations(peerConnection)

        sdpObserver.onSetSuccess()

        verify(peerConnection, never()).createAnswer(any(SdpObserver::class.java), any(MediaConstraints::class.java))
    }

    @Test
    fun remoteOfferOnNegotiatedConnectionStopsVideoTransceivers_whenVideoIsNotReceived() {
        val audioOnly = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        PeerConnectionWrapper(
            factory,
            ArrayList<PeerConnection.IceServer>(),
            audioOnly,
            "remote-session",
            "zzz-local-session",
            null,
            false,
            false,
            "video",
            receiver,
            sender
        )
        val videoTransceiver = Mockito.mock(org.webrtc.RtpTransceiver::class.java)
        Mockito.`when`(videoTransceiver.mediaType).thenReturn(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        Mockito.`when`(peerConnection.transceivers).thenReturn(listOf(videoTransceiver))
        webRtcListener().onOffer("v=0", "nick", null)
        val sdpObserver = remoteDescriptionObserver()
        negotiated(SignalingState.HAVE_REMOTE_OFFER)

        sdpObserver.onSetSuccess()

        verify(videoTransceiver).stop()
    }
}
