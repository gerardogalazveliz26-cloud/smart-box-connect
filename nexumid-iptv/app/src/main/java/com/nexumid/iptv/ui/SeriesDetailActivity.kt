package com.nexumid.iptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexumid.iptv.R
import com.nexumid.iptv.data.Episode
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.Repo
import com.nexumid.iptv.data.SeriesInfo
import kotlinx.coroutines.launch

class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context, id: Int, name: String, image: String?) = ctx.startActivity(
            Intent(ctx, SeriesDetailActivity::class.java).putExtra("id", id).putExtra("name", name).putExtra("image", image)
        )
    }

    private var id = 0
    private lateinit var name: String
    private var image: String? = null
    private var info: SeriesInfo? = null
    private var season = 0
    private lateinit var btnContinue: Button
    private lateinit var btnFav: Button
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var seasonAdapter: SimpleAdapter<Int>
    private lateinit var episodeAdapter: SimpleAdapter<Episode>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.applyOrientation(this)
        if (Repo.api == null) {
            finish(); return
        }
        setContentView(R.layout.activity_series_detail)
        id = intent.getIntExtra("id", 0)
        name = intent.getStringExtra("name") ?: ""
        image = intent.getStringExtra("image")
        btnContinue = findViewById(R.id.btnContinue)
        btnFav = findViewById(R.id.btnFav)
        rvSeasons = findViewById(R.id.rvSeasons)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        findViewById<TextView>(R.id.tvName).text = name
        findViewById<ImageView>(R.id.ivBackdrop).loadImage(image, R.drawable.ic_poster_placeholder)

        btnFav.setOnClickListener {
            val added = Prefs.toggleFav("series", id)
            toast(if (added) "★ Agregada a favoritos" else "Quitada de favoritos")
            updateFav()
        }
        btnContinue.setOnClickListener { continueWatching() }
        listOf(btnContinue, btnFav).forEach { it.focusZoom(1.04f) }
        updateFav()

        seasonAdapter = SimpleAdapter(R.layout.item_season) { v, s, _ ->
            (v as TextView).text = "Temporada $s"
            v.isActivated = s == season
        }
        seasonAdapter.onFocus = { s, _ -> if (s != season) showSeason(s) }
        seasonAdapter.onClick = { s, _ ->
            showSeason(s)
            rvEpisodes.focusPosition(0)
        }
        rvSeasons.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvSeasons.adapter = seasonAdapter
        rvSeasons.itemAnimator = null

        episodeAdapter = SimpleAdapter(R.layout.item_episode) { v, e, _ ->
            v.findViewById<ImageView>(R.id.ivThumb).loadImage(e.image ?: info?.cover ?: image, R.drawable.ic_tv_placeholder)
            v.findViewById<TextView>(R.id.tvName).text = "${e.num}. ${cleanTitle(e.title)}"
            v.findViewById<TextView>(R.id.tvMeta).text = listOfNotNull(
                "T${e.season} E${e.num}", minutes(e.durationSecs).ifBlank { null }
            ).joinToString("  ·  ")
            v.findViewById<TextView>(R.id.tvPlot).text = e.plot ?: ""
            val r = Prefs.resume("ep:${e.id}")
            v.findViewById<ProgressBar>(R.id.pbResume).apply {
                if (r != null && r.dur > 0) {
                    visibility = View.VISIBLE
                    progress = (r.pos * 1000 / r.dur).toInt()
                } else visibility = View.GONE
            }
        }
        episodeAdapter.onClick = { e, _ -> playEpisode(e, false) }
        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = episodeAdapter
        rvEpisodes.itemAnimator = null

        load()
    }

    override fun onResume() {
        super.onResume()
        if (::episodeAdapter.isInitialized && info != null) {
            episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount, "state")
            updateContinue()
        }
    }

    private fun load() {
        val pb = findViewById<ProgressBar>(R.id.pbLoading)
        pb.visibility = View.VISIBLE
        lifecycleScope.launch {
            val i = runCatching { Repo.requireApi().seriesInfo(id) }.getOrNull()
            pb.visibility = View.GONE
            if (i == null || i.episodes.isEmpty()) {
                findViewById<TextView>(R.id.tvPlot).text = "No se encontraron episodios para esta serie."
                btnFav.requestFocus()
                return@launch
            }
            info = i
            if (i.name.isNotBlank()) {
                name = i.name
                findViewById<TextView>(R.id.tvName).text = i.name
            }
            if (image == null) image = i.cover
            findViewById<ImageView>(R.id.ivBackdrop).loadImage(i.backdrop ?: i.cover ?: image, R.drawable.ic_poster_placeholder)
            findViewById<TextView>(R.id.tvMeta).text = listOfNotNull(
                i.year, i.genre, rating(i.rating), "${i.seasons.size} temporada(s)"
            ).joinToString("  ·  ")
            findViewById<TextView>(R.id.tvPlot).text = i.plot ?: ""
            findViewById<TextView>(R.id.tvCredits).text = i.cast?.let { "Reparto: $it" } ?: ""
            seasonAdapter.items = i.seasons
            val last = lastWatched()
            showSeason(last?.season ?: i.seasons.first())
            updateContinue()
            if (btnContinue.visibility == View.VISIBLE) btnContinue.requestFocus() else rvEpisodes.focusPosition(0)
        }
    }

    private fun lastWatched(): Prefs.Resume? = Prefs.resumes().firstOrNull { it.type == "ep" && it.seriesId == id }

    private fun showSeason(s: Int) {
        val i = info ?: return
        val old = seasonAdapter.items.indexOf(season)
        season = s
        episodeAdapter.items = i.episodes.filter { it.season == s }
        seasonAdapter.refresh(old)
        seasonAdapter.refresh(seasonAdapter.items.indexOf(s))
    }

    private fun updateContinue() {
        val r = lastWatched()
        if (r != null && info?.episodes?.any { it.id == r.id } == true) {
            btnContinue.text = if (r.pos > 30_000) "▶  Continuar T${r.season}:E${r.episode} (${duration(r.pos)})"
            else "▶  Ver T${r.season}:E${r.episode}"
            btnContinue.visibility = View.VISIBLE
        } else if (info != null) {
            btnContinue.text = "▶  Ver desde el episodio 1"
            btnContinue.visibility = View.VISIBLE
        }
    }

    private fun continueWatching() {
        val i = info ?: return
        val r = lastWatched()
        val ep = i.episodes.firstOrNull { it.id == r?.id } ?: i.episodes.first()
        playEpisode(ep, false)
    }

    private fun cleanTitle(t: String): String {
        // Muchos paneles usan "Serie - S01E02 - Título": se deja solo el título.
        val m = Regex("(?i)s\\d{1,2}\\s*e\\d{1,3}\\s*[-–:]?\\s*(.*)$").find(t)
        val rest = m?.groupValues?.getOrNull(1)?.trim()
        return if (!rest.isNullOrBlank()) rest else t
    }

    private fun playEpisode(e: Episode, fromStart: Boolean) {
        val i = info ?: return
        val list = i.episodes.map {
            PlayItem("ep", it.id, it.ext, cleanTitle(it.title), i.cover ?: image ?: it.image, id, name, it.season, it.num)
        }
        PlayerActivity.start(this, list, i.episodes.indexOf(e).coerceAtLeast(0), fromStart)
    }

    private fun updateFav() {
        btnFav.text = if (Prefs.isFav("series", id)) "★  En favoritos" else "☆  Agregar a favoritos"
    }
}
