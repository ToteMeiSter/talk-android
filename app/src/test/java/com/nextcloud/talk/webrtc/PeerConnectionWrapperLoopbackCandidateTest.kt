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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

class PeerConnectionWrapperLoopbackCandidateTest {

    private lateinit var observer: PeerConnection.Observer
    private lateinit var sender: SignalingMessageSender

    @Before
    fun setUp() {
        val peerConnection = Mockito.mock(PeerConnection::class.java)
        val factory = Mockito.mock(PeerConnectionFactory::class.java)
        sender = Mockito.mock(SignalingMessageSender::class.java)

        val observerCaptor = ArgumentCaptor.forClass(PeerConnection.Observer::class.java)
        Mockito.`when`(
            factory.createPeerConnection(any(PeerConnection.RTCConfiguration::class.java), observerCaptor.capture())
        ).thenReturn(peerConnection)

        val statusDataChannel = Mockito.mock(DataChannel::class.java)
        Mockito.`when`(statusDataChannel.label()).thenReturn("status")
        Mockito.`when`(peerConnection.createDataChannel(eq("status"), any())).thenReturn(statusDataChannel)

        PeerConnectionWrapper(
            factory,
            ArrayList<PeerConnection.IceServer>(),
            MediaConstraints(),
            "the-session-id",
            "the-local-session-id",
            null,
            true,
            true,
            "video",
            Mockito.mock(SignalingMessageReceiver::class.java),
            sender
        )
        observer = observerCaptor.value
    }

    @Test
    fun loopbackCandidateIsNotSentAndOtherCandidateIs() {
        observer.onIceCandidate(IceCandidate("0", 0, "candidate:1 1 udp 2122260223 127.0.0.1 50000 typ host"))
        observer.onIceCandidate(IceCandidate("0", 0, "candidate:2 1 udp 2122194687 ::1 50001 typ host"))
        Mockito.verify(sender, Mockito.never()).send(any(NCSignalingMessageDto::class.java))

        val sdp = "candidate:3 1 udp 2122260223 10.0.0.2 50002 typ host"
        observer.onIceCandidate(IceCandidate("0", 0, sdp))

        val messageCaptor = ArgumentCaptor.forClass(NCSignalingMessageDto::class.java)
        Mockito.verify(sender).send(messageCaptor.capture())
        assertEquals(sdp, messageCaptor.value.payload!!.iceCandidate!!.candidate)
    }
}
