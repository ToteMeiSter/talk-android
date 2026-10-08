/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.app.Application
import androidx.work.Data
import androidx.work.NetworkType
import com.nextcloud.talk.utils.bundle.BundleKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The request of stage 2 of a push: it waits for the network and carries nothing but the encrypted input. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class NotificationWorkerStageTest {

    private val stage1Input = Data.Builder()
        .putString(BundleKeys.KEY_NOTIFICATION_SUBJECT, "encrypted-subject")
        .putString(BundleKeys.KEY_NOTIFICATION_SIGNATURE, "signature")
        .putLong(BundleKeys.KEY_NOTIFICATION_PUSH_SENT_TIME, SENT_TIME)
        .build()

    @Test
    fun stageTwoWaitsForTheNetwork() {
        val spec = NotificationWorker.networkStageRequest(stage1Input, subjectShown = true).workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(NotificationWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun stageTwoIsExpeditedOnApi31AndLater() {
        assertTrue(NotificationWorker.networkStageRequest(stage1Input, true).workSpec.expedited)
    }

    @Test
    @Config(sdk = [30])
    fun stageTwoIsNotExpeditedBeforeApi31() {
        assertFalse(NotificationWorker.networkStageRequest(stage1Input, true).workSpec.expedited)
    }

    @Test
    fun stageTwoGetsTheEncryptedInputAndTwoFlagsOnly() {
        val input = NotificationWorker.networkStageRequest(stage1Input, subjectShown = true).workSpec.input

        assertEquals(
            setOf(
                BundleKeys.KEY_NOTIFICATION_SUBJECT,
                BundleKeys.KEY_NOTIFICATION_SIGNATURE,
                BundleKeys.KEY_NOTIFICATION_PUSH_SENT_TIME,
                BundleKeys.KEY_NOTIFICATION_NETWORK_STAGE,
                BundleKeys.KEY_NOTIFICATION_SUBJECT_SHOWN
            ),
            input.keyValueMap.keys
        )
        assertEquals("encrypted-subject", input.getString(BundleKeys.KEY_NOTIFICATION_SUBJECT))
        assertEquals(SENT_TIME, input.getLong(BundleKeys.KEY_NOTIFICATION_PUSH_SENT_TIME, 0L))
        assertTrue(input.getBoolean(BundleKeys.KEY_NOTIFICATION_NETWORK_STAGE, false))
        assertTrue(input.getBoolean(BundleKeys.KEY_NOTIFICATION_SUBJECT_SHOWN, false))
    }

    @Test
    fun stageTwoRemembersThatNothingWasShown() {
        val input = NotificationWorker.networkStageRequest(stage1Input, subjectShown = false).workSpec.input

        assertFalse(input.getBoolean(BundleKeys.KEY_NOTIFICATION_SUBJECT_SHOWN, true))
    }

    @Test
    fun stageOneInputIsNotAStageTwoInput() {
        assertFalse(stage1Input.getBoolean(BundleKeys.KEY_NOTIFICATION_NETWORK_STAGE, false))
    }

    private companion object {
        const val SENT_TIME = 1_700_000_000_000L
    }
}
