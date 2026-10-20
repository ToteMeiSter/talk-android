/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSwitchTrackerTest {
    private val wifi = 1L
    private val mobile = 2L
    private val newWifiConnection = 3L

    private val tracker = NetworkSwitchTracker()

    @Test
    fun firstNetworkIsNotASwitch() {
        assertFalse(tracker.onValidated(wifi))
    }

    @Test
    fun sameNetworkReportedAgainIsNotASwitch() {
        tracker.onValidated(wifi)

        // also what a loss and return of VALIDATED on the same network looks like
        assertFalse(tracker.onValidated(wifi))
        assertFalse(tracker.onValidated(wifi))
    }

    @Test
    fun otherNetworkIsASwitch() {
        tracker.onValidated(wifi)

        assertTrue(tracker.onValidated(mobile))
        assertFalse(tracker.onValidated(mobile))
    }

    @Test
    fun newConnectionOfTheSameWifiIsASwitch_asAndroidGivesItANewHandle() {
        tracker.onValidated(wifi)

        assertTrue(tracker.onValidated(newWifiConnection))
    }

    @Test
    fun switchBackAndForthIsASwitchEachTime() {
        tracker.onValidated(wifi)

        assertTrue(tracker.onValidated(mobile))
        assertTrue(tracker.onValidated(wifi))
        assertTrue(tracker.onValidated(mobile))
    }
}
