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
import com.nextcloud.talk.jobs.PushNotificationDecision.Stage1Route
import com.nextcloud.talk.jobs.PushNotificationDecision.Stage2Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Suppress("TooManyFunctions")
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
    fun chatPushIsShownFromTheSubjectAndThenGoesToTheServer() {
        assertEquals(Stage1Route.SUBJECT_THEN_SERVER, PushNotificationDecision.stage1Route("chat"))
    }

    @Test
    fun otherTalkPushesThatNeedTheServerWaitForStageTwo() {
        listOf("room", "recording", "reminder", "remote_talk_share").forEach {
            assertEquals(it, Stage1Route.SERVER, PushNotificationDecision.stage1Route(it))
        }
    }

    @Test
    fun callPushRingsFromThePushInStageOne() {
        assertEquals(Stage1Route.CALL_FROM_PUSH, PushNotificationDecision.stage1Route("call"))
    }

    @Test
    fun unknownTypeGoesNowhere() {
        assertEquals(Stage1Route.NONE, PushNotificationDecision.stage1Route("something_new"))
        assertEquals(Stage1Route.NONE, PushNotificationDecision.stage1Route(null))
    }

    @Test
    fun everyTypeThatStageOneHandsOverHasAStageTwoHandler() {
        listOf("chat", "room", "recording", "reminder", "remote_talk_share", "call", "something_new", null).forEach {
            val handedOver = PushNotificationDecision.stage1Route(it) != Stage1Route.NONE
            val handled = PushNotificationDecision.stage2Handler(it) != PushNotificationDecision.Stage2Handler.NONE
            assertEquals("$it", handedOver, handled)
        }
    }

    @Test
    fun stoppedStageRunsAgainWhateverTheError() {
        listOf(false, true).forEach { notified ->
            listOf(null, 404, 500).forEach { code ->
                val failure = PushNotificationDecision.onStage2Failure(
                    stopped = true,
                    alreadyNotified = notified,
                    httpCode = code
                )
                assertEquals(Stage2Failure.RERUN, failure)
            }
        }
    }

    @Test
    fun notFoundMeansTheServerDroppedTheNotification() {
        assertEquals(
            Stage2Failure.NO_SHOW,
            PushNotificationDecision.onStage2Failure(stopped = false, alreadyNotified = false, httpCode = 404)
        )
    }

    @Test
    fun failedRequestKeepsTheChatShownInStageOne() {
        assertEquals(
            Stage2Failure.NO_SHOW,
            PushNotificationDecision.onStage2Failure(stopped = false, alreadyNotified = true, httpCode = null)
        )
    }

    @Test
    fun failedRequestOfAPushThatShowedNothingFallsBackToTheSubject() {
        listOf(null, 403, 500).forEach { code ->
            assertEquals(
                Stage2Failure.SUBJECT_FALLBACK,
                PushNotificationDecision.onStage2Failure(stopped = false, alreadyNotified = false, httpCode = code)
            )
        }
    }

    @Test
    fun serverNotificationThatIsAlreadyShownIsWrittenAgainWithoutSound() {
        assertEquals(Alert.SILENT, PushNotificationDecision.decideNonChat(619L, 619L))
        assertEquals(Alert.ALERT, PushNotificationDecision.decideNonChat(618L, 619L))
        assertEquals(Alert.ALERT, PushNotificationDecision.decideNonChat(null, 619L))
        assertEquals(Alert.ALERT, PushNotificationDecision.decideNonChat(null, null))
    }

    @Test
    fun stageTwoKeepsTheTimeOfStageOne() {
        assertEquals(1_000L, PushNotificationDecision.pushTimestamp(stage1Time = 1_000L, now = 9_000L))
        assertEquals(9_000L, PushNotificationDecision.pushTimestamp(stage1Time = 0L, now = 9_000L))
    }

    @Test
    fun onlyARunThatJobSchedulerDidNotStartWaitsForAClosedNetwork() {
        assertTrue(PushNotificationDecision.shouldWaitForNetwork(startedByJobScheduler = false, networkBlocked = true))
        assertFalse(PushNotificationDecision.shouldWaitForNetwork(startedByJobScheduler = true, networkBlocked = true))
        assertFalse(
            PushNotificationDecision.shouldWaitForNetwork(startedByJobScheduler = false, networkBlocked = false)
        )
    }

    /** A clock that only [sleep] moves. */
    private class FakeClock {
        var time = 0L
        val sleeps = mutableListOf<Long>()
        fun sleep(ms: Long) {
            sleeps += ms
            time += ms
        }
    }

    @Test
    fun waitEndsAsSoonAsTheNetworkOpens() {
        val clock = FakeClock()
        val result = PushNotificationDecision.waitForOpenNetwork(
            isBlocked = { clock.time < 1_000L },
            isStopped = { false },
            now = { clock.time },
            sleep = clock::sleep
        )

        assertEquals(PushNotificationDecision.NetworkWait(waitedMs = 1_000L, opened = true), result)
    }

    @Test
    fun openNetworkIsNotWaitedFor() {
        val clock = FakeClock()
        val result = PushNotificationDecision.waitForOpenNetwork(
            isBlocked = { false },
            isStopped = { false },
            now = { clock.time },
            sleep = clock::sleep
        )

        assertEquals(PushNotificationDecision.NetworkWait(waitedMs = 0L, opened = true), result)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun waitIsBoundedAndNeverSleepsPastTheLimit() {
        val clock = FakeClock()
        val result = PushNotificationDecision.waitForOpenNetwork(
            isBlocked = { true },
            isStopped = { false },
            now = { clock.time },
            sleep = clock::sleep,
            maxMs = 1_000L,
            stepMs = 300L
        )

        assertEquals(PushNotificationDecision.NetworkWait(waitedMs = 1_000L, opened = false), result)
        assertEquals(listOf(300L, 300L, 300L, 100L), clock.sleeps)
    }

    @Test
    fun waitEndsWhenTheSystemStopsTheStage() {
        val clock = FakeClock()
        val result = PushNotificationDecision.waitForOpenNetwork(
            isBlocked = { true },
            isStopped = { clock.time >= 500L },
            now = { clock.time },
            sleep = clock::sleep,
            stepMs = 250L
        )

        assertEquals(PushNotificationDecision.NetworkWait(waitedMs = 500L, opened = false), result)
    }
}
