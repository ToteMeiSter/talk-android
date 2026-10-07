/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests for the signaling WebSocket client configuration ([WebSocketInstance.createSignalingHttpClient]).
 *
 * Without a ping interval OkHttp sends no protocol-level pings on WebSockets and uses an infinite read timeout,
 * so a half-open connection (e.g. after a WiFi to cellular switch without a TCP reset) is never detected: the
 * reconnect path via "onFailure" never runs and the call goes silently mute/deaf. These tests pin the ping
 * configuration that makes dead connections fail fast.
 */
class WebSocketInstanceSignalingClientTest {

    @Test
    fun signalingClientHasPingIntervalConfigured() {
        val signalingClient = WebSocketInstance.createSignalingHttpClient(OkHttpClient())

        // hardcoded on purpose: fails if the ping interval in WebSocketInstance changes or is removed
        assertEquals(
            "signaling WebSocket client must send pings to detect half-open connections",
            30_000,
            signalingClient.pingIntervalMillis
        )
    }

    @Test
    fun signalingClientIsDerivedFromBaseClientWithoutMutatingIt() {
        val baseClient = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        val signalingClient = WebSocketInstance.createSignalingHttpClient(baseClient)

        assertNotSame("a dedicated client instance must be used", baseClient, signalingClient)
        assertEquals(
            "base client (shared with regular HTTP calls) must stay unchanged",
            0,
            baseClient.pingIntervalMillis
        )
        assertEquals(
            "base client (shared with regular HTTP calls) must keep its timeouts",
            45_000,
            baseClient.connectTimeoutMillis
        )
        assertEquals(45_000, baseClient.readTimeoutMillis)
    }

    @Test
    fun signalingClientGivesUpAConnectionAttemptWithinTheResumeWindowOfTheServer() {
        val baseClient = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        val signalingClient = WebSocketInstance.createSignalingHttpClient(baseClient)

        // The server keeps a session 30 seconds for a resume: several attempts must fit in, not one hanging attempt.
        assertTrue(
            "connect timeout ${signalingClient.connectTimeoutMillis} ms must leave room for retries within 30 s",
            signalingClient.connectTimeoutMillis in 1..10_000
        )
        assertTrue(
            "read timeout ${signalingClient.readTimeoutMillis} ms (the answer of the upgrade) must leave room too",
            signalingClient.readTimeoutMillis in 1..10_000
        )
    }
}
