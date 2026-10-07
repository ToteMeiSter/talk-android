/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LogEntryParseTest {
    @Test
    fun `verbose and fatal and assert lines of logcat are parsed`() {
        val v = LogEntry.parseHeader(FakeLogcat.line("V", "OkHttp", "verbose text"))
        val f = LogEntry.parseHeader(FakeLogcat.line("F", "libc", "Fatal signal 11"))
        val a = LogEntry.parseHeader(FakeLogcat.line("A", "Tag", "wtf"))
        assertEquals(Level.VERBOSE, v?.level)
        assertEquals(Level.FATAL, f?.level)
        assertEquals(Level.FATAL, a?.level)
        assertEquals("Fatal signal 11", f?.message)
    }

    @Test
    fun `logcat banner and other service lines are ignored by the parser`() {
        val lines = listOf(
            "--------- beginning of main",
            "--------- beginning of crash",
            FakeLogcat.line("I", "WebSocketInstance", "connected"),
            "logcat: unexpected EOF",
            ""
        )
        assertNull(LogEntry.parseHeader(lines[0]))
        val entries = LogEntry.parseLines(lines)
        assertEquals(1, entries.size)
        assertEquals("WebSocketInstance", entries[0].tag)
    }

    @Test
    fun `entry text is the logcat threadtime format and parses back`() {
        val entry = LogEntry.parseHeader(FakeLogcat.line("W", "IceDiag", "Candidate error"))
        assertNotNull(entry)
        val again = LogEntry.parseHeader(entry.toString())
        assertEquals(entry, again)
    }

    @Test
    fun `fromTag knows all logcat priorities`() {
        listOf("V", "D", "I", "W", "E", "F", "A").forEach { assertNotNull(Level.fromTag(it)) }
        assertNull(Level.fromTag("S"))
    }

    @Test
    fun `none stays the highest level so that it silences everything`() {
        Level.entries.filter { it != Level.NONE }.forEach { assert(it < Level.NONE) }
    }
}
