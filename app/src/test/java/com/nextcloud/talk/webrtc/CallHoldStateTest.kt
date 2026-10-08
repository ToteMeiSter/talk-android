/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHoldStateTest {

    @Test
    fun `hold keeps the media state from before the hold`() {
        val hold = CallHoldState()

        assertEquals(CallHoldState.LocalMedia(true, false), hold.enter(microphoneOn = true, videoOn = false))
        assertTrue(hold.isOnHold)
        assertEquals(CallHoldState.LocalMedia(true, false), hold.exit())
        assertFalse(hold.isOnHold)
    }

    @Test
    fun `microphone that was off stays off after the hold`() {
        val hold = CallHoldState()

        hold.enter(microphoneOn = false, videoOn = true)

        assertEquals(CallHoldState.LocalMedia(false, true), hold.exit())
    }

    @Test
    fun `second enter does not overwrite the saved state with the muted one`() {
        val hold = CallHoldState()

        hold.enter(microphoneOn = true, videoOn = true)

        assertNull(hold.enter(microphoneOn = false, videoOn = false))
        assertEquals(CallHoldState.LocalMedia(true, true), hold.exit())
    }

    @Test
    fun `exit without hold changes nothing`() {
        val hold = CallHoldState()

        assertNull(hold.exit())
        assertFalse(hold.isOnHold)
    }

    @Test
    fun `hold can be entered again after it was left`() {
        val hold = CallHoldState()

        hold.enter(microphoneOn = true, videoOn = false)
        hold.exit()

        assertEquals(CallHoldState.LocalMedia(false, false), hold.enter(microphoneOn = false, videoOn = false))
    }
}
