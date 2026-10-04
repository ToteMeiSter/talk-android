/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.camera

import android.view.OrientationEventListener
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextcloud.talk.R
import java.io.File

private val ControlSize = 48.dp
private val ShutterSize = 72.dp
private val ShutterRingWidth = 4.dp
private val ScreenPadding = 16.dp
private const val DISABLED_ALPHA = 0.4f

/**
 * Full screen photo capture: preview, close and flash on top, shutter and lens switch at the bottom.
 * [newPhotoFile] makes the target of a photo, [onCaptured] gets the finished file, [onFailed] the target of a
 * photo that could not be written.
 */
@Composable
internal fun PhotoCaptureScreen(
    newPhotoFile: () -> File?,
    onCaptured: (File) -> Unit,
    onFailed: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val camera = remember { PhotoCamera(context, lifecycleOwner, previewView) }

    DisposableEffect(camera) {
        camera.start()
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                rotationForDeviceOrientation(orientation)?.let(camera::setTargetRotation)
            }
        }
        orientationListener.enable()
        onDispose {
            orientationListener.disable()
            camera.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        CaptureTopBar(camera, onClose)
        CaptureBottomBar(camera) {
            if (camera.isCapturing || !camera.isBound) return@CaptureBottomBar
            val file = newPhotoFile()
            if (file == null) {
                onFailed()
            } else {
                camera.takePicture(file) { success ->
                    if (success) {
                        onCaptured(file)
                    } else {
                        file.delete()
                        onFailed()
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CaptureTopBar(camera: PhotoCamera, onClose: () -> Unit) {
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().systemBarsPadding().padding(ScreenPadding)) {
        CaptureIconButton(
            icon = R.drawable.ic_baseline_close_24,
            description = stringResource(R.string.close),
            modifier = Modifier.align(Alignment.CenterStart),
            onClick = onClose
        )
        if (camera.hasFlashUnit) {
            val (icon, description) = flashPresentation(camera.flash)
            CaptureIconButton(
                icon = icon,
                description = stringResource(description),
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = camera::cycleFlash
            )
        }
    }
}

@Composable
private fun BoxScope.CaptureBottomBar(camera: PhotoCamera, onShutter: () -> Unit) {
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(ScreenPadding)) {
        val enabled = camera.isBound && !camera.isCapturing
        val shutterDescription = stringResource(R.string.take_photo)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(ShutterSize)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .border(ShutterRingWidth, Color.White, CircleShape)
                .padding(ShutterRingWidth * 2)
                .clip(CircleShape)
                .background(Color.White)
                .semantics {
                    contentDescription = shutterDescription
                    role = Role.Button
                }
                .clickable(enabled = enabled, onClick = onShutter)
        )
        if (camera.canSwitchLens) {
            CaptureIconButton(
                icon = R.drawable.ic_baseline_flip_camera_android_24,
                description = stringResource(R.string.nc_video_recording_switch_camera),
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = camera::switchLens
            )
        }
    }
}

@Composable
private fun CaptureIconButton(icon: Int, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(ControlSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painter = painterResource(icon), contentDescription = description, tint = Color.White)
    }
}

private fun flashPresentation(setting: FlashSetting): Pair<Int, Int> =
    when (setting) {
        FlashSetting.OFF -> R.drawable.ic_baseline_flash_off_24 to R.string.nc_photo_capture_flash_off
        FlashSetting.AUTO -> R.drawable.ic_baseline_flash_auto_24 to R.string.nc_photo_capture_flash_auto
        FlashSetting.ON -> R.drawable.ic_baseline_flash_on_24 to R.string.nc_photo_capture_flash_on
    }
