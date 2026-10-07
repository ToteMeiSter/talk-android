/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

/**
 * Log priorities in ascending order. [NONE] must stay last: it means "log nothing" when used as a minimum level.
 * [VERBOSE] and [FATAL] only come from the captured logcat (the logger itself writes D/I/W/E).
 */
enum class Level(val tag: String) {
    VERBOSE("V"),
    DEBUG("D"),
    INFO("I"),
    WARNING("W"),
    ERROR("E"),
    FATAL("F"),
    NONE("-");

    companion object {
        // logcat prints "A" (assert) for Log.wtf; it is shown as a fatal entry.
        private const val ASSERT_TAG = "A"

        fun fromTag(tag: String): Level? = if (tag == ASSERT_TAG) FATAL else entries.find { it.tag == tag }
    }
}
