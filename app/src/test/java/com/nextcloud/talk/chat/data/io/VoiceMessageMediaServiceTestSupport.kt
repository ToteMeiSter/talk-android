/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.data.io

import androidx.media3.datasource.cache.CacheDataSource
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplicationComponent
import com.nextcloud.talk.chat.audio.ChatAudioDataSources
import com.nextcloud.talk.chat.audio.ChatAudioStore
import com.nextcloud.talk.chat.audio.VoiceMessageWaveformLoader
import com.nextcloud.talk.data.network.NetworkMonitor
import com.nextcloud.talk.utils.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The service takes its dependencies from the application component when it is created. These helpers give it
 * mocks instead, so that a Robolectric test can create the service without Dagger.
 */
internal object VoiceMessageMediaServiceTestSupport {

    fun install() {
        val dataSources = mock<ChatAudioDataSources> {
            on { cacheDataSourceFactory } doReturn mock<CacheDataSource.Factory>()
        }
        val store = mock<ChatAudioStore> { on { accountRemovals } doReturn MutableSharedFlow() }
        val component = mock<NextcloudTalkApplicationComponent> {
            on { inject(any<VoiceMessageMediaService>()) } doAnswer {
                it.getArgument<VoiceMessageMediaService>(0).apply {
                    this.dataSources = dataSources
                    this.store = store
                    waveformLoader = mock<VoiceMessageWaveformLoader>()
                    networkMonitor = mock<NetworkMonitor>()
                    appPreferences = mock<AppPreferences>()
                }
                Unit
            }
        }
        setSharedApplication(mock<NextcloudTalkApplication> { on { componentApplication } doReturn component })
    }

    fun uninstall() = setSharedApplication(null)

    // NextcloudTalkApplication.sharedApplication's setter is `protected` (only meant to be set
    // from onCreate()/onTerminate()); reflection is the only way in from a test.
    private fun setSharedApplication(application: NextcloudTalkApplication?) {
        NextcloudTalkApplication.Companion::class.java
            .getDeclaredMethod("setSharedApplication", NextcloudTalkApplication::class.java)
            .apply { isAccessible = true }
            .invoke(NextcloudTalkApplication.Companion, application)
    }
}
