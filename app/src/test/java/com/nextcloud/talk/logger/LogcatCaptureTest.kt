/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import com.nextcloud.talk.logger.FakeLogcat.Companion.eventually
import com.nextcloud.talk.logger.FakeLogcat.Companion.line
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class LogcatCaptureTest {
    private val fake = FakeLogcat()
    private val written = CopyOnWriteArrayList<String>()
    private val verified = CopyOnWriteArrayList<Level>()
    private val unavailable = CopyOnWriteArrayList<String>()
    private var capture: LogcatCapture? = null

    private val listener = object : LogcatCaptureListener {
        override fun onLine(line: String) {
            written.add(line)
        }

        override fun onVerified(effectiveLevel: Level) {
            verified.add(effectiveLevel)
        }

        override fun onUnavailable(reason: String) {
            unavailable.add(reason)
        }
    }

    private fun newCapture(verifyTimeoutMs: Long = 2000, respawnDelayMs: Long = 10): LogcatCapture =
        LogcatCapture(
            launcher = fake,
            pid = 4242,
            markerEmitter = fake.markerEmitter,
            listener = listener,
            verifyTimeoutMs = verifyTimeoutMs,
            respawnDelayMs = respawnDelayMs
        ).also { capture = it }

    @After
    fun tearDown() {
        capture?.stop()
    }

    @Test
    fun `logcat is started for the own process with threadtime and the priority of the level`() {
        newCapture().start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { fake.launches.size == 1 })
        assertEquals(listOf("-v", "threadtime", "--pid=4242", "*:D"), fake.launches[0])
        assertTrue(eventually { verified.size == 1 })

        capture!!.start(Level.WARNING, fromProcessStart = false)
        assertTrue(eventually { fake.launches.size == 2 })
        assertEquals("*:W", fake.launches[1].last())
        assertEquals("-T", fake.launches[1][3])
    }

    @Test
    fun `call diagnostics from logcat reach the sink once the check line came`() {
        val tags = listOf("WebSocketInstance", "CallActivity", "IceDiag", "WebRtcAudioManager", "PeerConnectionWrapper")
        fake.history = listOf("--------- beginning of main") +
            tags.mapIndexed { i, tag -> line("D", tag, "message $i", time = "10-07 12:00:01.00$i") }
        newCapture().start(Level.DEBUG, fromProcessStart = true)

        assertTrue(eventually { verified.size == 1 })
        assertEquals(Level.DEBUG, verified[0])
        assertTrue(eventually { written.count { l -> tags.any { l.contains(" $it: ") } } == tags.size })
        assertTrue(unavailable.isEmpty())
        assertTrue(capture!!.isActive)
        assertFalse(written.any { it.startsWith("---------") })
        // the check line is written too: it shows in the file that and when the capture started
        assertTrue(written.any { it.contains("LogcatCapture: capture check") })
    }

    @Test
    fun `lines after the check line are written at once and verbose and fatal lines are kept as they are`() {
        newCapture().start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        val v = line("V", "OkHttp", "v line", time = "10-07 12:00:02.000")
        val f = line("F", "libc", "Fatal signal 6", time = "10-07 12:00:03.000")
        val a = line("A", "Tag", "wtf", time = "10-07 12:00:04.000")
        listOf(v, f, a).forEach { fake.current.emit(it) }
        assertTrue(eventually { written.containsAll(listOf(v, f, a)) })
    }

    @Test
    fun `a missing check line makes the capture unavailable and logcat is stopped`() {
        fake.echoMarker = false
        fake.history = listOf(line("I", "Other", "some line"))
        newCapture(verifyTimeoutMs = 200).start(Level.DEBUG, fromProcessStart = true)

        assertTrue(eventually { unavailable.size == 1 })
        assertTrue(unavailable[0], unavailable[0].contains("did not come through logcat in 200 ms"))
        assertTrue(eventually { fake.current.closed })
        assertTrue(verified.isEmpty())
        assertTrue("held lines must not be written after a failure", written.isEmpty())
        assertFalse(capture!!.isActive)
    }

    @Test
    fun `an error text of logcat is part of the reason`() {
        fake.echoMarker = false
        fake.history = listOf("logcat: Permission denied")
        newCapture(verifyTimeoutMs = 5000).start(Level.INFO, fromProcessStart = true)
        assertTrue(eventually { fake.streams.size == 1 })
        fake.current.end()
        assertTrue(eventually { unavailable.size == 1 })
        assertTrue(unavailable[0], unavailable[0].contains("logcat: Permission denied"))
    }

    @Test
    fun `a failing exec makes the capture unavailable`() {
        fake.failWith = IOException("Cannot run program \"sh\": error=13, Permission denied")
        newCapture().start(Level.INFO, fromProcessStart = true)
        assertTrue(eventually { unavailable.size == 1 })
        assertTrue(unavailable[0], unavailable[0].startsWith("cannot start logcat: IOException"))
        assertTrue(unavailable[0].contains("Permission denied"))
    }

    @Test
    fun `stop closes the stream and nothing is written afterwards`() {
        newCapture().start(Level.DEBUG, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        val stream = fake.current
        capture!!.stop()
        assertTrue(stream.closed)
        val before = written.size
        stream.emit(line("D", "Late", "after stop", time = "10-07 12:30:00.000"))
        Thread.sleep(150)
        assertEquals(before, written.size)
        assertTrue(unavailable.isEmpty())
    }

    @Test
    fun `a restart in the same process does not write the history again`() {
        fake.history = listOf(
            line("I", "A", "one", time = "10-07 12:00:05.000"),
            line("I", "A", "two", time = "10-07 12:00:06.500"),
            line("I", "A", "two", time = "10-07 12:00:06.500"),
            line("I", "B", "three", time = "10-07 12:00:06.500")
        )
        fake.markerTime = "10-07 12:00:06.500"
        newCapture().start(Level.INFO, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        assertTrue(eventually { written.count { !it.contains("LogcatCapture") } == 4 })

        // logcat -T includes the whole last millisecond, so the run starts with the lines written already
        fake.history = fake.history + line("I", "A", "four", time = "10-07 12:00:07.000")
        capture!!.start(Level.DEBUG, fromProcessStart = false)
        assertTrue(eventually { fake.launches.size == 2 })
        assertTrue(fake.launches[1].joinToString(" "), fake.launches[1].containsAll(listOf("-T", "10-07 12:00:06.500")))
        assertTrue(eventually { written.any { it.contains("four") } })

        val body = written.filter { !it.contains("LogcatCapture") }
        assertEquals(body.toString(), 5, body.size)
        assertEquals(1, body.count { it.contains(" A: one") })
        assertEquals(2, body.count { it.contains(" A: two") })
        assertEquals(1, body.count { it.contains(" B: three") })
    }

    @Test
    fun `a new capture after stop starts from now and not from the old history`() {
        newCapture().start(Level.INFO, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        fake.current.emit(line("I", "A", "old", time = "10-07 12:00:09.000"))
        assertTrue(eventually { written.any { it.contains("old") } })
        capture!!.stop()

        capture!!.start(Level.INFO, fromProcessStart = false)
        assertTrue(eventually { fake.launches.size == 2 })
        val since = fake.launches[1][fake.launches[1].indexOf("-T") + 1]
        assertTrue(since, since != "10-07 12:00:09.000")
    }

    @Test
    fun `logcat that ends by itself is started again from the last line`() {
        newCapture().start(Level.INFO, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        fake.current.emit(line("I", "A", "before", time = "10-07 12:00:10.000"))
        assertTrue(eventually { written.any { it.contains("before") } })

        fake.current.end()
        assertTrue(eventually { fake.launches.size == 2 })
        assertTrue(fake.launches[1].containsAll(listOf("-T", "10-07 12:00:10.000")))
        fake.current.emit(line("I", "A", "after", time = "10-07 12:00:11.000"))
        assertTrue(eventually { written.any { it.contains("after") } })
        assertTrue(unavailable.isEmpty())
    }

    @Test
    fun `logcat that keeps ending makes the capture unavailable`() {
        newCapture().start(Level.INFO, fromProcessStart = true)
        assertTrue(eventually { verified.size == 1 })
        var ended = 0
        assertTrue(
            eventually {
                if (fake.streams.size > ended) {
                    ended = fake.streams.size
                    fake.current.end()
                }
                unavailable.isNotEmpty()
            }
        )
        assertTrue(unavailable[0], unavailable[0].startsWith("logcat keeps ending"))
    }

    @Test
    fun `a device that drops debug lines gets a capture at info level`() {
        fake.dropDebug = true
        newCapture(verifyTimeoutMs = 200).start(Level.DEBUG, fromProcessStart = true)

        assertTrue(eventually { verified.size == 1 })
        assertEquals(listOf(Level.INFO), verified.toList())
        assertEquals(2, fake.launches.size)
        assertEquals("*:D", fake.launches[0].last())
        assertEquals("*:I", fake.launches[1].last())
        assertTrue(fake.streams[0].closed)
        assertTrue(unavailable.isEmpty())
        assertTrue(capture!!.isActive)
    }
}
