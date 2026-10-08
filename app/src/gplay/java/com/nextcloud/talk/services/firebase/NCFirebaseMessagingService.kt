/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2022 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2017-2019 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.services.firebase

import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import autodagger.AutoInjector
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.jobs.NotificationWorker
import com.nextcloud.talk.jobs.PushRegistrationWorker
import com.nextcloud.talk.logger.AppLog as Log
import com.nextcloud.talk.logger.Logger
import com.nextcloud.talk.utils.PushDiag
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.preferences.AppPreferences
import com.nextcloud.talk.utils.setExpeditedIfSupported
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class NCFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var appPreferences: AppPreferences

    // creating the logger installs AppLog: the lines of this service after the injection reach the log of the settings
    @Suppress("unused")
    @Inject
    lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        sharedApplication!!.componentApplication.inject(this)
        // after the injection: only then AppLog reaches the log of the settings
        Log.d(TAG, "onCreate")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "onMessageReceived")
        PushDiag.i(
            "onMessageReceived: messageId=${remoteMessage.messageId} dataKeys=${remoteMessage.data.size} " +
                "priority=${remoteMessage.priority} originalPriority=${remoteMessage.originalPriority} " +
                "sentTime=${remoteMessage.sentTime} ${PushDiag.describeNetworkAndProcess(applicationContext)}"
        )
        logFcmExtras(remoteMessage)
        sharedApplication!!.componentApplication.inject(this)

        Log.d(TAG, "remoteMessage.priority: " + remoteMessage.priority)
        Log.d(TAG, "remoteMessage.originalPriority: " + remoteMessage.originalPriority)

        val data = remoteMessage.data
        val subject = data[KEY_NOTIFICATION_SUBJECT]
        val signature = data[KEY_NOTIFICATION_SIGNATURE]

        if (!subject.isNullOrEmpty() && !signature.isNullOrEmpty()) {
            val messageData = Data.Builder()
                .putString(BundleKeys.KEY_NOTIFICATION_SUBJECT, subject)
                .putString(BundleKeys.KEY_NOTIFICATION_SIGNATURE, signature)
                .putLong(BundleKeys.KEY_NOTIFICATION_PUSH_SENT_TIME, remoteMessage.sentTime)
                .build()
            val notificationWork =
                OneTimeWorkRequest.Builder(NotificationWorker::class.java).setInputData(messageData)
                    .setExpeditedIfSupported()
                    .build()
            WorkManager.getInstance().enqueue(notificationWork)
            PushDiag.i("onMessageReceived: NotificationWorker enqueued")
        } else {
            PushDiag.w(
                "onMessageReceived: dropped, subject present=${!subject.isNullOrEmpty()}, " +
                    "signature present=${!signature.isNullOrEmpty()}"
            )
        }
    }

    /** Priority and lifetime the app gets from FCM, see [PushDiag.describeFcmExtras]. Never the data of the push. */
    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    private fun logFcmExtras(remoteMessage: RemoteMessage) {
        try {
            val extras = remoteMessage.toIntent().extras ?: return
            PushDiag.i("fcm extras: ${PushDiag.describeFcmExtras(extras.keySet()) { extras.get(it) }}")
        } catch (e: RuntimeException) {
            PushDiag.w("fcm extras unavailable", e)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushDiag.i("onNewToken: present=${token.isNotEmpty()} length=${token.length}")

        appPreferences.pushToken = token
        appPreferences.pushTokenLatestGeneration = System.currentTimeMillis()

        val data: Data =
            Data.Builder().putString(PushRegistrationWorker.ORIGIN, "NCFirebaseMessagingService#onNewToken").build()
        val pushRegistrationWork = OneTimeWorkRequest.Builder(PushRegistrationWorker::class.java)
            .setInputData(data)
            .build()
        WorkManager.getInstance().enqueue(pushRegistrationWork)
    }

    companion object {
        private val TAG = NCFirebaseMessagingService::class.simpleName
        const val KEY_NOTIFICATION_SUBJECT = "subject"
        const val KEY_NOTIFICATION_SIGNATURE = "signature"
    }
}
