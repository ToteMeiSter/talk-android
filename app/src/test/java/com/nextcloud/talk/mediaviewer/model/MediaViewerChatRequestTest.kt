/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.mediaviewer.model

import com.nextcloud.talk.chat.ui.MessageActionsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaViewerChatRequestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun state(allowed: Boolean) =
        MessageActionsState(
            showEmojiBar = false,
            selfReactions = emptySet(),
            showEditInfo = false,
            lastEditedBy = "",
            lastEditedAt = "",
            showReply = allowed,
            showReplyPrivately = false,
            showOpenThread = false,
            showForward = false,
            showForwardFile = allowed,
            canSendToConversation = allowed,
            showEdit = false,
            showCopy = false,
            showCopyMessageLink = false,
            showMarkAsUnread = false,
            showRemind = false,
            showPin = false,
            isPinned = false,
            showTranslate = false,
            showShareToNote = false,
            showShare = false,
            showSave = false,
            showOpenInFiles = false,
            showDelete = allowed
        )

    // request

    @Test
    fun request_parsesWhatTheViewerSends() {
        assertEquals(
            MediaViewerChatRequest(MediaViewerChatAction.REPLY, 12L),
            MediaViewerChatRequest.parse("REPLY", 12L, null, null)
        )
        assertEquals(
            MediaViewerChatRequest(MediaViewerChatAction.DRAW, 12L, localPath = "/c/a.jpg"),
            MediaViewerChatRequest.parse("DRAW", 12L, "/c/a.jpg", "Talk/a.jpg")?.copy(remotePath = null)
        )
    }

    @Test
    fun request_rejectsIncompleteOrUnknown() {
        assertNull(MediaViewerChatRequest.parse(null, 12L, null, null))
        assertNull(MediaViewerChatRequest.parse("EXPLODE", 12L, null, null))
        assertNull(MediaViewerChatRequest.parse("REPLY", 0L, null, null))
        assertNull(MediaViewerChatRequest.parse("DRAW", 12L, null, "Talk/a.jpg"))
        assertNull(MediaViewerChatRequest.parse("FORWARD", 12L, "/c/a.jpg", ""))
    }

    @Test
    fun chatRecheck_usesTheRuleOfEachAction() {
        val denied = state(false)
        MediaViewerChatAction.entries.forEach {
            assertFalse(it.name, isMediaActionAllowed(it, denied))
            assertTrue(it.name, isMediaActionAllowed(it, state(true)))
        }
    }

    // paths

    @Test
    fun remoteSharePath_hasExactlyOneLeadingSlash() {
        assertEquals("/Talk/a.jpg", remoteSharePath("Talk/a.jpg"))
        assertEquals("/Talk/a.jpg", remoteSharePath("/Talk/a.jpg"))
        assertEquals("/Talk/a.jpg", remoteSharePath("//Talk/a.jpg"))
    }

    @Test
    fun insideDirectory_acceptsNestedFiles() {
        val dir = temp.newFolder("shared_attachments")
        val nested = File(File(dir, "42").also { it.mkdirs() }, "a.jpg").also { it.writeText("x") }
        assertTrue(isInsideDirectory(dir, nested))
    }

    @Test
    fun insideDirectory_rejectsTraversalAndOutsideFiles() {
        val dir = temp.newFolder("shared_attachments")
        val outside = temp.newFile("secret.txt")
        assertFalse(isInsideDirectory(dir, outside))
        assertFalse(isInsideDirectory(dir, File(dir, "../secret.txt")))
        assertFalse(isInsideDirectory(dir, dir))
    }
}
