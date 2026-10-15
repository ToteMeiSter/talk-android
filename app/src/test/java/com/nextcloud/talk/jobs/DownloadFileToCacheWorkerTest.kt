/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.FileUtils
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Two downloads of the same file (the media viewer and a forwarded copy) run side by side. Each one writes its own
 * temp file, so neither fails and the cached file is whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DownloadFileToCacheWorkerTest {

    private val user: User = mock {
        on { id } doReturn 1L
        on { userId } doReturn "alice"
        on { username } doReturn "alice"
        on { token } doReturn "secret"
        on { baseUrl } doReturn "https://cloud.example"
    }
    private val userManager: UserManager = mock {
        on { runBlocking { getUserWithId(1L) } } doReturn user
    }
    private val ncApi: NcApi = mock()

    @Test
    fun `a download that is overtaken by another one of the same file still succeeds`() {
        val target = FileUtils.resolveSharedAttachmentFile(context().cacheDir, FILE_ID, FILE_NAME)!!
        val slow = bodyOf("first-and-slow") {
            // while the first download is reading, the second one runs through and is done
            serve(bodyOf("second-and-fast") {})
            assertEquals(Success, run())
        }
        serve(slow)

        val first = run()

        assertEquals(Success, first)
        assertEquals("first-and-slow", target.readText())
        assertEquals("no temp file is left", listOf(FILE_NAME), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a download of a file that is cached whole already succeeds`() {
        val target = FileUtils.resolveSharedAttachmentFile(context().cacheDir, FILE_ID, FILE_NAME)!!
        target.writeText("cached")
        serve(bodyOf("downloaded") {})

        assertEquals(Success, run())
        assertTrue(target.isFile)
    }

    private fun serve(body: ResponseBody) {
        val call: Call<ResponseBody> = mock { on { execute() } doReturn Response.success(body) }
        whenever(ncApi.downloadFile(any(), any())).thenReturn(call)
    }

    // the body gives [text], after [onFirstRead] ran
    private fun bodyOf(text: String, onFirstRead: () -> Unit): ResponseBody =
        object : ResponseBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = text.length.toLong()

            override fun source(): BufferedSource {
                val bytes = ByteArrayInputStream(text.toByteArray())
                var first = true
                val stream = object : InputStream() {
                    override fun read(): Int = bytes.read()

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (first) {
                            first = false
                            onFirstRead()
                        }
                        return bytes.read(b, off, len)
                    }
                }
                return stream.source().buffer()
            }
        }

    private fun run(): ListenableWorker.Result {
        val data = Data.Builder()
            .putLong("KEY_INTERNAL_USER_ID", 1L)
            .putString(DownloadFileToCacheWorker.KEY_ATTACHMENT_FOLDER, "/Talk")
            .putString(DownloadFileToCacheWorker.KEY_FILE_ID, FILE_ID)
            .putString(DownloadFileToCacheWorker.KEY_FILE_NAME, FILE_NAME)
            .putString(DownloadFileToCacheWorker.KEY_FILE_PATH, "Talk/a.jpg")
            .build()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker =
                DownloadFileToCacheWorker(appContext, workerParameters).also {
                    it.userManager = userManager
                    it.ncApi = ncApi
                }
        }
        return TestListenableWorkerBuilder<DownloadFileToCacheWorker>(context())
            .setInputData(data)
            .setWorkerFactory(factory)
            .build()
            .doWork()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val FILE_ID = "4711"
        private const val FILE_NAME = "a.jpg"
        private val Success = ListenableWorker.Result.success()
    }
}
