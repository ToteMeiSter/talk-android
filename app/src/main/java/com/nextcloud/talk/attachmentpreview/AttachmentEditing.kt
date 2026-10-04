/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.utils.FileUtils
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "AttachmentEditing"
private const val EDIT_FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss-SSS"
private const val EDIT_JPEG_QUALITY = 95
private const val EDIT_PNG_QUALITY = 100

/** Creates the (not yet existing) output file of an edit, in the cache dir shared through the app's FileProvider. */
internal fun createEditOutputFile(context: Context, sourceName: String, sourceMimeType: String?): File {
    val directory = FileUtils.getSharedAttachmentsDirectory(context.cacheDir) ?: context.cacheDir
    val stamp = SimpleDateFormat(EDIT_FILE_STAMP_PATTERN, Locale.ROOT).format(Date())
    val extension = if (editOutputIsPng(sourceMimeType)) "png" else "jpg"
    return File(directory, editedFileName(sourceName, stamp, extension))
}

/** The same URI form the camera capture uses for files in the shared attachments cache. */
internal fun editedFileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName, file)

/** Intent of uCrop's crop-and-rotate screen, writing the result to [destination]. */
internal fun createCropIntent(context: Context, source: Uri, destination: File, sourceMimeType: String?): Intent {
    val png = editOutputIsPng(sourceMimeType)
    val options = UCrop.Options().apply {
        setCompressionFormat(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG)
        setCompressionQuality(if (png) EDIT_PNG_QUALITY else EDIT_JPEG_QUALITY)
        setFreeStyleCropEnabled(true)
    }
    return UCrop.of(source, Uri.fromFile(destination)).withOptions(options).getIntent(context)
}

/**
 * Burns [strokes] into a full-resolution copy of [source] (EXIF orientation applied, so the result
 * is upright without an orientation tag) and writes it to [destination]. Returns false on any failure.
 */
internal fun renderDrawing(
    context: Context,
    source: Uri,
    strokes: List<DrawStroke>,
    destination: File,
    png: Boolean
): Boolean =
    try {
        val bitmap = decodeUpright(context, source)
        bitmap != null && writeDrawing(bitmap, strokes, destination, png)
    } catch (e: IOException) {
        NextcloudTalkApplication.sharedApplication?.logger?.w(TAG, "Failed to save drawing", e)
        false
    } catch (e: OutOfMemoryError) {
        NextcloudTalkApplication.sharedApplication?.logger?.w(TAG, "Out of memory while saving drawing", e)
        false
    }

private fun writeDrawing(bitmap: Bitmap, strokes: List<DrawStroke>, destination: File, png: Boolean): Boolean {
    drawStrokes(bitmap, strokes)
    val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    val quality = if (png) EDIT_PNG_QUALITY else EDIT_JPEG_QUALITY
    val written = FileOutputStream(destination).use { bitmap.compress(format, quality, it) }
    bitmap.recycle()
    return written
}

private fun decodeUpright(context: Context, source: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight)
        inMutable = true
    }
    val decoded = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    } else {
        null
    }
    val exif = resolver.openInputStream(source)?.use { ExifInterface(it) }
    return decoded?.let { uprightCopy(it, exif) }
}

private fun uprightCopy(decoded: Bitmap, exif: ExifInterface?): Bitmap {
    val matrix = Matrix().apply {
        if (exif?.isFlipped == true) postScale(-1f, 1f)
        postRotate((exif?.rotationDegrees ?: 0).toFloat())
    }
    if (matrix.isIdentity) return decoded

    val mapped = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat()).also { matrix.mapRect(it) }
    matrix.postTranslate(-mapped.left, -mapped.top)
    val upright = createBitmap(mapped.width().roundToInt(), mapped.height().roundToInt())
    Canvas(upright).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    decoded.recycle()
    return upright
}

private fun drawStrokes(bitmap: Bitmap, strokes: List<DrawStroke>) {
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.points.isEmpty()) return@forEach
        paint.color = stroke.colorArgb
        paint.strokeWidth = strokeWidthPixels(stroke, bitmap.width)
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val (x, y) = toPixels(point, bitmap.width, bitmap.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (stroke.points.size == 1) {
            val (x, y) = toPixels(stroke.points.first(), bitmap.width, bitmap.height)
            canvas.drawPoint(x, y, paint)
        } else {
            canvas.drawPath(path, paint)
        }
    }
}
