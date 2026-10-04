/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Krainov Gleb <krajnov.g@kontentplus.ru>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordHintPopupPlacementTest {

    private fun place(anchorLeft: Int, anchorTop: Int = 1000, windowWidth: Int = 1000) =
        RecordHintPopup.hintPlacement(
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorWidth = 100,
            windowWidth = windowWidth,
            hintWidth = 300,
            hintHeight = 80,
            margin = 20,
            gap = 10,
            arrowWidth = 40
        )

    @Test
    fun hintEndsAboveTheAnchorWithTheGap() {
        val placement = place(anchorLeft = 450)
        assertEquals(1000 - 80 - 10, placement.y)
        // the whole hint, from y to y + height, stays above the top edge of the anchor
        assertTrue(placement.y + 80 <= 1000)
    }

    @Test
    fun hintIsCentredOnTheAnchor() {
        val placement = place(anchorLeft = 450)
        assertEquals(500 - 150, placement.x)
        // the arrow is in the middle of the hint
        assertEquals(150 - 20, placement.arrowLeftMargin)
    }

    @Test
    fun hintIsKeptInsideTheWindowAtTheRightEdge() {
        val placement = place(anchorLeft = 900)
        assertEquals(1000 - 20 - 300, placement.x)
        // the arrow still points at the anchor centre (950)
        assertEquals(950, placement.x + placement.arrowLeftMargin + 20)
    }

    @Test
    fun hintIsKeptInsideTheWindowAtTheLeftEdge() {
        val placement = place(anchorLeft = 0)
        assertEquals(20, placement.x)
        assertEquals(50 - 20 - 20, placement.arrowLeftMargin)
    }

    @Test
    fun anchorInANarrowWindowIsStillPointedAt() {
        // the chat in a pane of 600 px: only the window width matters, not the width of the screen
        val placement = place(anchorLeft = 500, windowWidth = 600)
        assertEquals(600 - 20 - 300, placement.x)
        assertEquals(550, placement.x + placement.arrowLeftMargin + 20)
    }
}
