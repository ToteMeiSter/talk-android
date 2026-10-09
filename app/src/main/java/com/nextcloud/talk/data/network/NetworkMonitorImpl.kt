/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Julius Linus <juliuslinus1@gmail.com>
 * SPDX-FileCopyrightText: 2024 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.nextcloud.talk.logger.AppLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks connectivity for the whole process.
 *
 * The upstream callback is shared eagerly rather than while subscribed, because [isOnline] is read
 * synchronously via its value from contexts that never collect it — background workers handling a
 * push notification, for instance, run without any UI collector. A while-subscribed flow would
 * keep returning the value captured when this singleton was constructed, which stays wrong for as
 * long as the process lives.
 */
@Singleton
class NetworkMonitorImpl @Inject constructor(private val context: Context) : NetworkMonitor {

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!

    override val isOnlineLiveData: LiveData<Boolean>
        get() = isOnline.asLiveData()

    override val isOnline: StateFlow<Boolean> get() = _isOnline

    private val switchTracker = NetworkSwitchTracker()
    private val _networkSwitches = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val networkSwitches: SharedFlow<Unit> get() = _networkSwitches

    private val capabilitiesFilter = NetDiag.CapabilitiesChangeFilter()

    private val _isOnline: StateFlow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val connected = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                val notSuspended = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
                )
                val netId = NetDiag.netId(network)
                if (capabilitiesFilter.changed(netId, connected, notSuspended)) {
                    NetDiag.event(
                        "onCapabilitiesChanged net=$netId validated=$connected notSuspended=$notSuspended",
                        context
                    )
                }
                trySend(connected)
                Log.d(TAG, "Network status changed: $connected")
                if (connected && switchTracker.onValidated(network.networkHandle)) {
                    Log.d(TAG, "Network switched to ${network.networkHandle}")
                    _networkSwitches.tryEmit(Unit)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                trySend(false)
                Log.d(TAG, "Network status: onUnavailable")
                NetDiag.event("onUnavailable", context)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                trySend(false)
                Log.d(TAG, "Network status: onLost")
                capabilitiesFilter.forget(NetDiag.netId(network))
                NetDiag.event("onLost net=${NetDiag.netId(network)}", context)
            }

            // API 29+: the system tells that it blocks (true) or lets through (false) the network of this UID.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                super.onBlockedStatusChanged(network, blocked)
                NetDiag.event("onBlockedStatusChanged net=${NetDiag.netId(network)} blocked=$blocked", context)
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                trySend(true)
                Log.d(TAG, "Network status: onAvailable")
                NetDiag.event("onAvailable net=${NetDiag.netId(network)}", context)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.stateIn(
        CoroutineScope(Dispatchers.IO),
        SharingStarted.Eagerly,
        isCurrentlyConnected()
    )

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val ticker = NetDiag.Ticker()
            while (isActive) {
                NetDiag.event(ticker.next(), context)
                delay(NetDiag.TICK_INTERVAL_MS)
            }
        }
    }

    private fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private val TAG = NetworkMonitorImpl::class.java.simpleName
    }
}
