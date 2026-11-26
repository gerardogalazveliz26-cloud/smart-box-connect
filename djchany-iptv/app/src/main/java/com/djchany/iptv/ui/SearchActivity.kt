package com.djchany.iptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.djchany.iptv.R
import com.djchany.iptv.data.LiveStream
import com.djchany.iptv.data.Poster
import com.djchany.iptv.data.Prefs
import com.djchany.iptv.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Búsqueda global (canales, películas y series) y pantalla de favoritos. */
class SearchActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FAV = "favorites"
        fun open(ctx: Context, favorites: Boolean) =
            ctx.startActivity(Intent(ctx, SearchActivity::class.java).putExtra(EXTRA_FAV, favorites))
    }

    private var favMode = false
    private lateinit var etQuery: EditText
    private lateinit var tvStatus: TextView
    private lateinit var liveAdapter: SimpleAdapter<LiveStream>
    private lateinit var vodAdapter: SimpleAdapter<Poster>
    private lateinit var seriesAdapter: SimpleAdapter<Poster>
    private val debounce by lazy { Debouncer(lifecycleScope, 350) }

    private var live: List<Pair<String, LiveStream>>? = null
    private var vod: List<Pair<String, Poster>>? = null
    private var series: List<Pair<String, Poster>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.api == null) {
            finish(); return
        }
        setContentView(R.layout.activity_search)
        favMode = intent.getBooleanExtra(EXTRA_FAV, false)
        etQuery = findViewById(R.id.etQuery)
        tvStatus = findViewById(R.id.tvStatus)
        findViewById<TextView>(R.id.tvTitle).text = if (favMode) "★ Mis favoritos" else "Buscar"

        liveAdapter = SimpleAdapter(R.layout.item_channel_card, 1.08f) { v, ch, _ ->
            v.findViewById<ImageView>(R.id.ivLogo).loadImage(ch.icon, R.drawable.ic_tv_placeholder)
            v.findViewById<TextView>(R.id.tvName).text = ch.name
        }
        liveAdapter.onClick = { ch, _ -> LiveActivity.play(this, ch.id) }
        liveAdapter.onLongClick = { ch, _ -> fav("live", ch.id) }

        vodAdapter = posterAdapter()
        vodAdapter.onClick = { p, _ -> VodDetailActivity.open(this, p) }
        vodAdapter.onLongClick = { p, _ -> fav("vod", p.id) }

        seriesAdapter = posterAdapter()
        seriesAdapter.onClick = { p, _ -> SeriesDetailActivity.open(this, p.id, p.name, p.image) }
        seriesAdapter.onLongClick = { p, _ -> fav("series", p.id) }

        row(R.id.rvLive, liveAdapter)
        row(R.id.rvVod, vodAdapter)
        row(R.id.rvSeries, seriesAdapter)

        if (favMode) {
            etQuery.visibility = View.GONE
        } else {
            etQuery.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    debounce.run { search(s?.toString().orEmpty()) }
                }
            })
            etQuery.requestFocus()
            tvStatus.text = "Escribe al menos 2 letras"
        }
    }

    override fun onResume() {
        super.onResume()
        if (favMode && ::liveAdapter.isInitialized) showFavorites()
    }

    private fun posterAdapter() = SimpleAdapter<Poster>(R.layout.item_poster_row, 1.08f) { v, p, _ ->
        v.findViewById<ImageView>(R.id.ivPoster).loadImage(p.image, R.drawable.ic_poster_placeholder)
        v.findViewById<TextView>(R.id.tvName).text = p.name
        val r = rating(p.rating)
        v.findViewById<TextView>(R.id.tvRating).apply {
            text = r
            visibility = if (r == null) View.GONE else View.VISIBLE
        }
    }

    private fun <T> row(id: Int, a: SimpleAdapter<T>) {
        findViewById<RecyclerView>(id).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity, RecyclerView.HORIZONTAL, false)
            adapter = a
            itemAnimator = null
        }
    }

    private fun fav(type: String, id: Int) {
        val added = Prefs.toggleFav(type, id)
        toast(if (added) "★ Agregado a favoritos" else "Quitado de favoritos")
        if (favMode) showFavorites()
    }

    private suspend fun ensureLoaded() {
        if (live != null && vod != null && series != null) return
        coroutineScopeLoad()
    }

    private suspend fun coroutineScopeLoad() = withContext(Dispatchers.Default) {
        val l = async { runCatching { Repo.liveStreams() }.getOrDefault(emptyList()) }
        val v = async { runCatching { Repo.vodStreams() }.getOrDefault(emptyList()) }
        val s = async { runCatching { Repo.series() }.getOrDefault(emptyList()) }
        live = l.await().map { normalize(it.name) to it }
        vod = v.await().map { normalize(it.name) to it }
        series = s.await().map { normalize(it.name) to it }
    }

    private fun show(l: List<LiveStream>, v: List<Poster>, s: List<Poster>) {
        liveAdapter.items = l
        vodAdapter.items = v
        seriesAdapter.items = s
        findViewById<View>(R.id.tvLive).visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvVod).visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvSeries).visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun search(raw: String) {
        val q = normalize(raw.trim())
        if (q.length < 2) {
            show(emptyList(), emptyList(), emptyList())
            tvStatus.text = "Escribe al menos 2 letras"
            return
        }
        tvStatus.text = "Buscando…"
        lifecycleScope.launch {
            try {
                ensureLoaded()
                val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
                fun <T> match(list: List<Pair<String, T>>?) =
                    list.orEmpty().asSequence().filter { (n, _) -> words.all { n.contains(it) } }.map { it.second }.take(60).toList()
                val (l, v, s) = withContext(Dispatchers.Default) { Triple(match(live), match(vod), match(series)) }
                show(l, v, s)
                val total = l.size + v.size + s.size
                tvStatus.text = if (total == 0) "Sin resultados para \"$raw\"" else "$total resultado(s)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvStatus.text = "Error al buscar: ${e.message ?: ""}"
            }
        }
    }

    private fun showFavorites() {
        tvStatus.text = "Cargando…"
        lifecycleScope.launch {
            ensureLoaded()
            val fl = Prefs.favorites("live")
            val fv = Prefs.favorites("vod")
            val fs = Prefs.favorites("series")
            val l = live.orEmpty().map { it.second }.filter { it.id.toString() in fl }
            val v = vod.orEmpty().map { it.second }.filter { it.id.toString() in fv }
            val s = series.orEmpty().map { it.second }.filter { it.id.toString() in fs }
            show(l, v, s)
            tvStatus.text = if (l.isEmpty() && v.isEmpty() && s.isEmpty())
                "Aún no tienes favoritos. Mantén OK (o pulsa MENÚ) sobre un canal, película o serie para agregarlo."
            else "Mantén OK sobre un elemento para quitarlo"
            if (currentFocus == null) {
                listOf(R.id.rvLive, R.id.rvVod, R.id.rvSeries).map { findViewById<RecyclerView>(it) }
                    .firstOrNull { (it.adapter?.itemCount ?: 0) > 0 }?.focusPosition(0)
            }
        }
    }
}
