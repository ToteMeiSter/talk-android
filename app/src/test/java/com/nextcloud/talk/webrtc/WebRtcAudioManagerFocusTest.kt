/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audio focus handling of [WebRtcAudioManager] (issue #6541).
 *
 * A transient focus holder (e.g. telephony during a GSM call) switches the global audio mode and restores its own
 * saved mode on release, clobbering MODE_IN_COMMUNICATION. [WebRtcAudioManager.AudioFocusState] decides when the
 * communication mode must be re-asserted, and [WebRtcAudioManager.buildCallAudioFocusRequest] pins the focus
 * request configuration for a long-running call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebRtcAudioManagerFocusTest {

    @Test
    fun `focus request asks for long-term voice communication gain`() {
        val request = WebRtcAudioManager.buildCallAudioFocusRequest { }

        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, request.audioAttributes.usage)
        assertTrue(request.acceptsDelayedFocusGain())
        assertFalse(request.willPauseWhenDucked())
    }

    @Test
    fun `restore is reported when focus returns after transient loss`() {
        val state = WebRtcAudioManager.AudioFocusState()

        assertFalse(state.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertTrue(state.hasTransientLoss())
        assertTrue(state.handle(AudioManager.AUDIOFOCUS_GAIN))
        assertFalse(state.hasTransientLoss())
    }

    @Test
    fun `restore is reported only once per transient loss`() {
        val state = WebRtcAudioManager.AudioFocusState()

        state.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(state.handle(AudioManager.AUDIOFOCUS_GAIN))
        assertFalse(state.handle(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `duckable transient loss also requires restore`() {
        val state = WebRtcAudioManager.AudioFocusState()

        assertFalse(state.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertTrue(state.hasTransientLoss())
        assertTrue(state.handle(AudioManager.AUDIOFOCUS_GAIN))
        assertFalse(state.hasTransientLoss())
    }

    @Test
    fun `focus gain without preceding loss does not report restore`() {
        val state = WebRtcAudioManager.AudioFocusState()

        assertFalse(state.handle(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `permanent loss clears a pending transient loss`() {
        val state = WebRtcAudioManager.AudioFocusState()

        state.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        state.handle(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(state.handle(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `new call clears a transient loss left by the previous call`() {
        val state = WebRtcAudioManager.AudioFocusState()

        state.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        state.reset()

        assertFalse(state.hasTransientLoss())
        assertFalse(state.handle(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `phone call mode interrupts the call without audio focus loss`() {
        val state = WebRtcAudioManager.AudioFocusState()

        assertFalse(state.handleMode(AudioManager.MODE_RINGTONE))
        assertTrue(state.isInterrupted())
        assertFalse(state.handleMode(AudioManager.MODE_IN_CALL))
        assertTrue(state.isInterrupted())
        assertTrue(state.handleMode(AudioManager.MODE_NORMAL))
        assertFalse(state.isInterrupted())
    }

    @Test
    fun `own communication mode is not a phone call`() {
        val state = WebRtcAudioManager.AudioFocusState()

        assertFalse(state.handleMode(AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(state.isInterrupted())
        assertFalse(state.handleMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun `interruption lasts while either focus loss or phone call lasts`() {
        val state = WebRtcAudioManager.AudioFocusState()

        state.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        state.handleMode(AudioManager.MODE_IN_CALL)
        state.handle(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(state.isInterrupted())
        state.handleMode(AudioManager.MODE_NORMAL)
        assertFalse(state.isInterrupted())
    }

    @Test
    fun `ringing phone interrupts the call but does not hold it`() {
        val state = WebRtcAudioManager.AudioFocusState()

        state.handleMode(AudioManager.MODE_RINGTONE)
        assertTrue(state.isInterrupted())
        assertFalse(state.isCallAccepted())
    }

    @Test
    fun `accepted phone call holds the call until it is over`() {
        val state = WebRtcAudioManager.AudioFocusState()

        state.handleMode(AudioManager.MODE_RINGTONE)
        state.handleMode(AudioManager.MODE_IN_CALL)
        assertTrue(state.isCallAccepted())
        state.handleMode(AudioManager.MODE_NORMAL)
        assertFalse(state.isCallAccepted())
        assertFalse(state.isInterrupted())
    }

    @Test
    fun `declined phone call never holds the call`() {
        val state = WebRtcAudioManager.AudioFocusState()

        state.handleMode(AudioManager.MODE_RINGTONE)
        assertTrue(state.handleMode(AudioManager.MODE_NORMAL))
        assertFalse(state.isCallAccepted())
    }
}
