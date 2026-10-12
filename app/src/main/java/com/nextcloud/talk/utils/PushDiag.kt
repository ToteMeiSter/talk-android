/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.nextcloud.talk.logger.AppLog as Log
import com.nextcloud.talk.utils.bundle.BundleKeys
import retrofit2.HttpException

/**
 * Fork-only diagnostics of the push chain (`adb logcat -s PushDiag`).
 * Never pass push tokens, keys, signatures, device identifiers or credentials here.
 */
@Suppress("TooManyFunctions")
object PushDiag {
    const val TAG = "PushDiag"
    private const val MAX_TEXT = 200
    private const val MAX_CAUSES = 5
    private const val MAX_FCM_VALUE = 32

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, if (error == null) message else "$message: ${describe(error)}")
    }

    /**
     * State of the network and of the app at the moment of the push: whether the system lets the app use the
     * network (active network, VALIDATED, blocked state, background data restriction), power state and the
     * importance of the process, with [withPower] also what [describePowerRestrictions] reads (several binder calls:
     * once per stage). No tokens, no addresses.
     */
    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    fun describeNetworkAndProcess(context: Context, withPower: Boolean = false): String =
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val info = cm?.activeNetworkInfo
            val pm = context.getSystemService(PowerManager::class.java)
            val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            "activeNetwork=${network != null} " +
                "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                "internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                "notSuspended=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)} " +
                "networkInfoState=${info?.detailedState} " +
                "restrictBackground=${cm?.restrictBackgroundStatus} " +
                "deviceIdle=${pm?.isDeviceIdleMode} interactive=${pm?.isInteractive} " +
                "processImportance=${process.importance}" +
                if (withPower) " ${describePowerRestrictions(context)}" else ""
        } catch (e: RuntimeException) {
            "network state unavailable: ${describe(e)}"
        }

    /**
     * The system closes the network of the app (`NetworkInfo` BLOCKED). The network callback of WorkManager does not
     * report it on some Huawei devices, `getActiveNetworkInfo` does. False if the state is not known.
     */
    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    fun isNetworkBlocked(context: Context): Boolean =
        try {
            context.getSystemService(ConnectivityManager::class.java)?.activeNetworkInfo?.detailedState ==
                NetworkInfo.DetailedState.BLOCKED
        } catch (e: RuntimeException) {
            false
        }

    /**
     * What may close the network to the app besides Doze: standby bucket (10 active, 20 working set, 30 frequent,
     * 40 rare, 45 restricted), battery saver, battery optimization, background restriction, light Doze and low power
     * standby. A value the API level does not have is left out. Numbers and booleans only.
     */
    @Suppress("TooGenericExceptionCaught")
    fun describePowerRestrictions(context: Context): String =
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            val am = context.getSystemService(ActivityManager::class.java)
            val parts = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                parts += "standbyBucket=${context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket}"
            }
            parts += "powerSave=${pm?.isPowerSaveMode}"
            parts += "ignoringBatteryOpt=${pm?.isIgnoringBatteryOptimizations(context.packageName)}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                parts += "backgroundRestricted=${am?.isBackgroundRestricted}"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                parts += "lightIdle=${pm?.isDeviceLightIdleMode}"
                parts += "lowPowerStandby=${pm?.isLowPowerStandbyEnabled}"
            }
            parts.joinToString(" ")
        } catch (e: RuntimeException) {
            "power restrictions unavailable: ${e.javaClass.simpleName}"
        }

    // region notifications: every notify and cancel of the app is written here, to tell "the app removed it" from
    // "the system removed or hid it" (summary: the notification of a push was shown, then it was not in the shade)

    private const val GONE_CHECK_DELAY_MS = 10_000L
    private const val MAX_TRACKED_CANCELS = 64
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val cancelledByApp = ArrayDeque<Int>()

    /** Shows [notification] and writes what the system will use to decide how loud and where it appears. */
    fun notify(context: Context, source: String, id: Int, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java)
        i(
            "notify: source=$source ${describeNotification(
                id,
                notification
            )} ${describeAlertState(context, notification)}"
        )
        synchronized(cancelledByApp) { cancelledByApp.remove(id) }
        manager.notify(id, notification)
        i("notify done: id=$id active=${describeActive(context)}")
        scheduleGoneCheck(context, id)
    }

    /** The branch where the notification is not shown because POST_NOTIFICATIONS is not granted. */
    fun notifySkippedNoPermission(source: String, id: Int) {
        w("notify skipped: source=$source id=$id reason=POST_NOTIFICATIONS not granted")
    }

    /** Removes the notification [id]; [reason] names the calling place. */
    fun cancel(context: Context, reason: String, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val wasActive = activeOrEmpty(manager).firstOrNull { it.id == id }
        i(
            "cancel: reason=$reason id=$id serverId=${wasActive?.notification?.let {
                serverId(it)
            }} active=${wasActive != null}"
        )
        synchronized(cancelledByApp) {
            cancelledByApp.addLast(id)
            while (cancelledByApp.size > MAX_TRACKED_CANCELS) cancelledByApp.removeFirst()
        }
        manager.cancel(id)
    }

    /** One line for a scan that looks for notifications to cancel: what was there and what the scan took. */
    fun cancelScan(reason: String, filter: String, active: List<StatusBarNotification>, ids: List<Int>) {
        i(
            "cancel scan: reason=$reason filter=$filter scanned=${formatActive(
                active.map {
                    it.toRef()
                }
            )} cancelled=$ids"
        )
    }

    fun formatActive(items: List<ActiveRef>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = " ") { "${it.id}/${it.serverId}/${it.channelId}" }

    fun describeNotification(id: Int, notification: Notification): String =
        formatNotification(
            id,
            serverId(notification),
            notification.channelId,
            notification.group,
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            notification.shortcutId,
            notification.flags and Notification.FLAG_BUBBLE != 0,
            notification.bubbleMetadata != null
        )

    @Suppress("LongParameterList")
    fun formatNotification(
        id: Int,
        serverId: Long?,
        channelId: String?,
        group: String?,
        groupSummary: Boolean,
        onlyAlertOnce: Boolean,
        shortcutId: String?,
        bubbleFlag: Boolean,
        bubbleMetadata: Boolean
    ): String =
        "id=$id serverId=$serverId tag=null channel=$channelId group=$group groupSummary=$groupSummary " +
            "onlyAlertOnce=$onlyAlertOnce shortcutId=$shortcutId bubbleFlag=$bubbleFlag " +
            "bubbleMetadata=$bubbleMetadata"

    /**
     * Channel importance and sound, notification switch of the app, Do Not Disturb filter and ringer mode: the
     * settings that decide whether the notification makes a sound, pops up or is put in the silent section.
     */
    @Suppress("TooGenericExceptionCaught")
    fun describeAlertState(context: Context, notification: Notification): String =
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channelId = notification.channelId
            // API 30+: the conversation channel of the shortcut if the user made one, else the parent channel
            val shortcutId = notification.shortcutId
            val channel = if (channelId != null &&
                shortcutId != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                manager.getNotificationChannel(channelId, shortcutId)
            } else {
                channelId?.let { manager.getNotificationChannel(it) }
            }
            val conversation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && channel != null) {
                "conversationId=${channel.conversationId} parentChannel=${channel.parentChannelId} " +
                    "importantConversation=${channel.isImportantConversation} canBubble=${channel.canBubble()}"
            } else {
                "conversation=n/a"
            }
            formatAlertState(
                manager.areNotificationsEnabled(),
                manager.currentInterruptionFilter,
                context.getSystemService(AudioManager::class.java)?.ringerMode,
                channel?.importance,
                channel?.sound != null,
                channel?.canBypassDnd(),
                channel?.shouldVibrate()
            ) + " $conversation"
        } catch (e: RuntimeException) {
            "alert state unavailable: ${describe(e)}"
        }

    @Suppress("LongParameterList")
    fun formatAlertState(
        enabled: Boolean,
        interruptionFilter: Int,
        ringerMode: Int?,
        channelImportance: Int?,
        channelSound: Boolean,
        channelBypassDnd: Boolean?,
        channelVibrate: Boolean?
    ): String =
        "enabled=$enabled dnd=${interruptionFilterName(interruptionFilter)} ringer=${ringerModeName(ringerMode)} " +
            "chImportance=$channelImportance chSound=$channelSound chBypassDnd=$channelBypassDnd " +
            "chVibrate=$channelVibrate"

    fun interruptionFilterName(filter: Int): String =
        when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
            else -> "UNKNOWN($filter)"
        }

    fun ringerModeName(mode: Int?): String =
        when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
            else -> "UNKNOWN($mode)"
        }

    data class ActiveRef(val id: Int, val serverId: Long?, val channelId: String?)

    private fun StatusBarNotification.toRef() = ActiveRef(id, serverId(notification), notification.channelId)

    private fun serverId(notification: Notification): Long? =
        notification.extras?.takeIf { it.containsKey(BundleKeys.KEY_NOTIFICATION_ID) }
            ?.getLong(BundleKeys.KEY_NOTIFICATION_ID)

    @Suppress("TooGenericExceptionCaught")
    private fun activeOrEmpty(manager: NotificationManager): List<StatusBarNotification> =
        try {
            manager.activeNotifications.toList()
        } catch (e: RuntimeException) {
            emptyList()
        }

    fun describeActive(context: Context): String =
        formatActive(activeOrEmpty(context.getSystemService(NotificationManager::class.java)).map { it.toRef() })

    /**
     * Looks at the notification again after 10 s. Gone and not cancelled by the app: the system (or the user)
     * removed it. The check runs on the main thread of the process: a frozen process runs it late, `elapsed` shows
     * by how much, and a process the system killed does not run it at all.
     */
    private fun scheduleGoneCheck(context: Context, id: Int) {
        val appContext = context.applicationContext
        val postedAt = SystemClock.elapsedRealtime()
        mainHandler.postDelayed({
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val elapsedS = (SystemClock.elapsedRealtime() - postedAt) / MS_PER_S
            val present = activeOrEmpty(manager).any { it.id == id }
            val byApp = synchronized(cancelledByApp) { id in cancelledByApp }
            if (present) {
                i("notification still active after $elapsedS s: id=$id")
            } else {
                i("notification gone after $elapsedS s: id=$id cancelledByApp=$byApp")
            }
        }, GONE_CHECK_DELAY_MS)
    }

    private const val MS_PER_S = 1000L
    private const val MAX_EXIT_RECORDS = 3

    /** The way the previous processes of the app ended (API 30+): reason, time, importance, description. */
    @Suppress("TooGenericExceptionCaught")
    fun logPreviousProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val exits = context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(null, 0, MAX_EXIT_RECORDS).orEmpty()
            i(
                "previous process exits: " + if (exits.isEmpty()) {
                    "none"
                } else {
                    exits.joinToString(" | ") {
                        formatExit(it.pid, it.processName, it.reason, it.timestamp, it.importance, it.description)
                    }
                }
            )
        } catch (e: RuntimeException) {
            w("previous process exits unavailable", e)
        }
    }

    @Suppress("LongParameterList")
    fun formatExit(pid: Int, process: String?, reason: Int, timestamp: Long, importance: Int, description: String?) =
        "pid=$pid process=$process reason=${exitReasonName(reason)} at=$timestamp importance=$importance " +
            "description=${description?.take(MAX_TEXT)}"

    @Suppress("CyclomaticComplexMethod")
    fun exitReasonName(reason: Int): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            "UNKNOWN($reason)"
        } else {
            when (reason) {
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                ApplicationExitInfo.REASON_OTHER -> "OTHER"
                ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
                else -> "UNKNOWN($reason)"
            }
        }

    // endregion

    /** `ok`, or the class of the error with the HTTP code: no body, no message, no address. */
    fun describeOutcome(error: Throwable?): String =
        when (error) {
            null -> "ok"
            is HttpException -> "HttpException code=${error.code()}"
            else -> error.javaClass.name
        }

    /** Keys of the FCM extras whose values are written to the log. */
    val FCM_LOGGED_KEYS: List<String> = listOf(
        "google.delivered_priority",
        "google.original_priority",
        "google.priority",
        "google.priority_reduced",
        "google.ttl",
        "google.sent_time",
        "message_type"
    )

    /**
     * Priority, lifetime and type of an FCM message for the log, and only the names of its other `google.*` keys.
     * The values of all other keys are never read: they hold the subject, the signature, identifiers and tokens.
     *
     * @param keys the keys of the extras of the message
     * @param valueOf the value of a key; asked for the keys of [FCM_LOGGED_KEYS] only
     */
    fun describeFcmExtras(keys: Set<String>, valueOf: (String) -> Any?): String {
        val values = FCM_LOGGED_KEYS.joinToString(" ") { key ->
            val value = if (key in keys) valueOf(key)?.toString()?.take(MAX_FCM_VALUE) else null
            "$key=$value"
        }
        val otherGoogleKeys = keys.filter { it.startsWith("google.") && it !in FCM_LOGGED_KEYS }.sorted()
        return "$values otherGoogleKeys=$otherGoogleKeys"
    }

    /** Exception class and message including the cause chain, truncated. */
    fun describe(error: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) sb.append(" <- ")
            sb.append(current.javaClass.name).append(": ").append(current.message?.take(MAX_TEXT))
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return sb.toString()
    }

    /** Like [describe], plus HTTP code and truncated error body; [secrets] are removed from the body. */
    @Suppress("TooGenericExceptionCaught")
    fun describeHttp(error: Throwable, vararg secrets: String?): String {
        if (error !is HttpException) return describe(error)
        val body = try {
            error.response()?.errorBody()?.string()
        } catch (e: Exception) {
            "<unreadable: ${e.javaClass.simpleName}>"
        }
        var text = body.orEmpty()
        secrets.filterNot { it.isNullOrEmpty() }.forEach { text = text.replace(it!!, "<redacted>") }
        return "HttpException code=${error.code()} body=${text.take(MAX_TEXT)}"
    }
}
