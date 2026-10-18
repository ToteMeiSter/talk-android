/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.data.io

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import java.time.Duration

/**
 * The music of another app must be paused before the microphone is opened (audio focus first, capture after), and
 * the players of the chat must take the audio focus while they play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioFocusBeforeCaptureTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var shadow: ShadowAudioManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadow = shadowOf(audioManager)
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `capture starts at once when no music plays`() {
        var started = false

        AudioFocusRequestManager(context).audioFocusRequest(true, waitForOtherAudio = true) { started = true }

        assertTrue(started)
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            shadow.lastAudioFocusRequest.audioFocusRequest.focusGain
        )
    }

    @Test
    fun `capture waits for the music to stop after the focus was granted`() {
        shadow.setIsMusicActive(true)
        var started = false

        AudioFocusRequestManager(context).audioFocusRequest(true, waitForOtherAudio = true) { started = true }
        assertNotNull(shadow.lastAudioFocusRequest)
        idle(200)
        assertFalse("the microphone must not open while the music plays", started)

        shadow.setIsMusicActive(false)
        idle(100)
        assertTrue(started)
    }

    @Test
    fun `capture starts after the limit when the music does not stop`() {
        shadow.setIsMusicActive(true)
        var started = false

        AudioFocusRequestManager(context).audioFocusRequest(true, waitForOtherAudio = true) { started = true }
        idle(500)
        assertFalse(started)
        idle(700)

        assertTrue(started)
    }

    @Test
    fun `giving the focus back drops a capture that still waits`() {
        shadow.setIsMusicActive(true)
        var started = false
        val manager = AudioFocusRequestManager(context)

        manager.audioFocusRequest(true, waitForOtherAudio = true) { started = true }
        manager.audioFocusRequest(false) { }
        shadow.setIsMusicActive(false)
        idle(2000)

        assertFalse(started)
    }

    @Test
    fun `request without waiting runs at once also while music plays`() {
        shadow.setIsMusicActive(true)
        var started = false

        AudioFocusRequestManager(context).audioFocusRequest(true) { started = true }

        assertTrue(started)
    }

    @Test
    fun `music wait is bounded`() {
        assertTrue(shouldWaitForMusicToStop(isMusicActive = true, waitedMs = 0))
        assertFalse(shouldWaitForMusicToStop(isMusicActive = false, waitedMs = 0))
        assertFalse(shouldWaitForMusicToStop(isMusicActive = true, waitedMs = 1000))
    }

    @Test
    fun `player needs the focus only while it plays`() {
        assertTrue(playerNeedsAudioFocus(true, Player.STATE_READY))
        assertTrue(playerNeedsAudioFocus(true, Player.STATE_BUFFERING))
        assertFalse(playerNeedsAudioFocus(false, Player.STATE_READY))
        assertFalse(playerNeedsAudioFocus(true, Player.STATE_ENDED))
        assertFalse(playerNeedsAudioFocus(true, Player.STATE_IDLE))
    }

    @Test
    fun `playback takes a transient focus and gives it back at the end`() {
        val player = mock<Player>()
        val listener = argumentCaptor<Player.Listener>()
        val focus = PlaybackAudioFocus(context, player)
        verify(player).addListener(listener.capture())

        whenever(player.playWhenReady).thenReturn(true)
        whenever(player.playbackState).thenReturn(Player.STATE_READY)
        listener.firstValue.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadow.lastAudioFocusRequest.audioFocusRequest.focusGain)
        assertEquals(null, shadow.lastAbandonedAudioFocusRequest)

        whenever(player.playbackState).thenReturn(Player.STATE_ENDED)
        listener.firstValue.onPlaybackStateChanged(Player.STATE_ENDED)
        assertNotNull("the music of the other app can come back", shadow.lastAbandonedAudioFocusRequest)
        focus.release()
    }

    @Test
    fun `playback pauses when the focus is lost`() {
        val player = mock<Player>()
        val listener = argumentCaptor<Player.Listener>()
        PlaybackAudioFocus(context, player)
        verify(player).addListener(listener.capture())
        whenever(player.playWhenReady).thenReturn(true)
        whenever(player.playbackState).thenReturn(Player.STATE_READY)
        listener.firstValue.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        shadow.lastAudioFocusRequest.listener
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        verify(player).pause()
    }
}
