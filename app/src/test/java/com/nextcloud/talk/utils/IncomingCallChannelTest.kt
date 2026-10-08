/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import com.nextcloud.talk.utils.NotificationUtils.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An incoming call during another call is announced in a channel of its own (heads-up, one short sound, vibration),
 * the looping ringtone channel stays for calls without a call going on.
 */
class IncomingCallChannelTest {

    @Test
    fun `call during a call uses the heads-up channel`() {
        assertEquals(
            NotificationChannels.NOTIFICATION_CHANNEL_CALLS_WHILE_IN_CALL_V1,
            NotificationUtils.incomingCallChannel(duringCall = true)
        )
    }

    @Test
    fun `call without a call going on uses the ringtone channel`() {
        assertEquals(
            NotificationChannels.NOTIFICATION_CHANNEL_CALLS_V5,
            NotificationUtils.incomingCallChannel(duringCall = false)
        )
    }
}
