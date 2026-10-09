/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetDiagTest {
    @Test
    fun firstTickHasNoPreviousValues() {
        val line = NetDiag.Ticker().next(uptimeMs = 1_000, elapsedMs = 1_500)

        assertEquals(
            "tick uptime=1000 elapsed=1500 sinceLastTick=-1 ms deepSleep=500 ms deepSleepSinceLastTick=-1 ms",
            line
        )
    }

    @Test
    fun frozenProcessShowsGapWithoutDeepSleep() {
        val ticker = NetDiag.Ticker()
        ticker.next(uptimeMs = 100_000, elapsedMs = 100_000)

        // the process ran again after 95 s, the device did not sleep: uptime kept pace with elapsedRealtime
        val line = ticker.next(uptimeMs = 195_000, elapsedMs = 195_000)

        assertTrue(line, line.contains("sinceLastTick=95000 ms"))
        assertTrue(line, line.contains("deepSleepSinceLastTick=0 ms"))
    }

    @Test
    fun deviceSleepShowsGrowthOfDeepSleep() {
        val ticker = NetDiag.Ticker()
        ticker.next(uptimeMs = 100_000, elapsedMs = 100_000)

        val line = ticker.next(uptimeMs = 110_000, elapsedMs = 160_000)

        assertTrue(line, line.contains("deepSleepSinceLastTick=50000 ms"))
    }

    @Test
    fun capabilitiesLineIsWrittenOnlyOnChange() {
        val filter = NetDiag.CapabilitiesChangeFilter()

        assertTrue(filter.changed("100", validated = true, notSuspended = true))
        assertFalse(filter.changed("100", validated = true, notSuspended = true))
        assertTrue(filter.changed("100", validated = true, notSuspended = false))
        assertTrue(filter.changed("101", validated = true, notSuspended = true))
    }

    @Test
    fun forgottenNetworkIsReportedAgain() {
        val filter = NetDiag.CapabilitiesChangeFilter()
        filter.changed("100", validated = true, notSuspended = true)

        filter.forget("100")

        assertTrue(filter.changed("100", validated = true, notSuspended = true))
    }

    @Test
    fun missingNetworkHasName() {
        assertEquals("none", NetDiag.netId(null))
    }
}
