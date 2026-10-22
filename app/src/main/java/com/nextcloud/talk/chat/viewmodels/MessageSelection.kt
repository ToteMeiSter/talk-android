/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.viewmodels

import com.nextcloud.talk.chat.data.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The messages of a chat selected for an action on several of them. The selection is active while it is not empty:
 * deselecting the last message ends it.
 */
class MessageSelection {
    private val _ids = MutableStateFlow<Set<Int>>(emptySet())
    val ids: StateFlow<Set<Int>> = _ids

    val isActive: Boolean
        get() = _ids.value.isNotEmpty()

    fun toggle(messageId: Int) {
        _ids.update { if (messageId in it) it - messageId else it + messageId }
    }

    fun clear() {
        _ids.value = emptySet()
    }

    /** Drops the ids that are not in [existing] any more, e.g. a pending message replaced by its server copy. */
    fun retainOnly(existing: Set<Int>) {
        _ids.update { ids -> ids.filterTo(HashSet()) { it in existing } }
    }

    /** Ends an active selection for the back action. Returns false, when there is none and back is not handled. */
    fun consumeBack(): Boolean {
        if (!isActive) {
            return false
        }
        clear()
        return true
    }
}

/** What a forward of selected messages sends: one text per message in chat order, and how many were left out. */
data class ForwardPlan(val texts: List<String>, val skipped: Int) {
    /** What the user is told: nothing to forward stops the forward, left out messages are named, all sent is silent. */
    val outcome: ForwardOutcome
        get() = when {
            texts.isEmpty() -> ForwardOutcome.NOTHING_TO_FORWARD
            skipped > 0 -> ForwardOutcome.SOME_SKIPPED
            else -> ForwardOutcome.ALL
        }
}

enum class ForwardOutcome { ALL, SOME_SKIPPED, NOTHING_TO_FORWARD }

/**
 * Plans the forward of [messages] (any order): each forwardable message becomes its own text, ordered by id. The text
 * is what the message shows (a mention is its name), with the note of [noteOf] on the first line. A message without
 * text is not forwarded as a text.
 */
fun planForward(
    messages: List<ChatMessage>,
    canForward: (ChatMessage) -> Boolean,
    noteOf: (ChatMessage) -> String
): ForwardPlan {
    val texts = messages.sortedBy { it.jsonMessageId }
        .filter(canForward)
        .mapNotNull { forwardedText(it, noteOf(it)) }
    return ForwardPlan(texts, messages.size - texts.size)
}

/** The text a forward sends of [message]: [note] on the first line, then the text as shown; null without a text. */
fun forwardedText(message: ChatMessage, note: String): String? =
    message.getRichText().takeIf { it.isNotBlank() }?.let { "$note\n$it" }

/** The caption of a forwarded file: [note] on the first line, then the original [caption], if there is one. */
fun forwardedCaption(note: String, caption: String): String = if (caption.isBlank()) note else "$note\n$caption"

/** Whether a message with [messageId] can be selected. Pending messages have a negative id until the server answers. */
fun isSelectableMessageId(messageId: Int): Boolean = messageId > 0
