/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import com.nextcloud.talk.errorhandling.buildLogcatJson
import com.nextcloud.talk.errorhandling.loadLogEntries
import com.nextcloud.talk.logger.FakeLogcat.Companion.eventually
import com.nextcloud.talk.logger.FakeLogcat.Companion.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerImplCaptureTest : LoggerImplTestBase() {
    @Test
    fun `call diagnostics from logcat are in the log file and in the export`() {
        val tags = listOf("WebSocketInstance", "CallActivity", "IceDiag", "WebRtcAudioManager", "PeerConnectionWrapper")
        fake.history = tags.mapIndexed { i, tag -> line("D", tag, "diag $i", time = "10-07 12:00:0$i.000") }
        val impl = newLogger()
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { impl.isLogcatCaptureActive })

        assertTrue(eventually { tags.all { fileText().contains(" $it: ") } })

        // the export that "Share diagnosis report" attaches
        val entries = loadLogEntries(logDir)
        val json = buildLogcatJson("com.nextcloud.talk2", entries)
        tags.forEach { tag ->
            assertTrue("$tag missing in export", json.contains("\"tag\": \"$tag\""))
        }
        assertTrue(json.contains("\"message\": \"diag 3\""))
    }

    @Test
    fun `lines of the logger itself are written once while the capture works`() {
        val impl = newLogger()
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { impl.isLogcatCaptureActive })

        impl.d("Dup", "written by the logger")
        impl.w("Dup", "second line")
        // on a device Log.d puts the same lines into logcat
        fake.current.emit(line("D", "Dup", "written by the logger", time = "10-07 12:01:00.000"))
        fake.current.emit(line("W", "Dup", "second line", time = "10-07 12:01:00.001"))

        assertTrue(eventually { fileText().contains("second line") })
        val text = fileText()
        assertEquals(text, 1, countOf(text, "written by the logger"))
        assertEquals(text, 1, countOf(text, "second line"))
    }

    @Test
    fun `own lines held before the check line are not lost and not duplicated`() {
        fake.echoMarker = false
        val impl = newLogger(verifyTimeoutMs = 5000)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { fake.streams.size == 1 })
        impl.d("Early", "logged while the capture starts")
        // on a device Log.d puts the line into logcat before the check line
        fake.current.emit(line("D", "Early", "logged while the capture starts", time = "10-07 12:02:00.000"))
        assertTrue(eventually { fake.emitted.isNotEmpty() })
        fake.deliverEmitted()

        assertTrue(eventually { impl.isLogcatCaptureActive })
        assertTrue(eventually { fileText().contains("logged while the capture starts") })
        assertEquals(1, countOf(fileText(), "logged while the capture starts"))
    }

    @Test
    fun `when logcat is unavailable the logger writes its own lines and one warning`() {
        fake.echoMarker = false
        val impl = newLogger(verifyTimeoutMs = 200)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        impl.i("Own", "line while pending")
        assertTrue(eventually { fileText().contains("logcat capture unavailable: ") })
        impl.i("Own", "line after the failure")
        val text = fileText()

        assertEquals(text, 1, Regex("logcat capture unavailable: ").findAll(text).count())
        assertTrue(text.contains(" W LogcatCapture: logcat capture unavailable: the W check line did not come"))
        assertEquals(text, 1, Regex("line while pending").findAll(text).count())
        assertEquals(text, 1, Regex("line after the failure").findAll(text).count())
        assertFalse(impl.isLogcatCaptureActive)
    }

    @Test
    fun `a failing exec gives the warning with the reason and the old behaviour`() {
        fake.failWith = java.io.IOException("error=13, Permission denied")
        val impl = newLogger()
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { fileText().contains("logcat capture unavailable: cannot start logcat") })
        impl.e("Own", "error after the failure")
        assertTrue(fileText().contains("error after the failure"))
        assertTrue(fileText().contains("Permission denied"))
    }

    @Test
    fun `without a logcat setup nothing changes`() {
        val impl = newLogger(withLogcat = false)
        impl.minimumLevel = Level.INFO
        impl.start()
        impl.i("Own", "plain line")
        impl.d("Own", "below the level")
        val text = fileText()
        assertTrue(text.contains("plain line"))
        assertFalse(text.contains("below the level"))
        assertTrue(fake.launches.isEmpty())
    }

    @Test
    fun `no logcat process at the normal levels`() {
        for (level in listOf(Level.WARNING, Level.INFO, Level.NONE)) {
            val impl = newLogger()
            impl.minimumLevel = level
            impl.start()
            impl.e("Own", "line at " + level.name)
            impl.flush()
            assertFalse(impl.isLogcatCaptureActive)
        }
        assertTrue(fake.launches.isEmpty())
    }

    @Test
    fun `advanced level starts logcat and leaving it stops logcat`() {
        val impl = newLogger()
        impl.minimumLevel = Level.INFO
        impl.start()
        assertTrue(fake.launches.isEmpty())

        impl.minimumLevel = Level.DEBUG
        assertTrue(eventually { impl.isLogcatCaptureActive })
        assertEquals("*:D", fake.launches[0].last())
        val stream = fake.current

        impl.minimumLevel = Level.INFO
        assertTrue(eventually { stream.closed })
        assertFalse(impl.isLogcatCaptureActive)
        impl.i("Own", "written by the logger again")
        assertEquals(1, countOf(fileText(), "written by the logger again"))
    }

    @Test
    fun `level none stops logcat at once and writes nothing`() {
        val impl = startAdvanced()
        val stream = fake.current
        impl.minimumLevel = Level.NONE
        assertTrue(eventually { stream.closed })
        impl.e("Own", "nothing is written with level none")
        assertFalse(fileText().contains("nothing is written with level none"))
    }

    @Test
    fun `lines in transit at the change from advanced to info are neither lost nor doubled`() {
        val impl = startAdvanced()
        impl.d("Own", "debug before the change")
        impl.i("Own", "info before the change")
        // logcat has the first two lines in its pipe when the level changes
        fake.current.emit(line("D", "Own", "debug before the change", time = "10-07 12:20:00.000"))
        fake.current.emit(line("I", "Own", "info before the change", time = "10-07 12:20:00.001"))
        impl.minimumLevel = Level.INFO

        assertTrue(eventually { fileText().contains("debug before the change") })
        assertEquals(1, countOf(fileText(), "debug before the change"))
        assertEquals(1, countOf(fileText(), "info before the change"))
    }

    @Test
    fun `secrets of both sources are masked in the file`() {
        val impl = newLogger()
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { impl.isLogcatCaptureActive })
        fake.current.emit(
            line(
                "D",
                "WebSocketInstance",
                """Receiving : x {"hello":{"resumeid":"Resume12345678"}}""",
                time = "10-07 12:03:00.000"
            )
        )
        fake.current.emit(line("D", "HTTP", "Authorization: Bearer topsecrettoken123", time = "10-07 12:03:00.100"))
        assertTrue(eventually { fileText().contains("WebSocketInstance") })
        assertTrue(eventually { fileText().contains("HTTP: Authorization: ***") })
        assertFalse(fileText().contains("Resume12345678"))
        assertFalse(fileText().contains("topsecrettoken123"))
    }

    @Test
    fun `on a device without debug lines the logger keeps writing its own debug lines`() {
        fake.dropDebug = true
        val impl = newLogger(verifyTimeoutMs = 200)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        impl.d("Own", "debug line while pending")
        assertTrue(eventually { impl.isLogcatCaptureActive })
        impl.d("Own", "debug line after the check")
        impl.i("Own", "info line after the check")
        // logcat delivers the info line, not the debug lines
        fake.current.emit(line("I", "Own", "info line after the check", time = "10-07 12:05:00.000"))

        assertTrue(eventually { fileText().contains(" I Own: info line after the check") })
        val text = fileText()
        assertEquals(text, 1, Regex("debug line while pending").findAll(text).count())
        assertEquals(text, 1, Regex("debug line after the check").findAll(text).count())
        assertEquals(text, 1, Regex("info line after the check").findAll(text).count())
        assertTrue(text, text.contains("logcat capture limited to I and above"))
    }
}
