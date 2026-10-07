/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import com.nextcloud.talk.logger.FakeLogcat.Companion.eventually
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

abstract class LoggerImplTestBase {
    @get:Rule
    val folder = TemporaryFolder()

    protected val fake = FakeLogcat()
    protected lateinit var logDir: File
    protected var handler: FileLogHandler = FileLogHandler(File("unused"), "nc_talk_log.txt", MAX_FILE_SIZE)
    protected var logger: LoggerImpl? = null

    @Before
    fun setUp() {
        logDir = folder.newFolder("logs")
        handler = createHandler()
    }

    protected open fun createHandler() = FileLogHandler(logDir, "nc_talk_log.txt", MAX_FILE_SIZE)

    @After
    fun tearDown() {
        logger?.stopLogcatCapture()
    }

    protected fun newLogger(withLogcat: Boolean = true, verifyTimeoutMs: Long = 2000): LoggerImpl =
        LoggerImpl(
            handler,
            logcatSetup = if (withLogcat) {
                LogcatSetup(
                    fake,
                    pid = PID,
                    markerEmitter = fake.markerEmitter,
                    timing = LogcatTiming(
                        verifyTimeoutMs = verifyTimeoutMs,
                        respawnDelayMs = 10,
                        drainTimeoutMs = DRAIN_MS
                    )
                )
            } else {
                null
            }
        ).also { logger = it }

    /** The log file after the logger has written everything it got so far. */
    protected fun fileText(): String {
        logger!!.flush()
        return handler.loadLogFiles().lines.joinToString("\n")
    }

    protected fun countOf(text: String, part: String) = Regex(Regex.escape(part)).findAll(text).count()

    protected fun startAdvanced(): LoggerImpl {
        val impl = newLogger()
        impl.minimumLevel = Level.DEBUG
        impl.start()
        eventually { impl.isLogcatCaptureActive }
        return impl
    }

    protected companion object {
        const val MAX_FILE_SIZE = 1_000_000L
        const val PID = 4242
        const val DRAIN_MS = 300L
    }
}
