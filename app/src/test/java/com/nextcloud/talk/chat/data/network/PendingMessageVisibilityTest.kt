/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.data.network

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextcloud.talk.conversationlist.data.network.ConversationListUpdater
import com.nextcloud.talk.data.database.dao.ChatMessagesDao
import com.nextcloud.talk.data.database.mappers.asEntity
import com.nextcloud.talk.data.database.model.ChatBlockEntity
import com.nextcloud.talk.data.database.model.SendStatus
import com.nextcloud.talk.data.network.NetworkMonitor
import com.nextcloud.talk.data.source.local.TalkDatabase
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.data.user.model.UserEntity
import com.nextcloud.talk.logger.Logger
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.conversations.ConversationDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The message list is read from [OfflineFirstChatRepository.observeLatestMessages]. A message that was just
 * sent is a temporary row and has to be part of that list at once, before the server has answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PendingMessageVisibilityTest {

    private lateinit var db: TalkDatabase
    private lateinit var dao: ChatMessagesDao
    private lateinit var repository: OfflineFirstChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TalkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chatMessagesDao()
        runBlocking {
            db.usersDao().saveUser(UserEntity(id = ACCOUNT_ID, userId = "me", username = "me", baseUrl = BASE_URL))
            // the messages of a conversation reference its row
            db.conversationsDao().insertConversation(ConversationDto(token = ROOM_TOKEN).asEntity(ACCOUNT_ID))
        }

        val networkMonitor: NetworkMonitor = mock()
        whenever(networkMonitor.isOnline).thenReturn(MutableStateFlow(true))
        val updater = ConversationListUpdater(dao, db.chatBlocksDao(), db.conversationsDao())
        repository = OfflineFirstChatRepository(
            mock<Logger>(),
            dao,
            db.chatBlocksDao(),
            mock(),
            networkMonitor,
            ChatMessageSyncer(dao, db.chatBlocksDao(), mock(), networkMonitor, updater),
            updater
        )
        val user = User(id = ACCOUNT_ID, userId = "me", username = "me", displayName = "Me", baseUrl = BASE_URL)
        repository.initData(user, "credentials", "$BASE_URL/chat", ROOM_TOKEN, null)
        repository.updateConversation(
            ConversationModel.mapToConversationModel(ConversationDto(token = ROOM_TOKEN), user)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a temporary message appears in the latest messages before the server answers`() {
        runBlocking {
            db.chatBlocksDao().upsertChatBlock(
                ChatBlockEntity(
                    internalConversationId = CONVERSATION_ID,
                    accountId = ACCOUNT_ID,
                    token = ROOM_TOKEN,
                    oldestMessageId = 100,
                    newestMessageId = 200,
                    hasHistory = true
                )
            )

            repository.addTemporaryMessage("hello", "Me", 0, false, TEXT_REFERENCE).first()

            val shown = repository.observeLatestMessages(CONVERSATION_ID).first()
            assertEquals(listOf("hello"), shown.map { it.message })
            assertEquals(SendStatus.PENDING, shown.single().sendStatus)
        }
    }

    companion object {
        private const val ACCOUNT_ID = 1L
        private const val BASE_URL = "https://server.example.com"
        private const val ROOM_TOKEN = "room"
        private const val CONVERSATION_ID = "1@room"
        private const val TEXT_REFERENCE = "ref-text"
    }
}
