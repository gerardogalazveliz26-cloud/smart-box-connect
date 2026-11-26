package com.djchany.iptv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.djchany.iptv.data.Net
import com.djchany.iptv.data.Prefs

object Players {
    @OptIn(UnstableApi::class)
    fun build(ctx: Context): ExoPlayer {
        val http = OkHttpDataSource.Factory(Net.client).setUserAgent(Prefs.userAgent)
        val dataSource = DefaultDataSource.Factory(ctx, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)
        val (minBuf, maxBuf) = when (Prefs.bufferMode) {
            1 -> 30_000 to 90_000
            2 -> 60_000 to 180_000
            else -> 15_000 to 50_000
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, 2_000, 4_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(load)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { playWhenReady = true }
    }
}
