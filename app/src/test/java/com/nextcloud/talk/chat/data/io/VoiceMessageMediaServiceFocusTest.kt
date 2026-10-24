/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.data.io

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The voice message of the chat plays in the player of [VoiceMessageMediaService], which the chat reaches through a
 * MediaController. That player must take the transient audio focus, so that the music of another app pauses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceMessageMediaServiceFocusTest {
    @Before
    fun setUp() = VoiceMessageMediaServiceTestSupport.install()

    private var createdService: VoiceMessageMediaService? = null

    // A service left alive keeps its media session, and the next test then fails on "Session ID must be unique".
    @After
    fun tearDown() {
        createdService?.onDestroy()
        createdService = null
        VoiceMessageMediaServiceTestSupport.uninstall()
    }

    private fun createService(): VoiceMessageMediaService =
        Robolectric.buildService(VoiceMessageMediaService::class.java).create().get().also { createdService = it }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadow = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    private fun servicePlayer(service: VoiceMessageMediaService): Player {
        val field = VoiceMessageMediaService::class.java.getDeclaredField("mediaSession")
        field.isAccessible = true
        return (field.get(service) as MediaSession).player
    }

    @Test
    fun `voice message played by the chat service holds a transient audio focus`() {
        val service = createService()
        val player = servicePlayer(service)
        val file = File.createTempFile("voice", ".m4a").apply { writeBytes(ByteArray(64)) }

        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.play()

        assertNotNull("the chat player must request the audio focus", shadow.lastAudioFocusRequest)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadow.lastAudioFocusRequest.audioFocusRequest.focusGain)
        assertNull(shadow.lastAbandonedAudioFocusRequest)

        player.pause()
        assertNotNull("the music of the other app can come back", shadow.lastAbandonedAudioFocusRequest)
    }

    // Real audio data, so that the player does not fail at once, which would end its playback and release the focus.
    private fun silentWav(): ByteArray {
        val data = ByteArray(WAV_SAMPLE_RATE * 2)
        return ByteBuffer.allocate(WAV_HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(WAV_HEADER_SIZE - 8 + data.size).put("WAVEfmt ".toByteArray())
            putInt(16).putShort(1).putShort(1).putInt(WAV_SAMPLE_RATE).putInt(WAV_SAMPLE_RATE * 2)
            putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size).put(data)
        }.array()
    }

    private fun chatItem(messageId: Int, kind: String): MediaItem {
        val file = File.createTempFile("audio", ".wav").apply { writeBytes(silentWav()) }
        return MediaItem.Builder()
            .setMediaId("talk-audio:1/room/$messageId")
            .setUri(file.toURI().toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putString("com.nextcloud.talk.chat.audio.KIND", kind) })
                    .build()
            )
            .build()
    }

    private fun focusGain() = shadow.lastAudioFocusRequest?.audioFocusRequest?.focusGain

    @Test
    fun `audio file does not take the transient focus`() {
        val player = servicePlayer(createService())

        player.setMediaItem(chatItem(1, "AUDIO_FILE"))
        player.prepare()
        player.play()

        assertNotEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, focusGain())
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `voice message then audio file gives the transient focus back before the player asks for gain`() {
        val service = createService()
        val player = servicePlayer(service)
        // The player asks for its own focus in setAudioAttributes, so what it holds at each refresh() of the
        // transient focus tells which of the two came first.
        val contentTypeAtRefresh = mutableListOf<Int>()
        val focus = mock<PlaybackAudioFocus> {
            on { refresh() } doAnswer { contentTypeAtRefresh += player.audioAttributes.contentType }
        }
        VoiceMessageMediaService::class.java.getDeclaredField("playbackAudioFocus")
            .apply { isAccessible = true }
            .set(service, focus)
        player.setMediaItems(listOf(chatItem(1, "VOICE_MESSAGE"), chatItem(2, "AUDIO_FILE")))
        player.prepare()
        player.play()
        val seekMark = contentTypeAtRefresh.size

        player.seekToNextMediaItem()

        assertEquals(
            "the transient focus is checked while the player still has the attributes of the voice message",
            C.AUDIO_CONTENT_TYPE_SPEECH,
            contentTypeAtRefresh.drop(seekMark).firstOrNull()
        )
        assertTrue("the file must not be paused", player.playWhenReady)
    }

    @Test
    fun `audio file then voice message requests the transient focus`() {
        val player = servicePlayer(createService())

        player.setMediaItems(listOf(chatItem(1, "AUDIO_FILE"), chatItem(2, "VOICE_MESSAGE")))
        player.prepare()
        player.play()

        player.seekToNextMediaItem()

        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, focusGain())
        assertTrue(player.playWhenReady)
    }
}

private const val WAV_SAMPLE_RATE = 8000
private const val WAV_HEADER_SIZE = 44
