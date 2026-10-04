/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private val previewState = ToolBarState(
    showQuality = true,
    highQuality = false,
    showPermission = true,
    allowUpdate = false
)

private val previewActions = ToolBarActions(
    onCrop = {},
    onDraw = {},
    onHighQualityChange = {},
    onAllowUpdateChange = {}
)

@Composable
private fun ToolRowPreviewContainer() {
    MaterialTheme {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                AttachmentToolBar(previewState, previewActions)
            }
            SendButton(enabled = true, onClick = {})
        }
    }
}

@Preview(name = "Narrow 360dp", widthDp = 360, showBackground = true)
@Preview(name = "Phone 393dp", widthDp = 393, showBackground = true)
@Preview(name = "Side pane 500dp", widthDp = 500, showBackground = true)
@Composable
private fun ToolRowWidthsPreview() {
    ToolRowPreviewContainer()
}
