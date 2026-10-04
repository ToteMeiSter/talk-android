/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

/**
 * Why a switch of the conversation inside the running [ChatActivity] must fall back to reopening the chat.
 */
internal enum class RoomSwitchFallback {
    /** The chat shows a thread, which is bound to the intent of the activity. */
    THREAD,

    /** A voice or video message is being recorded and would be lost. */
    RECORDING,

    /** A call is running or being dialed from this room. */
    CALL,

    /** The previous leave of the room is still in progress. */
    LEAVE_IN_PROGRESS
}

/**
 * Facts about the current room which decide if the chat can switch to another room without recreating the activity.
 */
internal data class RoomSwitchContext(
    val isThread: Boolean,
    val isRecording: Boolean,
    val isInCall: Boolean,
    val isLeavingRoom: Boolean
)

/**
 * Returns why the room can not be switched in place, or null when it can.
 */
internal fun roomSwitchFallback(context: RoomSwitchContext): RoomSwitchFallback? =
    when {
        context.isThread -> RoomSwitchFallback.THREAD
        context.isRecording -> RoomSwitchFallback.RECORDING
        context.isInCall -> RoomSwitchFallback.CALL
        context.isLeavingRoom -> RoomSwitchFallback.LEAVE_IN_PROGRESS
        else -> null
    }

/**
 * Keeps the view models of the shown room apart from the other view models of the activity. They live as long as
 * the room is shown, also over a configuration change. Switching to another room clears them, so their
 * coroutines (message polling, observers of the database) of the old room stop.
 *
 * Itself a view model of the activity, so the store survives a recreation of the activity.
 */
internal class ChatRoomStore : ViewModel() {
    private var store = ViewModelStore()
    private val retired = mutableSetOf<ViewModelStore>()

    /** Token of the room the current [store] belongs to. */
    var token: String? = null
        private set

    /**
     * The store of the room with [roomToken]. The store of any other room is cleared and replaced.
     */
    fun storeFor(roomToken: String): ViewModelStore {
        if (token != roomToken) {
            release()
            token = roomToken
        }
        return store
    }

    /**
     * Clears all view models of the current room.
     */
    fun release() {
        store.clear()
        store = ViewModelStore()
        token = null
    }

    /**
     * Detaches the store of the current room without clearing it, so operations of its view models which are still
     * running (edit, delete, reaction, scheduled send) can finish. The caller must hand the returned store to
     * [clearRetired] once the room is left. Whatever is left when this view model is cleared is cleared with it.
     *
     * @return the detached store, or null if no room was shown
     */
    fun retire(): ViewModelStore? {
        val old = store.takeIf { token != null }
        store = ViewModelStore()
        token = null
        old?.let { retired += it }
        return old
    }

    /**
     * Clears a store detached by [retire]. Does nothing if that already happened.
     */
    fun clearRetired(retiredStore: ViewModelStore) {
        if (retired.remove(retiredStore)) {
            retiredStore.clear()
        }
    }

    override fun onCleared() {
        release()
        retired.toList().forEach { clearRetired(it) }
    }
}

/**
 * Lifecycle of one shown room. It follows the lifecycle of the activity until [close] is called, which moves it down
 * to destroyed. Observers and coroutines of the room, like the view model of the chat, are therefore stopped
 * without the activity being finished.
 */
internal class RoomLifecycleOwner(private val activityLifecycle: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)

    private val activityObserver = LifecycleEventObserver { _, event -> registry.handleLifecycleEvent(event) }

    override val lifecycle: Lifecycle
        get() = registry

    init {
        // replays the events up to the current state of the activity
        activityLifecycle.addObserver(activityObserver)
    }

    fun close() {
        activityLifecycle.removeObserver(activityObserver)
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
