/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.app.Application
import com.nextcloud.talk.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Forwarding a file of another user goes through the share API. The server answers 403 when the user may not share
 * the file again, and the user has to be told that, not that "something went wrong".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShareOperationWorkerRefusalTest {

    private fun http(code: Int) =
        HttpException(Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))

    @Test
    fun forbiddenShareTellsTheUserHeMayNotShareTheFile() {
        assertEquals(R.string.nc_file_share_not_allowed, ShareOperationWorker.refusalMessage(http(403)))
    }

    @Test
    fun otherFailuresKeepTheGenericMessage() {
        assertEquals(R.string.nc_common_error_sorry, ShareOperationWorker.refusalMessage(http(500)))
        assertEquals(R.string.nc_common_error_sorry, ShareOperationWorker.refusalMessage(http(404)))
        assertEquals(R.string.nc_common_error_sorry, ShareOperationWorker.refusalMessage(IOException("offline")))
        assertEquals(R.string.nc_common_error_sorry, ShareOperationWorker.refusalMessage(null))
    }
}
