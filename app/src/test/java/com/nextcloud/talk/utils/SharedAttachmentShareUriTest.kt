/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
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
 * A chat attachment shared from Talk to Talk arrives in the conversation list as a uri of the own FileProvider, and
 * any app may send that activity a uri of the same provider. [FileUtils.isTrustedShareUri] is the rule the list
 * applies: foreign uris pass, own uris only inside the shared attachments directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SharedAttachmentShareUriTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val own = BuildConfig.APPLICATION_ID

    private fun isTrusted(uri: Uri): Boolean = FileUtils.isTrustedShareUri(uri, own, context.cacheDir)

    private fun isTrusted(uri: String): Boolean = isTrusted(Uri.parse(uri))

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
            .authority(own)
            .encodedPath(Uri.encode(rootName) + "/" + Uri.encode(path, "/"))
            .build()
    }

    @Test
    fun uriOfChatShareIsAccepted() {
        // ChatActivity.share(): file named after the attachment, directly in the shared attachments directory
        val file = FileUtils.resolveSharedAttachmentFile(context.cacheDir, "Счёт №5 (1).pdf")!!
        file.writeText("x")

        val uri = providerUri(file)

        assertEquals(own, uri.authority)
        assertTrue(isTrusted(uri))
    }

    @Test
    fun uriOfMediaViewerFileWithFileIdIsAccepted() {
        // MediaViewerActivity: file in a per-fileId subdirectory
        val file = FileUtils.resolveSharedAttachmentFile(context.cacheDir, "4711", "photo.jpg")!!
        file.writeText("x")

        assertTrue(isTrusted(providerUri(file)))
    }

    @Test
    fun otherOwnProviderDirectoriesStayRejected() {
        val logs = File(context.filesDir, "logs").apply { mkdirs() }.resolve("log.json").apply { writeText("x") }
        val photos = File(context.cacheDir, "photos").apply { mkdirs() }.resolve("p.jpg").apply { writeText("x") }

        assertFalse(isTrusted(providerUri(logs)))
        assertFalse(isTrusted(providerUri(photos)))
    }

    @Test
    fun foreignUrisAreAccepted() {
        assertTrue(isTrusted("content://media/external/images/media/42"))
        // work profile and multi user uris of the system carry a user id of their own
        assertTrue(isTrusted("content://10@media/external/images/media/42"))
    }

    @Test
    fun fileSchemeAndMissingAuthorityStayRejected() {
        assertFalse(isTrusted("file:///data/data/x/shared_attachments/a.jpg"))
        assertFalse(isTrusted("content:///shared_attachments/a.jpg"))
    }

    @Test
    fun userIdPrefixDoesNotHideTheOwnAuthority() {
        assertFalse(isTrusted("content://0@$own/logs/log.json"))
        assertFalse(isTrusted("content://10@$own/photos/p.jpg"))
        assertFalse(isTrusted("content://0%40$own/logs/log.json"))
        assertFalse(isTrusted("content://0@${own.uppercase()}/logs/log.json"))
        assertFalse(isTrusted("content://${own.uppercase()}/logs/log.json"))
        // the prefix changes nothing for the allowed directory
        assertTrue(isTrusted("content://0@$own/shared_attachments/a.pdf"))
    }

    @Test
    fun traversalAndEmptySegmentsStayInsideTheSharedAttachments() {
        assertFalse(isTrusted("content://$own/shared_attachments/%2E%2E/logs/log.json"))
        assertFalse(isTrusted("content://$own/shared_attachments/../logs/log.json"))
        assertFalse(isTrusted("content://$own/shared_attachments/%2E%2E%2Flogs%2Flog.json"))
        assertFalse(isTrusted("content://$own/shared_attachments/a%2Fb"))
        assertFalse(isTrusted("content://$own/shared_attachments//%2E%2E/logs/log.json"))
        assertFalse(isTrusted("content://$own//logs/log.json"))
        assertFalse(isTrusted("content://$own/shared_attachments"))
        // an empty segment is dropped on both sides and names the same file
        assertTrue(isTrusted("content://$own/shared_attachments//a.pdf"))
    }
}
