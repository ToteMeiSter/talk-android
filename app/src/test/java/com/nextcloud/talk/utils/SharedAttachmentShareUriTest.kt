/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nextcloud.talk.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The uris Talk hands to the system share sheet for a chat attachment come from its own FileProvider. When the user
 * picks Talk as the target, the conversation list gets such a uri back and must accept it, with the same rule it
 * applies in addFileToShareIfTrusted: another authority, or an own uri inside the shared attachments directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SharedAttachmentShareUriTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun isAccepted(uri: Uri): Boolean =
        ContentResolver.SCHEME_CONTENT == uri.scheme &&
            (
                uri.authority != BuildConfig.APPLICATION_ID ||
                    FileUtils.isSharedAttachmentProviderPath(context.cacheDir, uri.pathSegments)
                )

    /**
     * The uri FileProvider builds for [file] with the paths of res/xml/file_provider_paths.xml: the name of the
     * matching root, then the path inside it (SimplePathStrategy.getUriForFile). The provider itself is not
     * registered in the Robolectric package manager, so the format is rebuilt here.
     */
    private fun providerUri(file: File): Uri {
        val (rootName, root) = when {
            file.startsWith(File(context.cacheDir, "shared_attachments")) ->
                "shared_attachments" to File(context.cacheDir, "shared_attachments")
            file.startsWith(File(context.cacheDir, "photos")) -> "photos" to File(context.cacheDir, "photos")
            else -> "logs" to File(context.filesDir, "logs")
        }
        val path = file.relativeTo(root).path
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.APPLICATION_ID)
            .encodedPath(Uri.encode(rootName) + "/" + Uri.encode(path, "/"))
            .build()
    }

    @Test
    fun uriOfChatShareIsAccepted() {
        // ChatActivity.share(): file named after the attachment, directly in the shared attachments directory
        val file = FileUtils.resolveSharedAttachmentFile(context.cacheDir, "Счёт №5 (1).pdf")!!
        file.writeText("x")

        val uri = providerUri(file)

        assertEquals(BuildConfig.APPLICATION_ID, uri.authority)
        assertTrue(isAccepted(uri))
    }

    @Test
    fun uriOfMediaViewerFileWithFileIdIsAccepted() {
        // MediaViewerActivity: file in a per-fileId subdirectory
        val file = FileUtils.resolveSharedAttachmentFile(context.cacheDir, "4711", "photo.jpg")!!
        file.writeText("x")

        assertTrue(isAccepted(providerUri(file)))
    }

    @Test
    fun otherOwnProviderDirectoriesStayRejected() {
        val logs = File(context.filesDir, "logs").apply { mkdirs() }.resolve("log.json").apply { writeText("x") }
        val photos = File(context.cacheDir, "photos").apply { mkdirs() }.resolve("p.jpg").apply { writeText("x") }

        assertFalse(isAccepted(providerUri(logs)))
        assertFalse(isAccepted(providerUri(photos)))
    }

    @Test
    fun uriOfAnotherAppIsAccepted() {
        assertTrue(isAccepted(Uri.parse("content://media/external/images/media/42")))
    }

    @Test
    fun fileSchemeStaysRejected() {
        assertFalse(isAccepted(Uri.parse("file:///data/data/x/shared_attachments/a.jpg")))
    }
}
