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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for replacing the signaling session ([WebSocketInstance.restartWebSocketWithNewSession]): the old session
 * must be closed at the server with a "bye" before the new one says hello, and callbacks of the replaced socket
 * must not touch the state of the current one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WebSocketInstanceSessionReplaceTest {

    private val server = MockWebServer()
    private val serverLog = CopyOnWriteArrayList<String>()
    private val connectionCount = AtomicInteger()
    private var instance: WebSocketInstance? = null

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val connection = connectionCount.incrementAndGet()
                return MockResponse().withWebSocketUpgrade(RecordingServerListener("c$connection"))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun byeOfOldSessionIsSentBeforeHelloOfNewSession() {
        val instance = createConnectedInstance()

        instance.restartWebSocketWithNewSession()

        waitUntil("hello of the new session") { serverLog.any { it.startsWith("c2:hello") } }
        val byeIndex = serverLog.indexOf("c1:bye")
        val newHelloIndex = serverLog.indexOfFirst { it.startsWith("c2:hello") }
        assertTrue("bye must reach the server over the old connection: $serverLog", byeIndex >= 0)
        assertTrue("bye must come before the new hello: $serverLog", byeIndex < newHelloIndex)
        assertFalse(
            "new session must not try to resume the old one: $serverLog",
            serverLog[newHelloIndex].contains("resumeid")
        )
    }

    @Test
    fun oldConnectionIsClosedGracefullyAndNewSessionStaysConnected() {
        val instance = createConnectedInstance()

        instance.restartWebSocketWithNewSession()

        waitUntil("old connection closed") { serverLog.contains("c1:closed") }
        waitUntil("new session connected") { serverLog.contains("c2:hello-answered") }
        waitUntil("new session known to the client") { instance.isConnected }
        // the late callbacks of the old socket have been delivered by now
        Thread.sleep(SETTLE_MILLIS)
        assertTrue("closing the old socket must not mark the new one as closed", instance.isConnected)
        assertEquals(2, connectionCount.get())
    }

    @Test
    fun onClosedOfReplacedSocketKeepsCurrentConnection() {
        val instance = createConnectedInstance()
        val replacedSocket = mock<WebSocket>()

        instance.onClosed(replacedSocket, NORMAL_CLOSURE, "")

        assertTrue(instance.isConnected)
    }

    @Test
    fun onFailureOfReplacedSocketDoesNotRestartCurrentConnection() {
        val instance = createConnectedInstance()
        val replacedSocket = mock<WebSocket>()

        instance.onFailure(replacedSocket, IOException("test"), null as Response?)

        assertTrue(instance.isConnected)
        Thread.sleep(SETTLE_MILLIS)
        assertEquals("no new connection must be opened", 1, connectionCount.get())
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
            server.url("/").toString(),
            "ticket",
            client,
            EventBus.builder().build(),
            WebSocketConnectionHelper(client)
        )
        instance = created
        waitUntil("first session connected") { created.isConnected }
        return created
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
                text.contains("\"type\":\"bye\"") -> serverLog.add("$name:bye")

                text.contains("\"type\":\"hello\"") -> {
                    serverLog.add("$name:hello $text")
                    webSocket.send(
                        "{\"type\":\"hello\",\"hello\":{\"resumeid\":\"resume-$name\",\"sessionid\":\"session-$name\"}}"
                    )
                    serverLog.add("$name:hello-answered")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            serverLog.add("$name:closed")
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val TIMEOUT_MILLIS = 10_000L
        private const val POLL_MILLIS = 20L
        private const val SETTLE_MILLIS = 500L
    }
}
