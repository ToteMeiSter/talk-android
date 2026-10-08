/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PushDiagTest {

    private val extras = mapOf(
        "google.delivered_priority" to "high",
        "google.original_priority" to "normal",
        "google.priority_reduced" to "1",
        "google.ttl" to 2_419_200,
        "google.sent_time" to 1_700_000_000_000L,
        "google.to" to "token-value",
        "google.message_id" to "0:123%abc",
        "google.c.sender.id" to "999",
        "from" to "999",
        "collapse_key" to "do_not_collapse",
        "subject" to "secret-subject",
        "signature" to "secret-signature",
        "rawData" to "raw"
    )

    private fun describe(map: Map<String, Any?> = extras, asked: MutableList<String> = mutableListOf()) =
        PushDiag.describeFcmExtras(map.keys) {
            asked += it
            map[it]
        }

    @Test
    fun priorityLifetimeAndTypeAreWritten() {
        val text = describe()

        assertTrue(text, text.contains("google.delivered_priority=high"))
        assertTrue(text, text.contains("google.original_priority=normal"))
        assertTrue(text, text.contains("google.priority=null"))
        assertTrue(text, text.contains("google.priority_reduced=1"))
        assertTrue(text, text.contains("google.ttl=2419200"))
        assertTrue(text, text.contains("google.sent_time=1700000000000"))
        assertTrue(text, text.contains("message_type=null"))
    }

    @Test
    fun otherGoogleKeysAreWrittenByNameOnly() {
        val text = describe()

        assertTrue(text, text.endsWith("otherGoogleKeys=[google.c.sender.id, google.message_id, google.to]"))
    }

    @Test
    fun valuesOfOtherKeysAreNeitherReadNorWritten() {
        val asked = mutableListOf<String>()

        val text = describe(asked = asked)

        listOf("token-value", "0:123%abc", "secret-subject", "secret-signature", "raw", "do_not_collapse").forEach {
            assertFalse("$it in $text", text.contains(it))
        }
        assertTrue(asked.toString(), PushDiag.FCM_LOGGED_KEYS.containsAll(asked))
    }

    @Test
    fun messageTypeIsWritten() {
        val text = describe(mapOf("message_type" to "deleted_messages"))

        assertTrue(text, text.contains("message_type=deleted_messages"))
        assertTrue(text, text.endsWith("otherGoogleKeys=[]"))
    }

    @Test
    fun longValueIsCut() {
        val text = describe(mapOf("google.ttl" to "x".repeat(100)))

        assertTrue(text, text.contains("google.ttl=" + "x".repeat(32) + " "))
    }

    @Test
    fun outcomeHasNeitherBodyNorMessage() {
        val http = HttpException(Response.error<Any>(503, "secret body".toResponseBody()))

        assertEquals("ok", PushDiag.describeOutcome(null))
        assertEquals("HttpException code=503", PushDiag.describeOutcome(http))
        assertEquals("java.io.IOException", PushDiag.describeOutcome(IOException("https://host/secret")))
    }

    @Test
    fun powerRestrictionsOfApi33() {
        val text = PushDiag.describePowerRestrictions(ApplicationProvider.getApplicationContext())

        listOf("standbyBucket=", "powerSave=", "ignoringBatteryOpt=", "backgroundRestricted=", "lightIdle=")
            .forEach { assertTrue("$it in $text", text.contains(it)) }
        assertTrue(text, text.contains("lowPowerStandby="))
    }

    @Test
    @Config(sdk = [31])
    fun powerRestrictionsOfApi31LeaveOutWhatTheSystemDoesNotHave() {
        val text = PushDiag.describePowerRestrictions(ApplicationProvider.getApplicationContext())

        listOf("standbyBucket=", "powerSave=", "ignoringBatteryOpt=", "backgroundRestricted=")
            .forEach { assertTrue("$it in $text", text.contains(it)) }
        listOf("lightIdle", "lowPowerStandby").forEach { assertFalse("$it in $text", text.contains(it)) }
    }
}
