/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.utils.singletons.ApplicationWideCurrentRoomHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Which room session the call reuses ([ApplicationWideCurrentRoomHolder.getSessionForRoom], used by
 * CallActivity.joinRoomAndCall).
 *
 * The holder keeps the room session of the chat which is open. An incoming call for another conversation used to
 * join its room with that session: the signaling server allows one signaling session per room session and closed
 * the signaling session of the device ("room_session_reconnected"). Only the session of the called room and
 * account may be reused; otherwise the call gets its own via the joinRoom API.
 */
class CallActivityRoomSessionReuseTest {

    private val holder = ApplicationWideCurrentRoomHolder.getInstance()

    @Before
    fun setUp() {
        holder.clear()
        holder.currentRoomToken = ROOM_B
        holder.session = SESSION_B
        holder.userInRoom = User(id = USER_ID)
    }

    @After
    fun tearDown() {
        holder.clear()
    }

    @Test
    fun `call from the open conversation reuses its room session`() {
        assertEquals(SESSION_B, holder.getSessionForRoom(ROOM_B, USER_ID))
    }

    @Test
    fun `call of another conversation does not take the room session of the open chat`() {
        assertEquals("", holder.getSessionForRoom(ROOM_A, USER_ID))
    }

    @Test
    fun `call of another account with the same room token does not take the room session`() {
        assertEquals("", holder.getSessionForRoom(ROOM_B, OTHER_USER_ID))
    }

    @Test
    fun `empty holder gives no session`() {
        holder.clear()
        assertEquals("", holder.getSessionForRoom(ROOM_A, USER_ID))
    }

    @Test
    fun `holder without a session gives no session`() {
        holder.session = null
        assertEquals("", holder.getSessionForRoom(ROOM_B, USER_ID))
    }

    @Test
    fun `no user id gives no session`() {
        assertEquals("", holder.getSessionForRoom(ROOM_B, null))
    }

    private companion object {
        const val ROOM_A = "roomA"
        const val ROOM_B = "gcnwhx56"
        const val SESSION_B = "session-of-b"
        const val USER_ID = 7L
        const val OTHER_USER_ID = 8L
    }
}
