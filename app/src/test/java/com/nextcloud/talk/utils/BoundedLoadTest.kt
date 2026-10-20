/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLoadTest {

    @Test
    fun finishedLoadGivesItsResult() {
        assertEquals("picture", BoundedLoad.within(1_000L) { "picture" })
    }

    @Test
    fun slowLoadGivesNullAtTheLimitWithoutInterruptingTheThread() {
        val start = System.nanoTime()

        val result = BoundedLoad.within(100L) {
            delay(10_000L)
            "picture"
        }

        assertNull(result)
        assertFalse(Thread.currentThread().isInterrupted)
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
    }
}
