/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.models.json.signaling.settings.IceServerDto
import com.nextcloud.talk.models.json.signaling.settings.SignalingSettingsDto
import com.nextcloud.talk.utils.ApiUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IceServersFactoryTest {

    private fun settings(turnUser: String = "user"): SignalingSettingsDto =
        SignalingSettingsDto(
            stunServers = listOf(IceServerDto(url = "stun:old.example:3478", urls = listOf("stun:one.example:3478"))),
            turnServers = listOf(
                IceServerDto(
                    urls = listOf("turn:turn.example:3478?transport=udp", "turns:turn.example:443?transport=tcp"),
                    username = turnUser,
                    credential = "secret"
                )
            )
        )

    @Test
    fun apiV3UsesUrlsOfStunServers() {
        val servers = IceServersFactory.fromSettings(settings(), ApiUtils.API_V3)

        assertEquals(
            listOf(
                listOf("stun:one.example:3478"),
                listOf("turn:turn.example:3478?transport=udp"),
                listOf("turns:turn.example:443?transport=tcp")
            ),
            servers.map { it.urls }
        )
    }

    @Test
    fun olderApiUsesSingleUrlOfStunServers() {
        val servers = IceServersFactory.fromSettings(settings(), 2)

        assertEquals(listOf("stun:old.example:3478"), servers.first().urls)
    }

    @Test
    fun turnServersCarryCredentialsAndStunServersDoNot() {
        val servers = IceServersFactory.fromSettings(settings(), ApiUtils.API_V3)

        assertEquals("", servers[0].username)
        assertEquals("user", servers[1].username)
        assertEquals("secret", servers[2].password)
    }

    @Test
    fun serversWithoutUrlsAreSkipped() {
        val servers = IceServersFactory.fromSettings(
            SignalingSettingsDto(
                stunServers = listOf(IceServerDto(), IceServerDto(urls = null, url = null)),
                turnServers = listOf(IceServerDto(username = "user", credential = "secret"))
            ),
            ApiUtils.API_V3
        )

        assertTrue(servers.isEmpty())
    }

    @Test
    fun missingServerListsGiveEmptyList() {
        assertTrue(IceServersFactory.fromSettings(SignalingSettingsDto(), ApiUtils.API_V3).isEmpty())
    }

    @Test
    fun eachCallBuildsFreshListSoRejoinReplacesTheOldCredentials() {
        val first = IceServersFactory.fromSettings(settings("old"), ApiUtils.API_V3)
        val second = IceServersFactory.fromSettings(settings("new"), ApiUtils.API_V3)

        assertEquals(3, second.size)
        assertEquals("old", first[1].username)
        assertEquals("new", second[1].username)
        assertNull(second.firstOrNull { it.username == "old" })
    }
}
