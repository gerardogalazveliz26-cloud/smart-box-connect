package com.djchany.iptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.djchany.iptv.R
import com.djchany.iptv.data.EpgEntry
import com.djchany.iptv.data.LiveStream
import com.djchany.iptv.data.Prefs
import com.djchany.iptv.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class LiveActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PLAY_ID = "play_id"
        private const val CAT_FAV = "__fav"
        private const val CAT_RECENT = "__recent"
        private const val CAT_ALL = "__all"

        fun play(ctx: Context, streamId: Int) =
            ctx.startActivity(Intent(ctx, LiveActivity::class.java).putExtra(EXTRA_PLAY_ID, streamId))
    }

    private data class Cat(val id: String, val name: String, val count: Int)

    private lateinit var root: FrameLayout
    private lateinit var panes: View
    private lateinit var previewSlot: View
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvCatTitle: TextView
    private lateinit var rvCats: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var overlay: LinearLayout
    private lateinit var touchBar: View
    private lateinit var tvZap: TextView
    private lateinit var tvMsg: TextView

    private lateinit var player: ExoPlayer
    private lateinit var catAdapter: SimpleAdapter<Cat>
    private lateinit var chAdapter: SimpleAdapter<LiveStream>

    private var all: List<LiveStream> = emptyList()
    private var cats: List<Cat> = emptyList()
    private var currentCat: String? = null
    private var currentList: List<LiveStream> = emptyList()
    private var playing: LiveStream? = null
    private var focusedChannel: LiveStream? = null
    private var favIds: Set<String> = emptySet()
    private var fullscreen = false
    private var retries = 0
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val handler = Handler(Looper.getMainLooper())
    private val catDebounce by lazy { Debouncer(lifecycleScope, 220) }
    private val infoDebounce by lazy { Debouncer(lifecycleScope, 320) }
    private val hideOverlay = Runnable {
        overlay.visibility = View.GONE
        touchBar.visibility = View.GONE
    }
    private val retryRunnable = Runnable {
        if (playing != null) {
            player.prepare()
            player.play()
        }
    }
    private var numberBuffer = ""
    private val numberRunnable = Runnable {
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        tvZap.visibility = View.GONE
        if (n != null) jumpToNumber(n)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.applyOrientation(this)
        if (Repo.api == null) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_live)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        panes = findViewById(R.id.panes)
        previewSlot = findViewById(R.id.previewSlot)
        playerFrame = findViewById(R.id.playerFrame)
        playerView = findViewById(R.id.playerView)
        pbLoading = findViewById(R.id.pbLoading)
        tvStatus = findViewById(R.id.tvStatus)
        tvCatTitle = findViewById(R.id.tvCatTitle)
        rvCats = findViewById(R.id.rvCats)
        rvChannels = findViewById(R.id.rvChannels)
        overlay = findViewById(R.id.overlay)
        touchBar = findViewById(R.id.touchBar)
        tvZap = findViewById(R.id.tvZap)
        tvMsg = findViewById(R.id.tvMsg)

        player = Players.build(this)
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        playerView.resizeMode = resizeMode
        playerView.player = player
        player.addListener(playerListener)

        previewSlot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> previewSlot.post { syncPreviewBounds() } }

        setupLists()
        setupTouch()
        onBackPressedDispatcher.addCallback(this) {
            if (fullscreen) exitFullscreen() else finish()
        }
        tvMsg.setOnClickListener { load() }
        load()
    }

    // ------------------------------------------------------------------ listas

    private fun setupLists() {
        val portrait = Device.portraitUi(this)
        catAdapter = SimpleAdapter(if (portrait) R.layout.item_chip else R.layout.item_category) { v, c, _ ->
            v.findViewById<TextView>(R.id.tvName).text = c.name
            v.findViewById<TextView>(R.id.tvCount).text = c.count.toString()
            v.isActivated = c.id == currentCat
        }
        catAdapter.onFocus = { c, _ -> if (c.id != currentCat) catDebounce.run { showCategory(c.id) } }
        catAdapter.onClick = { c, _ ->
            showCategory(c.id)
            if (Device.isTv) rvChannels.post { rvChannels.focusPosition(0) }
        }
        rvCats.layoutManager = LinearLayoutManager(this, if (portrait) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

        chAdapter = SimpleAdapter(R.layout.item_channel) { v, ch, pos ->
            v.findViewById<TextView>(R.id.tvNum).text = if (ch.num > 0) ch.num.toString() else (pos + 1).toString()
            v.findViewById<ImageView>(R.id.ivLogo).loadImage(ch.icon, R.drawable.ic_tv_placeholder)
            v.findViewById<TextView>(R.id.tvName).text = ch.name
            v.findViewById<View>(R.id.tvFav).visibility = if (ch.id.toString() in favIds) View.VISIBLE else View.GONE
            val isPlaying = playing?.id == ch.id
            v.findViewById<View>(R.id.playing).visibility = if (isPlaying) View.VISIBLE else View.INVISIBLE
            v.isActivated = isPlaying
        }
        chAdapter.onFocus = { ch, _ ->
            focusedChannel = ch
            infoDebounce.run { showInfo(ch) }
        }
        chAdapter.onClick = { ch, _ -> if (playing?.id == ch.id) enterFullscreen() else play(ch) }
        chAdapter.onLongClick = { ch, _ -> toggleFav(ch) }
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = chAdapter
        rvChannels.itemAnimator = null
    }

    private fun setupTouch() {
        // Tocar el video: pantalla completa, o mostrar/ocultar controles si ya lo está.
        playerFrame.setOnClickListener {
            when {
                playing == null -> Unit
                !fullscreen -> enterFullscreen()
                overlay.visibility == View.VISIBLE -> hideOverlay.run()
                else -> showOverlay()
            }
        }
        findViewById<View>(R.id.btnExitFull).setOnClickListener { exitFullscreen() }
        findViewById<View>(R.id.btnPrevCh).setOnClickListener { zap(-1) }
        findViewById<View>(R.id.btnNextCh).setOnClickListener { zap(+1) }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleResize(); showOverlay() }
    }

    private fun load() {
        tvMsg.visibility = View.VISIBLE
        tvMsg.text = "Cargando canales…"
        lifecycleScope.launch {
            try {
                val categories = Repo.liveCategories()
                all = Repo.liveStreams()
                favIds = Prefs.favorites("live")
                val counts = all.groupingBy { it.categoryId }.eachCount()
                cats = buildList {
                    add(Cat(CAT_FAV, "★ Favoritos", favIds.size))
                    add(Cat(CAT_RECENT, "🕘 Recientes", Prefs.recentLive().size))
                    add(Cat(CAT_ALL, "Todos los canales", all.size))
                    categories.forEach { c -> add(Cat(c.id, c.name, counts[c.id] ?: 0)) }
                }
                catAdapter.items = cats
                tvMsg.visibility = View.GONE
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvMsg.text = "No se pudieron cargar los canales\n${e.message ?: ""}\n\nPulsa OK para reintentar"
                tvMsg.requestFocus()
            }
        }
    }

    private fun start() {
        val playId = intent.getIntExtra(EXTRA_PLAY_ID, -1)
        val target = all.firstOrNull { it.id == playId }
        if (target != null) {
            intent.removeExtra(EXTRA_PLAY_ID)
            val cat = target.categoryId?.takeIf { id -> cats.any { it.id == id } } ?: CAT_ALL
            showCategory(cat)
            play(target)
            enterFullscreen()
            return
        }
        val last = Prefs.lastLiveCategory?.takeIf { id -> cats.any { it.id == id } }
        val initial = last ?: when {
            favIds.isNotEmpty() -> CAT_FAV
            cats.size > 3 -> cats[3].id
            else -> CAT_ALL
        }
        showCategory(initial)
        val idx = cats.indexOfFirst { it.id == initial }
        if (Device.isTv) rvChannels.post { if (currentList.isNotEmpty()) rvChannels.focusPosition(0) else rvCats.focusPosition(idx) }
        else rvCats.scrollToPosition(idx.coerceAtLeast(0))
    }

    private fun listFor(catId: String): List<LiveStream> = when (catId) {
        CAT_ALL -> all
        CAT_FAV -> all.filter { it.id.toString() in favIds }
        CAT_RECENT -> {
            val byId = all.associateBy { it.id }
            Prefs.recentLive().mapNotNull { byId[it] }
        }
        else -> all.filter { it.categoryId == catId }
    }

    private fun showCategory(catId: String) {
        val oldIdx = cats.indexOfFirst { it.id == currentCat }
        currentCat = catId
        if (!catId.startsWith("__")) Prefs.lastLiveCategory = catId
        currentList = listFor(catId)
        chAdapter.items = currentList
        rvChannels.scrollToPosition(0)
        val cat = cats.firstOrNull { it.id == catId }
        tvCatTitle.text = "${cat?.name ?: ""}  ·  ${currentList.size}"
        catAdapter.refresh(oldIdx)
        catAdapter.refresh(cats.indexOfFirst { it.id == catId })
    }

    private fun refreshChannel(id: Int?) {
        if (id == null) return
        chAdapter.refresh(currentList.indexOfFirst { it.id == id })
    }

    private fun toggleFav(ch: LiveStream) {
        val added = Prefs.toggleFav("live", ch.id)
        favIds = Prefs.favorites("live")
        toast(if (added) "★ ${ch.name} agregado a favoritos" else "${ch.name} quitado de favoritos")
        refreshChannel(ch.id)
        val i = cats.indexOfFirst { it.id == CAT_FAV }
        if (i >= 0) {
            cats = cats.toMutableList().also { it[i] = it[i].copy(count = favIds.size) }
            catAdapter.items = cats
        }
    }

    // ------------------------------------------------------------------ reproducción

    private fun play(ch: LiveStream) {
        val old = playing?.id
        playing = ch
        retries = 0
        handler.removeCallbacks(retryRunnable)
        refreshChannel(old)
        refreshChannel(ch.id)
        findViewById<View>(R.id.tvPreviewHint).visibility = View.GONE
        val format = Prefs.liveFormat
        val item = MediaItem.Builder()
            .setUri(Repo.requireApi().liveUrl(ch.id, format))
            .setMediaId(ch.id.toString())
            .apply { if (format == "m3u8") setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        tvStatus.visibility = View.GONE
        Prefs.pushRecentLive(ch.id)
        if (!fullscreen) showInfo(ch)
        bindOverlay(ch)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            pbLoading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            when (state) {
                Player.STATE_READY -> {
                    retries = 0
                    tvStatus.visibility = View.GONE
                }
                // Algunos servidores cortan la transmisión: se vuelve a conectar solo.
                Player.STATE_ENDED -> scheduleRetry()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (playing == null) return
        if (retries >= 8) {
            pbLoading.visibility = View.GONE
            tvStatus.text = "Canal no disponible. Prueba otro o cambia el formato en Ajustes"
            tvStatus.visibility = View.VISIBLE
            return
        }
        retries++
        tvStatus.text = "Reconectando… ($retries)"
        tvStatus.visibility = View.VISIBLE
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, (1000L * retries).coerceAtMost(5000L))
    }

    private fun zap(delta: Int) {
        val list = currentList.ifEmpty { all }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == playing?.id }
        val next = if (idx < 0) list[0] else list[Math.floorMod(idx + delta, list.size)]
        play(next)
        showOverlay()
    }

    private fun jumpToNumber(n: Int) {
        val ch = all.firstOrNull { it.num == n } ?: currentList.getOrNull(n - 1)
        if (ch == null) {
            toast("No existe el canal $n")
            return
        }
        if (currentList.none { it.id == ch.id }) showCategory(CAT_ALL)
        play(ch)
        if (fullscreen) showOverlay() else rvChannels.focusPosition(currentList.indexOfFirst { it.id == ch.id })
    }

    private fun numberInput(d: Int) {
        if (numberBuffer.length >= 5) numberBuffer = ""
        numberBuffer += d
        tvZap.text = numberBuffer
        tvZap.visibility = View.VISIBLE
        handler.removeCallbacks(numberRunnable)
        handler.postDelayed(numberRunnable, 1500)
    }

    // ------------------------------------------------------------------ pantalla completa

    private fun syncPreviewBounds() {
        if (fullscreen || isFinishing) return
        val r = IntArray(2)
        val s = IntArray(2)
        root.getLocationInWindow(r)
        previewSlot.getLocationInWindow(s)
        val lp = FrameLayout.LayoutParams(previewSlot.width.coerceAtLeast(1), previewSlot.height.coerceAtLeast(1))
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = s[0] - r[0]
        lp.topMargin = s[1] - r[1]
        playerFrame.layoutParams = lp
    }

    private fun enterFullscreen() {
        if (playing == null) return
        fullscreen = true
        if (Device.isMobile) Device.landscape(this)
        Device.immersive(this, true)
        panes.visibility = View.INVISIBLE
        playerFrame.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        showOverlay()
    }

    private fun exitFullscreen() {
        fullscreen = false
        if (Device.isMobile) {
            Device.applyOrientation(this)
            Device.immersive(this, false)
        }
        handler.removeCallbacks(hideOverlay)
        overlay.visibility = View.GONE
        touchBar.visibility = View.GONE
        panes.visibility = View.VISIBLE
        syncPreviewBounds()
        playing?.let { ch ->
            showInfo(ch)
            val i = currentList.indexOfFirst { it.id == ch.id }
            if (Device.isTv) rvChannels.focusPosition(i) else if (i >= 0) rvChannels.scrollToPosition(i)
        }
    }

    private fun showOverlay() {
        if (!fullscreen) return
        playing?.let { bindOverlay(it) }
        overlay.visibility = View.VISIBLE
        if (Device.isMobile) touchBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 5000)
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

    // ------------------------------------------------------------------ guía

    private fun title(ch: LiveStream) = if (ch.num > 0) "${ch.num}  ${ch.name}" else ch.name

    private fun showInfo(ch: LiveStream) {
        val tvName = findViewById<TextView>(R.id.tvInfoName)
        val tvNow = findViewById<TextView>(R.id.tvNowTitle)
        val tvDesc = findViewById<TextView>(R.id.tvNowDesc)
        val tvNext = findViewById<TextView>(R.id.tvNext)
        val pb = findViewById<ProgressBar>(R.id.pbNow)
        tvName.text = title(ch)
        tvNow.text = ""
        tvDesc.text = ""
        tvNext.text = ""
        pb.visibility = View.INVISIBLE
        lifecycleScope.launch {
            val epg = Repo.epg(ch.id)
            val shown = if (fullscreen) playing else (focusedChannel ?: playing)
            if (shown?.id != ch.id) return@launch
            val (now, next) = Repo.nowNext(epg)
            fillEpg(now, next, tvNow, pb, tvNext)
            tvDesc.text = now?.desc ?: if (epg.isEmpty()) "Sin guía de programación" else ""
        }
    }

    private fun bindOverlay(ch: LiveStream) {
        findViewById<ImageView>(R.id.ivOvLogo).loadImage(ch.icon, R.drawable.ic_tv_placeholder)
        findViewById<TextView>(R.id.tvOvName).text = title(ch)
        val tvNow = findViewById<TextView>(R.id.tvOvNow)
        val tvNext = findViewById<TextView>(R.id.tvOvNext)
        val pb = findViewById<ProgressBar>(R.id.pbOv)
        tvNow.text = ""
        tvNext.text = ""
        pb.visibility = View.INVISIBLE
        lifecycleScope.launch {
            val epg = Repo.epg(ch.id)
            if (playing?.id != ch.id) return@launch
            val (now, next) = Repo.nowNext(epg)
            fillEpg(now, next, tvNow, pb, tvNext)
        }
    }

    private fun fillEpg(now: EpgEntry?, next: EpgEntry?, tvNow: TextView, pb: ProgressBar, tvNext: TextView) {
        if (now != null) {
            tvNow.text = "${clock(now.start)} - ${clock(now.end)}  ${now.title}"
            val p = ((System.currentTimeMillis() - now.start) * 1000 / (now.end - now.start).coerceAtLeast(1)).toInt()
            pb.progress = p.coerceIn(0, 1000)
            pb.visibility = View.VISIBLE
        } else {
            tvNow.text = ""
        }
        tvNext.text = next?.let { "A continuación: ${clock(it.start)}  ${it.title}" } ?: ""
    }

    // ------------------------------------------------------------------ control remoto

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && tvMsg.visibility != View.VISIBLE) {
            val code = event.keyCode
            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                numberInput(code - KeyEvent.KEYCODE_0); return true
            }
            if (code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
                numberInput(code - KeyEvent.KEYCODE_NUMPAD_0); return true
            }
            when (code) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    zap(+1); if (!fullscreen) refocusPlaying(); return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    zap(-1); if (!fullscreen) refocusPlaying(); return true
                }
            }
            if (fullscreen) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { zap(+1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { exitFullscreen(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showOverlay(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay()
                        return true
                    }
                    KeyEvent.KEYCODE_MENU -> { cycleResize(); return true }
                }
            } else if (code == KeyEvent.KEYCODE_MENU) {
                focusedChannel?.let { toggleFav(it) }
                return true
            }
        }
        if (fullscreen && event.action == KeyEvent.ACTION_UP &&
            event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    private fun refocusPlaying() {
        val id = playing?.id ?: return
        rvChannels.focusPosition(currentList.indexOfFirst { it.id == id })
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && playing != null && player.playbackState == Player.STATE_IDLE) {
            player.prepare()
            player.play()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(retryRunnable)
        // Libera la conexión con el servidor al salir de la pantalla.
        if (::player.isInitialized) player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}
