/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A logcat that is a queue: the test puts lines in as logcat would print them. */
class FakeLogcat : LogcatLauncher {
    val launches = CopyOnWriteArrayList<List<String>>()
    val streams = CopyOnWriteArrayList<FakeStream>()

    /** Lines that every new run prints first (the buffer history). */
    @Volatile
    var history: List<String> = emptyList()

    @Volatile
    var failWith: IOException? = null

    @Volatile
    var echoMarker = true

    /** A device that does not deliver debug lines to logcat (some Huawei EMUI versions). */
    @Volatile
    var dropDebug = false

    /** A device that delivers only the priorities from this one up to logcat (EMUI: 5 = warnings and above). */
    @Volatile
    var minPriority = 0

    /** Time of the check line; null is now, as on a device. */
    @Volatile
    var markerTime: String? = null

    inner class FakeStream : LogcatStream {
        private val queue = LinkedBlockingQueue<String>()

        @Volatile
        var closed = false

        fun emit(line: String) {
            queue.put(line)
        }

        fun end() {
            queue.put(END)
        }

        override fun readLine(): String? {
            while (true) {
                // like the pipe of a process on Android: a close ends a blocked read with an exception
                if (closed) throw IOException("Stream closed")
                val line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (line == END) return null
                if (line != null) return line
            }
        }

        override fun close() {
            closed = true
        }
    }

    override fun launch(args: List<String>): LogcatStream {
        failWith?.let { throw it }
        launches.add(args)
        val stream = FakeStream()
        // logcat -T <time> prints the lines from that time on, the time itself included
        val since = args.indexOf("-T").takeIf { it >= 0 }?.let { args[it + 1] }
        history.filter { since == null || it.take(TIME_LENGTH) >= since }.forEach { stream.emit(it) }
        streams.add(stream)
        return stream
    }

    val current: FakeStream get() = streams.last()

    /** Every check and stop line that the capture wrote to logcat. */
    val emitted = CopyOnWriteArrayList<String>()

    private fun markerLine(priority: Int, tag: String, message: String): String {
        val level = when (priority) {
            PRIORITY_DEBUG -> "D"
            PRIORITY_INFO -> "I"
            PRIORITY_WARN -> "W"
            else -> "E"
        }
        val time = markerTime ?: SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
        return line(level, tag, message, time = time)
    }

    /** What `Log.println` does on a device: the line shows up in the stream. */
    val markerEmitter = MarkerEmitter { priority, tag, message ->
        val text = markerLine(priority, tag, message)
        val delivered = !(dropDebug && priority == PRIORITY_DEBUG) && priority >= minPriority
        if (delivered) emitted.add(text)
        if (echoMarker && delivered) streams.lastOrNull()?.emit(text)
    }

    /** Lets the lines that were held back (echoMarker = false) reach the current stream now. */
    fun deliverEmitted() {
        emitted.forEach { current.emit(it) }
    }

    companion object {
        private const val END = "\u0000end"
        private const val TIME_LENGTH = 18
        private const val POLL_MS = 50L
        private const val SLEEP_MS = 10L
        private const val DEFAULT_WAIT_MS = 5000L
        private const val PID = 4242
        private const val TID = 4300
        private const val PID_WIDTH = 5
        private const val PRIORITY_DEBUG = 3
        private const val PRIORITY_INFO = 4
        private const val PRIORITY_WARN = 5

        fun line(level: String, tag: String, message: String, time: String = "10-07 12:00:00.123") =
            "$time ${PID.toString().padStart(PID_WIDTH)} ${TID.toString().padStart(PID_WIDTH)} $level $tag: $message"

        fun eventually(timeoutMs: Long = DEFAULT_WAIT_MS, condition: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (condition()) return true
                Thread.sleep(SLEEP_MS)
            }
            return condition()
        }
    }
}
