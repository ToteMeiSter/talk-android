/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room session reuse for the join of a call ([CallActivity.canReuseHolderSession]).
 *
 * When a call is answered from a notification while the chat of the called room is open, ChatActivity leaves the
 * room at the same moment (an HPB "leave room" and the backend DELETE of the session fire from its onPause,
 * while the call has not claimed ApplicationWideCurrentRoomHolder yet). The cached session is stale or is being
 * torn down, so the call must fetch a fresh one via the joinRoom API; a join with the cached session is rejected
 * by the signaling server and nothing retries it — the call stays connected with no media in either direction.
 */
class CallActivityAnswerJoinSessionTest {

    @Test
    fun `call answered from notification does not reuse the holder session`() {
        assertFalse(CallActivity.canReuseHolderSession(isIncomingCallFromNotification = true))
    }

    @Test
    fun `call started in the app reuses the holder session`() {
        assertTrue(CallActivity.canReuseHolderSession(isIncomingCallFromNotification = false))
    }
}
