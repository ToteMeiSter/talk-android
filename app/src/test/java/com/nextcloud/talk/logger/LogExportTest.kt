/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import com.nextcloud.talk.errorhandling.buildLogcatJson
import com.nextcloud.talk.errorhandling.loadAllLogLines
import com.nextcloud.talk.errorhandling.loadLogEntries
import com.nextcloud.talk.logger.FakeLogcat.Companion.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun logDir(): File = folder.newFolder("logs")

    @Test
    fun `export reads the current file and all rotated files oldest first`() {
        val dir = logDir()
        val base = "nc_talk_log.txt"
        File(dir, "$base.4").writeText(line("I", "T", "oldest", time = "10-07 10:00:00.000") + "\n")
        File(dir, "$base.1").writeText(line("I", "T", "middle", time = "10-07 11:00:00.000") + "\n")
        File(dir, "$base.0").writeText(line("I", "T", "newer", time = "10-07 11:30:00.000") + "\n")
        File(dir, base).writeText(line("I", "T", "newest", time = "10-07 12:00:00.000") + "\n")
        File(dir, "nc_talk_log_export.json").writeText("{}")
        File(dir, "other.txt").writeText("ignored")

        val lines = loadAllLogLines(dir)
        assertEquals(listOf("oldest", "middle", "newer", "newest"), lines.map { it.substringAfter(": ") })
        assertEquals(4, loadLogEntries(dir).size)
    }

    @Test
    fun `entries keep the order of the files and secrets of old unmasked files are masked`() {
        val dir = logDir()
        File(dir, "nc_talk_log.txt").writeText(
            listOf(
                line("D", "HTTP", "password=hunter2&x=1", time = "10-07 12:00:02.000"),
                line("D", "CallActivity", "written later, earlier time", time = "10-07 12:00:01.000"),
                "--------- beginning of main",
                line("F", "libc", "Fatal signal 11", time = "10-07 12:00:03.000")
            ).joinToString("\n") + "\n"
        )
        val entries = loadLogEntries(dir)
        assertEquals(listOf("HTTP", "CallActivity", "libc"), entries.map { it.tag })
        val json = buildLogcatJson("pkg", entries)
        assertFalse(json.contains("hunter2"))
        assertTrue(json.contains("password=***"))
        assertTrue(json.contains("\"logLevel\": \"FATAL\""))
    }

    @Test
    fun `json escapes control characters`() {
        val entry = LogEntry.parseHeader(line("E", "T", "bell\u0007 and \"quote\" and back\\slash"))!!
        val json = buildLogcatJson("pkg", listOf(entry))
        assertTrue(json, json.contains("bell\\u0007 and \\\"quote\\\" and back\\\\slash"))
    }
}
