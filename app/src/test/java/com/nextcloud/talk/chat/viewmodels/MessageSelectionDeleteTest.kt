/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.viewmodels

import com.nextcloud.talk.chat.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSelectionDeleteTest {

    private val selection = MessageSelection()

    private fun message(id: Int, text: String?): ChatMessage =
        ChatMessage().apply {
            jsonMessageId = id
            message = text
        }

    @Test
    fun `delete is offered only when every selected message was loaded and can be deleted`() {
        val loaded = listOf(message(1, "a"), message(2, "b"))

        assertTrue(canDeleteSelected(2, loaded) { true })
        assertFalse(canDeleteSelected(2, loaded) { it.jsonMessageId == 1 })
        assertFalse(canDeleteSelected(3, loaded) { true })
        assertFalse(canDeleteSelected(0, emptyList()) { true })
    }

    @Test
    fun `a partial delete failure keeps the failed messages selected and names them`() {
        selection.toggle(1, null)
        selection.toggle(2, null)
        selection.toggle(3, null)
        val outcome = DeleteOutcome(requested = setOf(1, 2, 3), failed = setOf(2))

        selection.remove(outcome.deleted)

        assertTrue(outcome.isPartialFailure)
        assertEquals(setOf(2), selection.ids.value)
        assertTrue(selection.isActive)
    }

    @Test
    fun `deleting everything ends the selection`() {
        selection.toggle(1, null)
        selection.toggle(2, null)
        val outcome = DeleteOutcome(requested = setOf(1, 2), failed = emptySet())

        selection.remove(outcome.deleted)

        assertFalse(outcome.isPartialFailure)
        assertFalse(selection.isActive)
    }

    @Test
    fun `a failed single delete is not a partial failure`() {
        assertFalse(DeleteOutcome(requested = setOf(1), failed = setOf(1)).isPartialFailure)
    }
}
