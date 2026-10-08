/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.app.Application
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle.Message
import androidx.core.app.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PushMessageHistoryTest {

    private val sender = Person.Builder().setKey("1@sender").setName("Dilyara").build()

    private fun message(text: String, time: Long) = Message(text, time, sender)

    private fun texts(messages: List<Message>) = messages.map { it.text.toString() }

    @Test
    fun enrichedMessageReplacesTheSubjectMessageOfTheSamePush() {
        val subjectMessage = PushMessageHistory.merge(emptyList(), 619L, message("Да есть чу…", 1L))

        val merged = PushMessageHistory.merge(subjectMessage, 619L, message("Да есть чуть-чуть", 2L))

        assertEquals(listOf("Да есть чуть-чуть"), texts(merged))
    }

    @Test
    fun replacementKeepsThePlaceAmongOtherMessages() {
        val first = PushMessageHistory.merge(emptyList(), 618L, message("one", 1L))
        val second = PushMessageHistory.merge(first, 619L, message("two…", 2L))
        val third = PushMessageHistory.merge(second, 620L, message("three", 3L))

        val merged = PushMessageHistory.merge(third, 619L, message("two", 2L))

        assertEquals(listOf("one", "two", "three"), texts(merged))
    }

    @Test
    fun messageOfAnotherPushIsAdded() {
        val first = PushMessageHistory.merge(emptyList(), 618L, message("one", 1L))

        val merged = PushMessageHistory.merge(first, 619L, message("two", 2L))

        assertEquals(listOf("one", "two"), texts(merged))
    }

    @Test
    fun messageWithoutPushIdIsNeverReplaced() {
        val merged = PushMessageHistory.merge(listOf(message("old", 1L)), 619L, message("new", 2L))

        assertEquals(listOf("old", "new"), texts(merged))
        assertFalse(PushMessageHistory.isMessageOfPush(merged.first(), 619L))
        assertTrue(PushMessageHistory.isMessageOfPush(merged.last(), 619L))
    }

    @Test
    fun missingPushIdAddsTheMessage() {
        val merged = PushMessageHistory.merge(listOf(message("old", 1L)), null, message("new", 2L))

        assertEquals(listOf("old", "new"), texts(merged))
    }

    @Test
    fun twoMessagesOfTheSamePushCollapseToOne() {
        val twice = listOf(message("a", 1L), message("a again", 1L)).map {
            it.also { m -> m.extras.putLong("push_notification_id", 619L) }
        }

        val merged = PushMessageHistory.merge(twice, 619L, message("a enriched", 1L))

        assertEquals(listOf("a enriched"), texts(merged))
    }

    @Test
    fun imageOfAnEarlierMessageSurvivesTheMerge() {
        val image = message("", 1L).also { it.setData("image/png", android.net.Uri.parse("content://preview/1")) }

        val merged = PushMessageHistory.merge(listOf(image), 619L, message("next", 2L))

        assertEquals("image/png", merged.first().dataMimeType)
        assertEquals("content://preview/1", merged.first().dataUri.toString())
    }

    @Test
    fun markOfThePushSurvivesBuildAndExtraction() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setKey("me").setName("Me").build())
        PushMessageHistory.merge(emptyList(), 618L, message("one", 1L)).forEach { style.addMessage(it) }
        val withSecond = PushMessageHistory.merge(
            style.messages,
            619L,
            message("two", 2L)
        )
        val newStyle = NotificationCompat.MessagingStyle(Person.Builder().setKey("me").setName("Me").build())
        withSecond.forEach { newStyle.addMessage(it) }
        val notification = NotificationCompat.Builder(context, "1")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(newStyle)
            .build()

        val extracted = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!

        assertEquals(listOf("one", "two"), texts(extracted.messages))
        assertTrue(PushMessageHistory.isMessageOfPush(extracted.messages[0], 618L))
        assertTrue(PushMessageHistory.isMessageOfPush(extracted.messages[1], 619L))
        assertFalse(PushMessageHistory.isMessageOfPush(extracted.messages[1], 618L))
    }
}
