/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import android.app.Application
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.json.capabilities.CapabilitiesDto
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import com.nextcloud.talk.signaling.SignalingMessageReceiver
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private fun sessionOf(room: String) = "session-in-$room"

private fun joinFrame(session: String, user: String) =
    "{\"type\":\"event\",\"event\":{\"target\":\"room\",\"type\":\"join\",\"join\":" +
        "[{\"sessionid\":\"$session\",\"userid\":\"$user\",\"user\":{\"displayname\":\"$user\"}}]}}"

private fun errorFrame(code: String) = "{\"type\":\"error\",\"error\":{\"code\":\"$code\",\"message\":\"t\"}}"

/**
 * The sessions of the room the signaling connection is in, fed by the frames the signaling server sends. A frame
 * after the one under test serves as a barrier: the client handles frames in order, so when the barrier session is
 * known, everything before it has been handled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WebSocketInstanceRoomSessionsTest {

    private val server = MockWebServer()
    private val serverLog = CopyOnWriteArrayList<String>()
    private val serverSockets = CopyOnWriteArrayList<ServerListener>()
    private val eventBus = EventBus.builder().build()
    private val instances = CopyOnWriteArrayList<WebSocketInstance>()
    private val path = "/t-${UUID.randomUUID()}/"

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == path) {
                    MockResponse().withWebSocketUpgrade(ServerListener("c${serverSockets.size + 1}"))
                } else {
                    MockResponse().setResponseCode(HTTP_NOT_FOUND)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        instances.forEach { eventBus.unregister(it) }
        server.shutdown()
    }

    @Test
    fun switchingToAnotherRoomDropsSessionsOfThePreviousRoom() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        instance.joinRoomWithRoomTokenAndSession(ROOM_B, "room-session-b")

        waitUntil("sessions of room B") { instance.isSessionInCurrentRoom(sessionOf(ROOM_B)) }
        assertFalse(instance.isSessionInCurrentRoom(sessionOf(ROOM_A)))
        assertEquals(listOf(sessionOf(ROOM_B)), instance.getRoomSessionIds())
    }

    @Test
    fun leavingTheRoomDropsTheSessions() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        instance.joinRoomWithRoomTokenAndSession("", "room-session-a")

        waitUntil("room left") { serverLog.contains("c1:left") }
        sendBarrierJoin(instance)
        assertEquals(listOf(BARRIER), instance.getRoomSessionIds())
    }

    @Test
    fun resumedSessionKeepsTheSessions() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        instance.restartWebSocket("test")
        waitUntil("resumed hello answered") { serverLog.contains("c2:hello-answered") }
        waitUntil("connected again") { instance.isConnected }
        sendBarrierJoin(instance)

        assertTrue(instance.isSessionInCurrentRoom(sessionOf(ROOM_A)))
    }

    @Test
    fun newSessionWithoutResumeStartsWithoutSessions() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        instance.clearResumeId()
        instance.restartWebSocket("test")
        waitUntil("new session said hello") { serverLog.contains("c2:hello-answered") }
        waitUntil("connected again") { instance.isConnected }
        sendBarrierJoin(instance)

        assertFalse(serverLog.first { it.startsWith("c2:hello ") }.contains("resumeid"))
        assertEquals(listOf(BARRIER), instance.getRoomSessionIds())
    }

    @Test
    fun alreadyJoinedAnswerKeepsTheSessions() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-call")
        waitUntil("second join answered") { serverLog.count { it == "c1:already_joined" } == 1 }
        sendBarrierJoin(instance)

        assertTrue(instance.isSessionInCurrentRoom(sessionOf(ROOM_A)))
    }

    @Test
    fun expiredSessionDropsTheSessions() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        serverSockets.first().push(errorFrame("no_such_session"))
        waitUntil("new session said hello") { serverLog.contains("c2:hello-answered") }
        waitUntil("connected again") { instance.isConnected }
        sendBarrierJoin(instance)

        assertEquals(listOf(BARRIER), instance.getRoomSessionIds())
    }

    @Test
    fun rejectedRoomSessionDropsTheSessions() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        serverSockets.first().push(errorFrame("no_such_room"))
        sendBarrierJoin(instance)

        assertEquals(listOf(BARRIER), instance.getRoomSessionIds())
    }

    @Test
    fun leaveEventRemovesTheSessionAndNotifiesTheListeners() {
        val instance = createConnectedInstance()
        val listener = mock<SignalingMessageReceiver.ConversationMessageListener>()
        instance.getSignalingMessageReceiver().addListener(listener)
        instance.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        waitUntil("sessions of room A") { instance.isSessionInCurrentRoom(sessionOf(ROOM_A)) }

        serverSockets.first().push(
            "{\"type\":\"event\",\"event\":{\"target\":\"room\",\"type\":\"leave\",\"leave\":" +
                "[\"${sessionOf(ROOM_A)}\"]}}"
        )
        sendBarrierJoin(instance)

        assertFalse(instance.isSessionInCurrentRoom(sessionOf(ROOM_A)))
        verify(listener).onSessionsLeft(listOf(sessionOf(ROOM_A)))
    }

    @Test
    fun accountsDoNotShareSessions() {
        val first = createConnectedInstance()
        val second = createConnectedInstance()
        first.joinRoomWithRoomTokenAndSession(ROOM_A, "room-session-a")
        second.joinRoomWithRoomTokenAndSession(ROOM_B, "room-session-b")
        waitUntil("sessions of room A") { first.isSessionInCurrentRoom(sessionOf(ROOM_A)) }
        waitUntil("sessions of room B") { second.isSessionInCurrentRoom(sessionOf(ROOM_B)) }

        assertEquals(listOf(sessionOf(ROOM_A)), first.getRoomSessionIds())
        assertEquals(listOf(sessionOf(ROOM_B)), second.getRoomSessionIds())
    }

    private fun sendBarrierJoin(instance: WebSocketInstance) {
        serverSockets.last().push(joinFrame(BARRIER, "barrier-user"))
        waitUntil("barrier handled") { instance.isSessionInCurrentRoom(BARRIER) }
    }

    private fun createConnectedInstance(): WebSocketInstance {
        val user = User(
            userId = "alice",
            baseUrl = "https://cloud.example.com",
            capabilities = CapabilitiesDto().apply {
                spreedCapability = SpreedCapabilityDto(listOf("signaling-v3"), null, "")
            }
        )
        val client = OkHttpClient()
        val created = WebSocketInstance(
            user,
            server.url(path).toString(),
            "ticket",
            client,
            eventBus,
            WebSocketConnectionHelper(client)
        )
        instances.add(created)
        waitUntil("session connected") { created.isConnected }
        return created
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what, server log: $serverLog" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * Behaves like the signaling server: a join of a room is answered with the "room" message, followed by the
     * join event of a session of that room; joining the same room again is an "already_joined" error.
     */
    private inner class ServerListener(private val name: String) : WebSocketListener() {
        private var webSocket: WebSocket? = null
        private var joinedRoom: String? = null

        init {
            serverSockets.add(this)
        }

        fun push(frame: String) {
            webSocket!!.send(frame)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            this.webSocket = webSocket
            when {
                text.contains("\"type\":\"hello\"") -> {
                    serverLog.add("$name:hello $text")
                    webSocket.send(
                        "{\"type\":\"hello\",\"hello\":{\"resumeid\":\"resume-$name\",\"sessionid\":\"own-$name\"}}"
                    )
                    serverLog.add("$name:hello-answered")
                }

                text.contains("\"type\":\"room\"") -> {
                    val room = Regex("\"roomid\":\"([^\"]*)\"").find(text)!!.groupValues[1]
                    when {
                        room.isEmpty() -> {
                            joinedRoom = null
                            webSocket.send("{\"type\":\"room\",\"room\":{\"roomid\":\"\"}}")
                            serverLog.add("$name:left")
                        }

                        room == joinedRoom -> {
                            webSocket.send(errorFrame("already_joined"))
                            serverLog.add("$name:already_joined")
                        }

                        else -> {
                            joinedRoom = room
                            webSocket.send(
                                "{\"type\":\"room\",\"room\":{\"roomid\":\"$room\",\"properties\":{\"name\":\"n\"}}}"
                            )
                            webSocket.send(joinFrame(sessionOf(room), "user-in-$room"))
                        }
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }
    }

    companion object {
        private const val ROOM_A = "roomA"
        private const val ROOM_B = "roomB"
        private const val BARRIER = "barrier-session"
        private const val NORMAL_CLOSURE = 1000
        private const val HTTP_NOT_FOUND = 404
        private const val TIMEOUT_MILLIS = 10_000L
        private const val POLL_MILLIS = 20L
    }
}
