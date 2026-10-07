/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import com.nextcloud.talk.logger.FakeLogcat.Companion.eventually
import com.nextcloud.talk.logger.FakeLogcat.Companion.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatCaptureLifecycleTest : LogcatCaptureTestBase() {
    @Test
    fun `a restart after the step down to info keeps the point where the first run started`() {
        fake.dropDebug = true
        newCapture(
            LogcatTiming(verifyTimeoutMs = 200, respawnDelayMs = 10)
        ).start(Level.DEBUG, fromProcessStart = false)
        assertTrue(eventually { verified.size == 1 })
        assertEquals(2, fake.launches.size)
        val first = fake.launches[0]
        assertEquals(first.indexOf("-T"), fake.launches[1].indexOf("-T"))
        assertEquals(first[first.indexOf("-T") + 1], fake.launches[1][fake.launches[1].indexOf("-T") + 1])
    }

    @Test
    fun `the closing of a blocked read after a timeout still steps down to info`() {
        fake.dropDebug = true
        newCapture(LogcatTiming(verifyTimeoutMs = 150, respawnDelayMs = 10)).start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { verified == listOf(Level.INFO) })
        assertTrue(unavailable.isEmpty())
    }

    @Test
    fun `a logcat that ends often but runs steadily in between is never given up`() {
        newCapture(LogcatTiming(verifyTimeoutMs = 2000, respawnDelayMs = 5, steadyAfterMs = 60))
            .start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        repeat(RUNS) { run ->
            assertTrue(eventually { fake.streams.size == run + 1 })
            Thread.sleep(STEADY_SLEEP_MS)
            fake.current.end()
        }
        assertTrue(eventually { fake.streams.size == RUNS + 1 })
        assertTrue(unavailable.isEmpty())
        assertTrue(capture!!.isActive)
    }

    @Test
    fun `stop with drain reads up to the stop line and then closes`() {
        newCapture().start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        val stream = fake.current
        fake.echoMarker = false
        capture!!.stop(drain = true)
        assertFalse(capture!!.isActive)

        val transit = line("D", "Transit", "in the pipe when the level changed", time = "10-07 12:10:00.000")
        stream.emit(transit)
        assertTrue(eventually { written.contains(transit) })
        assertFalse(stream.closed)
        assertTrue(eventually { fake.emitted.isNotEmpty() })
        fake.deliverEmitted()
        assertTrue(eventually { stream.closed })
        assertFalse(written.any { it.contains("capture stop") })
    }

    @Test
    fun `stop with drain closes after the drain timeout when the stop line does not come`() {
        newCapture(LogcatTiming(drainTimeoutMs = 150)).start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        val stream = fake.current
        fake.echoMarker = false
        capture!!.stop(drain = true)
        assertTrue(eventually { stream.closed })
    }

    @Test
    fun `stop without drain closes at once and does not block the caller`() {
        newCapture().start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        val begin = System.nanoTime()
        capture!!.stop()
        assertTrue((System.nanoTime() - begin) / NANOS_PER_MILLI < MAX_STOP_MS)
        assertTrue(eventually { fake.current.closed })
    }

    private companion object {
        const val RUNS = 6
        const val STEADY_SLEEP_MS = 100L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_STOP_MS = 500L
    }
}
