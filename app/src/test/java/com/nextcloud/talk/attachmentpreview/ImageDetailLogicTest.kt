/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import androidx.exifinterface.media.ExifInterface
import com.nextcloud.talk.utils.ImageCompressor.ImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDetailLogicTest {

    private val describe: (ImageInfo) -> String = { "${it.width}x${it.height} ${it.sizeBytes}" }
    private val cameraShot = ImageInfo(4000, 3000, 3_000_000)
    private val compressed = ImageInfo(960, 1280, 153_000)

    @Test
    fun quarterTurnOrientationsSwapDimensions() {
        listOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE
        ).forEach { assertTrue(exifSwapsDimensions(it)) }
    }

    @Test
    fun otherOrientationsKeepDimensions() {
        listOf(
            ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL
        ).forEach { assertFalse(exifSwapsDimensions(it)) }
    }

    @Test
    fun rotatedPhotoReportsPortraitSize() {
        val shown = cameraShot.inDisplayOrientation(ExifInterface.ORIENTATION_ROTATE_90)

        assertEquals(ImageInfo(3000, 4000, 3_000_000), shown)
    }

    @Test
    fun uprightPhotoKeepsItsSize() {
        assertEquals(cameraShot, cameraShot.inDisplayOrientation(ExifInterface.ORIENTATION_NORMAL))
    }

    @Test
    fun standardQualityShowsCompressedResult() {
        val variants = imageDetailVariants(cameraShot, compressed, compress = true, describe)

        assertEquals("960x1280 153000", variants.current)
        assertEquals("4000x3000 3000000", variants.alternate)
    }

    @Test
    fun highQualityShowsOriginal() {
        val variants = imageDetailVariants(cameraShot, compressed, compress = false, describe)

        assertEquals("4000x3000 3000000", variants.current)
        assertEquals("960x1280 153000", variants.alternate)
    }

    @Test
    fun missingEstimateFallsBackToOriginal() {
        val variants = imageDetailVariants(cameraShot, null, compress = true, describe)

        assertEquals("4000x3000 3000000", variants.current)
        assertEquals(variants.current, variants.alternate)
    }
}
