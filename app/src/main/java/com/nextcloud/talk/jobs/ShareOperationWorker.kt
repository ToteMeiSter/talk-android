/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.net.Uri
import androidx.annotation.StringRes
import androidx.work.WorkInfo
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import com.nextcloud.talk.utils.setExpeditedIfSupported
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.FileUtils
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CONVERSATION_NAME
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_FILE_PATHS
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_META_DATA
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutionException
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class ShareOperationWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var ncApi: NcApi

    private val userId: Long = workerParams.inputData.getLong(KEY_INTERNAL_USER_ID, 0)
    private val roomToken: String? = workerParams.inputData.getString(KEY_ROOM_TOKEN)
    private val conversationName: String = workerParams.inputData.getString(KEY_CONVERSATION_NAME).orEmpty()
    private val metaData: String? = workerParams.inputData.getString(KEY_META_DATA)
    private val filesArray: MutableList<String?> = ArrayList()
    private val copySource: CopySource? = CopySource.from(workerParams.inputData)

    // the permissions of the file as the message tells them; null when it does not
    private val filePermissions: Int? = workerParams.inputData.getInt(KEY_FILE_PERMISSIONS, NO_PERMISSIONS)
        .takeIf { it != NO_PERMISSIONS }
    private lateinit var credentials: String
    private var baseUrl: String? = null
    private var lastRefusal: Throwable? = null

    /** The file as the chat knows it: enough to find it in the cache or to download it, see [CopySource.from]. */
    data class CopySource(val fileId: String, val fileName: String, val fileSize: Long, val attachmentFolder: String) {
        internal fun putInto(builder: Data.Builder): Data.Builder =
            builder
                .putString(KEY_COPY_FILE_ID, fileId)
                .putString(KEY_COPY_FILE_NAME, fileName)
                .putLong(KEY_COPY_FILE_SIZE, fileSize)
                .putString(KEY_COPY_ATTACHMENT_FOLDER, attachmentFolder)

        companion object {
            internal fun from(data: Data): CopySource? {
                val id = data.getString(KEY_COPY_FILE_ID)
                val name = data.getString(KEY_COPY_FILE_NAME)
                val folder = data.getString(KEY_COPY_ATTACHMENT_FOLDER)
                return if (id.isNullOrBlank() || name.isNullOrBlank() || folder == null) {
                    null
                } else {
                    CopySource(id, name, data.getLong(KEY_COPY_FILE_SIZE, -1), folder)
                }
            }
        }
    }

    /**
     * Known limitation: the permissions come from the message as of the last sync. If the owner has withdrawn the
     * right to share since, the 403 "no right" is taken for "already shared": no copy and no error. The 403 is not
     * told apart by its text, the server sends a localized hint (spreed RoomShareProvider.php:143).
     */
    override fun doWork(): Result {
        // Invariant: dependencies that are already set (a test worker factory) are not overwritten.
        if (!::ncApi.isInitialized) {
            sharedApplication!!.componentApplication.inject(this)
        }
        filesArray.addAll(inputData.getStringArray(KEY_FILE_PATHS)?.toList().orEmpty())
        val operationsUser = runBlocking { userManager.getUserWithId(userId) }!!
        baseUrl = operationsUser.baseUrl
        credentials = ApiUtils.getCredentials(operationsUser.username, operationsUser.token)!!

        // Known permissions without the right to share (or to update, which Talk needs for a share) decide before
        // the request: the server would refuse, and 404 or 403 would not tell why.
        val mayShare = filePermissions?.let { it and PERMISSION_SHARE != 0 && it and PERMISSION_UPDATE != 0 }
        if (mayShare == false) {
            copyOrTell()
        } else {
            val failed = filesArray.count { !tryCreateShare(it) }
            if (failed > 0) {
                // The work itself is not retried (the share was refused), so the user has to be told.
                when {
                    // the file may be shared, so 403 says "already shared": the server has posted it again
                    // (known limitation: a right withdrawn after the sync is taken for this too, see doWork)
                    mayShare == true && isForbidden(lastRefusal) -> Unit
                    isForbidden(lastRefusal) -> copyOrTell()
                    else -> showToast(R.string.nc_common_error_sorry)
                }
            }
        }
        roomToken?.let { _shareCompletedFlow.tryEmit(it) }
        return Result.success()
    }

    /**
     * Copies the file, or tells the user that it may not be shared when no copy is possible. Called when the
     * permissions say so; with permissions that are out of date the share API's 403 may be missed, see [doWork].
     */
    private fun copyOrTell() {
        val source = copySource
        if (source != null && roomToken != null) {
            copyFile(source, roomToken)
        } else {
            showToast(R.string.nc_file_share_not_allowed)
        }
    }

    /**
     * Forwards a copy of a file the user may not hand on (a file of another user shared without the right to share
     * it again): the permissions of the file say so, or, when they are unknown, the share API answered 403. The file
     * from the cache of the media viewer, or downloaded now, goes through the normal upload of attachments of the
     * conversation.
     */
    private fun copyFile(source: CopySource, room: String) {
        val cached = FileUtils.resolveSharedAttachmentFile(applicationContext.cacheDir, source.fileId, source.fileName)
        if (cached == null) {
            showToast(R.string.nc_forward_file_copy_failed)
        } else {
            // null: this work was stopped while it waited; WorkManager runs it again, and the file is cached then
            when (ensureCached(source, cached)) {
                true -> if (!isStopped) upload(cached, room)
                false -> showToast(R.string.nc_forward_file_copy_failed)
                null -> Unit
            }
        }
    }

    /** Whether [cached] exists, downloading it when needed; null when this work was stopped during the download. */
    private fun ensureCached(source: CopySource, cached: File): Boolean? =
        if (cached.isFile) true else download(source)?.let { it && cached.isFile }

    // A failure of the upload is shown by its own worker (notification).
    private fun upload(cached: File, room: String) {
        UploadAndShareFilesWorker.upload(
            userId = userId,
            fileUri = Uri.fromFile(cached).toString(),
            roomToken = room,
            conversationName = conversationName,
            metaData = metaData,
            internalConversationId = "$userId@$room",
            // this work can run again (WorkManager restarts a stopped one): the copy is queued once
            onceTag = "forward_copy:$id"
        )
    }

    /**
     * Downloads the file into the cache with [DownloadFileToCacheWorker], as the media viewer does, or waits for the
     * download of the same file that is running already (the media viewer or an earlier forward). The download is
     * a work of its own, not part of the queue of uploads of the conversation: it ends as failed when the download
     * fails, and WorkManager would fail the uploads queued behind a failed work.
     * Returns whether it succeeded, null when this work was stopped or interrupted while it waited.
     */
    private fun download(source: CopySource): Boolean? {
        if (isStopped) return null
        val workManager = WorkManager.getInstance(applicationContext)
        return try {
            val running = workManager.getWorkInfosByTag(source.fileId).get()
                .firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            val workId = running?.id ?: enqueueDownload(workManager, source)
            awaitWork(workManager, workId)
        } catch (e: ExecutionException) {
            Log.e(TAG, "Could not ask WorkManager about the download", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun enqueueDownload(workManager: WorkManager, source: CopySource): UUID {
        val data = Data.Builder()
            .putLong(KEY_INTERNAL_USER_ID, userId)
            .putString(DownloadFileToCacheWorker.KEY_ATTACHMENT_FOLDER, source.attachmentFolder)
            .putString(DownloadFileToCacheWorker.KEY_FILE_ID, source.fileId)
            .putString(DownloadFileToCacheWorker.KEY_FILE_NAME, source.fileName)
            .putString(DownloadFileToCacheWorker.KEY_FILE_PATH, filesArray.firstOrNull()?.trimStart('/'))
            .putLong(DownloadFileToCacheWorker.KEY_FILE_SIZE, source.fileSize)
            .build()
        val request = OneTimeWorkRequest.Builder(DownloadFileToCacheWorker::class.java)
            .setInputData(data)
            .addTag(source.fileId)
            .setExpeditedIfSupported()
            .build()
        workManager.enqueue(request)
        return request.id
    }

    /** Waits for the work to finish. True when it succeeded, null when this work was stopped meanwhile. */
    private fun awaitWork(workManager: WorkManager, workId: UUID): Boolean? {
        var result: Boolean? = null
        while (!isStopped) {
            val info = workManager.getWorkInfoById(workId).get()
            if (info == null || info.state.isFinished) {
                result = info?.state == WorkInfo.State.SUCCEEDED
                break
            }
            Thread.sleep(DOWNLOAD_POLL_MS)
        }
        return result
    }

    private fun showToast(@StringRes message: Int) = userMessage(message)

    /** Tells the user [message]; a test replaces it, the unit tests have no string resources to show. */
    internal var userMessage: (Int) -> Unit = { message ->
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun isForbidden(error: Throwable?) = error is HttpException && error.code() == HTTP_FORBIDDEN

    @Suppress("TooGenericExceptionCaught")
    private fun tryCreateShare(filePath: String?): Boolean {
        var created = false
        for (attempt in 1..SHARE_MAX_ATTEMPTS) {
            var succeeded = false
            var shouldRetry = false
            ncApi.createRemoteShare(
                credentials,
                ApiUtils.getSharingUrl(baseUrl!!),
                filePath,
                roomToken,
                "10",
                metaData,
                "" // no reference id
            )
                .subscribeOn(Schedulers.io())
                .blockingSubscribe(
                    { succeeded = true },
                    { e ->
                        if (e is HttpException && e.code() == HTTP_NOT_FOUND && attempt < SHARE_MAX_ATTEMPTS) {
                            shouldRetry = true
                        } else {
                            Log.w(TAG, "error while creating RemoteShare", e)
                            lastRefusal = e
                        }
                    }
                )
            if (succeeded || !shouldRetry) {
                created = succeeded
                break
            }
            Thread.sleep(SHARE_RETRY_DELAY_MS)
        }
        return created
    }

    companion object {
        private val TAG = ShareOperationWorker::class.simpleName
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_FORBIDDEN = 403
        private const val SHARE_MAX_ATTEMPTS = 4
        private const val SHARE_RETRY_DELAY_MS = 2000L
        private const val DOWNLOAD_POLL_MS = 500L
        private const val KEY_COPY_FILE_ID = "KEY_COPY_FILE_ID"
        private const val KEY_COPY_FILE_NAME = "KEY_COPY_FILE_NAME"
        private const val KEY_COPY_FILE_SIZE = "KEY_COPY_FILE_SIZE"
        private const val KEY_COPY_ATTACHMENT_FOLDER = "KEY_COPY_ATTACHMENT_FOLDER"
        private const val KEY_FILE_PERMISSIONS = "KEY_FILE_PERMISSIONS"
        private const val NO_PERMISSIONS = -1
        private const val PERMISSION_UPDATE = 2
        private const val PERMISSION_SHARE = 16

        private val _shareCompletedFlow: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 1)
        val shareCompletedFlow: SharedFlow<String> = _shareCompletedFlow

        @Suppress("LongParameterList")
        fun shareFile(
            roomToken: String?,
            conversationName: String,
            currentUser: User,
            remotePath: String,
            metaData: String?,
            copySource: CopySource?,
            permissions: Int? = null
        ) {
            val paths: MutableList<String> = ArrayList()
            paths.add(remotePath)

            val data = Data.Builder()
                .putLong(KEY_INTERNAL_USER_ID, currentUser.id!!)
                .putString(KEY_ROOM_TOKEN, roomToken)
                .putStringArray(KEY_FILE_PATHS, paths.toTypedArray())
                .putString(KEY_META_DATA, metaData)
                .putString(KEY_CONVERSATION_NAME, conversationName)
                .apply { copySource?.putInto(this) }
                .apply { permissions?.let { putInt(KEY_FILE_PERMISSIONS, it) } }
                .build()
            val shareWorker = OneTimeWorkRequest.Builder(ShareOperationWorker::class.java)
                .setInputData(data)
                .setExpeditedIfSupported()
                .build()
            WorkManager.getInstance().enqueue(shareWorker)
        }
    }
}
