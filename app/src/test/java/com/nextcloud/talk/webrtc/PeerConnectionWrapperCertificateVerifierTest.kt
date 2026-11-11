/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.signaling.SignalingMessageReceiver
import com.nextcloud.talk.signaling.SignalingMessageSender
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionDependencies
import org.webrtc.PeerConnectionFactory
import org.webrtc.sslCertificateVerifier

class PeerConnectionWrapperCertificateVerifierTest {

    private val mockedPeerConnection: PeerConnection = Mockito.mock(PeerConnection::class.java)
    private val mockedPeerConnectionFactory: PeerConnectionFactory = Mockito.mock(PeerConnectionFactory::class.java)
    private val mockedSignalingMessageReceiver: SignalingMessageReceiver =
        Mockito.mock(SignalingMessageReceiver::class.java)
    private val mockedSignalingMessageSender: SignalingMessageSender =
        Mockito.mock(SignalingMessageSender::class.java)

    @Test
    fun testPeerConnectionIsCreatedWithSystemTrustCertificateVerifier() {
        val dependenciesCaptor: ArgumentCaptor<PeerConnectionDependencies> =
            ArgumentCaptor.forClass(PeerConnectionDependencies::class.java)

        Mockito.`when`(
            mockedPeerConnectionFactory.createPeerConnection(
                any(PeerConnection.RTCConfiguration::class.java),
                dependenciesCaptor.capture()
            )
        ).thenReturn(mockedPeerConnection)

        PeerConnectionWrapper(
            mockedPeerConnectionFactory,
            ArrayList(),
            MediaConstraints(),
            "the-session-id",
            "the-local-session-id",
            null,
            false,
            false,
            "video",
            mockedSignalingMessageReceiver,
            mockedSignalingMessageSender,
            false,
            null,
            null
        )

        val verifier = dependenciesCaptor.value.sslCertificateVerifier()
        assertTrue(verifier === SystemTrustSslCertificateVerifier.shared())
    }
}
