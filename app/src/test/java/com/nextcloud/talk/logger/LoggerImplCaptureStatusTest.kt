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

/** The state of the logcat capture is in the log file (also after "Delete all logs"), and AppLog reaches the file. */
class LoggerImplCaptureStatusTest : LoggerImplTestBase() {
    private val diagTags = listOf("WebSocketInstance", "CallActivity", "NotificationWorker", "CallRecoveryPolicy")

    @After
    fun uninstallFacade() {
        AppLog.install(null)
    }

    private fun failedCapture(): LoggerImpl {
        fake.echoMarker = false
        val impl = newLogger(verifyTimeoutMs = 100)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { fileText().contains("logcat capture unavailable: ") })
        return impl
    }

    @Test
    fun `the refusal of the capture survives Delete all logs`() {
        val impl = failedCapture()

        impl.deleteAll()
        val text = fileText()

        assertEquals(text, 1, countOf(text, " W LogcatCapture: logcat capture unavailable: "))
        assertTrue(text, text.contains("(also tried D, I)"))
        assertTrue(impl.captureStatus, impl.captureStatus.startsWith("unavailable: "))
    }

    @Test
    fun `the positive state is in the file and comes back after Delete all logs`() {
        val impl = startAdvanced()
        assertTrue(eventually { fileText().contains(" I LogcatCapture: logcat capture active from D") })
        assertEquals("active from D", impl.captureStatus)

        impl.deleteAll()
        val text = fileText()

        assertEquals(text, 1, countOf(text, " I LogcatCapture: logcat capture active from D"))
    }

    @Test
    fun `status is off at the normal level and nothing is written after the delete`() {
        val impl = newLogger()
        impl.minimumLevel = Level.WARNING
        impl.start()
        impl.w("Own", "warning")
        impl.deleteAll()

        assertFalse(fileText().contains("LogcatCapture"))
        assertTrue(impl.captureStatus, impl.captureStatus.startsWith("off"))
    }

    @Test
    fun `a device that gives only warnings steps down to W and keeps the lower own lines`() {
        fake.minPriority = 5
        val impl = newLogger(verifyTimeoutMs = 150)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { impl.isLogcatCaptureActive })
        AppLog.install(impl)
        AppLog.d("CallActivity", "debug line")
        AppLog.i("CallActivity", "info line")

        assertEquals(listOf("D", "I", "W"), fake.launches.map { it.last().removePrefix("*:") })
        assertTrue(eventually { fileText().contains("info line") })
        val text = fileText()
        assertTrue(text, text.contains("logcat capture limited to W and above"))
        assertEquals("limited to W and above", impl.captureStatus)
        assertEquals(text, 1, countOf(text, "debug line"))

        impl.deleteAll()
        assertTrue(fileText().contains("logcat capture limited to W and above"))
    }

    @Test
    fun `with a refused capture the diagnostic lines of the call reach the file through AppLog`() {
        val impl = failedCapture()
        AppLog.install(impl)

        diagTags.forEachIndexed { i, tag -> AppLog.d(tag, "diag $i") }
        AppLog.e("NotificationWorker", "push failed", IllegalStateException("boom"))
        AppLog.w("CallRecoveryPolicy", "network switched")

        val text = fileText()
        diagTags.forEachIndexed { i, tag -> assertTrue("$tag missing\n$text", text.contains(" $tag: diag $i")) }
        assertTrue(text, text.contains(" E NotificationWorker: push failed"))
        assertTrue(text, text.contains(" W CallRecoveryPolicy: network switched"))
    }

    @Test
    fun `with a working capture a line through AppLog is written once`() {
        val impl = startAdvanced()
        AppLog.install(impl)

        AppLog.d("WebSocketInstance", "Open webSocket 1")
        AppLog.w("CallActivity", "ice failed")
        // on a device the logger puts the same lines into logcat
        fake.current.emit(line("D", "WebSocketInstance", "Open webSocket 1", time = "10-07 12:10:00.000"))
        fake.current.emit(line("W", "CallActivity", "ice failed", time = "10-07 12:10:00.001"))

        assertTrue(eventually { fileText().contains("ice failed") })
        val text = fileText()
        assertEquals(text, 1, countOf(text, "Open webSocket 1"))
        assertEquals(text, 1, countOf(text, "ice failed"))
    }

    @Test
    fun `without an installed logger AppLog does not fail`() {
        AppLog.install(null)
        AppLog.d("Any", "no logger yet")
        AppLog.w("Any", "no logger yet", IllegalStateException("x"))
        AppLog.e("Any", "no logger yet", IllegalStateException("y"))
    }

    @Test
    fun `logcat that stays silent is detected and the lines left to it are written`() {
        fake.echoMarker = true
        val impl = newLogger(stallAfterMs = 300)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { impl.isLogcatCaptureActive })
        AppLog.install(impl)

        AppLog.w("WebSocketInstance", "line that logcat never delivers")

        assertTrue(eventually { fileText().contains("logcat capture unavailable: no line came from logcat") })
        val text = fileText()
        assertEquals(text, 1, countOf(text, "line that logcat never delivers"))
        assertFalse(impl.isLogcatCaptureActive)
        assertTrue(impl.captureStatus, impl.captureStatus.startsWith("unavailable: "))
        // from now on the logger writes its own lines again
        AppLog.w("WebSocketInstance", "line after the stall")
        assertEquals(1, countOf(fileText(), "line after the stall"))
    }

    @Test
    fun `a delivered line keeps the capture alive`() {
        val impl = newLogger(stallAfterMs = 300)
        impl.minimumLevel = Level.DEBUG
        impl.start()
        assertTrue(eventually { impl.isLogcatCaptureActive })
        AppLog.install(impl)

        AppLog.w("WebSocketInstance", "delivered line")
        fake.current.emit(line("W", "WebSocketInstance", "delivered line", time = "10-07 12:11:00.000"))
        assertTrue(eventually { fileText().contains("delivered line") })
        Thread.sleep(900)

        assertTrue(impl.isLogcatCaptureActive)
        assertEquals(fileText(), 1, countOf(fileText(), "delivered line"))
        assertFalse(fileText().contains("no line came from logcat"))
    }
}
