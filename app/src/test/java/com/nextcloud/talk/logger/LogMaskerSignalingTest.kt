/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMaskerSignalingTest {
    private fun assertMasked(input: String, secret: String) {
        val masked = LogMasker.mask(input)
        assertFalse("secret still in: $masked", masked.contains(secret))
        assertTrue("no mask in: $masked", masked.contains(LogMasker.MASK))
        assertEquals("masking must be idempotent", masked, LogMasker.mask(masked))
    }

    @Test
    fun `signaling hello and resume data are masked`() {
        assertMasked(
            """Receiving : RealWebSocket@1 {"type":"hello","hello":{"resumeid":"Abc123XyzResume","sessionid":"s1"}}""",
            "Abc123XyzResume"
        )
        assertMasked(
            """{"type":"hello","hello":{"version":"1.0","auth":{"params":{"userid":"u","ticket":"1:tick3tvalue"}}}}""",
            "tick3tvalue"
        )
        assertMasked("WebSocket 5 resumeID Abc123XyzResume expired", "Abc123XyzResume")
    }

    @Test
    fun `turn server credentials are masked`() {
        val json = """{"turnservers":[{"urls":["turn:t.example:443?transport=tcp"],""" +
            """"username":"1760000000:alice","credential":"c3RybmdDcmVk"}]}"""
        val masked = LogMasker.mask(json)
        assertFalse(masked.contains("1760000000:alice"))
        assertFalse(masked.contains("c3RybmdDcmVk"))
        assertTrue(masked.contains("turn:t.example:443?transport=tcp"))
        assertMasked(
            "IceServerDto(urls=[turn:t.example], username=1760000000:alice, credential=c3RybmdDcmVk)",
            "c3RybmdDcmVk"
        )
        assertFalse(
            LogMasker.mask(
                "IceServerDto(urls=[turn:t], username=1760:alice, credential=zzzzzzzz)"
            ).contains("1760:alice")
        )
    }

    @Test
    fun `turn credentials inside an escaped json string are masked`() {
        val line = """{"message":"{\"username\":\"1760:alice\",\"credential\":\"s3cr3tc4ed\",\"urls\":\"turn:x\"}"}"""
        val masked = LogMasker.mask(line)
        assertFalse(masked, masked.contains("s3cr3tc4ed"))
        assertFalse(masked, masked.contains("1760:alice"))
    }

    @Test
    fun `username outside of turn data is kept`() {
        val line = """D Login: {"username":"alice","displayName":"Alice"}"""
        assertEquals(line, LogMasker.mask(line))
    }

    @Test
    fun `sdp ice password is masked`() {
        assertMasked(
            """{"sdp":"v=0\r\na=ice-ufrag:abcd\r\na=ice-pwd:Zk3pQ9sd8fgh2jkl3mnop4qr\r\na=fingerprint:sha-256 AA"}""",
            "Zk3pQ9sd8fgh2jkl3mnop4qr"
        )
    }

    @Test
    fun `private key marker is masked`() {
        assertMasked("-----BEGIN RSA PRIVATE KEY----- MIIEowIBAAKCAQEA", "MIIEowIBAAKCAQEA")
    }

    @Test
    fun `room tokens and diagnostics are kept`() {
        val lines = listOf(
            "D CallActivity: onNewIntent: newRoomToken=abc123xy roomToken=abc123xy newUserId=2",
            "D WebSocketInstance: Room abc123xy was joined already",
            """{"token":"abc123xy","type":3,"name":"Test"}""",
            "I IceDiag: Created video connection over s1 iceServers=[turn:t.example:443?transport=tcp(auth)]",
            "W Something: no user or credentials found for user id 5"
        )
        lines.forEach { assertEquals(it, LogMasker.mask(it)) }
    }

    @Test
    fun `every line of a multi line text is masked`() {
        val masked = LogMasker.mask("first\npassword=aaaa1111\nthird token=bbbb2222\n")
        assertEquals("first\npassword=***\nthird token=***\n", masked)
    }
}
