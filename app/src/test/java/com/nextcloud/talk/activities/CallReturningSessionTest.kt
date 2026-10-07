/*
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import com.nextcloud.talk.call.CallParticipantList
import com.nextcloud.talk.models.json.participants.ParticipantDto
import com.nextcloud.talk.models.json.participants.ParticipantDto.InCallFlags
import com.nextcloud.talk.signaling.SignalingMessageReceiver
import com.nextcloud.talk.webrtc.PeerConnectionWrapper
import com.nextcloud.talk.webrtc.PeerConnectionWrapper.PeerConnectionObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.webrtc.AudioTrack
import org.webrtc.MediaStream
import org.webrtc.VideoTrack

/**
 * A participant leaves the call and comes back with the same signaling session while the local one stays in the
 * call: the old connection is gone, and the stream of the new one has to end up in the participant, with audio and
 * video, whether the offer of the MCU or the update which reports the participant arrives first.
 */
class CallReturningSessionTest {

    private val other = "other-session"
    private val inCallWithMedia = (InCallFlags.IN_CALL + InCallFlags.WITH_AUDIO + InCallFlags.WITH_VIDEO).toLong()

    private fun participant(inCall: Long) =
        ParticipantDto().apply {
            sessionId = other
            this.inCall = inCall
        }

    private fun streamWithTracks(audio: Int, video: Int): MediaStream {
        val stream: MediaStream = mock()
        setField(stream, "audioTracks", MutableList(audio) { mock<AudioTrack>() })
        setField(stream, "videoTracks", MutableList(video) { mock<VideoTrack>() })
        return stream
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = MediaStream::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun wrapperWith(stream: MediaStream?): PeerConnectionWrapper {
        val wrapper: PeerConnectionWrapper = mock()
        whenever(wrapper.stream).thenReturn(stream)
        return wrapper
    }

    private fun newHandler() =
        ParticipantHandler(
            other,
            "",
            "",
            mock<SignalingMessageReceiver>(),
            onParticipantShareScreen = { },
            onParticipantUnshareScreen = { }
        )

    @Test
    fun `participant who left and returns with the same session is reported as joined again`() {
        val receiver: SignalingMessageReceiver = mock()
        val list = CallParticipantList(receiver)
        val captor = argumentCaptor<SignalingMessageReceiver.ParticipantListMessageListener>()
        verify(receiver).addListener(captor.capture())
        val joinedCount = mutableListOf<Int>()
        val leftCount = mutableListOf<Int>()
        list.addObserver(object : CallParticipantList.Observer {
            override fun onCallParticipantsChanged(
                joined: Collection<ParticipantDto>,
                updated: Collection<ParticipantDto>,
                left: Collection<ParticipantDto>,
                unchanged: Collection<ParticipantDto>
            ) {
                joinedCount.add(joined.size)
                leftCount.add(left.size)
            }

            override fun onCallEndedForAll() {
                // not needed
            }
        })

        captor.firstValue.onParticipantsUpdate(mutableListOf(participant(inCallWithMedia)))
        captor.firstValue.onParticipantsUpdate(mutableListOf(participant(InCallFlags.DISCONNECTED.toLong())))
        captor.firstValue.onParticipantsUpdate(mutableListOf(participant(inCallWithMedia)))

        assertEquals(listOf(1, 0, 1), joinedCount)
        assertEquals(listOf(0, 1, 0), leftCount)
    }

    @Test
    fun `stream of the new connection replaces the old one with audio and video`() {
        val handler = newHandler()
        val oldStream = streamWithTracks(audio = 1, video = 1)
        val newStream = streamWithTracks(audio = 1, video = 1)

        handler.setPeerConnection(wrapperWith(oldStream))
        assertSame(oldStream, handler.uiState.value.mediaStream)

        // The participant left: the connection is ended.
        handler.setPeerConnection(null)
        assertEquals(null, handler.uiState.value.mediaStream)
        assertFalse(handler.uiState.value.isStreamEnabled)

        // The participant came back: the new connection brings its own stream.
        handler.setPeerConnection(wrapperWith(newStream))

        val state = handler.uiState.value
        assertSame(newStream, state.mediaStream)
        assertTrue(state.isAudioEnabled)
        assertTrue(state.isStreamEnabled)
    }

    @Test
    fun `stream added after the connection was bound reaches the participant with audio and video`() {
        val handler = newHandler()
        val wrapper = wrapperWith(null)
        handler.setPeerConnection(wrapper)
        assertFalse(handler.uiState.value.isStreamEnabled)

        val observer = argumentCaptor<PeerConnectionObserver>()
        verify(wrapper).addObserver(observer.capture())
        val stream = streamWithTracks(audio = 1, video = 1)
        observer.firstValue.onStreamAdded(stream)

        val state = handler.uiState.value
        assertNotNull(state.mediaStream)
        assertSame(stream, state.mediaStream)
        assertTrue(state.isAudioEnabled)
        assertTrue(state.isStreamEnabled)
    }

    @Test
    fun `connection set up by an offer is kept when the update says the participant has no media yet`() {
        assertFalse(CallActivity.shouldUnbindPeerConnection(hasConnection = true))
    }

    @Test
    fun `participant without a connection is cleared when no connection is to be created`() {
        assertTrue(CallActivity.shouldUnbindPeerConnection(hasConnection = false))
    }
}
