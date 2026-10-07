/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lines of the output of one `logcat` run. */
interface LogcatStream : Closeable {
    /** The next line, or null when the output has ended (also after [close]). */
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
    /** A line of logcat (threadtime format), without the service lines. Called in the order of the lines. */
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

/**
 * Runs `logcat -v threadtime --pid=<own pid>` through a small shell wrapper and hands every line to a sink.
 *
 * The wrapper ends `logcat` as soon as the app process is gone or the wrapper is stopped, so no logcat process
 * outlives the app. [ProcessLogcatLauncher] starts it; tests replace the launcher.
 */
class LogcatCapture(
    private val launcher: LogcatLauncher,
    private val pid: Int,
    private val markerEmitter: MarkerEmitter,
    private val listener: LogcatCaptureListener,
    private val verifyTimeoutMs: Long = DEFAULT_VERIFY_TIMEOUT_MS,
    private val respawnDelayMs: Long = DEFAULT_RESPAWN_DELAY_MS
) {
    companion object {
        const val MARKER_TAG = "LogcatCapture"
        const val DEFAULT_VERIFY_TIMEOUT_MS = 4000L
        private const val DEFAULT_RESPAWN_DELAY_MS = 1000L
        private const val MAX_RESPAWNS = 3
        private const val PREDECESSOR_JOIN_MS = 3000L
        private const val MAX_PENDING_LINES = 20_000
        private const val SPILL_DIVISOR = 2
        private const val NONCE_LENGTH = 8
        private const val JUNK_MAX_LENGTH = 200
        private const val BANNER_PREFIX = "--------- "
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
    }

    private val lock = Any()
    private val stateLock = Any()

    @Volatile
    private var session: Session? = null

    // The timestamp of the last written line and the lines written at exactly that timestamp, so that a new
    // logcat run (-T includes the timestamp itself) does not write them a second time.
    private var lastTimestamp: String? = null
    private val writtenAtLastTimestamp = HashMap<String, Int>()

    val isActive: Boolean get() = session?.let { it.verified && !it.closed } ?: false

    /**
     * Starts (or restarts) the capture. [fromProcessStart]: read everything the process logged so far, otherwise
     * read from the last written line (or from now).
     */
    fun start(level: Level, fromProcessStart: Boolean) {
        synchronized(lock) {
            val previous = session
            previous?.close()
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

    /** Stops the capture. A later [start] begins from now: the time while the capture was off is not read back. */
    fun stop() {
        synchronized(lock) {
            session?.close()
            session = null
        }
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

        // The level of the logcat filter and of the check line. Starts as the requested level; one step down to
        // INFO if the device does not deliver the check line of a debug level.
        @Volatile
        private var captureLevel = level

        @Volatile
        private var stream: LogcatStream? = null
        private val verifiedLatch = CountDownLatch(1)

        @Volatile
        var reported = false
            private set
        val thread = Thread(this, "NcTalkLogcatReader").apply { isDaemon = true }

        fun close() {
            closed = true
            verifiedLatch.countDown()
            runCatching { stream?.close() }
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

        private fun capture() {
            var respawns = 0
            var failure: String? = null
            var since = if (fromProcessStart) null else (lastTimestamp ?: formatTime(requestedAt))
            while (!closed && failure == null) {
                val outcome = runOnce(since)
                failure = (outcome as? Outcome.Failed)?.reason
                if (outcome is Outcome.Failed && outcome.checkLineMissing && captureLevel < Level.INFO) {
                    captureLevel = Level.INFO
                    failure = null
                    continue
                }
                if (outcome is Outcome.Ended && !closed) {
                    respawns++
                    if (respawns > MAX_RESPAWNS) {
                        failure = "logcat keeps ending: ${outcome.detail}"
                    } else {
                        Thread.sleep(respawnDelayMs)
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
            return readAndClose(opened, skip)
        }

        private fun readAndClose(opened: LogcatStream, skip: HashMap<String, Int>?): Outcome =
            try {
                if (closed) Outcome.Ended("closed") else readLines(opened, skip)
            } catch (e: IOException) {
                if (closed) Outcome.Ended("closed") else Outcome.Ended("read error: ${e.message}")
            } finally {
                runCatching { opened.close() }
            }

        private fun readLines(opened: LogcatStream, skip: HashMap<String, Int>?): Outcome {
            val nonce = UUID.randomUUID().toString().take(NONCE_LENGTH)
            val reader = LineReader(nonce, skip)
            timedOut = false
            if (!verified) {
                startWatchdog(opened)
                markerEmitter.emit(
                    priorityOf(captureLevel),
                    MARKER_TAG,
                    "capture check $nonce pid=$pid level=${captureLevel.name}"
                )
            }
            while (true) {
                val line = opened.readLine() ?: break
                if (closed) break
                reader.accept(line)
            }
            if (!verified && !closed) {
                val reason = if (timedOut) {
                    "the ${captureLevel.tag} check line did not come through logcat in " +
                        "$verifyTimeoutMs ms${reader.junkText()}"
                } else {
                    "logcat ended before the check line came${reader.junkText()}"
                }
                return Outcome.Failed(reason, checkLineMissing = timedOut)
            }
            return Outcome.Ended(reader.junkText().ifEmpty { "end of output" })
        }

        @Volatile
        private var timedOut = false

        private fun startWatchdog(opened: LogcatStream) {
            Thread {
                val ok = try {
                    verifiedLatch.await(verifyTimeoutMs, TimeUnit.MILLISECONDS)
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
                    write(match.groupValues[1], line)
                } else {
                    pending.add(line)
                    if (line.contains(nonce) && line.contains(MARKER_TAG)) {
                        confirm()
                    } else if (pending.size > MAX_PENDING_LINES) {
                        spill()
                    }
                }
            }

            private fun confirm() {
                for (held in pending) {
                    THREADTIME.matchEntire(held)?.let { write(it.groupValues[1], held) }
                }
                pending.clear()
                synchronized(lock) {
                    if (closed) return
                    verified = true
                    verifiedLatch.countDown()
                    listener.onVerified(captureLevel)
                }
            }

            private fun spill() {
                val half = pending.size / SPILL_DIVISOR
                pending.subList(0, half).forEach { held ->
                    THREADTIME.matchEntire(held)?.let { write(it.groupValues[1], held) }
                }
                pending.subList(0, half).clear()
            }

            private fun write(timestamp: String, line: String) {
                synchronized(stateLock) {
                    if (closed) return
                    if (skip != null) {
                        if (timestamp == lastTimestamp) {
                            val left = skip?.get(line) ?: 0
                            if (left > 0) {
                                skip?.put(line, left - 1)
                                return
                            }
                        } else {
                            skip = null
                        }
                    }
                    listener.onLine(line)
                    if (timestamp != lastTimestamp) {
                        lastTimestamp = timestamp
                        writtenAtLastTimestamp.clear()
                    }
                    writtenAtLastTimestamp.merge(line, 1, Int::plus)
                }
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
                synchronized(stateLock) {
                    lastTimestamp = null
                    writtenAtLastTimestamp.clear()
                }
                listener.onUnavailable(reason)
            }
        }
    }
}

/**
 * Starts logcat through `sh`. The shell script runs logcat in the background and polls both the app process
 * (`$PPID`) and logcat: when the app dies or logcat ends, the script ends logcat / itself, and when the wrapper
 * gets SIGTERM (stop) the trap ends logcat. Without this, a logcat of a dead app would stay forever, because it
 * only notices a closed pipe when it writes the next line.
 */
class ProcessLogcatLauncher(private val shell: String = "sh", private val logcat: String = "logcat") :
    LogcatLauncher {

    companion object {
        const val WRAPPER_SCRIPT =
            "\"\$@\" & LP=\$!\n" +
                "trap 'kill \$LP 2>/dev/null; exit 0' TERM HUP INT\n" +
                "while kill -0 \$PPID 2>/dev/null && kill -0 \$LP 2>/dev/null; do sleep 2 & wait \$!; done\n" +
                "kill \$LP 2>/dev/null\n"
    }

    override fun launch(args: List<String>): LogcatStream {
        val command = listOf(shell, "-c", WRAPPER_SCRIPT, "sh", logcat) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        return object : LogcatStream {
            private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

            override fun readLine(): String? = reader.readLine()

            override fun close() {
                // destroy() first: BufferedReader.close() would wait for a readLine() that is blocked.
                process.destroy()
                runCatching { process.inputStream.close() }
            }
        }
    }
}
