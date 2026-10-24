/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import com.nextcloud.talk.activities.CallActivity
import com.nextcloud.talk.jobs.CallPushRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One dial, 55 seconds, then the call is cancelled: the caller and the callee use the same time. */
class CallRingTimeTest {

    @Test
    fun callerGivesUpAfterFiftyFiveSeconds() {
        assertEquals(55_000L, CallActivity.CALLING_TIMEOUT)
    }

    @Test
    fun incomingCallNotificationAndWindowEndAtTheSameTimeAsTheDial() {
        assertEquals(CallActivity.CALLING_TIMEOUT, NotificationUtils.CALL_NOTIFICATION_TIMEOUT_MS)
    }

    @Test
    fun missedCallPollEndsBeforeTheRingingNotificationDoes() {
        assertTrue(CallPushRetryPolicy.POLL_WINDOW_MS < NotificationUtils.CALL_NOTIFICATION_TIMEOUT_MS)
    }
}
