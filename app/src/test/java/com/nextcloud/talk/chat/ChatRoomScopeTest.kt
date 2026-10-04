/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nextcloud.talk.dagger.modules.ViewModelFactoryWithParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRoomScopeTest {

    private class RoomViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private class FakeActivity : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private fun viewModelOf(store: ChatRoomStore, token: String): RoomViewModel =
        ViewModelProvider(
            store.storeFor(token),
            ViewModelFactoryWithParams(RoomViewModel::class.java) { RoomViewModel() }
        )[RoomViewModel::class.java]

    private val idle = RoomSwitchContext(
        isThread = false,
        isRecording = false,
        isInCall = false,
        isLeavingRoom = false
    )

    @Test
    fun roomIsSwitchedInPlaceWhenNothingIsInTheWay() {
        assertNull(roomSwitchFallback(idle))
    }

    @Test
    fun eachBlockerFallsBackToReopening() {
        assertEquals(RoomSwitchFallback.THREAD, roomSwitchFallback(idle.copy(isThread = true)))
        assertEquals(RoomSwitchFallback.RECORDING, roomSwitchFallback(idle.copy(isRecording = true)))
        assertEquals(RoomSwitchFallback.CALL, roomSwitchFallback(idle.copy(isInCall = true)))
        assertEquals(RoomSwitchFallback.LEAVE_IN_PROGRESS, roomSwitchFallback(idle.copy(isLeavingRoom = true)))
    }

    @Test
    fun threadIsReportedBeforeRecording() {
        assertEquals(
            RoomSwitchFallback.THREAD,
            roomSwitchFallback(idle.copy(isThread = true, isRecording = true, isInCall = true))
        )
    }

    @Test
    fun sameRoomKeepsItsViewModels() {
        val store = ChatRoomStore()
        val first = viewModelOf(store, "room1")
        val second = viewModelOf(store, "room1")

        assertSame(first, second)
        assertFalse(first.cleared)
        assertEquals("room1", store.token)
    }

    @Test
    fun otherRoomClearsTheViewModelsOfTheOldRoom() {
        val store = ChatRoomStore()
        val old = viewModelOf(store, "room1")
        val new = viewModelOf(store, "room2")

        assertTrue(old.cleared)
        assertFalse(new.cleared)
        assertNotSame(old, new)
        assertEquals("room2", store.token)
    }

    @Test
    fun releaseClearsTheViewModelsAndForgetsTheRoom() {
        val store = ChatRoomStore()
        val old = viewModelOf(store, "room1")

        store.release()

        assertTrue(old.cleared)
        assertNull(store.token)
        assertNotSame(old, viewModelOf(store, "room1"))
    }

    @Test
    fun roomLifecycleFollowsTheActivity() {
        val activity = FakeActivity().apply { moveTo(Lifecycle.State.RESUMED) }
        val room = RoomLifecycleOwner(activity.lifecycle)

        assertEquals(Lifecycle.State.RESUMED, room.lifecycle.currentState)

        activity.moveTo(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, room.lifecycle.currentState)

        activity.moveTo(Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.RESUMED, room.lifecycle.currentState)
    }

    @Test
    fun closedRoomLifecyclePausesAndStopsItsObservers() {
        val activity = FakeActivity().apply { moveTo(Lifecycle.State.RESUMED) }
        val room = RoomLifecycleOwner(activity.lifecycle)
        val events = mutableListOf<Lifecycle.Event>()
        room.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })
        events.clear()

        room.close()

        assertEquals(
            listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY),
            events
        )
        assertEquals(Lifecycle.State.DESTROYED, room.lifecycle.currentState)
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
    }

    @Test
    fun closedRoomLifecycleIgnoresTheActivity() {
        val activity = FakeActivity().apply { moveTo(Lifecycle.State.RESUMED) }
        val room = RoomLifecycleOwner(activity.lifecycle)
        room.close()

        activity.moveTo(Lifecycle.State.CREATED)
        activity.moveTo(Lifecycle.State.DESTROYED)

        assertEquals(Lifecycle.State.DESTROYED, room.lifecycle.currentState)
    }

    @Test
    fun lifecycleObserverOfTheRoomIsPausedWhenTheRoomIsClosed() {
        val activity = FakeActivity().apply { moveTo(Lifecycle.State.RESUMED) }
        val room = RoomLifecycleOwner(activity.lifecycle)
        var paused = false
        room.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    paused = true
                }
            }
        )

        room.close()

        assertTrue(paused)
    }

    @Test
    fun newRoomLifecycleStartsInTheStateOfTheActivity() {
        val activity = FakeActivity().apply { moveTo(Lifecycle.State.RESUMED) }
        RoomLifecycleOwner(activity.lifecycle).close()

        val next = RoomLifecycleOwner(activity.lifecycle)

        assertEquals(Lifecycle.State.RESUMED, next.lifecycle.currentState)
    }
}
