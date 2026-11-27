package com.nexumid.iptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nexumid.iptv.R
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo
import org.json.JSONArray
import org.json.JSONObject

/** Elemento reproducible: película o episodio. */
data class PlayItem(
    val type: String,        // "vod" | "ep"
    val id: String,
    val ext: String,
    val title: String,
    val image: String?,
    val seriesId: Int = 0,
    val seriesName: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
) {
    val key: String get() = "$type:$id"

    val displayTitle: String
        get() = if (type == "ep") "${seriesName ?: ""}  ·  T$season E$episode  ·  $title" else title

    fun url(): String {
        val api = Repo.requireApi()
        return if (type == "ep") api.episodeUrl(id, ext) else api.vodUrl(id, ext)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("id", id).put("ext", ext).put("title", title).put("image", image ?: "")
        .put("seriesId", seriesId).put("seriesName", seriesName ?: "").put("season", season).put("episode", episode)

    fun toResume(pos: Long, dur: Long) = Prefs.Resume(
        key = key, type = type, id = id, ext = ext, title = title, image = image, pos = pos, dur = dur,
        time = System.currentTimeMillis(), seriesId = seriesId, seriesName = seriesName, season = season, episode = episode,
    )

    companion object {
        fun from(o: JSONObject) = PlayItem(
            type = o.optString("type"), id = o.optString("id"), ext = o.optString("ext"),
            title = o.optString("title"), image = o.optString("image").ifBlank { null },
            seriesId = o.optInt("seriesId"), seriesName = o.optString("seriesName").ifBlank { null },
            season = o.optInt("season"), episode = o.optInt("episode"),
        )

        fun fromResume(r: Prefs.Resume) = PlayItem(r.type, r.id, r.ext, r.title, r.image, r.seriesId, r.seriesName, r.season, r.episode)
    }
}

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_FROM_START = "from_start"

        fun start(ctx: Context, items: List<PlayItem>, index: Int, fromStart: Boolean = false) {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_ITEMS, arr.toString())
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_FROM_START, fromStart)
            )
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var tvTitle: TextView
    private lateinit var tvSeek: TextView
    private lateinit var player: ExoPlayer
    private var items: List<PlayItem> = emptyList()
    private var lastIndex = 0
    private var errors = 0
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val handler = Handler(Looper.getMainLooper())
    private val guardTick: Runnable = object : Runnable {
        override fun run() {
            ActivationGuard.check(this@PlayerActivity)
            handler.postDelayed(this, ActivationGuard.INTERVAL_MS)
        }
    }
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }
    private val hideSeek = Runnable { tvSeek.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.landscape(this)
        Device.immersive(this, true)
        if (Repo.api == null) {
            finish(); return
        }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.playerView)
        tvTitle = findViewById(R.id.tvTitle)
        tvSeek = findViewById(R.id.tvSeek)

        items = try {
            val a = JSONArray(intent.getStringExtra(EXTRA_ITEMS) ?: "[]")
            (0 until a.length()).map { PlayItem.from(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        if (items.isEmpty()) {
            finish(); return
        }
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
        lastIndex = index

        player = Players.build(this)
        playerView.player = player
        playerView.useController = true
        playerView.controllerShowTimeoutMs = 4000
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.setShowSubtitleButton(true)
        playerView.setShowNextButton(items.size > 1)
        playerView.setShowPreviousButton(items.size > 1)
        playerView.resizeMode = resizeMode
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> tvTitle.visibility = visibility }
        )

        val mediaItems = items.map { item ->
            MediaItem.Builder()
                .setUri(item.url())
                .setMediaId(item.key)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.displayTitle).build())
                .build()
        }
        val resume = if (intent.getBooleanExtra(EXTRA_FROM_START, false)) null else Prefs.resume(items[index].key)
        val startPos = resume?.takeIf { it.pos > 30_000 && (it.dur <= 0 || it.pos < it.dur - 60_000) }?.pos ?: 0L
        if (startPos > 0) toast("Continuando desde ${duration(startPos)}")

        player.addListener(listener)
        player.setMediaItems(mediaItems, index, startPos)
        player.prepare()
        player.play()
        tvTitle.text = items[index].displayTitle
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val idx = player.currentMediaItemIndex
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                items.getOrNull(lastIndex)?.let { Prefs.removeResume(it.key) }
                if (!Prefs.autoplayNext) player.pause()
            }
            lastIndex = idx
            items.getOrNull(idx)?.let {
                tvTitle.text = it.displayTitle
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) toast("Reproduciendo: ${it.displayTitle}")
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) errors = 0
            if (state == Player.STATE_ENDED) {
                items.getOrNull(player.currentMediaItemIndex)?.let { finished(it) }
                finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (errors < 3) {
                errors++
                toast("Error de reproducción, reintentando… ($errors)")
                handler.postDelayed({ player.prepare(); player.play() }, 1500L * errors)
            } else {
                toast("No se pudo reproducir este contenido")
            }
        }
    }

    /** Al terminar un episodio deja listo el siguiente en "Seguir viendo". */
    private fun finished(item: PlayItem) {
        Prefs.removeResume(item.key)
        if (item.type == "ep") {
            val next = items.getOrNull(items.indexOf(item) + 1)
            if (next != null) Prefs.saveResume(next.toResume(1, 0))
        }
    }

    private fun saveProgress() {
        if (!::player.isInitialized) return
        val item = items.getOrNull(player.currentMediaItemIndex) ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0 || pos < 15_000) return
        if (pos > dur * 0.93) finished(item) else Prefs.saveResume(item.toResume(pos, dur))
    }

    private fun seekBy(ms: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + ms).coerceIn(0, dur)
        player.seekTo(target)
        val sign = if (ms >= 0) "+" else "−"
        val total = if (dur == Long.MAX_VALUE) "" else " / ${duration(dur)}"
        tvSeek.text = "$sign${kotlin.math.abs(ms) / 1000} s   ${duration(target)}$total"
        tvSeek.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, 1200)
    }

    private fun cycleResize() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = resizeMode
        toast(
            when (resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Imagen: estirar"
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Imagen: zoom"
                else -> "Imagen: ajustar"
            }
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = if (event.repeatCount > 3) 30_000L else 10_000L
            if (!playerView.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-step); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(step); return true }
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    if (player.isPlaying) player.pause() else player.play(); playerView.showController(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem(); return true }
                KeyEvent.KEYCODE_MENU -> { cycleResize(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        handler.post(guardTick)
        if (::player.isInitialized) handler.postDelayed(saver, 10_000)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(guardTick)
        handler.removeCallbacks(saver)
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}
