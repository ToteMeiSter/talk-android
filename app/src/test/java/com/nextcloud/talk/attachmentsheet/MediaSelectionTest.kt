/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Krainov Gleb <krajnov.g@kontentplus.ru>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentsheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSelectionTest {

    @Test
    fun toggleSelectsInOrderAndNumbersFromOne() {
        val selection = MediaSelection().toggle(7).toggle(3).toggle(9)
        assertEquals(listOf(7L, 3L, 9L), selection.ids)
        assertEquals(1, selection.positionOf(7))
        assertEquals(3, selection.positionOf(9))
        assertEquals(3, selection.count)
    }

    @Test
    fun toggleTwiceDeselectsAndRenumbers() {
        val selection = MediaSelection().toggle(1).toggle(2).toggle(3).toggle(1)
        assertEquals(listOf(2L, 3L), selection.ids)
        assertNull(selection.positionOf(1))
        assertEquals(1, selection.positionOf(2))
    }

    @Test
    fun selectingBeyondLimitIsIgnored() {
        val selection = MediaSelection(limit = 2).toggle(1).toggle(2).toggle(3)
        assertEquals(listOf(1L, 2L), selection.ids)
        assertTrue(selection.isFull)
    }

    @Test
    fun deselectingAtLimitFreesASlot() {
        val selection = MediaSelection(limit = 2).toggle(1).toggle(2).toggle(1).toggle(3)
        assertEquals(listOf(2L, 3L), selection.ids)
    }

    @Test
    fun emptySelectionIsEmpty() {
        assertTrue(MediaSelection().isEmpty)
        assertFalse(MediaSelection().toggle(1).isEmpty)
    }

    @Test
    fun retainAvailableDropsMissingIdsAndKeepsOrder() {
        val selection = MediaSelection().toggle(5).toggle(6).toggle(7).retainAvailable(listOf(7L, 5L))
        assertEquals(listOf(5L, 7L), selection.ids)
    }
}
