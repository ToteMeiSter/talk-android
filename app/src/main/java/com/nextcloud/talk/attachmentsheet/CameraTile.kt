/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Krainov Gleb <krajnov.g@kontentplus.ru>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentsheet

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.cameraSelectorFor
import com.nextcloud.talk.chat.resolveLens
import java.util.concurrent.ExecutionException

private const val TAG = "CameraTile"

/**
 * First grid tile: live back-camera preview with a camera icon. Without the camera permission only the icon is
 * shown; the tap then goes through the regular photo flow, which asks for the permission.
 */
@Composable
internal fun CameraTile(onClick: () -> Unit) {
    val context = LocalContext.current
    val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color.Black)
            .clickable(onClick = onClick)
    ) {
        if (hasCameraPermission) {
            LiveCameraPreview(Modifier.fillMaxSize())
        }
        Icon(
            painter = painterResource(R.drawable.ic_baseline_photo_camera_24),
            contentDescription = stringResource(R.string.nc_upload_picture_from_cam),
            modifier = Modifier.align(Alignment.Center).padding(8.dp),
            tint = Color.White
        )
    }
}

@Composable
private fun LiveCameraPreview(modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            try {
                provider = providerFuture.get()
                preview = bindBackPreview(requireNotNull(provider), lifecycleOwner, previewView)
            } catch (e: ExecutionException) {
                Log.w(TAG, "camera provider is not available", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            preview?.let { provider?.unbind(it) }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun bindBackPreview(provider: ProcessCameraProvider, owner: LifecycleOwner, view: PreviewView): Preview? {
    val lens = resolveLens(provider, CameraSelector.LENS_FACING_BACK)
    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
    return try {
        provider.bindToLifecycle(owner, cameraSelectorFor(lens), preview)
        preview
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "cannot bind camera preview", e)
        null
    } catch (e: IllegalStateException) {
        Log.w(TAG, "cannot bind camera preview", e)
        null
    }
}
