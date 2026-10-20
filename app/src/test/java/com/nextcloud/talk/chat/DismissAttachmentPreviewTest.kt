/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat

import android.app.Application
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.nextcloud.talk.attachmentpreview.FileAttachmentPreviewFragment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The files of the attachment preview were picked for the room which is left by a switch in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DismissAttachmentPreviewTest {

    private fun activity(): FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    @Test
    fun previewOfTheLeftRoomIsDismissed() {
        val activity = activity()
        val fragmentManager = activity.supportFragmentManager
        val preview = DialogFragment()
        preview.show(fragmentManager, FileAttachmentPreviewFragment.TAG)
        fragmentManager.executePendingTransactions()
        assertTrue(preview.isAdded)

        dismissAttachmentPreview(fragmentManager)
        fragmentManager.executePendingTransactions()

        assertFalse(preview.isAdded)
        assertNull(fragmentManager.findFragmentByTag(FileAttachmentPreviewFragment.TAG))
    }

    @Test
    fun withoutPreviewNothingHappens() {
        val activity = activity()

        dismissAttachmentPreview(activity.supportFragmentManager)

        assertNull(activity.supportFragmentManager.findFragmentByTag(FileAttachmentPreviewFragment.TAG))
    }
}
