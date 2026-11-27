package com.nexumid.iptv.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexumid.iptv.R
import com.nexumid.iptv.data.Activation
import com.nexumid.iptv.data.LiveStream
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo
import kotlinx.coroutines.launch

/** Inicio con el diseño NexumID: tarjetas de vidrio, accesos rápidos y canales recientes. */
class HomeActivity : AppCompatActivity() {

    private lateinit var tvAccount: TextView
    private lateinit var tvWarn: TextView
    private lateinit var resumeAdapter: SimpleAdapter<Prefs.Resume>
    private lateinit var recentAdapter: SimpleAdapter<LiveStream>

    /** Novedades que se muestran en la campana. */
    private val notices = linkedMapOf<String, String>()
    private var pendingUpdate: Updater.Info? = null

    // Degradados de las tarjetas de canales, como en el diseño.
    private val palettes = listOf(
        intArrayOf(0xFF6A2BD9.toInt(), 0xFF23104F.toInt()),
        intArrayOf(0xFFD3162F.toInt(), 0xFF4E0812.toInt()),
        intArrayOf(0xFF1E6FE0.toInt(), 0xFF0A2459.toInt()),
        intArrayOf(0xFF5B2BD0.toInt(), 0xFF1A0E47.toInt()),
        intArrayOf(0xFF0F8FB8.toInt(), 0xFF07324A.toInt()),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.applyOrientation(this)
        if (Repo.api == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_home)
        tvAccount = findViewById(R.id.tvAccount)
        tvWarn = findViewById(R.id.tvWarn)

        hero(R.id.tileLive, R.drawable.ic_hero_live, R.drawable.hero_live, 0.70f, "TV en vivo", "Canales y deportes") {
            startActivity(Intent(this, LiveActivity::class.java))
        }
        hero(R.id.tileMovies, R.drawable.ic_hero_movie, R.drawable.hero_movies, 0.50f, "Películas", "Estrenos y clásicos") {
            BrowseActivity.open(this, BrowseActivity.TYPE_VOD)
        }
        hero(R.id.tileSeries, R.drawable.ic_hero_series, R.drawable.hero_series, 0.62f, "Series", "Temporadas completas") {
            BrowseActivity.open(this, BrowseActivity.TYPE_SERIES)
        }
        tile(R.id.tileSearch, R.drawable.ic_search, "Buscar") { SearchActivity.open(this, false) }
        tile(R.id.tileFav, R.drawable.ic_star, "Favoritos") { SearchActivity.open(this, true) }
        tile(R.id.tileAccounts, R.drawable.ic_list, "Listas") { LoginActivity.manage(this) }
        tile(R.id.tileSettings, R.drawable.ic_settings, "Ajustes") { openSettings() }

        findViewById<View>(R.id.btnGear).setOnClickListener { openSettings() }
        findViewById<View>(R.id.btnBell).setOnClickListener { showNotices() }

        setupRows()
        findViewById<View>(R.id.tileLive).requestFocus()
        loadAccount()
        // Precarga en segundo plano para que TV en vivo abra al instante.
        lifecycleScope.launch { runCatching { Repo.liveCategories(); Repo.liveStreams() } }
        Updater.check(this, false)
        lifecycleScope.launch {
            pendingUpdate = Updater.pending()
            pendingUpdate?.let { addNotice("update", "Nueva versión ${it.name} disponible. Toca para actualizar.") }
        }
        refreshActivation()
    }

