/*
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import com.nextcloud.talk.call.CallParticipantList
import com.nextcloud.talk.models.json.participants.ParticipantDto
import com.nextcloud.talk.models.json.participants.ParticipantDto.InCallFlags
import com.nextcloud.talk.signaling.SignalingMessageReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * A participant which is in the call before the local one gets known to [CallParticipantList] by the first update
 * which reports it. When that update arrives before the local participant is in the call (e.g. an event which waited
 * for a resumed signaling session), [CallActivity] drops it ("Self not in call"), and the update which puts the local
 * participant in the call reports the other one as unchanged: no connection was ever set up for it.
 */
class CallActivityPeersToConnectTest {

    private val self = "self"
    private val other = "other"

    private fun participant(sessionId: String, inCall: Long) =
        ParticipantDto().apply {
            this.sessionId = sessionId
            this.inCall = inCall
        }

    private val inCallWithMedia = (InCallFlags.IN_CALL + InCallFlags.WITH_AUDIO + InCallFlags.WITH_VIDEO).toLong()

    /** Plays the updates against a real [CallParticipantList] and returns the sessions [connect] was asked for. */
    private fun playUpdates(
        connect: (
            ParticipantDto?,
            Collection<ParticipantDto>,
            Collection<ParticipantDto>,
            Collection<ParticipantDto>,
            Collection<ParticipantDto>
        ) -> Collection<ParticipantDto>
    ): Set<String?> {
        val receiver: SignalingMessageReceiver = mock()
        val list = CallParticipantList(receiver)
        val captor = argumentCaptor<SignalingMessageReceiver.ParticipantListMessageListener>()
        verify(receiver).addListener(captor.capture())
        val connected = mutableSetOf<String?>()
        list.addObserver(object : CallParticipantList.Observer {
            override fun onCallParticipantsChanged(
                joined: Collection<ParticipantDto>,
                updated: Collection<ParticipantDto>,
                left: Collection<ParticipantDto>,
                unchanged: Collection<ParticipantDto>
            ) {
                // Same early exit as CallActivity.handleCallParticipantsChanged.
                val all = joined + updated + unchanged
                val selfParticipant = all.firstOrNull { it.sessionId == self }
                if (selfParticipant == null || selfParticipant.inCall == 0L) {
                    return
                }
                connect(selfParticipant, joined, updated, unchanged, left).forEach { connected.add(it.sessionId) }
            }

            override fun onCallEndedForAll() {
                // not needed
            }
        })

        // The call of the other participant, reported while the local one is not in the call yet.
        captor.firstValue.onParticipantsUpdate(
            mutableListOf(participant(other, inCallWithMedia), participant(self, InCallFlags.DISCONNECTED.toLong()))
        )
        // The local participant joins the call.
        captor.firstValue.onParticipantsUpdate(
            mutableListOf(participant(other, inCallWithMedia), participant(self, inCallWithMedia))
        )
        return connected
    }

    @Test
    fun `only the joined ones are connected without the fix - the other participant is lost`() {
        val connected = playUpdates { _, joined, _, _, _ -> joined }

        assertEquals(setOf<String?>(self), connected)
    }

    @Test
    fun `participant reported before self joined is connected when self joins`() {
        val connected = playUpdates { _, joined, updated, unchanged, _ ->
            CallActivity.peersToConnect(joined, updated, unchanged, self) { false }
        }

        assertEquals(setOf<String?>(self, other), connected)
    }

    @Test
    fun `participant with a call participant already is not connected again`() {
        val result = CallActivity.peersToConnect(
            emptyList(),
            emptyList(),
            listOf(participant(other, inCallWithMedia)),
            self
        ) { it == other }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `self and joined ones are not repeated`() {
        val joined = listOf(participant(self, inCallWithMedia), participant(other, inCallWithMedia))

        val result = CallActivity.peersToConnect(
            joined,
            listOf(participant(other, inCallWithMedia), participant(self, inCallWithMedia)),
            listOf(participant(self, inCallWithMedia)),
            self
        ) { false }

        assertEquals(joined, result)
    }
}
