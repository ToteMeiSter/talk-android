/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Krainov Gleb <krajnov.g@kontentplus.ru>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentsheet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.nextcloud.talk.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val GRID_COLUMNS = 3
private const val TILE_GAP_DP = 2
private const val BADGE_SIZE_DP = 24
private const val BAR_ITEM_WIDTH_DP = 84
private const val SCRIM_ALPHA = 0.45f

data class AttachmentSheetModel(val actions: List<AttachmentAction>, val cloudLabel: String, val maxSelection: Int)

data class AttachmentSheetCallbacks(
    val onAction: (AttachmentAction) -> Unit,
    val onTakePhoto: () -> Unit,
    val onSend: (List<Uri>) -> Unit,
    val onDismiss: () -> Unit
)

/**
 * Bottom sheet in the style of messengers: recent photos and videos in a grid (first tile is the camera), a bar with
 * all other attachment entries below. It opens half-high and can be dragged up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(model: AttachmentSheetModel, callbacks: AttachmentSheetCallbacks) {
    ModalBottomSheet(
        onDismissRequest = callbacks.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        AttachmentSheetBody(model, callbacks)
    }
}

@Composable
private fun AttachmentSheetBody(model: AttachmentSheetModel, callbacks: AttachmentSheetCallbacks) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshKey++ }

    val access = remember(refreshKey) { currentMediaAccess(context) }
    val media by produceState<List<RecentMedia>?>(null, refreshKey, access) {
        value = if (access == MediaAccess.NONE) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { RecentMediaLoader.load(context.contentResolver) }
        }
    }
    var selection by remember { mutableStateOf(MediaSelection(limit = model.maxSelection)) }
    val permissionRequest = rememberMediaPermissionRequest { refreshKey++ }

    val hiddenBottomPx = rememberHiddenBottomPx()
    val hiddenBottomDp = with(LocalDensity.current) { hiddenBottomPx.value.toDp() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { hiddenBottomPx.update(it.positionInWindow().y, it.size.height) }
            .padding(bottom = hiddenBottomDp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (access == MediaAccess.NONE) {
                MediaAccessRequest(onRequest = permissionRequest)
            } else {
                MediaGrid(
                    media = media.orEmpty(),
                    selection = selection,
                    onToggle = { selection = selection.toggle(it.key) },
                    onTakePhoto = callbacks.onTakePhoto.takeIf { AttachmentAction.PICTURE_FROM_CAM in model.actions },
                    onSelectMore = permissionRequest.takeIf { access == MediaAccess.PARTIAL }
                )
            }
        }
        if (selection.isEmpty) {
            ActionBar(
                model = model,
                onAction = callbacks.onAction,
                modifier = if (hiddenBottomPx.value > 0) Modifier.navigationBarsPadding() else Modifier
            )
        } else {
            SendBar(
                count = selection.count,
                onClear = { selection = MediaSelection(limit = model.maxSelection) },
                onSend = {
                    val chosen = media.orEmpty().associateBy { it.key }
                    callbacks.onSend(selection.ids.mapNotNull { chosen[it] }.map { RecentMediaLoader.uriOf(it) })
                }
            )
        }
    }
}

/**
 * Part of the sheet body that is currently pushed below the window bottom while the sheet is half open, so the
 * bars stay visible.
 */
private class HiddenBottom(private val windowHeightPx: Int) {
    var value by mutableIntStateOf(0)
        private set

    fun update(topInWindow: Float, height: Int) {
        value = (topInWindow.roundToInt() + height - windowHeightPx).coerceAtLeast(0)
    }
}

@Composable
private fun rememberHiddenBottomPx(): HiddenBottom {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    return remember(windowHeight) { HiddenBottom(windowHeight) }
}

private fun grantedMediaPermissions(context: Context): Set<String> =
    mediaPermissionsToRequest(Build.VERSION.SDK_INT)
        .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        .toSet()

private fun currentMediaAccess(context: Context): MediaAccess =
    resolveMediaAccess(Build.VERSION.SDK_INT, grantedMediaPermissions(context))

/**
 * Asks for media access. After a denial the system dialog does not appear again, so the next call opens the app
 * settings instead.
 */
@Composable
private fun rememberMediaPermissionRequest(onChanged: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var deniedBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        deniedBefore = currentMediaAccess(context) == MediaAccess.NONE
        onChanged()
    }
    return {
        if (deniedBefore) {
            val appDetails = Uri.fromParts("package", context.packageName, null)
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appDetails))
        } else {
            launcher.launch(mediaPermissionsToRequest(Build.VERSION.SDK_INT))
        }
    }
}

@Composable
private fun MediaAccessRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.baseline_photo_library_24),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.read_storage_no_permission),
            modifier = Modifier.padding(vertical = 16.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRequest) {
            Text(stringResource(R.string.nc_permissions_ask))
        }
    }
}

@Composable
private fun MediaGrid(
    media: List<RecentMedia>,
    selection: MediaSelection,
    onToggle: (RecentMedia) -> Unit,
    onTakePhoto: (() -> Unit)?,
    onSelectMore: (() -> Unit)?
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)
    ) {
        if (onSelectMore != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = onSelectMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.attachment_sheet_select_more))
                }
            }
        }
        if (onTakePhoto != null) {
            item { CameraTile(onClick = onTakePhoto) }
        }
        items(media, key = { it.key }) { item ->
            MediaTile(item, selection.positionOf(item.key), onClick = { onToggle(item) })
        }
    }
}

@Composable
private fun MediaTile(media: RecentMedia, position: Int?, onClick: () -> Unit) {
    Box(modifier = Modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        MediaThumbnail(media)
        if (media.isVideo) {
            Text(
                text = formatVideoDuration(media.durationMs),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA), CircleShape)
                    .padding(horizontal = 6.dp),
                color = Color.White,
                fontSize = 11.sp
            )
        }
        SelectionBadge(position, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
private fun MediaThumbnail(media: RecentMedia) {
    if (media.isVideo) {
        val resolver = LocalContext.current.contentResolver
        val frame by produceState<android.graphics.Bitmap?>(null, media.key) {
            value = withContext(Dispatchers.IO) { RecentMediaLoader.videoThumbnail(resolver, media) }
        }
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        AsyncImage(
            model = RecentMediaLoader.uriOf(media),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun SelectionBadge(position: Int?, modifier: Modifier = Modifier) {
    val base = modifier.size(BADGE_SIZE_DP.dp).clip(CircleShape)
    if (position == null) {
        Box(base.border(2.dp, Color.White, CircleShape).background(Color.Black.copy(alpha = 0.15f)))
    } else {
        Box(
            modifier = base.background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(text = position.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SendBar(count: Int, onClear: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onClear) { Text(stringResource(R.string.nc_cancel)) }
        Button(onClick = onSend) { Text(stringResource(R.string.attachment_sheet_send, count)) }
    }
}

@Composable
private fun ActionBar(
    model: AttachmentSheetModel,
    onAction: (AttachmentAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        model.actions.forEach { action ->
            val label = if (action == AttachmentAction.FILE_FROM_CLOUD) {
                model.cloudLabel
            } else {
                stringResource(action.labelRes())
            }
            Column(
                modifier = Modifier
                    .width(BAR_ITEM_WIDTH_DP.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onAction(action) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painter = painterResource(action.iconRes()),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
