/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import android.app.Application
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.events.WebSocketCommunicationEvent
import com.nextcloud.talk.models.json.capabilities.CapabilitiesDto
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The signaling server answers a join of a room which the signaling session is in already with the error
 * "already_joined", and takes the room session of the request over. The client has to take that as a join: the call
 * waits for the "roomJoined" event, and a chat which joined before the call started leaves it without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WebSocketInstanceAlreadyJoinedTest {

    private val server = MockWebServer()
    private val serverLog = CopyOnWriteArrayList<String>()
    private val eventBus = EventBus.builder().build()
    private val events = CopyOnWriteArrayList<String>()
    private var instance: WebSocketInstance? = null
    private val path = "/t-${UUID.randomUUID()}/"

    private val eventCollector = object {
        @Subscribe
        fun onEvent(event: WebSocketCommunicationEvent) {
            events.add("${event.type}:${event.hashMap?.get("roomToken")}")
        }
    }

    @Before
    fun setUp() {
        eventBus.register(eventCollector)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == path) {
                    MockResponse().withWebSocketUpgrade(ServerListener())
                } else {
                    MockResponse().setResponseCode(HTTP_NOT_FOUND)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        eventBus.unregister(eventCollector)
        server.shutdown()
    }

    @Test
    fun joinOfRoomAlreadyJoinedWithOtherRoomSessionIsTakenForJoined() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM, "session-chat")
        waitUntil("first join answered") { events.contains("roomJoined:$ROOM") }
        events.clear()

        // the call joins with the room session it got itself
        instance.joinRoomWithRoomTokenAndSession(ROOM, "session-call")

        waitUntil("event for the second join") { events.contains("roomJoined:$ROOM") }
        assertEquals(
            listOf("room:$ROOM:session-chat", "room:$ROOM:session-call"),
            serverLog.filter { it.startsWith("room") }
        )
        assertTrue("the connection must stay: ${instance.isConnected}", instance.isConnected)
    }

    @Test
    fun joinOfSameRoomAndSessionAgainIsAnsweredLocally() {
        val instance = createConnectedInstance()
        instance.joinRoomWithRoomTokenAndSession(ROOM, "session-chat")
        waitUntil("first join answered") { events.contains("roomJoined:$ROOM") }
        events.clear()

        instance.joinRoomWithRoomTokenAndSession(ROOM, "session-chat")

        assertEquals(listOf("roomJoined:$ROOM"), events.toList())
        assertEquals(1, serverLog.count { it.startsWith("room") })
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
        instance = created
        waitUntil("session connected") { created.isConnected }
        return created
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "Timed out waiting for $what, server log: $serverLog, events: $events"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Behaves like the signaling server: the second join of a room is an "already_joined" error. */
    private inner class ServerListener : WebSocketListener() {
        private var joinedRoom: String? = null

        override fun onMessage(webSocket: WebSocket, text: String) {
            when {
                text.contains("\"type\":\"hello\"") -> webSocket.send(
                    "{\"type\":\"hello\",\"hello\":{\"resumeid\":\"resume\",\"sessionid\":\"session\"}}"
                )

                text.contains("\"type\":\"room\"") -> {
                    val room = Regex("\"roomid\":\"([^\"]*)\"").find(text)!!.groupValues[1]
                    val session = Regex("\"sessionid\":\"([^\"]*)\"").find(text)?.groupValues?.get(1)
                    serverLog.add("room:$room:$session")
                    if (joinedRoom == room) {
                        webSocket.send(
                            "{\"type\":\"error\",\"error\":{\"code\":\"already_joined\"," +
                                "\"message\":\"Already joined this room.\"," +
                                "\"details\":{\"room\":{\"roomid\":\"$room\"}}}}"
                        )
                    } else {
                        joinedRoom = room
                        webSocket.send(
                            "{\"type\":\"room\",\"room\":{\"roomid\":\"$room\",\"properties\":{\"name\":\"n\"}}}"
                        )
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }
    }

    companion object {
        private const val ROOM = "abc123"
        private const val NORMAL_CLOSURE = 1000
        private const val HTTP_NOT_FOUND = 404
        private const val TIMEOUT_MILLIS = 10_000L
        private const val POLL_MILLIS = 20L
    }
}
