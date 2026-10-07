/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.errorhandling

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nextcloud.talk.BuildConfig
import com.nextcloud.talk.R
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.dagger.modules.UtilsModule
import com.nextcloud.talk.logger.FileLogHandler
import com.nextcloud.talk.logger.LogEntry
import com.nextcloud.talk.logger.LogMasker
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TAG = ShowErrorActivity::class.java.simpleName

private const val MILLIS_PER_SECOND = 1000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val CONTROL_CHARS_END = 0x20
private const val ESCAPE_HEADROOM_DIVISOR = 8
private const val EXPORT_FILE_NAME = "nc_talk_log_export.json"
private const val EXPORT_TEMP_PREFIX = "nc_talk_log_export_tmp"

// The log files are read, masked and written as JSON on an IO thread, the chooser starts on the main thread.
fun shareLogsAndDiagnosis(context: Context, subject: String, diagnosisText: String) {
    val logDir = File(context.filesDir, UtilsModule.LOG_DIR_NAME)
    CoroutineScope(Dispatchers.IO).launch {
        val jsonFile = runCatching { buildLogcatJsonFile(context, logDir) }.getOrNull()
        withContext(Dispatchers.Main) { startShareChooser(context, subject, diagnosisText, jsonFile) }
    }
}

// An error here must not end the app: it is logged and the user sees a message.
@Suppress("TooGenericExceptionCaught")
private fun startShareChooser(context: Context, subject: String, diagnosisText: String, jsonFile: File?) {
    try {
        openShareChooser(context, subject, diagnosisText, jsonFile)
    } catch (e: Exception) {
        NextcloudTalkApplication.sharedApplication?.logger?.e(TAG, "Sharing the logs failed", e)
        Toast.makeText(context, R.string.nc_common_error_sorry, Toast.LENGTH_LONG).show()
    }
}

private fun openShareChooser(context: Context, subject: String, diagnosisText: String, jsonFile: File?) {
    val uris = ArrayList<Uri>()
    if (jsonFile != null) {
        uris.add(FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID, jsonFile))
    }

    val pleaseDescribe = context.getString(R.string.error_crash_please_describe)
    val body = buildString {
        appendLine("# $pleaseDescribe:")
        appendLine("...")
        appendLine()
        appendLine()
        appendLine()
        appendLine()
        append(diagnosisText)
    }

    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.nc_report_email)))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (uris.isNotEmpty()) {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, subject))
    } catch (_: ActivityNotFoundException) {
        NextcloudTalkApplication.sharedApplication?.logger?.w(TAG, "No app found to handle sharing logs")
        Toast.makeText(context, R.string.nc_logs_share_no_app_found, Toast.LENGTH_LONG).show()
    }
}

fun saveLogsAsZip(context: Context, outputStream: OutputStream, diagnosisText: String) {
    val logDir = File(context.filesDir, UtilsModule.LOG_DIR_NAME)
    val entries = loadLogEntries(logDir)
    ZipOutputStream(outputStream).use { zip ->
        if (entries.isNotEmpty()) {
            zip.putNextEntry(ZipEntry("nc_talk_log_export.json"))
            val writer = OutputStreamWriter(zip, Charsets.UTF_8)
            writeLogcatJson(context.packageName, entries, writer)
            writer.flush()
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("diagnosisReport.md"))
        zip.write(diagnosisText.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

// Reads all log files (the current one and every rotated one), parses them, and writes a single JSON file in the
// format Android Studio's logcat panel produces — so the file can be directly imported.
private fun buildLogcatJsonFile(context: Context, logDir: File): File? {
    val entries = loadLogEntries(logDir)
    if (entries.isEmpty()) return null
    return writeJsonAtomically(File(logDir, EXPORT_FILE_NAME)) { out ->
        writeLogcatJson(context.packageName, entries, out)
    }
}

// Two builds at once (a double tap) write their own temporary files; the rename replaces the target in one step, so
// a reader never sees a half written file.
internal fun writeJsonAtomically(target: File, write: (Appendable) -> Unit): File {
    val temp = File.createTempFile(EXPORT_TEMP_PREFIX, ".json", target.parentFile)
    try {
        temp.writer(Charsets.UTF_8).buffered().use { write(it) }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temp.delete()
    }
    return target
}

/** All entries of all log files in the order they were written. Lines are masked again: older files are unmasked. */
internal fun loadLogEntries(logDir: File): List<LogEntry> = entriesFromLines(loadAllLogLines(logDir))

internal fun entriesFromLines(lines: List<String>): List<LogEntry> =
    LogEntry.parseLines(lines.map { LogMasker.mask(it) })

// The current file and the rotated ones (`nc_talk_log.txt.0` ... `.N`), oldest file first.
internal fun loadAllLogLines(logDir: File): List<String> {
    val allLines = mutableListOf<String>()
    logDir.listFiles()
        ?.filter { it.isFile }
        ?.mapNotNull { file -> FileLogHandler.rotationRank(UtilsModule.LOG_FILE_NAME, file.name)?.let { it to file } }
        ?.sortedBy { it.first }
        ?.forEach { (_, file) ->
            runCatching { allLines.addAll(file.readLines(Charsets.UTF_8)) }
        }
    return allLines
}

internal fun buildLogcatJson(packageName: String, entries: List<LogEntry>): String =
    StringBuilder().also { writeLogcatJson(packageName, entries, it) }.toString()

internal fun writeLogcatJson(packageName: String, entries: List<LogEntry>, out: Appendable) {
    out.appendLine("{")
    out.appendLine("  \"metadata\": {")
    out.appendLine("    \"projectApplicationIds\": [\"$packageName\"]")
    out.appendLine("  },")
    out.appendLine("  \"logcatMessages\": [")
    entries.forEachIndexed { i, entry ->
        val seconds = entry.timestamp.time / MILLIS_PER_SECOND
        val nanos = (entry.timestamp.time % MILLIS_PER_SECOND) * NANOS_PER_MILLI
        out.appendLine("    {")
        out.appendLine("      \"header\": {")
        out.appendLine("        \"logLevel\": \"${entry.level.name}\",")
        out.appendLine("        \"pid\": ${entry.pid},")
        out.appendLine("        \"tid\": ${entry.tid},")
        out.appendLine("        \"applicationId\": \"$packageName\",")
        out.appendLine("        \"processName\": \"$packageName\",")
        out.appendLine("        \"tag\": \"${jsonEscape(entry.tag)}\",")
        out.appendLine("        \"timestamp\": { \"seconds\": $seconds, \"nanos\": $nanos }")
        out.appendLine("      },")
        out.append("      \"message\": \"${jsonEscape(entry.message)}\"")
        out.appendLine()
        out.append("    }")
        if (i < entries.size - 1) out.append(",")
        out.appendLine()
    }
    out.appendLine("  ]")
    out.append("}")
}

private fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + s.length / ESCAPE_HEADROOM_DIVISOR)
    for (c in s) {
        when {
            c == '\\' -> sb.append("\\\\")
            c == '"' -> sb.append("\\\"")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < CONTROL_CHARS_END.toChar() -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
