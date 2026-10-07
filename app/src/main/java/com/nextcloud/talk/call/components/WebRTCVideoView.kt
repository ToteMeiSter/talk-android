/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.call.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.SurfaceViewRenderer

@Composable
fun WebRTCVideoView(mediaStream: MediaStream, eglBase: EglBase?) {
    // The sink is bound to the track when the view is created, so a new stream (a participant who came back with
    // the same session) needs a new view; otherwise the old, closed track stays attached and the tile stays empty.
    key(mediaStream) {
        WebRTCVideoViewForStream(mediaStream, eglBase)
    }
}

@Composable
private fun WebRTCVideoViewForStream(mediaStream: MediaStream, eglBase: EglBase?) {
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBase?.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setMirror(false)
                mediaStream.videoTracks?.firstOrNull()?.addSink(this)
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = {
            mediaStream.videoTracks?.firstOrNull()?.removeSink(it)
            it.release()
        }
    )
}
