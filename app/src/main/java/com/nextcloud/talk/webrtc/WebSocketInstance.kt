/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ProcessLifecycleOwner
import autodagger.AutoInjector
import com.bluelinelabs.logansquare.LoganSquare
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.events.WebSocketCommunicationEvent
import com.nextcloud.talk.models.json.participants.ParticipantDto
import com.nextcloud.talk.models.json.participants.ParticipantDto.ActorType
import com.nextcloud.talk.models.json.signaling.NCSignalingMessageDto
import com.nextcloud.talk.models.json.signaling.settings.FederationSettingsDto
import com.nextcloud.talk.models.json.websocket.BaseWebSocketMessageDto
import com.nextcloud.talk.models.json.websocket.ByeWebSocketMessageDto
import com.nextcloud.talk.models.json.websocket.CallOverallWebSocketMessage
import com.nextcloud.talk.models.json.websocket.CallWebSocketMessageDto
import com.nextcloud.talk.models.json.websocket.ErrorOverallWebSocketMessage
import com.nextcloud.talk.models.json.websocket.EventOverallWebSocketMessage
import com.nextcloud.talk.models.json.websocket.HelloResponseOverallWebSocketMessage
import com.nextcloud.talk.models.json.websocket.JoinedRoomOverallWebSocketMessage
import com.nextcloud.talk.signaling.SignalingMessageReceiver
import com.nextcloud.talk.signaling.SignalingMessageSender
import com.nextcloud.talk.utils.bundle.BundleKeys
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.greenrobot.eventbus.EventBus
import java.io.IOException
import java.lang.Thread.sleep
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
@Suppress("TooManyFunctions")
class WebSocketInstance
@JvmOverloads
@VisibleForTesting
internal constructor(
    conversationUser: User,
    connectionUrl: String,
    webSocketTicket: String,
    // Test seam: when the client and the bus are given, dependency injection via the application component is skipped.
    testOkHttpClient: OkHttpClient? = null,
    testEventBus: EventBus? = null,
    testConnectionHelper: WebSocketConnectionHelper? = null
) : WebSocketListener() {
    @JvmField
    @Inject
    var okHttpClient: OkHttpClient? = null

    @JvmField
    @Inject
    var eventBus: EventBus? = null

    @JvmField
    @Inject
    var context: Context? = null
    private val conversationUser: User
    private val webSocketTicket: String
    private var resumeId: String? = null
    var sessionId: String? = null
        private set
    private var hasMCU = false
    private var supportsChatRelay = false
    var isConnected: Boolean
        private set
    private val webSocketConnectionHelper: WebSocketConnectionHelper

    @Volatile
    private var internalWebSocket: WebSocket? = null
    private val connectionUrl: String
    private var currentRoomToken: String? = null
    private var currentNormalBackendSession: String? = null

    // The room of the last join message which the server did not answer yet.
    private var pendingJoinRoomToken: String? = null
    private var currentFederation: FederationSettingsDto? = null
    private var reconnecting = false

    // Guards the state of the connection (internalWebSocket, isConnected, reconnecting, messagesQueue): it is read and
    // changed by the callers of the app and by the threads of OkHttp. Never held while a message is dispatched to
    // listeners or while waiting.
    private val connectionLock = Any()

    // Times (SystemClock.elapsedRealtime) for the log of a failure: how long the connection lived and how long ago the
    // server was heard from last.
    @Volatile
    private var connectStartedAt = 0L

    @Volatile
    private var lastFrameAt = 0L
    private val usersHashMap: HashMap<String?, ParticipantDto>
    private var messagesQueue: MutableList<String> = ArrayList()
    private val signalingMessageReceiver = ExternalSignalingMessageReceiver()
    val signalingMessageSender = ExternalSignalingMessageSender()
    private val signalingHttpClient: OkHttpClient by lazy { createSignalingHttpClient(okHttpClient!!) }

    init {
        require((testOkHttpClient == null) == (testEventBus == null)) {
            "testOkHttpClient and testEventBus must be given together"
        }
        if (testOkHttpClient != null && testEventBus != null) {
            okHttpClient = testOkHttpClient
            eventBus = testEventBus
        } else {
            sharedApplication!!.componentApplication.inject(this)
        }
        this.connectionUrl = connectionUrl
        this.conversationUser = conversationUser
        this.webSocketTicket = webSocketTicket
        webSocketConnectionHelper = testConnectionHelper ?: WebSocketConnectionHelper()
        usersHashMap = HashMap()
        isConnected = false
        // A new instance right after the end of a call means the process was killed, as only the process start creates
        // one (WebsocketConnectionsWorker): the age of the process tells it.
        Log.d(TAG, "Created for user ${conversationUser.id}, process age ${processAgeMillis()} ms")
        restartWebSocket("created")
    }

    private fun processAgeMillis(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    private fun sendHello(webSocket: WebSocket) {
        try {
            if (TextUtils.isEmpty(resumeId)) {
                webSocket.send(
                    LoganSquare.serialize(
                        webSocketConnectionHelper
                            .getAssembledHelloModel(conversationUser, webSocketTicket)
                    )
                )
            } else {
                webSocket.send(
                    LoganSquare.serialize(
                        webSocketConnectionHelper
                            .getAssembledHelloModelForResume(resumeId)
                    )
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to serialize hello model")
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        synchronized(connectionLock) {
            val currentWebSocket = internalWebSocket
            Log.d(TAG, "Open webSocket ${webSocket.hashCode()} (current is ${currentWebSocket?.hashCode()})")
            if (webSocket !== currentWebSocket) {
                // A restart replaced this socket while its upgrade was under way. Taking it for the current one would
                // leave the new socket without a hello, so the server closes it, and the hello would go to the old one.
                Log.d(TAG, "Ignoring the open of the replaced webSocket ${webSocket.hashCode()}")
                return
            }
            sendHello(webSocket)
        }
    }

    private fun closeWebSocket(webSocket: WebSocket, reason: String) {
        synchronized(connectionLock) {
            logClosing(webSocket, reason)
            // The socket has failed already, so there is nobody to receive a close frame: cancel it at once.
            webSocket.close(NORMAL_CLOSURE, null)
            webSocket.cancel()
            if (webSocket !== internalWebSocket) {
                return
            }
            isConnected = false
            if (TextUtils.isEmpty(resumeId)) {
                Log.d(TAG, "closeWebSocket: dropping ${messagesQueue.size} queued messages, new session follows")
                messagesQueue = ArrayList()
            } else {
                // The session is resumed, so the server still knows the room and the peers. Messages that did not
                // leave the device (the "room" join, "requestoffer") must reach it, or the stream of the other
                // participants is never requested.
                Log.d(TAG, "closeWebSocket: keeping ${messagesQueue.size} queued messages for the resumed session")
            }
        }
        Log.w(TAG, "Reconnecting webSocket in 1 s after: $reason")
        sleep(ONE_SECOND)
        synchronized(connectionLock) {
            // A message sent during the pause opens a new socket (see sendMessage). A restart now would cancel that
            // socket, which has not said hello yet, and open yet another one a few milliseconds later.
            if (webSocket !== internalWebSocket) {
                Log.d(TAG, "closeWebSocket: ${webSocket.hashCode()} was replaced during the pause, no restart")
                return
            }
            restartWebSocket("reconnect after: $reason")
        }
    }

    fun clearResumeId() {
        resumeId = ""
    }

    /**
     * @param reason who wants the restart and why. It only goes to the log, to tell later which code path ended a
     * connection.
     */
    @JvmOverloads
    fun restartWebSocket(reason: String = "unspecified") {
        synchronized(connectionLock) {
            val wasConnected = isConnected
            val previousWebSocket = openNewWebSocket()
            if (previousWebSocket != null) {
                logClosing(previousWebSocket, "restartWebSocket: $reason")
                previousWebSocket.close(NORMAL_CLOSURE, null)
                // A connection which said hello is healthy as far as the client knows: let the close frame leave the
                // device. cancel() drops the send queue, so the server would see the connection vanish without any
                // closing. OkHttp cancels a close which the server does not answer by itself after 60 seconds.
                // A connection which did not get as far as a hello is probably dead already.
                if (!wasConnected) {
                    previousWebSocket.cancel()
                }
            }
        }
    }

    /**
     * Logs which code path ends [webSocket] and in which state, to be able to tell from the log of a field test who
     * closed a connection and why. The stack shows the caller, as several parts of the app can end a connection.
     */
    private fun logClosing(webSocket: WebSocket, reason: String) {
        val caller = Throwable().stackTrace
            .drop(CALLER_STACK_SKIP)
            .take(CALLER_STACK_DEPTH)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        Log.w(
            TAG,
            "Closing webSocket ${webSocket.hashCode()}: $reason " +
                "(isCurrent=${webSocket === internalWebSocket}, isConnected=$isConnected, " +
                "reconnecting=$reconnecting, hasResumeId=${!TextUtils.isEmpty(resumeId)}) caller: $caller"
        )
    }

    /**
     * Opens a new signaling session (hello without resumeid) and closes the old one at the server at once with a
     * "bye", instead of leaving it to expire after the resume window. The old session would otherwise stay in the
     * call for up to 30 seconds and show up as a second participant. Use it when the old session is not going to
     * be resumed.
     *
     * The old WebSocket is closed gracefully and not cancelled: cancel() drops the send queue, so the "bye" would
     * never leave the device. The new WebSocket is made the current one before the "bye" is sent, so callbacks of
     * the old one are never taken for the current one.
     */
    fun restartWebSocketWithNewSession() {
        synchronized(connectionLock) {
            Log.d(TAG, "restartWebSocketWithNewSession: $connectionUrl, isConnected=$isConnected")
            val wasConnected = isConnected
            resumeId = ""
            val previousWebSocket = openNewWebSocket()
            val byeSent = wasConnected && previousWebSocket != null && sendBye(previousWebSocket)
            previousWebSocket?.close(NORMAL_CLOSURE, null)
            if (!byeSent) {
                previousWebSocket?.cancel()
            }
        }
    }

    /**
     * @return the previous WebSocket, which is still open
     */
    private fun openNewWebSocket(): WebSocket? {
        Log.d(TAG, "restartWebSocket: $connectionUrl")
        val previousWebSocket = internalWebSocket
        isConnected = false
        reconnecting = true
        connectStartedAt = SystemClock.elapsedRealtime()
        lastFrameAt = 0L
        val request = Request.Builder().url(connectionUrl).build()
        internalWebSocket = signalingHttpClient.newWebSocket(request, this)
        return previousWebSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket === internalWebSocket) {
            lastFrameAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "Receiving : $webSocket $text")
            try {
                val (messageType) = LoganSquare.parse(text, BaseWebSocketMessageDto::class.java)
                if (messageType != null) {
                    when (messageType) {
                        "hello" -> processHelloMessage(webSocket, text)
                        "error" -> processErrorMessage(webSocket, text)
                        "room" -> processJoinedRoomMessage(text)
                        "event" -> processEventMessage(text)
                        "message" -> processMessage(text)
                        "bye" -> {
                            // Nothing reconnects after this until the next message is sent.
                            Log.d(TAG, "Server ended the signaling session with a bye: $text")
                            isConnected = false
                            resumeId = ""
                        }

                        else -> {}
                    }
                } else {
                    Log.e(TAG, "Received message with type: null")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to recognize WebSocket message", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun processMessage(text: String) {
        val (_, callWebSocketMessage) = LoganSquare.parse(text, CallOverallWebSocketMessage::class.java)
        if (callWebSocketMessage != null) {
            val ncSignalingMessage = callWebSocketMessage.ncSignalingMessage

            if (ncSignalingMessage != null &&
                TextUtils.isEmpty(ncSignalingMessage.from) &&
                callWebSocketMessage.senderWebSocketMessage != null
            ) {
                ncSignalingMessage.from = callWebSocketMessage.senderWebSocketMessage!!.sessionId
            }

            signalingMessageReceiver.processChatMessage(callWebSocketMessage)
        }
    }

    @Throws(IOException::class)
    private fun processEventMessage(text: String) {
        val eventOverallWebSocketMessage = LoganSquare.parse(text, EventOverallWebSocketMessage::class.java)
        if (eventOverallWebSocketMessage.eventMap != null) {
            val target = eventOverallWebSocketMessage.eventMap!!["target"] as String?
            if (target != null) {
                when (target) {
                    Globals.TARGET_ROOM -> {
                        if ("message" == eventOverallWebSocketMessage.eventMap!!["type"]) {
                            processRoomMessageMessage(eventOverallWebSocketMessage, text)
                        } else if ("join" == eventOverallWebSocketMessage.eventMap!!["type"]) {
                            processRoomJoinMessage(eventOverallWebSocketMessage)
                        } else if ("leave" == eventOverallWebSocketMessage.eventMap!!["type"]) {
                            processRoomLeaveMessage(eventOverallWebSocketMessage)
                        }
                        signalingMessageReceiver.processChatMessage(eventOverallWebSocketMessage.eventMap)
                    }

                    Globals.TARGET_PARTICIPANTS ->
                        signalingMessageReceiver.processChatMessage(eventOverallWebSocketMessage.eventMap)

                    else ->
                        Log.i(TAG, "Received unknown/ignored event target: $target")
                }
            } else {
                Log.w(TAG, "Received message with event target: null")
            }
        }
    }

    private fun processRoomMessageMessage(eventOverallWebSocketMessage: EventOverallWebSocketMessage, text: String) {
        val messageHashMap = eventOverallWebSocketMessage.eventMap?.get("message") as Map<*, *>?

        if (messageHashMap != null && messageHashMap.containsKey("data")) {
            val dataHashMap = messageHashMap["data"] as Map<*, *>?

            if (dataHashMap != null && dataHashMap.containsKey("chat")) {
                val chatMap = dataHashMap["chat"] as Map<*, *>?
                if (chatMap != null && chatMap.containsKey("refresh") && chatMap["refresh"] as Boolean) {
                    val refreshChatHashMap = HashMap<String, String?>()
                    refreshChatHashMap[BundleKeys.KEY_ROOM_TOKEN] = messageHashMap["roomid"] as String?
                    refreshChatHashMap[BundleKeys.KEY_INTERNAL_USER_ID] = (conversationUser.id!!).toString()
                    eventBus!!.post(WebSocketCommunicationEvent("refreshChat", refreshChatHashMap))
                }

                if (chatMap != null && chatMap.containsKey("comment")) {
                    signalingMessageReceiver.processChatMessage(text)
                }
            } else if (dataHashMap != null && dataHashMap.containsKey("recording")) {
                val recordingMap = dataHashMap["recording"] as Map<*, *>?
                if (recordingMap != null && recordingMap.containsKey("status")) {
                    val status = (recordingMap["status"] as Long?)!!.toInt()
                    Log.d(TAG, "status is $status")
                    val recordingHashMap = HashMap<String, String>()
                    recordingHashMap[BundleKeys.KEY_RECORDING_STATE] = status.toString()
                    eventBus!!.post(WebSocketCommunicationEvent("recordingStatus", recordingHashMap))
                }
            }
        }
    }

    private fun processRoomJoinMessage(eventOverallWebSocketMessage: EventOverallWebSocketMessage) {
        val joinEventList = eventOverallWebSocketMessage.eventMap?.get("join") as List<HashMap<String, Any>>?
        var internalHashMap: HashMap<String, Any>
        var participant: ParticipantDto
        for (i in joinEventList!!.indices) {
            internalHashMap = joinEventList[i]
            val userMap = internalHashMap["user"] as HashMap<String, Any>?
            participant = ParticipantDto()
            val userId = internalHashMap["userid"] as String?
            if (userId != null) {
                participant.actorType = ActorType.USERS
                participant.actorId = userId
            } else {
                participant.actorType = ActorType.GUESTS
                // FIXME seems to be not given by the HPB: participant.setActorId();
            }
            if (userMap != null) {
                // There is no "user" attribute for guest participants.
                participant.displayName = userMap["displayname"] as String?
            }
            usersHashMap[internalHashMap["sessionid"] as String?] = participant
        }
    }

    private fun processRoomLeaveMessage(eventOverallWebSocketMessage: EventOverallWebSocketMessage) {
        val leaveEventList = eventOverallWebSocketMessage.eventMap?.get("leave") as List<String>?
        for (i in leaveEventList!!.indices) {
            usersHashMap.remove(leaveEventList[i])
        }
    }

    fun getUserMap(): HashMap<String?, ParticipantDto> = usersHashMap

    @Throws(IOException::class)
    private fun processJoinedRoomMessage(text: String) {
        val (_, roomWebSocketMessage) = LoganSquare.parse(text, JoinedRoomOverallWebSocketMessage::class.java)
        if (roomWebSocketMessage != null) {
            val alreadyInRoom = roomWebSocketMessage.roomId == currentRoomToken
            currentRoomToken = roomWebSocketMessage.roomId
            pendingJoinRoomToken = null
            if (roomWebSocketMessage.roomPropertiesWebSocketMessage != null && !TextUtils.isEmpty(currentRoomToken)) {
                if (alreadyInRoom) {
                    sendRoomUpdatedEvent()
                } else {
                    sendRoomJoinedEvent()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun processErrorMessage(webSocket: WebSocket, text: String) {
        Log.e(TAG, "Received error: $text")
        val (_, message) = LoganSquare.parse(text, ErrorOverallWebSocketMessage::class.java)
        if (message != null) {
            if ("no_such_session" == message.code) {
                Log.d(TAG, "WebSocket " + webSocket.hashCode() + " resumeID " + resumeId + " expired")
                resumeId = ""
                currentRoomToken = ""
                currentNormalBackendSession = ""
                restartWebSocket("error no_such_session")
            } else if ("hello_expected" == message.code) {
                restartWebSocket("error hello_expected")
            } else if ("already_joined" == message.code) {
                processAlreadyJoinedMessage()
            } else if ("no_such_room" == message.code) {
                // The room session is stale (e.g. reaped by the server). Clear the cached join state so a retry
                // actually sends, and let the call UI fetch a fresh room session via the joinRoom API.
                Log.d(TAG, "Joining the room was rejected, the room session needs to be refreshed")
                currentRoomToken = ""
                currentNormalBackendSession = ""
                pendingJoinRoomToken = null
                eventBus!!.post(WebSocketCommunicationEvent("roomJoinFailed", HashMap()))
            }
        }
    }

    /**
     * The server answers a join of a room which its signaling session is in already (e.g. the chat joined it, then
     * the call joins it with the room session of the call) with an error instead of a "room" message. It has taken the
     * room session of the request over by then, so the join worked. The waiting call would never be told otherwise.
     */
    private fun processAlreadyJoinedMessage() {
        val roomToken = pendingJoinRoomToken
        pendingJoinRoomToken = null
        Log.d(TAG, "Room $roomToken was joined already by this signaling session, the room session was updated")
        if (!TextUtils.isEmpty(roomToken)) {
            currentRoomToken = roomToken
            sendRoomJoinedEvent()
        }
    }

    @Throws(IOException::class)
    private fun processHelloMessage(webSocket: WebSocket, text: String) {
        synchronized(connectionLock) {
            if (webSocket !== internalWebSocket) {
                Log.d(TAG, "Ignoring the hello of the replaced webSocket ${webSocket.hashCode()}")
                return
            }
            isConnected = true
            reconnecting = false
        }
        val oldResumeId = resumeId
        val (_, helloResponseWebSocketMessage1) = LoganSquare.parse(
            text,
            HelloResponseOverallWebSocketMessage::class.java
        )
        if (helloResponseWebSocketMessage1 != null) {
            resumeId = helloResponseWebSocketMessage1.resumeId
            sessionId = helloResponseWebSocketMessage1.sessionId
            hasMCU = helloResponseWebSocketMessage1.serverHasMCUSupport()

            val features =
                helloResponseWebSocketMessage1.serverHelloResponseFeaturesWebSocketMessage?.features ?: emptyList()
            supportsChatRelay = features.contains("chat-relay")
            if (supportsChatRelay) {
                Log.d(TAG, "chat-relay is supported")
            } else {
                Log.d(TAG, "chat-relay is NOT supported")
            }
        }
        synchronized(connectionLock) {
            Log.d(
                TAG,
                "hello: resumed=${!TextUtils.isEmpty(oldResumeId)}, sending ${messagesQueue.size} queued messages"
            )
            for (i in messagesQueue.indices) {
                webSocket.send(messagesQueue[i])
            }
            messagesQueue = ArrayList()
        }
        val helloHashMap = HashMap<String, String?>()
        if (!TextUtils.isEmpty(oldResumeId)) {
            helloHashMap["oldResumeId"] = oldResumeId
        } else {
            currentRoomToken = ""
            currentNormalBackendSession = ""
            pendingJoinRoomToken = null
        }
        if (!TextUtils.isEmpty(currentRoomToken)) {
            helloHashMap[Globals.ROOM_TOKEN] = currentRoomToken
        }
        eventBus!!.post(WebSocketCommunicationEvent("hello", helloHashMap))
    }

    private fun sendRoomJoinedEvent() {
        val joinRoomHashMap = HashMap<String, String?>()
        joinRoomHashMap[Globals.ROOM_TOKEN] = currentRoomToken
        eventBus!!.post(WebSocketCommunicationEvent("roomJoined", joinRoomHashMap))
    }

    private fun sendRoomUpdatedEvent() {
        val hashMap = HashMap<String, String?>()
        hashMap[Globals.ROOM_TOKEN] = currentRoomToken
        eventBus!!.post(WebSocketCommunicationEvent("roomUpdated", hashMap))
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d(TAG, "Receiving bytes : " + bytes.hex())
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // A closing which the app did not start (no "Closing webSocket" line before) comes from the server or a proxy.
        Log.w(
            TAG,
            "onClosing : WebSocket ${webSocket.hashCode()} $code / $reason " +
                "(isCurrent=${webSocket === internalWebSocket}, ${describeTimes()})"
        )
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        val isCurrent = webSocket === internalWebSocket
        Log.w(
            TAG,
            "onClosed : WebSocket ${webSocket.hashCode()} $code / $reason " +
                "(isCurrent=$isCurrent, hasResumeId=${!TextUtils.isEmpty(resumeId)}, ${describeTimes()})"
        )
        // A replaced socket is closed gracefully (see restartWebSocketWithNewSession) and reports here late. It must
        // not mark the current connection as closed.
        if (isCurrent) {
            Log.w(TAG, "The current webSocket was closed, nothing reconnects until the next message is sent")
            isConnected = false
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val isCurrent = webSocket === internalWebSocket
        Log.e(
            TAG,
            "Error : WebSocket ${webSocket.hashCode()} (isCurrentInternalWebSocket=$isCurrent, " +
                "httpCode=${response?.code}, ${describeTimes()}, ${describeEnvironment()})",
            t
        )
        closeWebSocket(webSocket, "onFailure ${t.javaClass.simpleName}: ${t.message}")
    }

    private fun describeTimes(): String {
        val now = SystemClock.elapsedRealtime()
        val sinceConnectStart = if (connectStartedAt == 0L) -1 else now - connectStartedAt
        val sinceLastFrame = if (lastFrameAt == 0L) -1 else now - lastFrameAt
        return "sinceConnectStart=$sinceConnectStart ms, sinceLastFrame=$sinceLastFrame ms"
    }

    /**
     * The state of the phone at a failure. A connection that the phone closes a few seconds after a call, with the
     * network gone for seconds afterwards, points to the system (network switch, power management) and not to the app:
     * this line tells which.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun describeEnvironment(): String {
        val appContext = context ?: return "no context"
        return try {
            val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
            val network = connectivityManager?.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            val transport = when {
                capabilities == null -> "none"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            "network=${network?.networkHandle} $transport validated=$validated, " +
                "interactive=${powerManager?.isInteractive}, idle=${powerManager?.isDeviceIdleMode}, " +
                "importance=${processInfo.importance}, ${describeBackgroundLimits(appContext, connectivityManager)}"
        } catch (e: RuntimeException) {
            "environment unknown: ${e.javaClass.simpleName}"
        }
    }

    /**
     * What the system lets the app do in the background. A phone which cuts the network of an app that has left the
     * screen closes its sockets without a close frame (the server sees "unexpected EOF"): this tells whether it did.
     * appState is the state of the app process (RESUMED/STARTED: on screen, CREATED: in the background).
     */
    private fun describeBackgroundLimits(appContext: Context, connectivityManager: ConnectivityManager?): String {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val standbyBucket = appContext.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
        val appState = try {
            ProcessLifecycleOwner.get().lifecycle.currentState
        } catch (e: IllegalStateException) {
            Log.d(TAG, "No process lifecycle: ${e.message}")
            null
        }
        return "appState=$appState, backgroundRestricted=${activityManager?.isBackgroundRestricted}, " +
            "dataSaver=${connectivityManager?.restrictBackgroundStatus}, standbyBucket=$standbyBucket, " +
            "sdk=${Build.VERSION.SDK_INT}"
    }

    fun hasMCU(): Boolean = hasMCU
    fun supportsChatRelay(): Boolean = supportsChatRelay

    @Suppress("Detekt.ComplexMethod")
    fun joinRoomWithRoomTokenAndSession(
        roomToken: String,
        normalBackendSession: String?,
        federation: FederationSettingsDto? = null
    ) {
        Log.d(TAG, "joinRoomWithRoomTokenAndSession")
        Log.d(TAG, "   roomToken: $roomToken")
        Log.d(TAG, "   session: $normalBackendSession")
        try {
            val message = LoganSquare.serialize(
                webSocketConnectionHelper.getAssembledJoinOrLeaveRoomModel(roomToken, normalBackendSession, federation)
            )
            if (roomToken == "") {
                Log.d(TAG, "sending 'leave room' via websocket")
                currentNormalBackendSession = ""
                currentFederation = null
                pendingJoinRoomToken = null
                sendMessage(message)
            } else if (
                roomToken == currentRoomToken &&
                normalBackendSession == currentNormalBackendSession &&
                federation?.roomId == currentFederation?.roomId &&
                federation?.nextcloudServer == currentFederation?.nextcloudServer
            ) {
                Log.d(TAG, "roomToken & session are unchanged. Joining locally without to send websocket message")
                sendRoomJoinedEvent()
            } else {
                Log.d(TAG, "Sending join room message via websocket")
                currentNormalBackendSession = normalBackendSession
                currentFederation = federation
                pendingJoinRoomToken = roomToken
                sendMessage(message)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to serialize signaling message", e)
        }
    }

    private fun sendCallMessage(ncSignalingMessage: NCSignalingMessageDto) {
        try {
            val message = LoganSquare.serialize(
                webSocketConnectionHelper.getAssembledCallMessageModel(ncSignalingMessage)
            )
            sendMessage(message)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to serialize signaling message", e)
        }
    }

    private fun sendMessage(message: String) {
        // The check and the restart are one step: two threads which both find the connection down must not both
        // open a socket, as the second restart cancels the first socket before it has said hello.
        synchronized(connectionLock) {
            if (!isConnected || reconnecting) {
                messagesQueue.add(message)

                if (!reconnecting) {
                    restartWebSocket("message sent while not connected")
                }
            } else {
                if (!internalWebSocket!!.send(message)) {
                    messagesQueue.add(message)
                    restartWebSocket("send() refused the message")
                }
            }
        }
    }

    fun sendBye(): Boolean {
        val webSocket = internalWebSocket
        return isConnected && webSocket != null && sendBye(webSocket)
    }

    /**
     * @return true if the "bye" was handed over to [webSocket]
     */
    private fun sendBye(webSocket: WebSocket): Boolean {
        var sent = false
        try {
            val byeWebSocketMessage = ByeWebSocketMessageDto()
            byeWebSocketMessage.type = "bye"
            byeWebSocketMessage.bye = HashMap()
            sent = webSocket.send(LoganSquare.serialize(byeWebSocketMessage))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to serialize bye message")
        }
        return sent
    }

    fun getDisplayNameForSession(session: String?): String? {
        val participant = usersHashMap[session]
        if (participant != null) {
            if (participant.displayName != null) {
                return participant.displayName
            }
        }
        return ""
    }

    fun getSignalingMessageReceiver(): SignalingMessageReceiver = signalingMessageReceiver

    /**
     * Temporary implementation of SignalingMessageReceiver until signaling related code is extracted to a Signaling
     * class.
     *
     *
     * All listeners are called in the WebSocket reader thread. This thread should be the same as long as the WebSocket
     * stays connected, but it may change whenever it is connected again.
     */
    private class ExternalSignalingMessageReceiver : SignalingMessageReceiver() {
        fun processChatMessage(eventMap: Map<String, Any>?) {
            processEvent(eventMap)
        }

        fun processChatMessage(message: CallWebSocketMessageDto?) {
            if (message?.ncSignalingMessage?.type == "startedTyping" ||
                message?.ncSignalingMessage?.type == "stoppedTyping"
            ) {
                processCallWebSocketMessage(message)
            } else {
                processSignalingMessage(message?.ncSignalingMessage)
            }
        }

        fun processChatMessage(jsonString: String) {
            processChatMessageWebSocketMessage(jsonString)
            Log.d(TAG, "processing Received chat message")
        }
    }

    inner class ExternalSignalingMessageSender : SignalingMessageSender {
        override fun send(ncSignalingMessage: NCSignalingMessageDto) {
            sendCallMessage(ncSignalingMessage)
        }
    }

    companion object {
        private const val TAG = "WebSocketInstance"
        private const val NORMAL_CLOSURE = 1000
        private const val ONE_SECOND: Long = 1000
        private const val CALLER_STACK_SKIP = 2
        private const val CALLER_STACK_DEPTH = 6
        private const val PING_INTERVAL_SECONDS: Long = 30

        // The server keeps the session for a resume 30 seconds. The base client waits 45 seconds for a connection
        // and for the answer of the upgrade request, so one hanging attempt used up the whole window and the next
        // one could only open a new session. Once connected the read timeout is off (OkHttp), pings take over.
        private const val CONNECT_TIMEOUT_SECONDS: Long = 10

        // Dedicated client with pings, so half-open WebSocket connections
        // (e.g. after a WiFi to cellular switch without TCP reset) fail and trigger the reconnect path.
        internal fun createSignalingHttpClient(baseClient: OkHttpClient): OkHttpClient =
            baseClient.newBuilder()
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
