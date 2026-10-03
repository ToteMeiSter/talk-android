/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextcloud.talk.conversationlist.ui.ConversationList
import com.nextcloud.talk.conversationlist.viewmodels.ConversationsListViewModel
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.ui.theme.ViewThemeUtils

/**
 * Conversation list shown beside the chat on wide screens where activity embedding is not available.
 */
@Composable
fun ChatConversationListPane(
    viewModel: ConversationsListViewModel,
    viewThemeUtils: ViewThemeUtils,
    onConversationClick: (ConversationModel) -> Unit
) {
    val user = viewModel.currentUser
    val entries by viewModel.conversationListEntriesFlow.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isLoadingRooms.collectAsStateWithLifecycle()

    LaunchedEffect(user) { viewModel.getRooms(user) }

    MaterialTheme(colorScheme = viewThemeUtils.getColorScheme(LocalContext.current)) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
        ) {
            Row {
                Box(modifier = Modifier.weight(1f)) {
                    ConversationList(
                        entries = entries,
                        isRefreshing = isRefreshing,
                        currentUser = user,
                        credentials = viewModel.credentials,
                        onConversationClick = onConversationClick,
                        onConversationLongClick = {},
                        onMessageResultClick = {},
                        onContactClick = {},
                        onLoadMoreClick = {},
                        onRefresh = { viewModel.getRooms(user) }
                    )
                }
                VerticalDivider()
            }
        }
    }
}
