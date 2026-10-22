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

private const val EXPECTED_MESSAGE_LIMIT = 100

class MessageSelectionTest {

    private val selection = MessageSelection()

    @Test
    fun `a new selection is not active`() {
        assertFalse(selection.isActive)
        assertEquals(emptySet<Int>(), selection.ids.value)
    }

    @Test
    fun `toggling a message selects it and starts the selection`() {
        selection.toggle(7, null)

        assertTrue(selection.isActive)
        assertEquals(setOf(7), selection.ids.value)
    }

    @Test
    fun `toggling a selected message deselects it`() {
        selection.toggle(7, null)
        selection.toggle(8, null)
        selection.toggle(7, null)

        assertEquals(setOf(8), selection.ids.value)
    }

    @Test
    fun `deselecting the last message ends the selection`() {
        selection.toggle(7, null)
        selection.toggle(7, null)

        assertFalse(selection.isActive)
    }

    @Test
    fun `back ends an active selection and is handled`() {
        selection.toggle(7, null)
        selection.toggle(8, null)

        assertTrue(selection.consumeBack())
        assertFalse(selection.isActive)
        assertEquals(emptySet<Int>(), selection.ids.value)
    }

    @Test
    fun `back without a selection is not handled`() {
        assertFalse(selection.consumeBack())
    }

    @Test
    fun `ids that left the chat are dropped from the selection`() {
        selection.toggle(-3, null)
        selection.toggle(7, null)
        selection.toggle(8, null)

        selection.retainOnly(setOf(7, 8, 9))
        selection.retainOnly(setOf(7, 9))

        assertEquals(setOf(7), selection.ids.value)
    }

    @Test
    fun `dropping the last selected id ends the selection`() {
        selection.toggle(-3, null)

        selection.retainOnly(setOf(7))

        assertFalse(selection.isActive)
    }

    @Test
    fun `the hundredth message is selected but the next one is rejected`() {
        repeat(EXPECTED_MESSAGE_LIMIT) { assertEquals(null, selection.toggle(it + 1, "x")) }

        assertEquals(SelectionLimit.MESSAGE_COUNT, selection.toggle(101, "x"))
        assertEquals(100, selection.ids.value.size)
    }

    @Test
    fun `exactly 32000 forwarded characters fit but one more does not`() {
        assertEquals(null, selection.toggle(1, "x".repeat(31_999)))
        assertEquals(null, selection.toggle(2, "x"))

        assertEquals(SelectionLimit.TEXT_LENGTH, selection.toggle(3, "x"))
        assertEquals(setOf(1, 2), selection.ids.value)
    }

    @Test
    fun `deselecting after either limit succeeds and frees capacity`() {
        repeat(EXPECTED_MESSAGE_LIMIT) { selection.toggle(it + 1, "x") }
        assertEquals(SelectionLimit.MESSAGE_COUNT, selection.toggle(101, "x"))
        assertEquals(null, selection.toggle(1, null))
        assertEquals(null, selection.toggle(101, "x"))

        selection.clear()
        selection.toggle(1, "x".repeat(32_000))
        assertEquals(SelectionLimit.TEXT_LENGTH, selection.toggle(2, "x"))
        assertEquals(null, selection.toggle(1, null))
        assertEquals(null, selection.toggle(2, "x"))
    }

    @Test
    fun `a pending message with a negative id cannot be selected`() {
        assertFalse(isSelectableMessageId(-1))
        assertFalse(isSelectableMessageId(0))
        assertTrue(isSelectableMessageId(1))
    }
}

class ForwardPlanTest {

    private fun message(id: Int, text: String?, author: String = "Me"): ChatMessage =
        ChatMessage().apply {
            jsonMessageId = id
            message = text
            actorDisplayName = author
        }

    private fun plan(messages: List<ChatMessage>, canForward: (ChatMessage) -> Boolean = { true }) =
        planForward(messages, canForward) { "From ${it.actorDisplayName}" }

    @Test
    fun `each forwardable message is its own text in chat order`() {
        val plan = plan(listOf(message(30, "c"), message(10, "a"), message(20, "b")))

        assertEquals(listOf("From Me\na", "From Me\nb", "From Me\nc"), plan.texts)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `own and foreign messages are forwarded alike, each with its own author`() {
        val plan = plan(listOf(message(3, "own two"), message(2, "theirs", "Bob"), message(1, "own one")))

        assertEquals(listOf("From Me\nown one", "From Bob\ntheirs", "From Me\nown two"), plan.texts)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `a mention of a foreign message is forwarded by the name it shows`() {
        val mention = message(2, "Hi {mention-user1}", "Bob").apply {
            messageParameters = hashMapOf("mention-user1" to hashMapOf("type" to "user", "name" to "Alice"))
        }

        assertEquals(listOf("From Bob\nHi @Alice"), plan(listOf(mention)).texts)
    }

    @Test
    fun `messages that cannot be forwarded are counted as skipped`() {
        val plan = plan(listOf(message(1, "a"), message(2, "{file}"), message(3, "c"))) { it.jsonMessageId != 2 }

        assertEquals(listOf("From Me\na", "From Me\nc"), plan.texts)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `nothing forwardable gives no texts`() {
        val plan = plan(listOf(message(1, "{file}"))) { false }

        assertTrue(plan.texts.isEmpty())
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `everything forwardable is sent without a notice`() {
        assertEquals(ForwardOutcome.ALL, plan(listOf(message(1, "a"), message(2, "b"))).outcome)
    }

    @Test
    fun `left out messages give the skipped notice and the rest is sent`() {
        val plan = plan(listOf(message(1, "a"), message(2, "{file}"))) { it.jsonMessageId == 1 }

        assertEquals(ForwardOutcome.SOME_SKIPPED, plan.outcome)
        assertEquals(listOf("From Me\na"), plan.texts)
    }

    @Test
    fun `an empty plan is reported and not silent`() {
        assertEquals(ForwardOutcome.NOTHING_TO_FORWARD, plan(listOf(message(1, "a"))) { false }.outcome)
        assertEquals(ForwardOutcome.NOTHING_TO_FORWARD, plan(emptyList()).outcome)
        assertEquals(ForwardOutcome.NOTHING_TO_FORWARD, plan(listOf(message(1, "  "))).outcome)
    }

    @Test
    fun `a file caption has the note on the first line, the original caption after it`() {
        assertEquals("From Bob", forwardedCaption("From Bob", ""))
        assertEquals("From Bob\nlook", forwardedCaption("From Bob", "look"))
    }
}
