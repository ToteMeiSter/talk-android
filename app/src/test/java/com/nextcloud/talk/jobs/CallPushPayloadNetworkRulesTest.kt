/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.app.Application
import com.nextcloud.talk.models.json.participants.ParticipantDto
import com.nextcloud.talk.utils.bundle.BundleKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallPushPayloadNetworkRulesTest {

    @Test
    fun pushForTheCallTheUserIsInIsOwnCallOnlyForSameRoomAndAccount() {
        val active = CallPushPayload.ActiveCall(USER_ID, TOKEN)

        assertTrue(CallPushPayload.isOwnCall(active, USER_ID, TOKEN))
        // another room of the same account still rings (heads-up)
        assertFalse(CallPushPayload.isOwnCall(active, USER_ID, "other-room"))
        // the same room token on another account is another call
        assertFalse(CallPushPayload.isOwnCall(active, USER_ID + 1, TOKEN))
        assertFalse(CallPushPayload.isOwnCall(null, USER_ID, TOKEN))
    }

    @Test
    fun stageOneShowsNothingForTheOwnCallBeforeAnythingElseButEncryption() {
        assertEquals(
            CallPushPayload.Stage1Action.OWN_CALL,
            CallPushPayload.stage1Action(stale = false, endToEndEncrypted = false, ownCall = true)
        )
        assertEquals(
            CallPushPayload.Stage1Action.OWN_CALL,
            CallPushPayload.stage1Action(stale = true, endToEndEncrypted = false, ownCall = true)
        )
        assertEquals(
            CallPushPayload.Stage1Action.RING,
            CallPushPayload.stage1Action(stale = false, endToEndEncrypted = false, ownCall = false)
        )
    }

    @Test
    fun cameraIsOnForVideoAnswerAndOffForVoiceAnswer() {
        assertTrue(CallPushPayload.startsWithCamera(voiceOnly = false, cameraPermissionGranted = true))
        assertFalse(CallPushPayload.startsWithCamera(voiceOnly = true, cameraPermissionGranted = true))
        assertFalse(CallPushPayload.startsWithCamera(voiceOnly = false, cameraPermissionGranted = false))
    }

    @Test
    fun notificationAnswerOfVideoCallIsNotVoiceOnlyAndOfVoiceCallIs() {
        val video = CallPushPayload.IncomingCall.fromPush(TOKEN, SUBJECT).copy(callFlag = VIDEO_CALL_FLAG)
        val voice = video.copy(callFlag = ParticipantDto.InCallFlags.IN_CALL)

        assertFalse(video.toAnswerBundle(USER_ID, NOTIFICATION_ID).getBoolean(BundleKeys.KEY_CALL_VOICE_ONLY, true))
        assertTrue(voice.toAnswerBundle(USER_ID, NOTIFICATION_ID).getBoolean(BundleKeys.KEY_CALL_VOICE_ONLY))
    }

    private companion object {
        const val TOKEN = "abc123"
        const val SUBJECT = "Alice is calling you"
        const val USER_ID = 7L
        const val NOTIFICATION_ID = 123456
        const val VIDEO_CALL_FLAG = ParticipantDto.InCallFlags.IN_CALL or ParticipantDto.InCallFlags.WITH_VIDEO
    }
}
