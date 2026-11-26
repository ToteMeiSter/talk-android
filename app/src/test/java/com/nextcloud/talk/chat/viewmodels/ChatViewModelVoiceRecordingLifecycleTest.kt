/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.viewmodels

import android.content.Context
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.work.testing.WorkManagerTestInitHelper
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.chat.data.ChatMessageRepository
import com.nextcloud.talk.chat.data.io.AudioFocusRequestManager
import com.nextcloud.talk.chat.data.io.MediaRecorderManager
import com.nextcloud.talk.chat.data.io.MediaRecorderManager.MediaRecorderState
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.domain.ConversationModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A voice recording must only end when it is really left or interrupted: other Talk screens over the chat (like the
 * media viewer) keep it running, and every end stops the recorder and gives the audio focus back, so the shown state
 * matches the capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatViewModelVoiceRecordingLifecycleTest {

    private val mediaRecorderManager: MediaRecorderManager = mock()
    private val audioFocusRequestManager: AudioFocusRequestManager = mock()
    private val chatRepository: ChatMessageRepository = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: ChatViewModel

    // the setter of the singleton is protected: only the real application sets it
    private val sharedApplicationField = NextcloudTalkApplication::class.java
        .getDeclaredField("sharedApplication")
        .apply { isAccessible = true }
    private var previousApplication: NextcloudTalkApplication? = null

    @Before
    fun setUp() {
        // ChatViewModel's init reconnects to running uploads through WorkManager and the application singleton.
        whenever(chatRepository.getUploadPlaceholders(any())).thenReturn(emptyFlow())
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        previousApplication = sharedApplicationField.get(null) as NextcloudTalkApplication?
        val application = mock<NextcloudTalkApplication>()
        whenever(application.applicationContext).thenReturn(context)
        sharedApplicationField.set(null, application)
        whenever(mediaRecorderManager.mediaRecorderState).thenReturn(MediaRecorderState.RECORDING)
        whenever(mediaRecorderManager.currentVoiceRecordFile).thenReturn("recording.mp3")
        doAnswer { (it.arguments[2] as () -> Unit).invoke() }
            .whenever(audioFocusRequestManager).audioFocusRequest(any(), any(), any())
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        sharedApplicationField.set(null, previousApplication)
    }

    @Test
    fun `another screen over the chat keeps the recording running`() {
        viewModel.onStop(mock<LifecycleOwner>())

        verify(mediaRecorderManager, never()).handleOnStop()
        verify(mediaRecorderManager, never()).stop()
    }

    @Test
    fun `leaving the chat discards the recording and gives the audio focus back`() {
        val file = kotlin.io.path.createTempFile(suffix = ".mp3").toFile()
        whenever(mediaRecorderManager.currentVoiceRecordFile).thenReturn(file.absolutePath)

        viewModelStore.clear()

        verify(mediaRecorderManager).stop()
        verify(audioFocusRequestManager).audioFocusRequest(eq(false), any(), any())
        assertFalse("recording file is deleted", file.exists())
    }

    @Test
    fun `leaving the chat without a recording does not touch the recorder or the audio focus`() {
        whenever(mediaRecorderManager.mediaRecorderState).thenReturn(MediaRecorderState.RELEASED)

        viewModelStore.clear()

        verify(mediaRecorderManager, never()).stop()
        verify(audioFocusRequestManager, never()).audioFocusRequest(any(), any(), any())
    }

    @Test
    fun `a silenced recording is interrupted`() {
        viewModel.setVoiceRecordingLocked(true)
        viewModel.startAudioRecording(mock<Context>(), mock<ConversationModel>())
        idleMainLooper()
        val onSilenced = argumentCaptor<() -> Unit>()
        verify(mediaRecorderManager).start(any(), any(), onSilenced.capture())

        onSilenced.firstValue.invoke()
        idleMainLooper()

        verify(mediaRecorderManager).stop()
        assertEquals(false, viewModel.getVoiceRecordingInProgress.value)
        assertEquals(true, viewModel.voiceRecordingInterrupted.value)
    }

    @Test
    fun `stopping a recording stops the recorder even if the audio focus is not given back`() {
        doAnswer { }.whenever(audioFocusRequestManager).audioFocusRequest(eq(false), any(), any())

        viewModel.stopAudioRecording()
        idleMainLooper()

        verify(mediaRecorderManager).stop()
        assertEquals(false, viewModel.getVoiceRecordingInProgress.value)
    }

    @Test
    fun `an interruption stops a locked recording and reports it as interrupted`() {
        viewModel.setVoiceRecordingLocked(true)
        idleMainLooper()

        viewModel.interruptAudioRecording()
        idleMainLooper()

        verify(mediaRecorderManager).stop()
        verify(audioFocusRequestManager).audioFocusRequest(eq(false), any(), any())
        assertEquals(false, viewModel.getVoiceRecordingInProgress.value)
        assertEquals(true, viewModel.voiceRecordingInterrupted.value)

        viewModel.onVoiceRecordingInterruptionShown()
        assertEquals(false, viewModel.voiceRecordingInterrupted.value)
    }

    @Test
    fun `an interruption discards a recording that was still held`() {
        val file = kotlin.io.path.createTempFile(suffix = ".mp3").toFile()
        whenever(mediaRecorderManager.currentVoiceRecordFile).thenReturn(file.absolutePath)

        viewModel.interruptAudioRecording()
        idleMainLooper()

        verify(mediaRecorderManager).stop()
        assertFalse("held recording is deleted", file.exists())
        assertEquals(false, viewModel.getVoiceRecordingInProgress.value)
        assertEquals(false, viewModel.voiceRecordingInterrupted.value)
    }

    @Test
    fun `an interruption without a recording changes nothing`() {
        whenever(mediaRecorderManager.mediaRecorderState).thenReturn(MediaRecorderState.RELEASED)
        viewModel.setVoiceRecordingLocked(true)
        idleMainLooper()

        viewModel.interruptAudioRecording()
        idleMainLooper()

        verify(mediaRecorderManager, never()).stop()
        assertEquals(false, viewModel.voiceRecordingInterrupted.value)
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** Created through a [ViewModelStore], so that clearing the store clears the view model like leaving the chat. */
    private fun newViewModel(): ChatViewModel {
        val viewModel = ChatViewModel(
            logger = mock(),
            appPreferences = mock(defaultAnswer = RETURNS_DEEP_STUBS),
            chatNetworkDataSource = mock(defaultAnswer = RETURNS_DEEP_STUBS),
            chatRepository = chatRepository,
            threadsRepository = mock(defaultAnswer = RETURNS_DEEP_STUBS),
            conversationRepository = mock(defaultAnswer = RETURNS_DEEP_STUBS),
            reactionsRepository = mock(defaultAnswer = RETURNS_DEEP_STUBS),
            unifiedSearchRepository = mock(defaultAnswer = RETURNS_DEEP_STUBS),
            mediaRecorderManager = mediaRecorderManager,
            audioFocusRequestManager = audioFocusRequestManager,
            userManager = mock(defaultAnswer = RETURNS_DEEP_STUBS),
            appScope = CoroutineScope(Dispatchers.Unconfined),
            initialUser = User().apply { id = 1L },
            chatRoomToken = "room",
            conversationThreadId = null
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
        }
        return ViewModelProvider(viewModelStore, factory)[ChatViewModel::class.java]
    }
}
