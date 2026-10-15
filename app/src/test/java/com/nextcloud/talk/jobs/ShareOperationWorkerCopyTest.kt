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
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.nextcloud.talk.R
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.FileUtils
import io.reactivex.Observable
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Forwarding a file of another user: the share API answers 403 (no right to share it again) and Talk forwards a copy
 * of the file instead. Any other answer leaves the file alone. The worker, the real WorkManager and a stand-in for
 * the download are used; the upload of the copy is only checked to be queued.
 */
@Suppress("TooManyFunctions")
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShareOperationWorkerCopyTest {

    private val executor = Executors.newFixedThreadPool(3)
    private val previousApplication = NextcloudTalkApplication.sharedApplication

    @Volatile
    private var downloadSucceeds = true

    @Volatile
    private var downloads = 0
    private val toasts = mutableListOf<Int>()

    private val user: User = mock {
        on { id } doReturn ACCOUNT_ID
        on { username } doReturn "alice"
        on { token } doReturn "secret"
        on { baseUrl } doReturn "https://cloud.example"
    }
    private val userManager: UserManager = mock {
        on { runBlocking { getUserWithId(ACCOUNT_ID) } } doReturn user
    }
    private val ncApi: NcApi = mock()

    @After
    fun tearDown() {
        executor.shutdownNow()
        setSharedApplication(previousApplication)
    }

    @Test
    fun `403 with the file in the cache queues the upload of a copy`() {
        refuseShare(403)
        val cached = cacheFile()
        val workManager = initWorkManager()

        assertTrue(run() is ListenableWorker.Result.Success)

        assertEquals(1, queuedUploads(workManager))
        assertEquals(0, downloads)
        assertTrue(cached.isFile)
    }

    @Test
    fun `403 without the file in the cache downloads it and then queues the upload of a copy`() {
        refuseShare(403)
        val workManager = initWorkManager()

        run()

        assertEquals(1, downloads)
        assertEquals(1, queuedUploads(workManager))
    }

    @Test
    fun `403 and a failed download queues no upload and tells the user`() {
        refuseShare(403)
        downloadSucceeds = false
        val workManager = initWorkManager()

        run()

        assertEquals(1, downloads)
        assertEquals(0, queuedUploads(workManager))
        assertEquals(R.string.nc_forward_file_copy_failed, lastToastText())
    }

    @Test
    fun `a share that is accepted makes no copy`() {
        shareAnswers(Observable.just(GenericOverall()))
        cacheFile()
        val workManager = initWorkManager()

        run()

        assertEquals(0, downloads)
        assertEquals(0, queuedUploads(workManager))
    }

    @Test
    fun `500 makes no copy and shows the common error`() {
        refuseShare(500)
        cacheFile()
        val workManager = initWorkManager()

        run()

        assertEquals(0, downloads)
        assertEquals(0, queuedUploads(workManager))
        assertEquals(R.string.nc_common_error_sorry, lastToastText())
    }

    @Test
    fun `an offline share makes no copy and shows the common error`() {
        shareAnswers(Observable.error(IOException("offline")))
        cacheFile()
        val workManager = initWorkManager()

        run()

        assertEquals(0, downloads)
        assertEquals(0, queuedUploads(workManager))
        assertEquals(R.string.nc_common_error_sorry, lastToastText())
    }

    private fun refuseShare(code: Int) {
        val error = HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())))
        shareAnswers(Observable.error(error))
    }

    private fun shareAnswers(answer: Observable<GenericOverall>) {
        whenever(
            ncApi.createRemoteShare(
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(answer)
    }

    private fun cacheFile(): File {
        val file = FileUtils.resolveSharedAttachmentFile(context().cacheDir, FILE_ID, FILE_NAME)!!
        file.writeText("content")
        return file
    }

    private fun run(): ListenableWorker.Result {
        val inputData = androidx.work.Data.Builder()
            .putLong("KEY_INTERNAL_USER_ID", ACCOUNT_ID)
            .putString("KEY_ROOM_TOKEN", "room")
            .putString("KEY_CONVERSATION_NAME", "Room")
            .putStringArray("KEY_FILE_PATHS", arrayOf("/Talk/Room/bob/a.jpg"))
            .let {
                ShareOperationWorker.CopySource(FILE_ID, FILE_NAME, 7L, "/Talk").putInto(it)
            }
            .build()
        val worker = TestListenableWorkerBuilder<ShareOperationWorker>(context())
            .setInputData(inputData)
            .setWorkerFactory(factory())
            .build()
        return worker.doWork()
    }

    private fun lastToastText(): Int? = toasts.lastOrNull()

    private fun queuedUploads(workManager: WorkManager): Int =
        workManager.getWorkInfosForUniqueWork(QUEUE_NAME).get().count { it.state == WorkInfo.State.ENQUEUED }

    private fun factory(): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? =
                when (workerClassName) {
                    ShareOperationWorker::class.java.name -> ShareOperationWorker(appContext, workerParameters).also {
                        it.userManager = userManager
                        it.ncApi = ncApi
                        it.userMessage = { message -> toasts.add(message) }
                    }
                    // the download itself needs a server; here it only writes the file or fails
                    DownloadFileToCacheWorker::class.java.name -> object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result {
                            downloads++
                            if (!downloadSucceeds) return Result.failure()
                            FileUtils.resolveSharedAttachmentFile(appContext.cacheDir, FILE_ID, FILE_NAME)!!
                                .writeText("downloaded")
                            return Result.success()
                        }
                    }
                    else -> null
                }
        }

    private fun initWorkManager(): WorkManager {
        val configuration = Configuration.Builder()
            .setExecutor(executor)
            .setWorkerFactory(factory())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context(), configuration)
        return WorkManager.getInstance(context())
    }

    private fun setSharedApplication(application: NextcloudTalkApplication?) {
        NextcloudTalkApplication::class.java.getDeclaredField("sharedApplication").apply {
            isAccessible = true
            set(null, application)
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val ACCOUNT_ID = 1L
        private const val FILE_ID = "4711"
        private const val FILE_NAME = "a.jpg"
        private const val CONVERSATION_ID = "1@room"
        private const val QUEUE_NAME = "upload_queue_$CONVERSATION_ID"
    }
}
