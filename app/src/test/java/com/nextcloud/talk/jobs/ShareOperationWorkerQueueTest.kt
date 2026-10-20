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
import androidx.work.testing.WorkManagerTestInitHelper
import com.nextcloud.talk.data.user.model.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The files of one forward reach the conversation in the order of the chat: a share with a queue name waits for the
 * earlier shares of that queue. The worker is a stand-in that does not finish until the test lets it, so the states
 * of the queued works can be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShareOperationWorkerQueueTest {

    private val executor = Executors.newFixedThreadPool(3)
    private val gate = CountDownLatch(1)

    private val user: User = mock { on { id } doReturn 1L }

    @After
    fun tearDown() {
        gate.countDown()
        executor.shutdownNow()
    }

    @Test
    fun `shares with one queue name run one after the other`() {
        val workManager = initWorkManager()

        share("/a.jpg", QUEUE)
        share("/b.jpg", QUEUE)
        share("/c.jpg", QUEUE)

        val states = workManager.getWorkInfosForUniqueWork(QUEUE).get().map { it.state }
        assertEquals(3, states.size)
        assertEquals(1, states.count { it == WorkInfo.State.RUNNING || it == WorkInfo.State.ENQUEUED })
        assertEquals(2, states.count { it == WorkInfo.State.BLOCKED })
    }

    @Test
    fun `shares without a queue name do not wait for each other`() {
        val workManager = initWorkManager()

        share("/a.jpg", null)
        share("/b.jpg", null)

        val states = workManager.getWorkInfosByTag(ShareOperationWorker::class.java.name).get().map { it.state }
        assertEquals(2, states.size)
        assertEquals(0, states.count { it == WorkInfo.State.BLOCKED })
    }

    private fun share(path: String, queueName: String?) {
        ShareOperationWorker.shareFile(
            roomToken = "room",
            conversationName = "Room",
            currentUser = user,
            remotePath = path,
            metaData = null,
            copySource = null,
            permissions = null,
            queueName = queueName
        )
    }

    private fun initWorkManager(): WorkManager {
        val configuration = Configuration.Builder()
            .setExecutor(executor)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters
                    ): ListenableWorker? =
                        if (workerClassName == ShareOperationWorker::class.java.name) {
                            WaitingWorker(appContext, workerParameters, gate)
                        } else {
                            null
                        }
                }
            )
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context(), configuration)
        return WorkManager.getInstance(context())
    }

    private class WaitingWorker(context: Context, params: WorkerParameters, private val gate: CountDownLatch) :
        Worker(context, params) {
        override fun doWork(): Result {
            gate.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return Result.success()
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val QUEUE = "send_message_queue_1@room"
        private const val WAIT_SECONDS = 30L
    }
}
