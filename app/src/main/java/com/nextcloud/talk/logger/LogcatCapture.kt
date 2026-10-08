/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lines of the output of one `logcat` run. */
interface LogcatStream : Closeable {
    /**
     * The next line, or null when the output has ended. A [close] from another thread ends a blocked call with null
     * or with an [IOException] (on Android the closed pipe throws).
     */
    @Throws(IOException::class)
    fun readLine(): String?
}

/** Starts `logcat` with [args] (without the program name). Replaced in tests. */
fun interface LogcatLauncher {
    @Throws(IOException::class)
    fun launch(args: List<String>): LogcatStream
}

fun interface MarkerEmitter {
    fun emit(priority: Int, tag: String, message: String)
}

interface LogcatCaptureListener {
    /** A line of logcat (threadtime format) without banner lines and without the capture's own check lines. */
    fun onLine(line: String)

    /**
     * The check line came through logcat: the capture works and delivers every line of [effectiveLevel] and above,
     * so the logger can stop writing its own lines of these levels. [effectiveLevel] is higher than the requested
     * level when the device does not deliver debug lines (some Huawei EMUI versions).
     */
    fun onVerified(effectiveLevel: Level)

    /** The capture does not work (or stopped working): the logger must write its own lines again. */
    fun onUnavailable(reason: String)
}

data class LogcatTiming(
    val verifyTimeoutMs: Long = LogcatCapture.DEFAULT_VERIFY_TIMEOUT_MS,
    val respawnDelayMs: Long = 1000L,
    /** A logcat run that lasted this long counts as stable: the restart counter starts again. */
    val steadyAfterMs: Long = 60_000L,
    /** How long a stopping capture waits for the stop line, so that lines in transit are not lost. */
    val drainTimeoutMs: Long = 1000L,
    /** The logger treats an active capture as dead when no logcat line comes this long after a line it left to it. */
    val stallAfterMs: Long = 5000L
)

/**
 * Runs `logcat -v threadtime --pid=<own pid>` through a small shell wrapper ([ProcessLogcatLauncher]) and hands every
 * line to the listener. The wrapper ends `logcat` as soon as the app process is gone or the wrapper is stopped.
 */
