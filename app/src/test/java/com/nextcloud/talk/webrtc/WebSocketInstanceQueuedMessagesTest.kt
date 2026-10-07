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
import com.nextcloud.talk.models.json.signaling.NCSignalingMessageDto
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A message that could not be sent while the signaling connection was down ("requestoffer" for a new participant,
 * the room join) must reach the server over the resumed session, even if the first reconnect attempt fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WebSocketInstanceQueuedMessagesTest {

    private val server = MockWebServer()
    private val serverLog = CopyOnWriteArrayList<String>()
    private val connectionCount = AtomicInteger()
    private val eventBus = EventBus.builder().build()
    private val path = "/t-${UUID.randomUUID()}/"

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val connection = if (request.path == path) connectionCount.incrementAndGet() else 0
                return when (connection) {
                    0 -> MockResponse().setResponseCode(HTTP_NOT_FOUND)
                    FAILING_CONNECTION -> MockResponse().setResponseCode(HTTP_UNAVAILABLE)
                    else -> MockResponse().withWebSocketUpgrade(RecordingServerListener("c$connection"))
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun messageQueuedWhileDownSurvivesFailedReconnectOfResumableSession() {
        val client = OkHttpClient()
        val instance = WebSocketInstance(
            User(
                userId = "alice",
                baseUrl = "https://cloud.example.com",
                capabilities = CapabilitiesDto().apply {
                    spreedCapability = SpreedCapabilityDto(listOf("signaling-v3"), null, "")
                }
            ),
            server.url(path).toString(),
            "ticket",
            client,
            eventBus,
            WebSocketConnectionHelper(client)
        )
        waitUntil("first session connected") { instance.isConnected }

        // onFailure waits a second before it reconnects; the message is queued during that time
        val socket = currentSocketOf(instance)
        thread { instance.onFailure(socket, IOException("connection lost"), null as Response?) }
        waitUntil("connection loss noticed") { !instance.isConnected }
        instance.signalingMessageSender.send(
            NCSignalingMessageDto().apply {
                to = "remote-session"
                roomType = "video"
                type = "requestoffer"
            }
        )

        waitUntil("requestoffer delivered") { serverLog.any { it.contains("requestoffer") } }
        assertTrue("a reconnect attempt must have failed: $serverLog", connectionCount.get() > FAILING_CONNECTION)
        assertTrue(
            "the message must arrive over a resumed session: $serverLog",
            serverLog.any { it.contains("hello") && it.contains("resumeid") }
        )
    }

    private fun currentSocketOf(instance: WebSocketInstance): WebSocket {
        val field = WebSocketInstance::class.java.getDeclaredField("internalWebSocket")
        field.isAccessible = true
        return field.get(instance) as WebSocket
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what, server log: $serverLog" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private inner class RecordingServerListener(private val name: String) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when {
                text.contains("\"type\":\"hello\"") -> {
                    serverLog.add("$name:hello $text")
                    webSocket.send(
                        "{\"type\":\"hello\",\"hello\":{\"resumeid\":\"resume-$name\",\"sessionid\":\"session-$name\"}}"
                    )
                }

                text.contains("requestoffer") -> serverLog.add("$name:requestoffer")
            }
        }
    }

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNAVAILABLE = 503
        private const val FAILING_CONNECTION = 2
        private const val TIMEOUT_MILLIS = 15_000L
        private const val POLL_MILLIS = 20L
    }
}
