/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushLogUtilsTest {

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
        PushLogUtils.describeFcmExtras(map.keys) {
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
        assertTrue(asked.toString(), PushLogUtils.FCM_LOGGED_KEYS.containsAll(asked))
    }

    @Test
    fun longValueIsCut() {
        val text = describe(mapOf("google.ttl" to "x".repeat(100)))

        assertTrue(text, text.contains("google.ttl=" + "x".repeat(32) + " "))
    }
}
