package com.nexumid.iptv.ui

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.nexumid.iptv.data.Net
import com.nexumid.iptv.data.Prefs

/**
 * Renderizadores con audio FFmpeg incluido. Muchos canales IPTV usan AC-3/E-AC-3 (Dolby), MP2 o DTS,
 * que varios celulares y TV Box no decodifican: sin esto, esos canales se ven pero no se escuchan.
 * Solo se agrega audio: el video sigue usando el decodificador por hardware del equipo.
 */
@OptIn(UnstableApi::class)
private class NexumRenderersFactory(context: Context, private val ffmpegFirst: Boolean) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        if (!FfmpegLibrary.isAvailable()) return
        val ffmpeg = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
        if (ffmpegFirst) out.add(0, ffmpeg) else out.add(ffmpeg)
    }
}

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
        val renderers = NexumRenderersFactory(ctx, ffmpegFirst = Prefs.audioMode == 0)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(load)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { playWhenReady = true }
    }
}
