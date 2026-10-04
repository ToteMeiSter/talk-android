/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextcloud.talk.R

private const val BRUSH_WIDTH_FRACTION = 0.012f
private const val SWATCH_SIZE_DP = 32
private const val SWATCH_SELECTED_BORDER_DP = 3
private const val COLOR_RED = 0xFFE53935
private const val COLOR_YELLOW = 0xFFFDD835
private const val COLOR_GREEN = 0xFF43A047
private const val COLOR_BLUE = 0xFF1E88E5
private val brushColors = listOf(
    Color.White,
    Color.Black,
    Color(COLOR_RED),
    Color(COLOR_YELLOW),
    Color(COLOR_GREEN),
    Color(COLOR_BLUE)
)

/**
 * Full-screen brush editor over an image. Strokes are kept in image-relative coordinates, so the
 * result doesn't depend on the screen size; [onDone] gets them for rendering
 * at the file's own resolution. [aspectRatio] is the image's displayed width / height.
 */
@Composable
internal fun DrawingEditor(
    imageUri: String,
    aspectRatio: Float,
    onCancel: () -> Unit,
    onDone: (List<DrawStroke>) -> Unit
) {
    var strokes by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var color by remember { mutableStateOf(brushColors.first()) }
    BackHandler(onBack = onCancel)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val rect = remember(widthPx, heightPx, aspectRatio) { fitRect(widthPx, heightPx, aspectRatio) }

            AsyncImage(
                model = imageUri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            DrawingSurface(
                rect = rect,
                strokes = strokes,
                color = color,
                onStroke = { strokes = strokes + it }
            )
        }

        EditorTopBar(
            canUndo = strokes.isNotEmpty(),
            onCancel = onCancel,
            onUndo = { strokes = undoLast(strokes) },
            onDone = { onDone(strokes) },
            modifier = Modifier.align(Alignment.TopCenter)
        )
        ColorRow(
            selected = color,
            onSelect = { color = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun DrawingSurface(rect: FitRect, strokes: List<DrawStroke>, color: Color, onStroke: (DrawStroke) -> Unit) {
    val live = remember { mutableStateListOf<NormalizedPoint>() }

    fun commit() {
        if (live.isNotEmpty()) {
            onStroke(DrawStroke(color.toArgb(), BRUSH_WIDTH_FRACTION, live.toList()))
            live.clear()
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(rect, color) {
                detectDragGestures(
                    onDragStart = { start ->
                        live.clear()
                        live.add(toNormalized(start.x, start.y, rect))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        live.add(toNormalized(change.position.x, change.position.y, rect))
                    },
                    onDragEnd = ::commit,
                    onDragCancel = ::commit
                )
            }
    ) {
        strokes.forEach { drawStroke(it.colorArgb, it.widthFraction, it.points, rect) }
        drawStroke(color.toArgb(), BRUSH_WIDTH_FRACTION, live.toList(), rect)
    }
}

private fun DrawScope.drawStroke(colorArgb: Int, widthFraction: Float, points: List<NormalizedPoint>, rect: FitRect) {
    if (points.isEmpty()) return
    val color = Color(colorArgb)
    val width = (widthFraction * rect.width).coerceAtLeast(1f)
    fun NormalizedPoint.toOffset() = Offset(rect.left + x * rect.width, rect.top + y * rect.height)
    if (points.size == 1) {
        drawCircle(color, radius = width / 2f, center = points.first().toOffset())
        return
    }
    val path = Path().apply {
        val first = points.first().toOffset()
        moveTo(first.x, first.y)
        points.drop(1).forEach { point -> point.toOffset().let { lineTo(it.x, it.y) } }
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .background(ScrimColor)
            .statusBarsPadding()
            .padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, stringResource(R.string.nc_common_dismiss), tint = Color.White)
        }
        Row {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    stringResource(R.string.nc_attachment_undo),
                    tint = Color.White.copy(alpha = if (canUndo) 1f else 0.4f)
                )
            }
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.Check, stringResource(R.string.save), tint = Color.White)
            }
        }
    }
}

@Composable
private fun ColorRow(selected: Color, onSelect: (Color) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(ScrimColor)
            .navigationBarsPadding()
            .padding(vertical = 12.dp)
    ) {
        brushColors.forEach { swatch ->
            val borderColor = if (swatch == selected) Color.White else Color.Gray
            val borderWidth = if (swatch == selected) SWATCH_SELECTED_BORDER_DP else 1
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(borderWidth.dp, borderColor, CircleShape)
                    .clickable { onSelect(swatch) }
            )
        }
    }
}