    override fun onResume() {
        super.onResume()
        if (!::resumeAdapter.isInitialized) return
        ActivationGuard.check(this)
        refreshRows()
        updateWifi()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    // ------------------------------------------------------------------ tarjetas

    private fun hero(id: Int, icon: Int, image: Int, fraction: Float, title: String, sub: String, action: () -> Unit) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.heroIcon).setImageResource(icon)
        v.findViewById<HeroImageView>(R.id.heroImage).apply {
            setImageResource(image)
            this.fraction = fraction
        }
        v.findViewById<TextView>(R.id.heroTitle).text = title
        v.findViewById<TextView>(R.id.heroSub).text = sub
        v.findViewById<View>(R.id.heroCard).clipToOutline = true
        v.setOnClickListener { action() }
        v.focusZoom(1.04f)
    }

    private fun tile(id: Int, icon: Int, title: String, action: () -> Unit) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.tileText).text = title
        v.setOnClickListener { action() }
        v.focusZoom(1.05f)
    }

    private fun setupRows() {
        val portrait = Device.portraitUi(this)
        val cardWidth = if (portrait) {
            // Dos tarjetas por pantalla en el celular, como en el diseño.
            ((resources.displayMetrics.widthPixels - dp(24)) / 2) - dp(4)
        } else dp(196)

        recentAdapter = SimpleAdapter(R.layout.item_recent, 1.06f) { v, ch, _ ->
            v.layoutParams = v.layoutParams.also { it.width = cardWidth }
            v.findViewById<View>(R.id.recentCard).clipToOutline = true
            val colors = palettes[Math.floorMod(ch.id, palettes.size)]
            v.findViewById<View>(R.id.recentTop).background =
                GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
            v.findViewById<ImageView>(R.id.ivLogo).loadImage(ch.icon, R.drawable.ic_tv_placeholder)
            v.findViewById<TextView>(R.id.tvName).text = if (ch.num > 0) "%02d | %s".format(ch.num, ch.name) else ch.name
            val pb = v.findViewById<ProgressBar>(R.id.pbNow)
            pb.progress = 0
            v.tag = ch.id
            lifecycleScope.launch {
                val (now, _) = Repo.nowNext(Repo.epg(ch.id))
                if (v.tag != ch.id) return@launch
                pb.progress = now?.let {
                    ((System.currentTimeMillis() - it.start) * 1000 / (it.end - it.start).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
                } ?: 0
            }
        }
        recentAdapter.onClick = { ch, _ -> LiveActivity.play(this, ch.id) }

        resumeAdapter = SimpleAdapter(R.layout.item_poster_row, 1.08f) { v, r, _ ->
            v.findViewById<ImageView>(R.id.ivPoster).loadImage(r.image, R.drawable.ic_poster_placeholder)
            v.findViewById<TextView>(R.id.tvName).text =
                if (r.type == "ep") "${r.seriesName ?: r.title}\nT${r.season} · E${r.episode}" else r.title
            val pb = v.findViewById<ProgressBar>(R.id.pbResume)
            if (r.dur > 0) {
                pb.visibility = View.VISIBLE
                pb.progress = (r.pos * 1000 / r.dur).toInt()
            } else pb.visibility = View.GONE
        }
        resumeAdapter.onClick = { r, _ ->
            if (r.type == "ep" && r.seriesId != 0) SeriesDetailActivity.open(this, r.seriesId, r.seriesName ?: r.title, r.image)
            else PlayerActivity.start(this, listOf(PlayItem.fromResume(r)), 0)
        }
        resumeAdapter.onLongClick = { r, _ ->
            Prefs.removeResume(r.key)
            toast("Quitado de \"Seguir viendo\"")
            refreshRows()
        }
        row(R.id.rvRecent, recentAdapter)
        row(R.id.rvResume, resumeAdapter)
    }

    private fun <T> row(id: Int, a: SimpleAdapter<T>) {
        findViewById<RecyclerView>(id).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity, RecyclerView.HORIZONTAL, false)
            adapter = a
            itemAnimator = null
        }
    }

    private fun refreshRows() {
        val res = Prefs.resumes().take(20)
        resumeAdapter.items = res
        findViewById<View>(R.id.tvResume).visibility = if (res.isEmpty()) View.GONE else View.VISIBLE
        lifecycleScope.launch {
            val ids = Prefs.recentLive()
            val list = if (ids.isEmpty()) emptyList() else runCatching {
                val byId = Repo.liveStreams().associateBy { it.id }
                ids.mapNotNull { byId[it] }.take(15)
            }.getOrDefault(emptyList())
            recentAdapter.items = list
            findViewById<View>(R.id.recentHeader).visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ barra superior

    private fun updateWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val online = cm.activeNetworkInfo?.isConnected == true
        findViewById<ImageView>(R.id.ivWifi).alpha = if (online) 1f else 0.3f
        if (!online) addNotice("offline", "Sin conexión a internet.") else removeNotice("offline")
    }

    private fun addNotice(key: String, text: String) {
        notices[key] = text
        findViewById<View>(R.id.bellDot).visibility = View.VISIBLE
    }

    private fun removeNotice(key: String) {
        notices.remove(key)
        if (notices.isEmpty()) findViewById<View>(R.id.bellDot).visibility = View.GONE
    }

    private fun showNotices() {
        if (notices.isEmpty()) {
            toast("No tienes novedades")
            return
        }
        val keys = notices.keys.toList()
        AlertDialog.Builder(this)
            .setTitle("Novedades")
            .setItems(keys.map { notices[it] }.toTypedArray()) { _, which ->
                if (keys[which] == "update") pendingUpdate?.let { Updater.install(this, it) }
            }
            .setPositiveButton("Cerrar", null)
            .show()
        findViewById<View>(R.id.bellDot).visibility = View.GONE
    }

    // ------------------------------------------------------------------ cuenta y activación

    private fun loadAccount() {
        val p = Prefs.current
        tvAccount.text = p?.name ?: ""
        lifecycleScope.launch {
            val acc = Repo.account ?: runCatching { Repo.requireApi().login() }.getOrNull()?.also { Repo.account = it }
            if (acc == null) {
                tvAccount.text = "${p?.name ?: ""} · sin conexión con el servidor"
                return@launch
            }
            tvAccount.text = p?.name ?: acc.username
            val daysLeft = acc.expDate?.let { (it - System.currentTimeMillis()) / 86_400_000L }
            when {
                !acc.status.equals("Active", true) -> warn("Tu cuenta está ${acc.status}. Contacta a tu proveedor.")
                daysLeft != null && daysLeft in 0..5 -> {
                    warn("Tu cuenta vence en $daysLeft día(s). ¡Renueva para no perder la señal!")
                    addNotice("expiry", "Tu cuenta vence en $daysLeft día(s).")
                }
                else -> tvWarn.visibility = View.GONE
            }
        }
    }

    private fun warn(msg: String) {
        tvWarn.text = msg
        tvWarn.visibility = View.VISIBLE
    }

    /** Si el equipo fue activado a distancia, aplica cambios o bajas hechos desde el panel. */
    private fun refreshActivation() {
        if (!Prefs.activated) return
        lifecycleScope.launch {
            when (val r = Activation.fetch()) {
                is Activation.Result.Found -> {
                    if (r.payload.updated != Prefs.activationStamp) {
                        val changed = Activation.apply(r.payload)
                        toast("Tu proveedor actualizó tu lista")
                        if (changed) {
                            Repo.account = null
                            recreate()
                            return@launch
                        }
                    }
                    r.payload.message?.let { msg -> addNotice("provider", msg) }
                }
                is Activation.Result.NotFound -> {
                    Activation.revoke()
                    if (Repo.api == null) {
                        toast("Este equipo fue desactivado por tu proveedor")
                        startActivity(
                            Intent(this@HomeActivity, LoginActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        finish()
                    } else recreate()
                }
                is Activation.Result.Error -> Unit
            }
        }
    }
}
