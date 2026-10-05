/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.upload

import java.io.File

/** The file an upload sends and the name it gets on the server. */
data class PreparedUpload(val file: File, val fileName: String)

/**
 * Holds what one upload needs across runs of its worker: the prepared file (copy of the content uri, compressed
 * media) and the number of server errors.
 *
 * The prepared file is created once. Its name, size and modification time stay the same on every run, so the
 * chunk folder on the server, which is keyed by them, stays the same too.
 */
class UploadWorkspace(private val dir: File) {

    private val preparedFile = File(dir, PREPARED_FILE)
    private val serverErrorsFile = File(dir, SERVER_ERRORS_FILE)

    /**
     * Returns the prepared file of an earlier run, or runs [prepare] once and keeps its result.
     * [prepare] gets an empty directory it may create files in, and returns null when the file cannot be prepared.
     */
    fun prepareOnce(prepare: (File) -> PreparedUpload?): PreparedUpload? {
        stored()?.let { return it }
        resetFiles()
        dir.mkdirs()
        return prepare(dir)?.also { write(preparedFile, "${it.file.absolutePath}\n${it.fileName}") }
    }

    fun serverErrors(): Int = readLong(serverErrorsFile)?.toInt() ?: 0

    fun registerServerError(): Int {
        val count = serverErrors() + 1
        dir.mkdirs()
        write(serverErrorsFile, count.toString())
        return count
    }

    fun delete() {
        dir.deleteRecursively()
    }

    private fun stored(): PreparedUpload? {
        val lines = runCatching { preparedFile.readLines() }.getOrNull()
        val file = lines?.getOrNull(0)?.let(::File)
        val name = lines?.getOrNull(1)
        return if (file != null && name != null && file.isFile) PreparedUpload(file, name) else null
    }

    private fun resetFiles() {
        dir.listFiles()?.filter { it.name != SERVER_ERRORS_FILE }?.forEach {
            it.deleteRecursively()
        }
    }

    private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

    private fun write(file: File, text: String) {
        val tmp = File(dir, file.name + TMP_SUFFIX)
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    companion object {
        private const val PREPARED_FILE = "prepared"
        private const val SERVER_ERRORS_FILE = "server_errors"
        private const val TMP_SUFFIX = ".tmp"

        /**
         * Removes the workspaces of uploads that are no longer alive, e.g. left behind by a killed process.
         * [isAlive] gets the name of a workspace directory, which is the id of its work.
         */
        fun deleteFinished(root: File, isAlive: (String) -> Boolean) {
            root.listFiles()?.filter { it.isDirectory && !isAlive(it.name) }?.forEach { it.deleteRecursively() }
        }
    }
}
