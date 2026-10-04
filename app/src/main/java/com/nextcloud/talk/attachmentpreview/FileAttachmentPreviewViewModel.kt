/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Holds the file list being reviewed for upload, which of them are excluded from sending, and
 * each file's (IO-derived) [FileDescription], so all survive configuration changes (e.g. screen
 * rotation) instead of resetting back to the dialog's original arguments. Everything else on the
 * screen — caption text, drag/scroll position, HQ toggle animation state — is ephemeral UI state
 * and stays in Compose's `remember`.
 */
internal class FileAttachmentPreviewViewModel @Inject constructor(private val context: Context) : ViewModel() {

    val files = mutableStateListOf<String>()

    private val _descriptionsByUri = mutableStateMapOf<String, FileDescription>()
    val descriptionsByUri: Map<String, FileDescription> get() = _descriptionsByUri

    /** No-op after the first call, so re-entering (e.g. after rotation) doesn't wipe edits made since. */
    fun setInitialFiles(initialFiles: List<String>) {
        if (files.isEmpty()) {
            files.addAll(initialFiles)
        }
    }

    fun addFiles(newFiles: List<String>) {
        files.addAll(newFiles.filterNot { it in files })
    }

    /** Files the user un-ticked; they stay in the list (and can be re-ticked) but are not sent. */
    val unselected = mutableStateListOf<String>()

    /** True while an edit (e.g. a drawing) is being written, so sending can't race it with the old file. */
    var isEditing by mutableStateOf(false)
        private set

    fun toggleSelected(uri: String) {
        val updated = toggleSelection(files, unselected.toSet(), uri)
        unselected.clear()
        unselected.addAll(updated)
    }

    fun selectedFiles(): List<String> = selectedFiles(files, unselected.toSet())

    /**
     * Swaps an edited file in at the position of the original. The old description is carried over
     * right away (re-pointed at [newUri]) so the pager doesn't drop the page while the fresh
     * description is computed; [describeFiles] then refreshes size/resolution.
     */
    fun replaceFile(oldUri: String, newUri: String) {
        val updated = replaceUri(files, oldUri, newUri)
        if (updated === files) return
        files.clear()
        files.addAll(updated)
        if (unselected.remove(oldUri)) {
            unselected.add(newUri)
        }
        _descriptionsByUri[oldUri]?.let { _descriptionsByUri[newUri] = it.copy(uri = newUri) }
        _descriptionsByUri.remove(oldUri)
    }

    /** Burns [strokes] into a copy of [description]'s image and swaps it in; keeps the original on failure. */
    fun saveDrawing(description: FileDescription, strokes: List<DrawStroke>) {
        if (strokes.isEmpty()) return
        isEditing = true
        viewModelScope.launch {
            val output = withContext(Dispatchers.IO) {
                val file = createEditOutputFile(context, description.name, description.mimeType)
                val saved = renderDrawing(
                    context,
                    description.uri.toUri(),
                    strokes,
                    file,
                    editOutputIsPng(description.mimeType)
                )
                if (saved) editedFileUri(context, file) else null
            }
            output?.let { replaceFile(description.uri, it.toString()) }
            isEditing = false
        }
    }

    fun reorder(from: Int, to: Int) {
        if (from != to && from in files.indices && to in files.indices) {
            val item = files.removeAt(from)
            files.add(to, item)
        }
    }

    /**
     * Re-describes every current file. Callers should only invoke this when the *set* of files or
     * [compress] changes — not on pure reordering — since it always redescribes the whole list.
     */
    fun describeFiles(compress: Boolean) {
        val snapshot = files.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val described = snapshot.associateWith { describeFile(context, it, compress) }
            _descriptionsByUri.clear()
            _descriptionsByUri.putAll(described)
        }
    }
}
