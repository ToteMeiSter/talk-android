/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.upload

import com.nextcloud.talk.utils.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadWorkspaceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun workspace(name: String = "work") = UploadWorkspace(File(tempFolder.root, name))

    private fun create(dir: File, name: String, lastModified: Long): PreparedUpload {
        val file = File(dir, name).apply {
            writeBytes(ByteArray(SIZE))
            setLastModified(lastModified)
        }
        return PreparedUpload(file, name)
    }

    @Test
    fun `file is prepared once and reused by later runs`() {
        var calls = 0
        val first = workspace().prepareOnce { dir -> calls++.let { create(dir, "photo.jpg", 1_000) } }
        val second = workspace().prepareOnce { dir -> calls++.let { create(dir, "photo.jpg", 2_000) } }

        assertEquals(1, calls)
        assertEquals(first, second)
    }

    @Test
    fun `chunk folder key stays the same between runs`() {
        val first = workspace().prepareOnce { create(it, "video.mp4", 1_000) }!!
        val keyBefore = FileUtils.md5Sum(first.file)
        val second = workspace().prepareOnce { create(it, "video.mp4", 9_000) }!!

        assertEquals(keyBefore, FileUtils.md5Sum(second.file))
    }

    @Test
    fun `a new preparation of the same file gets another chunk folder key`() {
        val first = workspace("a").prepareOnce { create(it, "video.mp4", 1_000) }!!
        val second = workspace("b").prepareOnce { create(it, "video.mp4", 2_000) }!!

        assertTrue(FileUtils.md5Sum(first.file) != FileUtils.md5Sum(second.file))
    }

    @Test
    fun `file is prepared again when it disappeared`() {
        val first = workspace().prepareOnce { create(it, "photo.jpg", 1_000) }!!
        first.file.delete()
        var calls = 0
        val second = workspace().prepareOnce { dir -> calls++.let { create(dir, "photo.jpg", 3_000) } }

        assertEquals(1, calls)
        assertNotNull(second)
        assertTrue(second!!.file.isFile)
    }

    @Test
    fun `leftovers of an interrupted preparation are removed before the next one`() {
        val ws = workspace()
        ws.prepareOnce { dir ->
            File(dir, "half.jpg").writeText("partial")
            null
        }
        ws.prepareOnce { dir ->
            assertEquals(emptyList<String>(), dir.list()!!.toList())
            create(dir, "photo.jpg", 1_000)
        }
    }

    @Test
    fun `server errors are counted across runs`() {
        assertEquals(0, workspace().serverErrors())
        assertEquals(1, workspace().registerServerError())
        assertEquals(2, workspace().registerServerError())
        assertEquals(2, workspace().serverErrors())
    }

    @Test
    fun `server error count survives a new preparation`() {
        workspace().registerServerError()
        workspace().prepareOnce { create(it, "photo.jpg", 1_000) }

        assertEquals(1, workspace().serverErrors())
    }

    @Test
    fun `delete removes the file and the counters`() {
        val ws = workspace()
        val prepared = ws.prepareOnce { create(it, "photo.jpg", 1_000) }!!
        ws.registerServerError()
        ws.delete()

        assertFalse(prepared.file.exists())
        assertEquals(0, workspace().serverErrors())
        assertNull(workspace().prepareOnce { null })
    }

    @Test
    fun `workspaces of finished uploads are removed and living ones kept`() {
        workspace("finished").prepareOnce { create(it, "a.jpg", 1_000) }
        workspace("waiting-for-network").prepareOnce { create(it, "b.jpg", 1_000) }

        UploadWorkspace.deleteFinished(tempFolder.root) { it == "waiting-for-network" }

        assertFalse(File(tempFolder.root, "finished").exists())
        assertTrue(File(tempFolder.root, "waiting-for-network").exists())
    }

    companion object {
        private const val SIZE = 16
    }
}
