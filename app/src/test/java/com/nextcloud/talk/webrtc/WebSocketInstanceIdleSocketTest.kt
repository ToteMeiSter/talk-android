/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The connect and read timeouts of the signaling client (10 s, inside the resume window of the server) must only
 * apply to the connection attempt. An open socket without traffic is normal (the server pings every 54 s) and must
 * live on; a read timeout which hit it would close every connection 10 s after the last frame.
 */
class WebSocketInstanceIdleSocketTest {

    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<String>()
    private val failure = AtomicReference<Throwable?>()
    private val opened = CountDownLatch(1)
    private val received = CountDownLatch(1)

    @Before
    fun setUp() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("hello")
                }
            })
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun socketWithoutTrafficSurvivesTheReadTimeout() {
        val client = WebSocketInstance.createSignalingHttpClient(OkHttpClient())
        val socket = client.newWebSocket(
            Request.Builder().url(server.url("/")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    events.add("message:$text")
                    received.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.set(t)
                    events.add("failure:${t.javaClass.simpleName}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.add("closed:$code")
                }
            }
        )

        assertTrue("socket not opened", opened.await(OPEN_WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue("no message", received.await(OPEN_WAIT_SECONDS, TimeUnit.SECONDS))

        // longer than the read timeout (10 s) of the client
        Thread.sleep(IDLE_MILLIS)

        assertNull("the idle socket failed: ${failure.get()}", failure.get())
        assertEquals(listOf("message:hello"), events.toList())
        socket.cancel()
    }

    companion object {
        private const val OPEN_WAIT_SECONDS = 5L
        private const val IDLE_MILLIS = 12_500L
    }
}
