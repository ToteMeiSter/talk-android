/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.Application
import androidx.emoji2.text.EmojiCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SafeEmojiTest {

    @Test
    fun fontNotLoadedYetGivesTheOriginalText() {
        var processed = false
        val result = SafeEmoji.processIfLoaded(
            "Hi",
            loadState = { EmojiCompat.LOAD_STATE_LOADING },
            process = {
                processed = true
                "changed"
            }
        )

        assertEquals("Hi", result)
        assertEquals(false, processed)
    }

    @Test
    fun failedFontGivesTheOriginalText() {
        val result = SafeEmoji.processIfLoaded("Hi", { EmojiCompat.LOAD_STATE_FAILED }, { "changed" })

        assertEquals("Hi", result)
    }

    @Test
    fun loadedFontProcessesTheText() {
        val result = SafeEmoji.processIfLoaded("Hi", { EmojiCompat.LOAD_STATE_SUCCEEDED }, { "changed" })

        assertEquals("changed", result)
    }

    @Test
    fun exceptionOfTheLibraryGivesTheOriginalText() {
        val result = SafeEmoji.processIfLoaded(
            "Hi",
            loadState = { EmojiCompat.LOAD_STATE_SUCCEEDED },
            process = { throw IllegalStateException("Not initialized yet") }
        )

        assertEquals("Hi", result)
    }

    @Test
    fun emojiCompatNotConfiguredGivesTheOriginalText() {
        assertEquals("Hi", SafeEmoji.process("Hi"))
    }

    @Test
    fun nullStaysNull() {
        assertNull(SafeEmoji.process(null))
    }
}
