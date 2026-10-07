/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * How the logger captures the logcat of the own process (see [LogcatCapture]). Without it, or when the capture does
 * not work, the logger writes only its own calls.
 */
data class LogcatSetup(
    val launcher: LogcatLauncher,
    val pid: Int = Process.myPid(),
    val markerEmitter: MarkerEmitter = MarkerEmitter { priority, tag, message -> Log.println(priority, tag, message) },
    val timing: LogcatTiming = LogcatTiming()
)

/**
 * Writes the log file. With the advanced log level (DEBUG) and a working logcat capture it writes every line of the
 * own process; the logger's own calls are in logcat too, so they are not written a second time. At every other level,
 * and when the capture does not work, it writes only its own calls.
 */
@Suppress("TooManyFunctions")
class LoggerImpl(
    private val handler: FileLogHandler,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    logcatSetup: LogcatSetup? = null
) : Logger,
    LogsRepository,
    LogcatCaptureListener {

    // OFF: the logger writes its own entries. PENDING: the capture is starting, own entries are held until the
    // check line shows that logcat works. ACTIVE: logcat delivers the lines, own entries are dropped (unless logcat
    // cannot deliver them whole).
    private enum class CaptureMode { OFF, PENDING, ACTIVE }

    companion object {
        private val TAG = LoggerImpl::class.java.simpleName
        private const val DEFAULT_QUEUE_CAPACITY = 1000
        private const val MAX_HELD_ENTRIES = 5000
        private const val ENQUEUE_TIMEOUT_SECONDS = 5L

        // logd keeps at most about 4 KB of tag and message in one record; the rest of a longer entry is cut.
        private const val LOGCAT_PAYLOAD_LIMIT = 4000
        private const val MAX_UTF8_BYTES_PER_CHAR = 3
        private const val CAPTURE_TAG = LogcatCapture.MARKER_TAG
        private const val LIMITED_MESSAGE =
            "logcat capture limited to %s and above: this device does not deliver the lower levels to logcat. " +
                "The logger writes its own lines of the lower levels."
        const val PREFS_NAME = "logger_prefs"
        const val PREF_LOG_LEVEL = "log_level"
        val DEFAULT_LEVEL = Level.WARNING
    }

    private data class Load(val onResult: (List<LogEntry>, Long) -> Unit)
    private class Delete
    private class Flush(val latch: CountDownLatch)
    private class RawLine(val text: String)

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val eventQueue = LinkedBlockingQueue<Any>(queueCapacity)
    private val processedEvents = mutableListOf<Any>()
    private val otherEvents = mutableListOf<Any>()
    private val missedLogs = AtomicBoolean()
    private val missedLogsCount = AtomicLong()

    @Suppress("TooGenericExceptionCaught")
    private val thread = Thread {
        while (!Thread.currentThread().isInterrupted) {
            try {
                eventLoop()
            } catch (_: InterruptedException) {
                Log.w(TAG, "Logger thread interrupted, shutting down")
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                // The logger must outlive a failing write: the next events are written as usual.
                runCatching { Log.e(TAG, "Logger loop failed, going on: " + t) }
                countMissed()
            }
        }
    }.apply {
        isDaemon = true
        name = "NcTalkLoggerThread"
    }

    override val lostEntries: Boolean get() = missedLogs.get()

    @Volatile
    private var started = false

    private val captureLock = Any()
    private var captureMode = CaptureMode.OFF
    private val heldEntries = ArrayList<LogEntry>()

    // The lowest level that logcat delivers while the capture is verified; own entries below it are still written.
    private var captureFloor = Level.NONE

    private val capture: LogcatCapture? = logcatSetup?.let { setup ->
        LogcatCapture(
            launcher = setup.launcher,
            pid = setup.pid,
            markerEmitter = setup.markerEmitter,
            listener = this,
            timing = setup.timing
        )
    }

    @Volatile
    override var minimumLevel: Level = DEFAULT_LEVEL
        set(value) {
            field = value
            if (started) updateCapture(fromProcessStart = false)
        }

    /** True while the logcat capture works and writes the lines of the whole process. */
    val isLogcatCaptureActive: Boolean get() = capture?.isActive == true

    fun start() {
        thread.start()
        started = true
        updateCapture(fromProcessStart = true)
    }

    /**
     * Stops the logcat capture and its process. The logger writes its own lines again at once. With [drain] the
     * capture still reads up to a stop line, so the lines that are in transit are not lost.
     */
    fun stopLogcatCapture(drain: Boolean = false) {
        val held = synchronized(captureLock) {
            val release = if (captureMode == CaptureMode.PENDING) takeHeld { it.level >= minimumLevel } else emptyList()
            captureMode = CaptureMode.OFF
            captureFloor = Level.NONE
            release
        }
        held.forEach { offerEntry(it) }
        capture?.stop(drain)
    }

    // The capture runs only for the advanced log level: at every other level there is no sh and no logcat process.
    private fun updateCapture(fromProcessStart: Boolean) {
        val capture = capture ?: return
        val level = minimumLevel
        if (level > Level.DEBUG) {
            stopLogcatCapture(drain = level != Level.NONE)
            return
        }
        val begin = synchronized(captureLock) {
            (captureMode == CaptureMode.OFF).also { if (it) captureMode = CaptureMode.PENDING }
        }
        if (begin) capture.start(level, fromProcessStart)
    }

    // Must be called with captureLock held.
    private fun takeHeld(filter: (LogEntry) -> Boolean): List<LogEntry> {
        val taken = heldEntries.filter(filter)
        heldEntries.clear()
        return taken
    }

    override fun onLine(line: String) {
        try {
            if (!eventQueue.offer(RawLine(line), ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) countMissed()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // The held entries are written with put() on the thread of the capture session, outside of captureLock.
    override fun onVerified(effectiveLevel: Level) {
        val release = synchronized(captureLock) {
            if (captureMode == CaptureMode.OFF) return
            captureFloor = effectiveLevel
            val own = if (captureMode == CaptureMode.PENDING) {
                takeHeld { (it.level < effectiveLevel && it.level >= minimumLevel) || truncatedByLogcat(it) }
            } else {
                emptyList()
            }
            captureMode = CaptureMode.ACTIVE
            if (effectiveLevel > minimumLevel) own + warningEntry(LIMITED_MESSAGE.format(effectiveLevel.tag)) else own
        }
        release.forEach { putEntry(it) }
    }

    override fun onUnavailable(reason: String) {
        Log.w(CAPTURE_TAG, "logcat capture unavailable: $reason")
        val release = synchronized(captureLock) {
            captureMode = CaptureMode.OFF
            captureFloor = Level.NONE
            takeHeld { it.level >= minimumLevel } + warningEntry("logcat capture unavailable: $reason")
        }
        release.forEach { putEntry(it) }
    }

    private fun warningEntry(message: String) =
        LogEntry(
            timestamp = Date(),
            level = Level.WARNING,
            tag = CAPTURE_TAG,
            message = message,
            pid = Process.myPid(),
            tid = Process.myTid()
        )

    private fun countMissed() {
        missedLogs.set(true)
        missedLogsCount.incrementAndGet()
    }

    private fun offerEntry(entry: LogEntry) {
        if (!eventQueue.offer(entry)) countMissed()
    }

    private fun putEntry(entry: LogEntry) {
        try {
            if (!eventQueue.offer(entry, ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) countMissed()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // Writes the entries that are held while the capture is not verified. Used before the process may die.
    private fun flushHeld() {
        val held = synchronized(captureLock) {
            if (captureMode == CaptureMode.PENDING) takeHeld { it.level >= minimumLevel } else emptyList()
        }
        held.forEach { offerEntry(it) }
    }

    fun flush(timeoutMs: Long = 2000L) {
        flushHeld()
        val latch = CountDownLatch(1)
        if (eventQueue.offer(Flush(latch), 1, TimeUnit.SECONDS)) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    override fun d(tag: String, message: String) {
        Log.d(tag, message)
        enqueue(Level.DEBUG, tag, message)
    }

    override fun d(tag: String, message: String, t: Throwable) {
        Log.d(tag, message, t)
        enqueue(Level.DEBUG, tag, "$message\n${Log.getStackTraceString(t)}")
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
        enqueue(Level.INFO, tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
        enqueue(Level.WARNING, tag, message)
    }

    override fun w(tag: String, message: String, t: Throwable) {
        Log.w(tag, message, t)
        enqueue(Level.WARNING, tag, "$message\n${Log.getStackTraceString(t)}")
    }

    override fun e(tag: String, message: String) {
        Log.e(tag, message)
        enqueue(Level.ERROR, tag, message)
    }

    override fun e(tag: String, message: String, t: Throwable) {
        Log.e(tag, message, t)
        enqueue(Level.ERROR, tag, "$message\n${Log.getStackTraceString(t)}")
    }

    override fun load(onLoaded: (entries: List<LogEntry>, totalLogSize: Long) -> Unit) {
        eventQueue.put(Load(onLoaded))
    }

    override fun deleteAll() {
        eventQueue.put(Delete())
    }

    private fun enqueue(level: Level, tag: String, message: String) {
        if (level.ordinal < minimumLevel.ordinal) return
        try {
            val entry = LogEntry(
                timestamp = Date(),
                level = level,
                tag = tag,
                message = message,
                pid = Process.myPid(),
                tid = Process.myTid()
            )
            if (!holdOrDrop(entry)) return
            if (!eventQueue.offer(entry, 1, TimeUnit.SECONDS)) countMissed()
        } catch (_: InterruptedException) {
            Log.w(TAG, "Interrupted while enqueueing log entry for tag $tag")
            Thread.currentThread().interrupt()
        }
    }

    // False when logcat writes the line (ACTIVE, level at or above the floor, short enough) or the line is held
    // (PENDING).
    private fun holdOrDrop(entry: LogEntry): Boolean =
        synchronized(captureLock) {
            when (captureMode) {
                CaptureMode.OFF -> true
                CaptureMode.ACTIVE -> entry.level < captureFloor || truncatedByLogcat(entry)
                CaptureMode.PENDING -> {
                    if (heldEntries.size < MAX_HELD_ENTRIES) heldEntries.add(entry) else countMissed()
                    false
                }
            }
        }

    private fun truncatedByLogcat(entry: LogEntry): Boolean {
        if (entry.message.length * MAX_UTF8_BYTES_PER_CHAR <= LOGCAT_PAYLOAD_LIMIT) return false
        return entry.tag.length + entry.message.toByteArray(Charsets.UTF_8).size > LOGCAT_PAYLOAD_LIMIT
    }

    private fun eventLoop() {
        processedEvents.clear()
        otherEvents.clear()

        processedEvents.add(eventQueue.take())
        eventQueue.drainTo(processedEvents)

        try {
            writeEvents()
        } finally {
            runCatching { handler.close() }
            handleOtherEvents()
        }
        writeLostNotice()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeEvents() {
        try {
            handler.open()
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot open the log file: " + t)
            processedEvents.forEach { if (it is LogEntry || it is RawLine) countMissed() else otherEvents.add(it) }
            return
        }
        for (event in processedEvents) {
            when (event) {
                is LogEntry -> writeSafely(event.toString() + "\n")
                is RawLine -> writeSafely(event.text + "\n")
                else -> otherEvents.add(event)
            }
        }
    }

    // A failing write loses that entry only; the file is opened again for the next ones.
    @Suppress("TooGenericExceptionCaught")
    private fun writeSafely(text: String) {
        try {
            handler.write(text)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot write to the log file: " + t)
            countMissed()
            runCatching { handler.close() }
            runCatching { handler.open() }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleOtherEvents() {
        for (event in otherEvents) {
            try {
                when (event) {
                    is Load -> {
                        val raw = handler.loadLogFiles()
                        val entries = LogEntry.parseLines(raw.lines)
                        mainThreadHandler.post { event.onResult(entries, raw.logSize) }
                    }
                    is Delete -> handler.deleteAll()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot handle a log request: " + t)
            } finally {
                if (event is Flush) event.latch.countDown()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeLostNotice() {
        val lostCount = missedLogsCount.getAndSet(0)
        if (lostCount <= 0) return
        try {
            handler.open()
            handler.write(
                LogEntry(
                    timestamp = Date(),
                    level = Level.WARNING,
                    tag = "Logger",
                    message = "Logger queue overflow. Approx $lostCount entries lost."
                ).toString() + "\n"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot write the notice about lost entries: " + t)
        } finally {
            runCatching { handler.close() }
        }
    }
}
