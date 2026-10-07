/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import org.junit.After
import java.util.concurrent.CopyOnWriteArrayList

abstract class LogcatCaptureTestBase {
    protected val fake = FakeLogcat()
    protected val written = CopyOnWriteArrayList<String>()
    protected val verified = CopyOnWriteArrayList<Level>()
    protected val unavailable = CopyOnWriteArrayList<String>()
    protected var capture: LogcatCapture? = null

    protected val listener = object : LogcatCaptureListener {
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

    protected fun newCapture(
        timing: LogcatTiming = LogcatTiming(verifyTimeoutMs = VERIFY_MS, respawnDelayMs = RESPAWN_MS)
    ) = LogcatCapture(fake, PID, fake.markerEmitter, listener, timing).also { capture = it }

    @After
    fun tearDown() {
        capture?.stop()
    }

    protected companion object {
        const val PID = 4242
        const val VERIFY_MS = 2000L
        const val RESPAWN_MS = 10L
    }
}
