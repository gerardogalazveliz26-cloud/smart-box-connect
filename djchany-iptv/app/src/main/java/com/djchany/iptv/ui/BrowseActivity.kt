package com.djchany.iptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.djchany.iptv.R
import com.djchany.iptv.data.Poster
import com.djchany.iptv.data.Prefs
import com.djchany.iptv.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Catálogo de películas o series: categorías a la izquierda y grilla de pósters. */
class BrowseActivity : AppCompatActivity() {

    companion object {
        const val TYPE_VOD = "vod"
        const val TYPE_SERIES = "series"
        private const val EXTRA_TYPE = "type"
        private const val CAT_FAV = "__fav"
        private const val CAT_ALL = "__all"
        private const val CAT_NEW = "__new"
        private val SORTS = listOf("Por defecto", "Recientes", "A - Z", "Puntuación")

        fun open(ctx: Context, type: String) =
            ctx.startActivity(Intent(ctx, BrowseActivity::class.java).putExtra(EXTRA_TYPE, type))
    }

    private data class Cat(val id: String, val name: String, val count: Int)

    private lateinit var type: String
    private lateinit var rvCats: RecyclerView
    private lateinit var rvGrid: RecyclerView
    private lateinit var tvCat: TextView
    private lateinit var btnSort: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var catAdapter: SimpleAdapter<Cat>
    private lateinit var gridAdapter: SimpleAdapter<Poster>

    private var all: List<Poster> = emptyList()
    private var cats: List<Cat> = emptyList()
    private var currentCat: String? = null
    private var sort = 0
    private var favIds: Set<String> = emptySet()
    private val catDebounce by lazy { Debouncer(lifecycleScope, 250) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.applyOrientation(this)
        if (Repo.api == null) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_browse)
        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_VOD
        rvCats = findViewById(R.id.rvCats)
        rvGrid = findViewById(R.id.rvGrid)
        tvCat = findViewById(R.id.tvCat)
        btnSort = findViewById(R.id.btnSort)
        pbLoading = findViewById(R.id.pbLoading)
        tvEmpty = findViewById(R.id.tvEmpty)
        findViewById<TextView>(R.id.tvTitle).text = if (type == TYPE_VOD) "Películas" else "Series"

