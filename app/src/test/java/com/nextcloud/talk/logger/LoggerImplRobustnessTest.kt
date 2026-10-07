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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class LoggerImplRobustnessTest : LoggerImplTestBase() {
    private val failures = AtomicInteger()

    override fun createHandler() =
        object : FileLogHandler(logDir, "nc_talk_log.txt", MAX_FILE_SIZE) {
            override fun write(logEntry: String) {
                if (logEntry.contains("make-it-fail")) {
                    failures.incrementAndGet()
                    throw IOException("disk is full")
                }
                super.write(logEntry)
            }
        }

    @Test
    fun `a write that throws loses that entry only and the next entries are written`() {
        val impl = newLogger(withLogcat = false)
        impl.minimumLevel = Level.INFO
        impl.start()
        impl.i("Own", "before")
        impl.i("Own", "make-it-fail")
        impl.i("Own", "after the failure")
        assertTrue(eventually { fileText().contains("after the failure") })
        assertEquals(1, failures.get())
        assertTrue(fileText().contains("before"))
        assertFalse(fileText().contains("make-it-fail"))

        impl.i("Own", "much later")
        assertTrue(eventually { fileText().contains("much later") })
        assertTrue(impl.lostEntries)
    }

    @Test
    fun `a write that throws does not leave a flush waiting`() {
        val impl = newLogger(withLogcat = false)
        impl.minimumLevel = Level.INFO
        impl.start()
        impl.i("Own", "make-it-fail")
        val begin = System.nanoTime()
        impl.flush(timeoutMs = FLUSH_MS)
        assertTrue((System.nanoTime() - begin) / NANOS_PER_MILLI < FLUSH_MS)
    }

    @Test
    fun `an entry that logcat would cut is written by the logger while the capture works`() {
        val impl = startAdvanced()
        val longMessage = "x".repeat(LONG) + " end-of-long-entry"
        val stack = (1..STACK_LINES).joinToString("\n") { "\tat com.example.Frame" + it + "(Frame.java:" + it + ")" }
        impl.d("Long", longMessage)
        impl.e("Stack", "boom\n" + stack)
        impl.d("Short", "short entry that logcat delivers")

        assertTrue(eventually { fileText().contains("end-of-long-entry") })
        val text = fileText()
        assertEquals(1, countOf(text, "end-of-long-entry"))
        assertEquals(1, countOf(text, "Frame" + STACK_LINES + "(Frame.java"))
        assertFalse("a short entry is left to logcat", text.contains("short entry that logcat delivers"))
    }

    @Test
    fun `a flush while the capture is not verified writes the held own entries`() {
        fake.echoMarker = false
        val impl = newLogger(verifyTimeoutMs = 5000)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { fake.streams.size == 1 })
        impl.d("Own", "held until the crash handler flushes")
        impl.w("Own", "second held entry")
        impl.flush()

        val text = handler.loadLogFiles().lines.joinToString("\n")
        assertEquals(1, countOf(text, "held until the crash handler flushes"))
        assertEquals(1, countOf(text, "second held entry"))
    }

    @Test
    fun `entries over the limit of the held list are counted as lost`() {
        fake.echoMarker = false
        val impl = newLogger(verifyTimeoutMs = 5000)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { fake.streams.size == 1 })
        assertFalse(impl.lostEntries)
        repeat(HELD_LIMIT + 1) { impl.d("Own", "entry " + it) }
        assertTrue(impl.lostEntries)
    }

    @Test
    fun `stopping on the calling thread does not wait for the reading thread`() {
        val impl = startAdvanced()
        val begin = System.nanoTime()
        impl.minimumLevel = Level.NONE
        assertTrue((System.nanoTime() - begin) / NANOS_PER_MILLI < MAX_STOP_MS)
        assertTrue(eventually { fake.current.closed })
    }

    @Test
    fun `held entries are written when the level leaves advanced before the check line came`() {
        fake.echoMarker = false
        val impl = newLogger(verifyTimeoutMs = 5000)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { fake.streams.size == 1 })
        fake.current.emit(line("D", "Other", "logcat line before the check", time = "10-07 12:30:00.000"))
        impl.w("Own", "held entry written at the stop")
        impl.minimumLevel = Level.INFO
        impl.minimumLevel = Level.DEBUG
        impl.minimumLevel = Level.INFO

        assertTrue(eventually { fileText().contains("held entry written at the stop") })
        assertEquals(1, countOf(fileText(), "held entry written at the stop"))
        assertEquals(0, countOf(fileText(), "logcat line before the check"))
    }

    private companion object {
        const val FLUSH_MS = 1500L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_STOP_MS = 500L
        const val LONG = 5000
        const val STACK_LINES = 120
        const val HELD_LIMIT = 5000
    }
}
