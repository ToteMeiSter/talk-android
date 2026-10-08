/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.app.Application
import androidx.core.app.NotificationCompat.MessagingStyle.Message
import androidx.core.app.Person
import com.nextcloud.talk.jobs.PushNotificationDecision.Alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PushNotificationDecisionTest {

    private val sender = Person.Builder().setKey("1@sender").setName("Dilyara").build()

    private fun messagesOf(vararg pushIds: Long): List<Message> =
        pushIds.fold(emptyList()) { history, id ->
            PushMessageHistory.merge(history, id, Message("m$id", id, sender))
        }

    @Test
    fun newMessageOfTheConversationAlerts() {
        assertEquals(Alert.ALERT, PushNotificationDecision.decide(false, null, 619L))
        assertEquals(Alert.ALERT, PushNotificationDecision.decide(false, messagesOf(618L), 619L))
    }

    @Test
    fun enrichmentOfAMessageThatIsStillShownIsSilent() {
        assertEquals(Alert.SILENT, PushNotificationDecision.decide(true, messagesOf(618L, 619L), 619L))
    }

    @Test
    fun enrichmentAfterTheUserDismissedTheNotificationWritesNothing() {
        assertEquals(Alert.SKIP, PushNotificationDecision.decide(true, null, 619L))
    }

    @Test
    fun enrichmentOfAMessageThatIsNoLongerInTheNotificationWritesNothing() {
        // a new notification of the conversation was created after the dismissal: it holds other messages
        assertEquals(Alert.SKIP, PushNotificationDecision.decide(true, messagesOf(620L), 619L))
    }

    @Test
    fun enrichmentWithoutPushIdWritesNothing() {
        assertEquals(Alert.SKIP, PushNotificationDecision.decide(true, messagesOf(619L), null))
    }

    @Test
    fun failedRequestShowsFromTheSubjectOnlyWhenNothingWasShown() {
        assertFalse(PushNotificationDecision.showFromSubjectAfterFailure(alreadyNotified = true))
        assertTrue(PushNotificationDecision.showFromSubjectAfterFailure(alreadyNotified = false))
    }
}
