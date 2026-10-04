/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Krainov Gleb <krajnov.g@kontentplus.ru>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecorderLifecycleTest {

    @Test
    fun cameraLifecycleStartsWithTheRecordingAndEndsWithIt() {
        val owner = RecordingLifecycleOwner()
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)

        owner.start()
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)

        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun destroyedCameraLifecycleCannotBeStartedAgain() {
        val owner = RecordingLifecycleOwner()
        owner.start()
        owner.destroy()

        owner.start()

        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun destroyingTwiceIsHarmless() {
        val owner = RecordingLifecycleOwner()
        owner.destroy()
        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun stopIsHeldBackWhileTheSwitchedCameraSettles() {
        assertTrue(VideoMessageRecorder.shouldDeferStop(switchSettling = true))
        assertFalse(VideoMessageRecorder.shouldDeferStop(switchSettling = false))
    }

    @Test
    fun cameraIsSwitchedOnlyOnceAndNotWhileStopping() {
        assertTrue(VideoMessageRecorder.canSwitchCamera(stopRequested = false, switchSettling = false))
        assertFalse(VideoMessageRecorder.canSwitchCamera(stopRequested = true, switchSettling = false))
        assertFalse(VideoMessageRecorder.canSwitchCamera(stopRequested = false, switchSettling = true))
    }
}
