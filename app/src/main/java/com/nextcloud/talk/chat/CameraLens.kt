/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat

import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

internal fun cameraSelectorFor(lens: Int): CameraSelector = CameraSelector.Builder().requireLensFacing(lens).build()

internal fun oppositeLens(lens: Int): Int =
    if (lens == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT

/**
 * The lens to bind: [preferred] when the device has it, otherwise the other one.
 */
internal fun resolveLens(provider: ProcessCameraProvider, preferred: Int): Int =
    if (provider.hasCamera(cameraSelectorFor(preferred))) preferred else oppositeLens(preferred)
