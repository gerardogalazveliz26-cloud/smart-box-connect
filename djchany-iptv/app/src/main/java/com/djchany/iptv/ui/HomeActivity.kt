package com.djchany.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.djchany.iptv.R
import com.djchany.iptv.data.LiveStream
import com.djchany.iptv.data.Prefs
import com.djchany.iptv.data.Repo
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var tvAccount: TextView
    private lateinit var tvWarn: TextView
    private lateinit var resumeAdapter: SimpleAdapter<Prefs.Resume>
    private lateinit var recentAdapter: SimpleAdapter<LiveStream>

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

        tile(R.id.tileLive, R.drawable.ic_live, "TV en vivo", "Canales y deportes") {
            startActivity(Intent(this, LiveActivity::class.java))
        }
        tile(R.id.tileMovies, R.drawable.ic_movie, "Películas", "Estrenos y clásicos") {
            BrowseActivity.open(this, BrowseActivity.TYPE_VOD)
        }
        tile(R.id.tileSeries, R.drawable.ic_series, "Series", "Temporadas completas") {
            BrowseActivity.open(this, BrowseActivity.TYPE_SERIES)
        }
        tile(R.id.tileSearch, R.drawable.ic_search, "Buscar") { SearchActivity.open(this, false) }
        tile(R.id.tileFav, R.drawable.ic_star, "Favoritos") { SearchActivity.open(this, true) }
        tile(R.id.tileAccounts, R.drawable.ic_account, "Listas") { LoginActivity.manage(this) }
        tile(R.id.tileSettings, R.drawable.ic_settings, "Ajustes") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
        recentAdapter = SimpleAdapter(R.layout.item_channel_card, 1.08f) { v, ch, _ ->
            v.findViewById<ImageView>(R.id.ivLogo).loadImage(ch.icon, R.drawable.ic_tv_placeholder)
            v.findViewById<TextView>(R.id.tvName).text = ch.name
        }
        recentAdapter.onClick = { ch, _ -> LiveActivity.play(this, ch.id) }
        row(R.id.rvResume, resumeAdapter)
        row(R.id.rvRecent, recentAdapter)

        findViewById<View>(R.id.tileLive).requestFocus()
        loadAccount()
        // Precarga en segundo plano para que TV en vivo abra al instante.
        lifecycleScope.launch { runCatching { Repo.liveCategories(); Repo.liveStreams() } }
        Updater.check(this, false)
    }

    override fun onResume() {
        super.onResume()
        if (::resumeAdapter.isInitialized) refreshRows()
    }

    private fun tile(id: Int, icon: Int, title: String, sub: String? = null, action: () -> Unit) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.tileText).text = title
        (v.findViewById<View>(R.id.tileSub) as? TextView)?.text = sub
        v.setOnClickListener { action() }
        v.focusZoom(1.05f)
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
            findViewById<View>(R.id.tvRecent).visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun loadAccount() {
        val p = Prefs.current
        tvAccount.text = p?.name ?: ""
        lifecycleScope.launch {
            val acc = Repo.account ?: runCatching { Repo.requireApi().login() }.getOrNull()?.also { Repo.account = it }
            if (acc == null) {
                tvAccount.text = "${p?.name ?: ""}\nSin conexión con el servidor"
                return@launch
            }
            val exp = acc.expDate?.let { "Vence: ${date(it)}" } ?: "Sin vencimiento"
            tvAccount.text = "${p?.name ?: acc.username} · ${acc.username}\n$exp · Conexiones ${acc.activeCons}/${acc.maxCons}"
            val daysLeft = acc.expDate?.let { (it - System.currentTimeMillis()) / 86_400_000L }
            when {
                !acc.status.equals("Active", true) -> warn("Tu cuenta está ${acc.status}. Contacta a tu proveedor.")
                daysLeft != null && daysLeft in 0..5 -> warn("Tu cuenta vence en $daysLeft día(s). ¡Renueva para no perder la señal!")
                else -> tvWarn.visibility = View.GONE
            }
        }
    }

    private fun warn(msg: String) {
        tvWarn.text = msg
        tvWarn.visibility = View.VISIBLE
    }
}
