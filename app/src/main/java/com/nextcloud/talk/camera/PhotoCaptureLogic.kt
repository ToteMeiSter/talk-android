/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Krainov Gleb <krajnov.g@kontentplus.ru>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.camera

import android.view.Surface
import androidx.camera.core.ImageCapture
import com.nextcloud.talk.utils.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FILE_DATE_PATTERN = "yyyy-MM-dd HH-mm-ss"
private const val PHOTO_SUFFIX = ".jpg"
private const val MAX_FILE_VARIANTS = 100
private const val ORIENTATION_QUARTER = 90
private const val ORIENTATION_EIGHTH = 45
private const val FULL_CIRCLE = 360

// Device turned clockwise by 0, 90, 180, 270 degrees: the display rotates the other way.
private val rotationByQuadrant = intArrayOf(
    Surface.ROTATION_0,
    Surface.ROTATION_270,
    Surface.ROTATION_180,
    Surface.ROTATION_90
)

/**
 * Flash choice of the capture screen. [next] is the order of the flash button: off, auto, on.
 */
internal enum class FlashSetting(val imageCaptureMode: Int) {
    OFF(ImageCapture.FLASH_MODE_OFF),
    AUTO(ImageCapture.FLASH_MODE_AUTO),
    ON(ImageCapture.FLASH_MODE_ON);

    fun next(): FlashSetting = entries[(ordinal + 1) % entries.size]
}

/**
 * The flash mode to apply: a camera without a flash unit always gets [ImageCapture.FLASH_MODE_OFF].
 */
internal fun effectiveFlashMode(setting: FlashSetting, hasFlashUnit: Boolean): Int =
    if (hasFlashUnit) setting.imageCaptureMode else ImageCapture.FLASH_MODE_OFF

/**
 * Maps the device orientation reported by OrientationEventListener (degrees, clockwise from upright) to the
 * [Surface] rotation for ImageCapture, or null when the orientation is unknown.
 */
internal fun rotationForDeviceOrientation(degrees: Int): Int? {
    if (degrees < 0) return null
    return rotationByQuadrant[((degrees + ORIENTATION_EIGHTH) % FULL_CIRCLE) / ORIENTATION_QUARTER]
}

internal fun formatCaptureTimestamp(date: Date): String = SimpleDateFormat(FILE_DATE_PATTERN, Locale.ROOT).format(date)

/**
 * Creates the target of a photo in the shared attachments cache directory, the one the file provider exposes.
 * An existing file is never reused: the name gets a number instead. Returns null when no usable name exists.
 */
internal fun createPhotoFile(cacheDir: File, baseName: String): File? {
    val directory = FileUtils.getSharedAttachmentsDirectory(cacheDir) ?: return null
    return (0 until MAX_FILE_VARIANTS)
        .asSequence()
        .map { if (it == 0) baseName else "$baseName ($it)" }
        .mapNotNull { FileUtils.resolveFileInDirectory(directory, it + PHOTO_SUFFIX) }
        .firstOrNull { !it.exists() }
}
