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
    val verifyTimeoutMs: Long = LogcatCapture.DEFAULT_VERIFY_TIMEOUT_MS
)

/**
 * Writes the log file. While the logcat capture works, it writes every line of the own process (the logger's own
 * calls are in logcat too, so they are not written a second time). Otherwise it writes its own calls only.
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
    // check line shows that logcat works. ACTIVE: logcat delivers everything, own entries are dropped.
    private enum class CaptureMode { OFF, PENDING, ACTIVE }

    companion object {
        private val TAG = LoggerImpl::class.java.simpleName
        private const val DEFAULT_QUEUE_CAPACITY = 1000
        private const val MAX_HELD_ENTRIES = 5000
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

    private val thread = Thread {
        while (!Thread.currentThread().isInterrupted) {
            try {
                eventLoop()
            } catch (_: InterruptedException) {
                Log.w(TAG, "Logger thread interrupted, shutting down")
                Thread.currentThread().interrupt()
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
            verifyTimeoutMs = setup.verifyTimeoutMs
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

    /** Stops the logcat capture and its process. */
    fun stopLogcatCapture() {
        synchronized(captureLock) {
            captureMode = CaptureMode.OFF
            captureFloor = Level.NONE
            heldEntries.clear()
        }
        capture?.stop()
    }

    private fun updateCapture(fromProcessStart: Boolean) {
        val capture = capture ?: return
        val level = minimumLevel
        if (level == Level.NONE) {
            stopLogcatCapture()
            return
        }
        synchronized(captureLock) {
            if (captureMode == CaptureMode.OFF) captureMode = CaptureMode.PENDING
        }
        capture.start(level, fromProcessStart)
    }

    override fun onLine(line: String) {
        try {
            eventQueue.put(RawLine(line))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun onVerified(effectiveLevel: Level) {
        synchronized(captureLock) {
            captureFloor = effectiveLevel
            if (captureMode == CaptureMode.PENDING) {
                captureMode = CaptureMode.ACTIVE
                heldEntries.filter { it.level < effectiveLevel && it.level >= minimumLevel }.forEach { offerEntry(it) }
                heldEntries.clear()
            }
            if (effectiveLevel > minimumLevel) {
                offerEntry(warningEntry(LIMITED_MESSAGE.format(effectiveLevel.tag)))
            }
        }
    }

    override fun onUnavailable(reason: String) {
        Log.w(CAPTURE_TAG, "logcat capture unavailable: $reason")
        synchronized(captureLock) {
            captureMode = CaptureMode.OFF
            captureFloor = Level.NONE
            val level = minimumLevel
            heldEntries.filter { it.level.ordinal >= level.ordinal }.forEach { offerEntry(it) }
            heldEntries.clear()
            offerEntry(warningEntry("logcat capture unavailable: $reason"))
        }
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

    private fun offerEntry(entry: LogEntry) {
        if (!eventQueue.offer(entry)) {
            missedLogs.set(true)
            missedLogsCount.incrementAndGet()
        }
    }

    fun flush(timeoutMs: Long = 2000L) {
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
            val enqueued = eventQueue.offer(entry, 1, TimeUnit.SECONDS)
            if (!enqueued) {
                missedLogs.set(true)
                missedLogsCount.incrementAndGet()
            }
        } catch (_: InterruptedException) {
            Log.w(TAG, "Interrupted while enqueueing log entry for tag $tag")
            Thread.currentThread().interrupt()
        }
    }

    // False when logcat writes the line (ACTIVE, level at or above the floor) or the line is held (PENDING).
    private fun holdOrDrop(entry: LogEntry): Boolean =
        synchronized(captureLock) {
            when (captureMode) {
                CaptureMode.OFF -> true
                CaptureMode.ACTIVE -> entry.level < captureFloor
                CaptureMode.PENDING -> {
                    if (heldEntries.size < MAX_HELD_ENTRIES) heldEntries.add(entry)
                    false
                }
            }
        }

    private fun eventLoop() {
        processedEvents.clear()
        otherEvents.clear()

        processedEvents.add(eventQueue.take())
        eventQueue.drainTo(processedEvents)

        handler.open()
        for (event in processedEvents) {
            when (event) {
                is LogEntry -> handler.write(event.toString() + "\n")
                is RawLine -> handler.write(event.text + "\n")
                else -> otherEvents.add(event)
            }
        }
        handler.close()

        for (event in otherEvents) {
            when (event) {
                is Load -> {
                    val raw = handler.loadLogFiles()
                    val entries = LogEntry.parseLines(raw.lines)
                    mainThreadHandler.post { event.onResult(entries, raw.logSize) }
                }
                is Delete -> handler.deleteAll()
                is Flush -> event.latch.countDown()
            }
        }

        val lostCount = missedLogsCount.getAndSet(0)
        if (lostCount > 0) {
            handler.open()
            handler.write(
                LogEntry(
                    timestamp = Date(),
                    level = Level.WARNING,
                    tag = "Logger",
                    message = "Logger queue overflow. Approx $lostCount entries lost."
                ).toString() + "\n"
            )
            handler.close()
        }
    }
}
