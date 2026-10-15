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
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.conversationlist.data.network.ConversationListUpdater
import com.nextcloud.talk.data.database.mappers.asEntity
import com.nextcloud.talk.data.database.model.ChatMessageEntity
import com.nextcloud.talk.data.database.model.SendStatus
import com.nextcloud.talk.data.network.NetworkMonitor
import com.nextcloud.talk.data.source.local.TalkDatabase
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.data.user.model.UserEntity
import com.nextcloud.talk.logger.Logger
import com.nextcloud.talk.models.json.chat.ChatMessageDto
import com.nextcloud.talk.models.json.conversations.ConversationDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A message that reaches the database only as the parent of another message (a reply, or a reaction, edit or
 * delete system message) must still drop its temporary placeholder. Runs against a real in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@Suppress("TooManyFunctions")
class ParentMessageTempPlaceholderTest {

    private lateinit var db: TalkDatabase
    private lateinit var syncer: ChatMessageSyncer
    private lateinit var repository: OfflineFirstChatRepository

    private val network: ChatNetworkDataSource = mock()
    private val networkMonitor: NetworkMonitor = mock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TalkDatabase::class.java).build()
        runBlocking {
            db.usersDao().saveUser(UserEntity(id = ACCOUNT_ID, userId = "me", username = "me", baseUrl = BASE_URL))
            db.conversationsDao().upsertConversations(
                ACCOUNT_ID,
                listOf(
                    ConversationDto(token = ROOM_TOKEN).asEntity(ACCOUNT_ID),
                    ConversationDto(token = OTHER_ROOM_TOKEN).asEntity(ACCOUNT_ID)
                )
            )
        }
        val conversationListUpdater =
            ConversationListUpdater(db.chatMessagesDao(), db.chatBlocksDao(), db.conversationsDao())
        syncer = ChatMessageSyncer(
            db.chatMessagesDao(),
            db.chatBlocksDao(),
            network,
            networkMonitor,
            conversationListUpdater
        )
        repository = OfflineFirstChatRepository(
            mock<Logger>(),
            db.chatMessagesDao(),
            db.chatBlocksDao(),
            network,
            networkMonitor,
            syncer,
            conversationListUpdater
        )
        repository.initData(user(), "credentials", CHAT_URL, ROOM_TOKEN, null)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `parent of a system message removes the temp placeholder with its referenceId`() =
        runBlocking {
            storePlaceholder()
            val systemMessage = dto(id = SYSTEM_MESSAGE_ID, referenceId = null).apply {
                systemMessageType = ChatMessage.SystemMessageType.MESSAGE_EDITED
                parentMessage = serverCopy()
            }

            syncer.persistChatMessagesAndHandleSystemMessages(target(), listOf(systemMessage))

            assertNull(tempMessage())
            assertNotNull(storedMessage(SERVER_MESSAGE_ID))
        }

    @Test
    fun `fetched missing parent removes the temp placeholder with its referenceId`() =
        runBlocking {
            storePlaceholder()
            whenever(network.getContextForChatMessage(any(), any(), any(), any(), any(), anyOrNull()))
                .thenReturn(listOf(serverCopy()))

            repository.fetchMissingParents(ROOM_TOKEN, listOf(SERVER_MESSAGE_ID))

            assertNull(tempMessage())
            assertNotNull(storedMessage(SERVER_MESSAGE_ID))
        }

    @Test
    fun `parent with another referenceId keeps the temp placeholder`() =
        runBlocking {
            storePlaceholder()
            val systemMessage = dto(id = SYSTEM_MESSAGE_ID, referenceId = null).apply {
                systemMessageType = ChatMessage.SystemMessageType.MESSAGE_EDITED
                parentMessage = serverCopy().apply { referenceId = "other-ref" }
            }

            syncer.persistChatMessagesAndHandleSystemMessages(target(), listOf(systemMessage))

            assertNotNull(tempMessage())
            assertEquals("other-ref", storedMessage(SERVER_MESSAGE_ID)!!.referenceId)
        }

    @Test
    fun `fetched parent with another referenceId keeps the temp placeholder`() =
        runBlocking {
            storePlaceholder()
            whenever(network.getContextForChatMessage(any(), any(), any(), any(), any(), anyOrNull()))
                .thenReturn(listOf(serverCopy().apply { referenceId = "other-ref" }))

            repository.fetchMissingParents(ROOM_TOKEN, listOf(SERVER_MESSAGE_ID))

            assertNotNull(tempMessage())
            assertEquals("other-ref", storedMessage(SERVER_MESSAGE_ID)!!.referenceId)
        }

    @Test
    fun `parent of a system message keeps the temp placeholder of another conversation`() =
        runBlocking {
            storePlaceholder(OTHER_ROOM_TOKEN)
            val systemMessage = dto(id = SYSTEM_MESSAGE_ID, referenceId = null).apply {
                systemMessageType = ChatMessage.SystemMessageType.MESSAGE_EDITED
                parentMessage = serverCopy()
            }

            syncer.persistChatMessagesAndHandleSystemMessages(target(), listOf(systemMessage))

            assertNotNull(storedMessage(SERVER_MESSAGE_ID))
            assertNotNull(tempMessage(OTHER_INTERNAL_CONVERSATION_ID))
        }

    @Test
    fun `fetched parent keeps the temp placeholder of another conversation`() =
        runBlocking {
            storePlaceholder(OTHER_ROOM_TOKEN)
            whenever(network.getContextForChatMessage(any(), any(), any(), any(), any(), anyOrNull()))
                .thenReturn(listOf(serverCopy()))

            repository.fetchMissingParents(ROOM_TOKEN, listOf(SERVER_MESSAGE_ID))

            assertNotNull(storedMessage(SERVER_MESSAGE_ID))
            assertNotNull(tempMessage(OTHER_INTERNAL_CONVERSATION_ID))
        }

    private fun dto(id: Long, referenceId: String?, token: String = ROOM_TOKEN) =
        ChatMessageDto(
            id = id,
            token = token,
            actorType = "users",
            actorId = "me",
            actorDisplayName = "Me",
            message = "hello",
            messageType = "comment",
            systemMessageType = ChatMessage.SystemMessageType.DUMMY,
            referenceId = referenceId
        )

    private fun serverCopy() = dto(id = SERVER_MESSAGE_ID, referenceId = REFERENCE_ID)

    private suspend fun storePlaceholder(token: String = ROOM_TOKEN) {
        val placeholder = dto(id = PLACEHOLDER_ID, referenceId = REFERENCE_ID, token = token)
            .asEntity(ACCOUNT_ID)
            .apply {
                isTemporary = true
                sendStatus = SendStatus.PENDING
            }
        db.chatMessagesDao().upsertChatMessage(placeholder)
        assertNotNull(tempMessage(placeholder.internalConversationId))
    }

    private suspend fun tempMessage(internalConversationId: String = INTERNAL_CONVERSATION_ID): ChatMessageEntity? =
        db.chatMessagesDao().getTempMessageForConversation(internalConversationId, REFERENCE_ID, null).first()

    private suspend fun storedMessage(id: Long): ChatMessageEntity? =
        db.chatMessagesDao().getChatMessageEntity(INTERNAL_CONVERSATION_ID, id)

    private fun target() = ChatMessageSyncer.SyncTarget(user(), ROOM_TOKEN, null, "credentials", CHAT_URL)

    private fun user(): User =
        User(id = ACCOUNT_ID, userId = "me", username = "me", displayName = "Me", baseUrl = BASE_URL)

    companion object {
        private const val ACCOUNT_ID = 1L
        private const val ROOM_TOKEN = "room1"
        private const val INTERNAL_CONVERSATION_ID = "$ACCOUNT_ID@$ROOM_TOKEN"
        private const val OTHER_ROOM_TOKEN = "room2"
        private const val OTHER_INTERNAL_CONVERSATION_ID = "$ACCOUNT_ID@$OTHER_ROOM_TOKEN"
        private const val BASE_URL = "https://server.example.com"
        private const val CHAT_URL = "$BASE_URL/ocs/v2.php/apps/spreed/api/v1/chat/$ROOM_TOKEN"
        private const val REFERENCE_ID = "ref-1"
        private const val SERVER_MESSAGE_ID = 100L
        private const val SYSTEM_MESSAGE_ID = 101L
        private const val PLACEHOLDER_ID = -5L
    }
}
