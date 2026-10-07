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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LoggerImplCaptureTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fake = FakeLogcat()
    private lateinit var logDir: File
    private lateinit var handler: FileLogHandler
    private var logger: LoggerImpl? = null

    @Before
    fun setUp() {
        logDir = folder.newFolder("logs")
        handler = FileLogHandler(logDir, "nc_talk_log.txt", 1_000_000L)
    }

    @After
    fun tearDown() {
        logger?.stopLogcatCapture()
    }

    private fun newLogger(withLogcat: Boolean = true, verifyTimeoutMs: Long = 2000): LoggerImpl =
        LoggerImpl(
            handler,
            logcatSetup = if (withLogcat) {
                LogcatSetup(fake, pid = 4242, markerEmitter = fake.markerEmitter, verifyTimeoutMs = verifyTimeoutMs)
            } else {
                null
            }
        ).also { logger = it }

    private fun fileText(): String {
        logger!!.flush()
        return handler.loadLogFiles().lines.joinToString("\n")
    }

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
        assertEquals(text, 1, Regex("written by the logger").findAll(text).count())
        assertEquals(text, 1, Regex("second line").findAll(text).count())
    }

    @Test
    fun `own lines held before the check line are not lost and not duplicated`() {
        fake.echoMarker = false
        fake.history = listOf(line("I", "Early", "logged while the capture starts", time = "10-07 12:02:00.000"))
        val impl = newLogger(verifyTimeoutMs = 5000)
        impl.minimumLevel = Level.INFO
        impl.start()
        assertTrue(eventually { fake.streams.size == 1 })
        impl.i("Early", "logged while the capture starts")

        // the level changes before the check line came: the new run reads from the same point again
        fake.echoMarker = true
        impl.minimumLevel = Level.DEBUG
        assertTrue(eventually { impl.isLogcatCaptureActive })
        assertEquals(2, fake.launches.size)
        assertFalse(fake.launches.any { it.contains("-T") })
        assertTrue(eventually { fileText().contains("logged while the capture starts") })
        assertEquals(1, Regex("logged while the capture starts").findAll(fileText()).count())
    }

    @Test
    fun `when logcat is unavailable the logger writes its own lines and one warning`() {
        fake.echoMarker = false
        val impl = newLogger(verifyTimeoutMs = 200)
        impl.minimumLevel = Level.INFO
        impl.start()
        impl.i("Own", "line while pending")
        assertTrue(eventually { fileText().contains("logcat capture unavailable: ") })
        impl.i("Own", "line after the failure")
        val text = fileText()

        assertEquals(text, 1, Regex("logcat capture unavailable: ").findAll(text).count())
        assertTrue(text.contains(" W LogcatCapture: logcat capture unavailable: the I check line did not come"))
        assertEquals(text, 1, Regex("line while pending").findAll(text).count())
        assertEquals(text, 1, Regex("line after the failure").findAll(text).count())
        assertFalse(impl.isLogcatCaptureActive)
    }

    @Test
    fun `a failing exec gives the warning with the reason and the old behaviour`() {
        fake.failWith = java.io.IOException("error=13, Permission denied")
        val impl = newLogger()
        impl.minimumLevel = Level.WARNING
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
    fun `level none stops logcat and level change restarts it with the new filter`() {
        val impl = newLogger()
        impl.minimumLevel = Level.INFO
        impl.start()
        assertTrue(eventually { impl.isLogcatCaptureActive })
        assertEquals("*:I", fake.launches[0].last())
        val first = fake.current

        impl.minimumLevel = Level.DEBUG
        assertTrue(eventually { fake.launches.size == 2 })
        assertEquals("*:D", fake.launches[1].last())
        assertTrue(eventually { first.closed })
        assertTrue(eventually { impl.isLogcatCaptureActive })

        val second = fake.current
        impl.minimumLevel = Level.NONE
        assertTrue(second.closed)
        assertFalse(impl.isLogcatCaptureActive)
        impl.e("Own", "nothing is written with level none")
        assertFalse(fileText().contains("nothing is written with level none"))
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