class LogcatCapture(
    private val launcher: LogcatLauncher,
    private val pid: Int,
    private val markerEmitter: MarkerEmitter,
    private val listener: LogcatCaptureListener,
    private val timing: LogcatTiming = LogcatTiming()
) {
    companion object {
        const val MARKER_TAG = "LogcatCapture"
        const val DEFAULT_VERIFY_TIMEOUT_MS = 4000L
        private const val MAX_RESPAWNS = 3
        private const val PREDECESSOR_JOIN_MS = 3000L
        private const val MAX_PENDING_LINES = 20_000
        private const val SPILL_DIVISOR = 2
        private const val NONCE_LENGTH = 8
        private const val JUNK_MAX_LENGTH = 200
        private const val BANNER_PREFIX = "--------- "
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val CHECK_TEXT = "capture check"
        private const val STOP_TEXT = "capture stop"
        private val THREADTIME = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) +\d+ +\d+ [VDIWEFA] .*$""")

        fun priorityOf(level: Level): Int =
            when (level) {
                Level.VERBOSE -> Log.VERBOSE
                Level.DEBUG -> Log.DEBUG
                Level.INFO -> Log.INFO
                Level.WARNING -> Log.WARN
                else -> Log.ERROR
            }

        /** Arguments of logcat: only own process, threadtime format, priority filter of [level], from [since]. */
        fun buildArgs(pid: Int, level: Level, since: String?): List<String> =
            buildList {
                add("-v")
                add("threadtime")
                add("--pid=$pid")
                if (since != null) {
                    add("-T")
                    add(since)
                }
                add("*:${level.tag}")
            }

        // The check and stop lines of any capture of this process. They are not written to the log file.
        private fun isServiceLine(line: String): Boolean =
            line.contains(" $MARKER_TAG: $CHECK_TEXT ") || line.contains(" $MARKER_TAG: $STOP_TEXT ")
    }

    private val lock = Any()
    private val stateLock = Any()

    @Volatile
    private var session: Session? = null

    // The timestamp of the last written line and the lines written at exactly that timestamp, so that a new
    // logcat run (-T includes the timestamp itself) does not write them a second time.
    private var lastTimestamp: String? = null
    private val writtenAtLastTimestamp = HashMap<String, Int>()

    val isActive: Boolean get() = session?.let { it.verified && !it.closed && !it.draining } ?: false

    /**
     * Starts (or restarts) the capture. [fromProcessStart]: read everything the process logged so far, otherwise
     * read from the last written line (or from now).
     */
    fun start(level: Level, fromProcessStart: Boolean) {
        synchronized(lock) {
            val previous = session
            previous?.close()
            if (previous?.draining == true) clearState()
            // A restart before the check line came continues the unverified run: the lines that the logger holds
            // since then must come from logcat again, so the new run reads from the same point.
            val continued = previous?.takeIf { !it.verified && !it.reported }
            session = Session(
                level = level,
                fromProcessStart = fromProcessStart || continued?.fromProcessStart == true,
                requestedAt = continued?.requestedAt ?: System.currentTimeMillis(),
                predecessor = previous
            ).also { it.thread.start() }
        }
    }

    /**
     * Stops the capture. A later [start] begins from now: the time while the capture was off is not read back.
     * With [drain] the capture first reads up to a stop line that it writes to logcat, so that the lines that are
     * still in transit reach the listener; the listener must not write its own lines of the same levels meanwhile.
     */
    fun stop(drain: Boolean = false) {
        val current = synchronized(lock) { session }
        if (drain && current != null && current.verified && !current.closed) {
            current.drain()
            return
        }
        synchronized(lock) {
            session?.close()
            session = null
        }
        clearState()
    }

    private fun clearState() {
        synchronized(stateLock) {
            lastTimestamp = null
            writtenAtLastTimestamp.clear()
        }
    }

    private fun formatTime(millis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(millis)

    private sealed interface Outcome {
        data class Failed(val reason: String, val checkLineMissing: Boolean = false) : Outcome
        data class Ended(val detail: String) : Outcome
    }

    private inner class Session(
        val level: Level,
        val fromProcessStart: Boolean,
        val requestedAt: Long,
        private val predecessor: Session?
    ) : Runnable {
        @Volatile
        var closed = false

        @Volatile
        var verified = false

        @Volatile
        var draining = false
            private set

        @Volatile
        var reported = false
            private set

        // The level of the logcat filter and of the check line. Starts as the requested level; steps down (D, I, W)
        // while the device does not deliver the check line of that level.
        @Volatile
        private var captureLevel = level
        private val triedLevels = mutableListOf<Level>()

        @Volatile
        private var stream: LogcatStream? = null

        @Volatile
        private var timedOut = false

        @Volatile
        private var stopNonce: String? = null
        private val verifiedLatch = CountDownLatch(1)
        val thread = Thread(this, "NcTalkLogcatReader").apply { isDaemon = true }

        // The stream is closed on another thread: closing a pipe must never hold up the caller (the main thread).
        fun close() {
            closed = true
            verifiedLatch.countDown()
            val toClose = stream ?: return
            Thread { runCatching { toClose.close() } }.apply {
                isDaemon = true
                name = "NcTalkLogcatClose"
            }.start()
        }

        fun drain() {
            val nonce = newNonce()
            stopNonce = nonce
            draining = true
            markerEmitter.emit(priorityOf(captureLevel), MARKER_TAG, "$STOP_TEXT $nonce pid=$pid")
            Thread {
                runCatching { Thread.sleep(timing.drainTimeoutMs) }
                if (!closed) finishDrain()
            }.apply {
                isDaemon = true
                name = "NcTalkLogcatDrain"
            }.start()
        }

        private fun finishDrain() {
            close()
            clearState()
        }

        @Suppress("TooGenericExceptionCaught")
        override fun run() {
            try {
                predecessor?.thread?.join(PREDECESSOR_JOIN_MS)
                if (!closed) capture()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                fail("unexpected ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        private fun newNonce() = UUID.randomUUID().toString().take(NONCE_LENGTH)

        private fun capture() {
            var respawns = 0
            var failure: String? = null
            // Fixed for the unverified attempts (also after the step down to INFO): the history from the same point.
            var since = if (fromProcessStart) null else (lastTimestamp ?: formatTime(requestedAt))
            while (!closed && failure == null) {
                val startedAt = System.nanoTime()
                val outcome = runOnce(since)
                failure = (outcome as? Outcome.Failed)?.reason
                if (outcome is Outcome.Failed && outcome.checkLineMissing && stepDown()) {
                    failure = null
                } else if (outcome is Outcome.Ended && !closed) {
                    if ((System.nanoTime() - startedAt) / NANOS_PER_MILLI >= timing.steadyAfterMs) respawns = 0
                    respawns++
                    if (respawns > MAX_RESPAWNS) {
                        failure = "logcat keeps ending: ${outcome.detail}"
                    } else {
                        Thread.sleep(timing.respawnDelayMs)
                        since = lastTimestamp ?: formatTime(System.currentTimeMillis())
                    }
                }
            }
            failure?.let { fail(it) }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun runOnce(since: String?): Outcome {
            val skip = synchronized(stateLock) {
                if (since != null && since == lastTimestamp) HashMap(writtenAtLastTimestamp) else null
            }
            val opened = try {
                launcher.launch(buildArgs(pid, captureLevel, since))
            } catch (e: Exception) {
                return Outcome.Failed("cannot start logcat: ${e.javaClass.simpleName}: ${e.message}")
            }
            stream = opened
            return try {
                if (closed) Outcome.Ended("closed") else readLines(opened, skip)
            } finally {
                runCatching { opened.close() }
            }
        }

        private fun readLines(opened: LogcatStream, skip: HashMap<String, Int>?): Outcome {
            val nonce = newNonce()
            val reader = LineReader(nonce, skip)
            timedOut = false
            if (!verified) {
                startWatchdog(opened)
                markerEmitter.emit(
                    priorityOf(captureLevel),
                    MARKER_TAG,
                    "$CHECK_TEXT $nonce pid=$pid level=${captureLevel.name}"
                )
            }
            var readError: String? = null
            try {
                while (true) {
                    val line = opened.readLine() ?: break
                    if (closed) break
                    reader.accept(line)
                }
            } catch (e: IOException) {
                // A blocked read ends with an exception when the stream is closed (stop or the watchdog).
                if (!closed && !timedOut) readError = "read error: ${e.message}"
            }
            if (!verified && !closed) {
                val reason = if (timedOut) {
                    "the ${captureLevel.tag} check line did not come through logcat in " +
                        "${timing.verifyTimeoutMs} ms${triedText()}${reader.junkText()}"
                } else {
                    "logcat ended before the check line came${reader.junkText()}"
                }
                return Outcome.Failed(reason, checkLineMissing = timedOut)
            }
            return Outcome.Ended(readError ?: reader.junkText().ifEmpty { "end of output" })
        }

        // The device did not deliver the check line of this level: try the next one up. False after W.
        private fun stepDown(): Boolean {
            if (captureLevel >= Level.WARNING) return false
            triedLevels.add(captureLevel)
            captureLevel = if (captureLevel < Level.INFO) Level.INFO else Level.WARNING
            return true
        }

        private fun triedText() =
            if (triedLevels.isEmpty()) "" else " (also tried ${triedLevels.joinToString(", ") { it.tag }})"

        private fun startWatchdog(opened: LogcatStream) {
            Thread {
                val ok = try {
                    verifiedLatch.await(timing.verifyTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    true
                }
                if (!ok && !verified && !closed) {
                    timedOut = true
                    runCatching { opened.close() }
                }
            }.apply {
                isDaemon = true
                name = "NcTalkLogcatWatchdog"
            }.start()
        }

        /** Handles lines of one run: the check line, the buffer before it, the dedup after a restart. */
        private inner class LineReader(private val nonce: String, private var skip: HashMap<String, Int>?) {
            private val pending = ArrayList<String>()
            private var junk: String? = null

            fun junkText(): String = junk?.let { ": $it" } ?: ""

            fun accept(line: String) {
                val match = THREADTIME.matchEntire(line)
                if (match == null) {
                    if (line.isNotBlank() && !line.startsWith(BANNER_PREFIX)) junk = line.take(JUNK_MAX_LENGTH)
                } else if (verified) {
                    deliver(match.groupValues[1], line)
                    if (draining && stopNonce?.let { line.contains(it) } == true) finishDrain()
                } else {
                    pending.add(line)
                    if (line.contains(nonce) && isServiceLine(line)) {
                        confirm()
                    } else if (pending.size > MAX_PENDING_LINES) {
                        spill()
                    }
                }
            }

            private fun confirm() {
                deliverPending(pending.size)
                val go = synchronized(lock) {
                    if (!closed) {
                        verified = true
                        verifiedLatch.countDown()
                    }
                    !closed
                }
                if (go) listener.onVerified(captureLevel)
            }

            private fun spill() = deliverPending(pending.size / SPILL_DIVISOR)

            private fun deliverPending(count: Int) {
                val part = pending.subList(0, count)
                part.forEach { held -> THREADTIME.matchEntire(held)?.let { deliver(it.groupValues[1], held) } }
                part.clear()
            }

            // The state is changed under the lock, the listener (which may block on a full queue) is called outside.
            private fun deliver(timestamp: String, line: String) {
                if (isServiceLine(line)) return
                val write = synchronized(stateLock) { shouldWrite(timestamp, line) }
                if (write && !closed) listener.onLine(line)
            }

            private fun shouldWrite(timestamp: String, line: String): Boolean {
                if (closed || isSkipped(timestamp, line)) return false
                if (timestamp != lastTimestamp) {
                    lastTimestamp = timestamp
                    writtenAtLastTimestamp.clear()
                }
                writtenAtLastTimestamp.merge(line, 1, Int::plus)
                return true
            }

            // The lines of the last millisecond that an earlier run wrote already.
            private fun isSkipped(timestamp: String, line: String): Boolean {
                val budget = skip
                val sameMillisecond = budget != null && timestamp == lastTimestamp
                if (budget != null && !sameMillisecond) skip = null
                val left = if (sameMillisecond) budget?.get(line) ?: 0 else 0
                if (left > 0) budget?.put(line, left - 1)
                return left > 0
            }
        }

        private fun fail(reason: String) {
            val first = synchronized(lock) {
                val wasClosed = closed || reported
                reported = true
                closed = true
                !wasClosed
            }
            verifiedLatch.countDown()
            runCatching { stream?.close() }
            if (first) {
                clearState()
                listener.onUnavailable(reason)
            }
        }
    }
}
