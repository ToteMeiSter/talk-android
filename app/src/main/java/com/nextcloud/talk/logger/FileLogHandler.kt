/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

open class FileLogHandler(
    private val logDir: File,
    private val logFilename: String,
    private val maxSize: Long,
    rotatedFiles: Int = ROTATED_LOGS_COUNT
) {
    data class RawLogs(val lines: List<String>, val logSize: Long)

    companion object {
        private val TAG = FileLogHandler::class.java.simpleName

        /** Rotated files next to the current one: `.0` is the newest of them, the highest number the oldest. */
        const val ROTATED_LOGS_COUNT = 5

        /**
         * Age rank of a log file name for sorting oldest first: the highest `.N` suffix first, the current file
         * last. Null if [name] is not [baseName] or one of its rotated files.
         */
        fun rotationRank(baseName: String, name: String): Int? {
            if (name == baseName) return Int.MAX_VALUE
            val number = name.removePrefix("$baseName.").takeIf { name.startsWith("$baseName.") }?.toIntOrNull()
            return number?.let { -it }
        }
    }

    private var writer: FileOutputStream? = null
    private var size: Long = 0

    private val rotationList = (rotatedFiles - 1 downTo 0).map { "$logFilename.$it" } + logFilename

    val logFile: File get() = File(logDir, logFilename)

    val isOpened: Boolean get() = writer != null

    open fun open() {
        try {
            writer = FileOutputStream(logFile, true)
            size = logFile.length()
        } catch (_: FileNotFoundException) {
            Log.w(TAG, "Log file parent directory missing, creating it and retrying")
            logFile.parentFile?.mkdirs()
            writer = FileOutputStream(logFile, true)
            size = logFile.length()
        }
    }

    open fun write(logEntry: String) {
        val bytes = LogMasker.mask(logEntry).toByteArray(Charsets.UTF_8)
        writer?.write(bytes)
        size += bytes.size
        if (size > maxSize) {
            rotateLogs()
        }
    }

    fun close() {
        try {
            writer?.close()
        } finally {
            writer = null
            size = 0L
        }
    }

    fun deleteAll() {
        rotationList.map { File(logDir, it) }.forEach { it.delete() }
    }

    fun rotateLogs() {
        val wasOpen = isOpened
        if (wasOpen) close()

        val existingFiles = logDir.listFiles()?.associate { it.name to it } ?: emptyMap()
        existingFiles[rotationList.first()]?.delete()

        for (i in 0 until rotationList.size - 1) {
            val dest = File(logDir, rotationList[i])
            existingFiles[rotationList[i + 1]]?.renameTo(dest)
        }

        if (wasOpen) open()
    }

    fun loadLogFiles(rotated: Int = ROTATED_LOGS_COUNT): RawLogs {
        require(rotated >= 0) { "Negative index" }
        val allLines = mutableListOf<String>()
        var totalSize = 0L
        for (i in 0..minOf(rotated, rotationList.size - 1)) {
            val file = File(logDir, rotationList[i])
            if (!file.exists()) continue
            try {
                allLines.addAll(file.readLines(Charsets.UTF_8))
                totalSize += file.length()
            } catch (_: IOException) {
                Log.w(TAG, "Skipping unreadable log file: ${file.name}")
            }
        }
        return RawLogs(lines = allLines, logSize = totalSize)
    }
}