        val portrait = Device.portraitUi(this)
        catAdapter = SimpleAdapter(if (portrait) R.layout.item_chip else R.layout.item_category) { v, c, _ ->
            v.findViewById<TextView>(R.id.tvName).text = c.name
            v.findViewById<TextView>(R.id.tvCount).text = c.count.toString()
            v.isActivated = c.id == currentCat
        }
        catAdapter.onFocus = { c, _ -> if (c.id != currentCat) catDebounce.run { showCategory(c.id) } }
        catAdapter.onClick = { c, _ ->
            showCategory(c.id)
            rvGrid.post { rvGrid.focusPosition(0) }
        }
        rvCats.layoutManager = LinearLayoutManager(this, if (portrait) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

        gridAdapter = SimpleAdapter(R.layout.item_poster, 1.07f) { v, p, _ ->
            v.findViewById<ImageView>(R.id.ivPoster).loadImage(p.image, R.drawable.ic_poster_placeholder)
            v.findViewById<TextView>(R.id.tvName).text = p.name
            val r = rating(p.rating)
            v.findViewById<TextView>(R.id.tvRating).apply {
                text = r
                visibility = if (r == null) View.GONE else View.VISIBLE
            }
            v.findViewById<View>(R.id.tvFav).visibility = if (p.id.toString() in favIds) View.VISIBLE else View.GONE
            val res = if (type == TYPE_VOD) Prefs.resume("vod:${p.id}") else null
            v.findViewById<ProgressBar>(R.id.pbResume).apply {
                if (res != null && res.dur > 0) {
                    visibility = View.VISIBLE
                    progress = (res.pos * 1000 / res.dur).toInt()
                } else visibility = View.GONE
            }
        }
        gridAdapter.onClick = { p, _ ->
            if (type == TYPE_VOD) VodDetailActivity.open(this, p) else SeriesDetailActivity.open(this, p.id, p.name, p.image)
        }
        gridAdapter.onLongClick = { p, pos -> toggleFav(p, pos) }
        val width = resources.configuration.screenWidthDp
        val spans = if (portrait) (width - 12) / 112 else (width - 260).coerceAtLeast(300) / 125
        rvGrid.layoutManager = GridLayoutManager(this, spans.coerceIn(3, 8))
        rvGrid.adapter = gridAdapter
        rvGrid.itemAnimator = null

        btnSort.text = "Orden: ${SORTS[sort]}"
        btnSort.setOnClickListener {
            sort = (sort + 1) % SORTS.size
            btnSort.text = "Orden: ${SORTS[sort]}"
            currentCat?.let { showCategory(it, force = true) }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::gridAdapter.isInitialized && all.isNotEmpty()) {
            favIds = Prefs.favorites(type)
            gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount, "state")
        }
    }

    private fun load() {
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val categories = if (type == TYPE_VOD) Repo.vodCategories() else Repo.seriesCategories()
                all = if (type == TYPE_VOD) Repo.vodStreams() else Repo.series()
                favIds = Prefs.favorites(type)
                val counts = all.groupingBy { it.categoryId }.eachCount()
                cats = buildList {
                    add(Cat(CAT_FAV, "★ Favoritos", favIds.size))
                    add(Cat(CAT_NEW, "Agregadas recientemente", minOf(all.size, 100)))
                    add(Cat(CAT_ALL, "Todo", all.size))
                    categories.forEach { add(Cat(it.id, it.name, counts[it.id] ?: 0)) }
                }
                catAdapter.items = cats
                pbLoading.visibility = View.GONE
                val first = if (cats.size > 3) cats[3].id else CAT_ALL
                showCategory(first)
                rvCats.focusPosition(cats.indexOfFirst { it.id == first })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pbLoading.visibility = View.GONE
                tvEmpty.text = "No se pudo cargar el catálogo\n${e.message ?: ""}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun showCategory(catId: String, force: Boolean = false) {
        if (catId == currentCat && !force) return
        val old = cats.indexOfFirst { it.id == currentCat }
        currentCat = catId
        var list = when (catId) {
            CAT_ALL -> all
            CAT_FAV -> all.filter { it.id.toString() in favIds }
            CAT_NEW -> all.sortedByDescending { it.added }.take(100)
            else -> all.filter { it.categoryId == catId }
        }
        list = when (sort) {
            1 -> list.sortedByDescending { it.added }
            2 -> list.sortedBy { normalize(it.name) }
            3 -> list.sortedByDescending { it.rating }
            else -> list
        }
        gridAdapter.items = list
        rvGrid.scrollToPosition(0)
        tvCat.text = "${cats.firstOrNull { it.id == catId }?.name ?: ""}  ·  ${list.size}"
        tvEmpty.text = if (catId == CAT_FAV) "Aún no tienes favoritos.\nMantén OK sobre un póster para agregarlo." else "No hay contenido en esta categoría"
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        catAdapter.refresh(old)
        catAdapter.refresh(cats.indexOfFirst { it.id == catId })
    }

    private fun toggleFav(p: Poster, pos: Int) {
        val added = Prefs.toggleFav(type, p.id)
        favIds = Prefs.favorites(type)
        toast(if (added) "★ Agregado a favoritos" else "Quitado de favoritos")
        gridAdapter.refresh(pos)
        val i = cats.indexOfFirst { it.id == CAT_FAV }
        if (i >= 0) {
            cats = cats.toMutableList().also { it[i] = it[i].copy(count = favIds.size) }
            catAdapter.refresh(i)
            catAdapter.items = cats
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            val pos = if (focused != null && focused.parent === rvGrid) rvGrid.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
            if (pos != RecyclerView.NO_POSITION) {
                toggleFav(gridAdapter.items[pos], pos)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
