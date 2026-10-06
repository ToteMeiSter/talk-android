/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.utils.bundle.BundleKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A server "delete" push must be able to dismiss the incoming call notification, which is built outside of
 * NotificationWorker.createNotificationBuilder() and used to carry no matching extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallNotificationDismissTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private val user = User(id = USER_ID)
    private val otherUser = User(id = OTHER_USER_ID)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }

    private fun buildCallNotification(userId: Long, serverId: Long?) =
        NotificationUtils.applyCallDismissal(
            NotificationCompat.Builder(context, "calls")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setOngoing(true),
            userId,
            serverId
        ).build()

    private fun postCallNotification(systemId: Int, userId: Long, serverId: Long?) {
        notificationManager.notify(systemId, buildCallNotification(userId, serverId))
    }

    private fun activeIds(): List<Int> = notificationManager.activeNotifications.map { it.id }

    @Test
    fun `delete push with the call notification id dismisses the call`() {
        postCallNotification(SYSTEM_ID, USER_ID, SERVER_ID)

        NotificationUtils.cancelNotification(context, user, SERVER_ID)

        assertEquals(emptyList<Int>(), activeIds())
    }

    @Test
    fun `delete push for another notification id keeps the call ringing`() {
        postCallNotification(SYSTEM_ID, USER_ID, SERVER_ID)

        NotificationUtils.cancelNotification(context, user, SERVER_ID + 1)

        assertEquals(listOf(SYSTEM_ID), activeIds())
    }

    @Test
    fun `delete push of another account keeps the call ringing`() {
        postCallNotification(SYSTEM_ID, USER_ID, SERVER_ID)

        NotificationUtils.cancelNotification(context, otherUser, SERVER_ID)

        assertEquals(listOf(SYSTEM_ID), activeIds())
    }

    @Test
    fun `delete push without id does not dismiss a call whose push carried no id`() {
        postCallNotification(SYSTEM_ID, USER_ID, null)

        NotificationUtils.cancelNotification(context, user, null)

        assertEquals(listOf(SYSTEM_ID), activeIds())
    }

    @Test
    fun `delete-all push dismisses the call of that account only`() {
        postCallNotification(SYSTEM_ID, USER_ID, SERVER_ID)
        postCallNotification(SYSTEM_ID + 1, OTHER_USER_ID, SERVER_ID + 1)

        NotificationUtils.cancelAllNotificationsForAccount(context, user)

        assertEquals(listOf(SYSTEM_ID + 1), activeIds())
    }

    @Test
    fun `call notification carries ids and a timeout but no room token`() {
        val notification = buildCallNotification(USER_ID, SERVER_ID)

        assertEquals(USER_ID, notification.extras.getLong(BundleKeys.KEY_INTERNAL_USER_ID))
        assertEquals(SERVER_ID, notification.extras.getLong(BundleKeys.KEY_NOTIFICATION_ID))
        assertNull(notification.extras.getString(BundleKeys.KEY_ROOM_TOKEN))
        assertEquals(NotificationUtils.CALL_NOTIFICATION_TIMEOUT_MS, notification.timeoutAfter)
    }

    companion object {
        private const val USER_ID = 7L
        private const val OTHER_USER_ID = 8L
        private const val SERVER_ID = 4242L
        private const val SYSTEM_ID = 1001
    }
}
