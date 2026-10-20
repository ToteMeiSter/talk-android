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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A signaling server that cannot be reached must not be tried once per second: the pause before the socket is
 * opened again grows with every failure in a row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WebSocketInstanceReconnectBackoffTest {

    private val server = MockWebServer()
    private val attempts = AtomicInteger()
    private val path = "/t-${UUID.randomUUID()}/"

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == path) {
                    attempts.incrementAndGet()
                }
                return MockResponse(code = HTTP_UNAVAILABLE)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun failingConnectionIsRetriedWithGrowingPauses() {
        val client = OkHttpClient()
        val user = User(
            userId = "alice",
            baseUrl = "https://cloud.example.com",
            capabilities = CapabilitiesDto().apply {
                spreedCapability = SpreedCapabilityDto(listOf("signaling-v3"), null, "")
            }
        )
        WebSocketInstance(
            user,
            server.url(path).toString(),
            "ticket",
            client,
            EventBus.builder().build(),
            WebSocketConnectionHelper(client)
        )

        Thread.sleep(OBSERVE_MILLIS)

        // The pauses are 1 s, 2 s, 4 s: the attempts start at 0 s, 1 s and 3 s, the next one at 7 s. A fixed pause of
        // 1 s made 7 attempts in the same time.
        val made = attempts.get()
        assertTrue("attempts=$made", made in MIN_ATTEMPTS..MAX_ATTEMPTS)
    }

    companion object {
        private const val HTTP_UNAVAILABLE = 503
        private const val OBSERVE_MILLIS = 6_500L
        private const val MIN_ATTEMPTS = 2
        private const val MAX_ATTEMPTS = 4
    }
}
