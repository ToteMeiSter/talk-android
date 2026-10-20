/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import androidx.emoji2.text.EmojiCompat

/**
 * EmojiCompat throws [IllegalStateException] until its font is loaded, and a push can start the process
 * moments before that. A notification must not depend on it: the original text is used then.
 */
object SafeEmoji {
    fun process(text: CharSequence?): CharSequence? =
        text?.let {
            processIfLoaded(
                it,
                loadState = { if (EmojiCompat.isConfigured()) EmojiCompat.get().loadState else NOT_CONFIGURED },
                process = { source -> EmojiCompat.get().process(source) }
            )
        }

    fun processIfLoaded(
        text: CharSequence,
        loadState: () -> Int,
        process: (CharSequence) -> CharSequence?
    ): CharSequence =
        try {
            if (loadState() == EmojiCompat.LOAD_STATE_SUCCEEDED) process(text) ?: text else text
        } catch (e: IllegalStateException) {
            text
        }

    private const val NOT_CONFIGURED = -1
}
