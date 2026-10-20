/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import android.app.Application
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class FileAttachmentPreviewViewModelTest {

    private val testScope = TestScope()
    private val described = mutableListOf<String>()
    private lateinit var viewModel: FileAttachmentPreviewViewModel

    @Before
    fun setUp() {
        viewModel = FileAttachmentPreviewViewModel(
            context = mock(Context::class.java),
            ioDispatcher = StandardTestDispatcher(testScope.testScheduler),
            mainDispatcher = StandardTestDispatcher(testScope.testScheduler),
            describer = { _, uri, _ ->
                described.add(uri)
                FileDescription(uri, uri, MediaKind.OTHER, "text/plain", null)
            },
            mimeTypeResolver = { _, uri -> if (uri.endsWith(".mp4")) "video/mp4" else "text/plain" }
        )
    }

    @Test
    fun `mime types are resolved without describing the files`() {
        viewModel.setInitialFiles(listOf("content://a/clip.mp4"))

        viewModel.resolveMimeTypes()
        testScope.advanceUntilIdle()

        assertEquals("video/mp4", viewModel.mimeTypesByUri["content://a/clip.mp4"])
        assertTrue(viewModel.descriptionsByUri.isEmpty())
        assertTrue(described.isEmpty())
    }

    @Test
    fun `mime types of removed files are dropped`() {
        viewModel.setInitialFiles(listOf("content://a/1.txt", "content://a/2.txt"))
        viewModel.resolveMimeTypes()
        testScope.advanceUntilIdle()

        viewModel.files.remove("content://a/1.txt")
        viewModel.resolveMimeTypes()
        testScope.advanceUntilIdle()

        assertEquals(setOf("content://a/2.txt"), viewModel.mimeTypesByUri.keys)
    }

    @Test
    fun `a newer describeFiles call cancels the previous job`() {
        viewModel.setInitialFiles(listOf("content://a/1.txt"))
        viewModel.describeFiles(true)
        viewModel.addFiles(listOf("content://a/2.txt"))
        viewModel.describeFiles(true)
        testScope.advanceUntilIdle()

        assertEquals(listOf("content://a/1.txt", "content://a/2.txt"), described)
        assertEquals(setOf("content://a/1.txt", "content://a/2.txt"), viewModel.descriptionsByUri.keys)
    }

    @Test
    fun `a cancelled describeFiles job that is still copying does not drop a newer description`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        viewModel = FileAttachmentPreviewViewModel(
            context = mock(Context::class.java),
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            mainDispatcher = StandardTestDispatcher(testScope.testScheduler),
            describer = { _, uri, _ ->
                if (uri == "content://a/1.txt" && started.count == 1L) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                FileDescription(uri, uri, MediaKind.OTHER, "text/plain", null)
            }
        )
        viewModel.setInitialFiles(listOf("content://a/1.txt"))
        viewModel.describeFiles(true)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        viewModel.addFiles(listOf("content://a/2.txt"))
        viewModel.describeFiles(true)
        Thread.sleep(300)
        testScope.advanceUntilIdle()
        assertEquals(
            setOf("content://a/1.txt", "content://a/2.txt"),
            viewModel.descriptionsByUri.keys
        )

        release.countDown()
        Thread.sleep(300)
        testScope.advanceUntilIdle()

        assertEquals(
            setOf("content://a/1.txt", "content://a/2.txt"),
            viewModel.descriptionsByUri.keys
        )
    }

    private fun newViewModel(vararg uris: String) =
        FileAttachmentPreviewViewModel(mock(Context::class.java)).apply { setInitialFiles(uris.toList()) }

    @Test
    fun replaceFileSwapsUriInPlace() {
        val vm = newViewModel("a", "b", "c")
        vm.replaceFile("b", "b2")
        assertEquals(listOf("a", "b2", "c"), vm.files.toList())
    }

    @Test
    fun replaceFileKeepsExcludedState() {
        val vm = newViewModel("a", "b")
        vm.toggleSelected("b")
        vm.replaceFile("b", "b2")
        assertEquals(listOf("a"), vm.selectedFiles())
        assertEquals(listOf("b2"), vm.unselected.toList())
    }

    @Test
    fun replaceFileOfUnknownUriIsIgnored() {
        val vm = newViewModel("a")
        vm.replaceFile("zzz", "y")
        assertEquals(listOf("a"), vm.files.toList())
    }

    @Test
    fun excludedFileIsNotSelectedButStaysInList() {
        val vm = newViewModel("a", "b", "c")
        vm.toggleSelected("b")
        assertEquals(listOf("a", "c"), vm.selectedFiles())
        assertEquals(listOf("a", "b", "c"), vm.files.toList())
        vm.toggleSelected("b")
        assertEquals(listOf("a", "b", "c"), vm.selectedFiles())
    }

    @Test
    fun lastSelectedFileStaysSelected() {
        val vm = newViewModel("a", "b")
        vm.toggleSelected("a")
        vm.toggleSelected("b")
        assertEquals(listOf("b"), vm.selectedFiles())
        assertEquals(listOf("a"), vm.unselected.toList())
    }

    @Test
    fun filesAddedLaterAreSelected() {
        val vm = newViewModel("a", "b")
        vm.toggleSelected("a")
        vm.addFiles(listOf("c"))
        assertEquals(listOf("b", "c"), vm.selectedFiles())
    }

    private fun description(uri: String, detail: String = "d") =
        FileDescription(uri, "name", MediaKind.IMAGE, "image/jpeg", detail)

    @Test
    fun replacedPageKeepsItsDescriptionUntilRefreshed() {
        val vm = newViewModel("a", "b")
        vm.publishDescriptions(mapOf("a" to description("a"), "b" to description("b")))
        vm.replaceFile("b", "b2")
        assertEquals(listOf("a", "b2"), vm.files.mapNotNull { vm.descriptionsByUri[it]?.uri })
    }

    @Test
    fun staleDescriptionResultCannotWipeOutReplacement() {
        val vm = newViewModel("a", "b")
        vm.publishDescriptions(mapOf("a" to description("a"), "b" to description("b")))
        vm.replaceFile("b", "b2")
        // a describe run started before the replacement finishes late and only knows the old uris
        vm.publishDescriptions(mapOf("a" to description("a", "late"), "b" to description("b", "late")))
        assertEquals(setOf("a", "b2"), vm.descriptionsByUri.keys)
        assertEquals("b2", vm.descriptionsByUri["b2"]?.uri)
    }

    @Test
    fun freshDescriptionsReplaceCarriedOverOnes() {
        val vm = newViewModel("a")
        vm.publishDescriptions(mapOf("a" to description("a")))
        vm.replaceFile("a", "a2")
        vm.publishDescriptions(mapOf("a2" to description("a2", "fresh")))
        assertEquals("fresh", vm.descriptionsByUri["a2"]?.detail)
    }
}
