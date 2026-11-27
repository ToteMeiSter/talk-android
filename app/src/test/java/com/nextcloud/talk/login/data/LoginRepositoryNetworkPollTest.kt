/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.login.data

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.nextcloud.talk.account.data.LoginRepository
import com.nextcloud.talk.account.data.io.LocalLoginDataSource
import com.nextcloud.talk.account.data.model.LoginResponse
import com.nextcloud.talk.account.data.network.NetworkLoginDataSource
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ServerSocket

/**
 * Wires the poll loop of [LoginRepository] to a real [NetworkLoginDataSource] and a real connection: the mock-based
 * tests stub the datasource, so they cannot catch a mismatch between the loop and the datasource semantics
 * (an IOException must be retried, a 200 answer without login data must end the poll).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LoginRepositoryNetworkPollTest {

    @get:Rule
    val rule = InstantTaskExecutorRule()

    @Mock
    lateinit var localLoginDataSource: LocalLoginDataSource

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
    }

    private companion object {
        /** How long the credentials are polled for. Mirrors LoginRepository.POLL_TIMEOUT. */
        const val POLL_TIMEOUT = 20 * 60 * 1000L
    }

    @Test
    fun `pollLogin keeps polling through real connection failures and ends on a real unusable 200 answer`() =
        runTest {
            val datasource = NetworkLoginDataSource(OkHttpClient())

            // A closed port: every request fails, the poll must keep retrying over the refused connection
            // until its own timeout instead of ending on the first failure.
            val deadPort: Int = ServerSocket(0).use { socket -> socket.localPort }
            val repository = LoginRepository(datasource, localLoginDataSource, StandardTestDispatcher(testScheduler))
            val failedResponse =
                LoginResponse("token123", "http://127.0.0.1:$deadPort/poll", "https://server.com/login")

            val outcome = runCatching { datasource.performLoginFlowV2(failedResponse) }
            assertTrue(
                "the real datasource must throw on a connection failure, got: $outcome",
                outcome.exceptionOrNull() is IOException
            )
            assertNull(repository.pollLogin(failedResponse))
            assertTrue(currentTime >= POLL_TIMEOUT)
            val timeAfterFailures = currentTime

            // A real server answering 200 without login data: the poll must end with null right away.
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().code(200).body("").build())
                val liveRepository =
                    LoginRepository(datasource, localLoginDataSource, StandardTestDispatcher(testScheduler))
                val usableResponse =
                    LoginResponse("token123", server.url("/poll").toString(), "https://server.com/login")

                val result = liveRepository.pollLogin(usableResponse)

                assertNull(result)
                // No retry pauses: the unusable 200 answer ended the poll immediately.
                assertEquals(timeAfterFailures, currentTime)
            }
        }
}
