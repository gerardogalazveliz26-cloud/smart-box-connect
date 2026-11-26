package com.djchany.iptv.ui

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
import com.djchany.iptv.R
import com.djchany.iptv.data.Poster
import com.djchany.iptv.data.Prefs
import com.djchany.iptv.data.Repo
import com.djchany.iptv.data.VodInfo
import kotlinx.coroutines.launch

class VodDetailActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context, p: Poster) = ctx.startActivity(
            Intent(ctx, VodDetailActivity::class.java)
                .putExtra("id", p.id).putExtra("name", p.name).putExtra("image", p.image).putExtra("ext", p.ext)
        )
    }

    private var id = 0
    private lateinit var name: String
    private var image: String? = null
    private var ext = "mp4"
    private lateinit var btnPlay: Button
    private lateinit var btnRestart: Button
    private lateinit var btnFav: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.api == null) {
            finish(); return
        }
        setContentView(R.layout.activity_vod_detail)
        id = intent.getIntExtra("id", 0)
        name = intent.getStringExtra("name") ?: ""
        image = intent.getStringExtra("image")
        ext = intent.getStringExtra("ext")?.ifBlank { null } ?: "mp4"
        btnPlay = findViewById(R.id.btnPlay)
        btnRestart = findViewById(R.id.btnRestart)
        btnFav = findViewById(R.id.btnFav)

        findViewById<TextView>(R.id.tvName).text = name
        findViewById<ImageView>(R.id.ivPoster).loadImage(image, R.drawable.ic_poster_placeholder)
        findViewById<ImageView>(R.id.ivBackdrop).loadImage(image, R.drawable.ic_poster_placeholder)

        btnPlay.setOnClickListener { play(false) }
        btnRestart.setOnClickListener { play(true) }
        btnFav.setOnClickListener {
            val added = Prefs.toggleFav("vod", id)
            toast(if (added) "★ Agregada a favoritos" else "Quitada de favoritos")
            updateFav()
        }
        listOf(btnPlay, btnRestart, btnFav).forEach { it.focusZoom(1.06f) }
        updateFav()
        btnPlay.requestFocus()
        loadInfo()
    }

    override fun onResume() {
        super.onResume()
        if (::btnPlay.isInitialized) updateResume()
    }

    private fun loadInfo() {
        val pb = findViewById<ProgressBar>(R.id.pbLoading)
        pb.visibility = View.VISIBLE
        lifecycleScope.launch {
            val info = runCatching { Repo.requireApi().vodInfo(id) }.getOrNull()
            pb.visibility = View.GONE
            if (info != null) bind(info)
        }
    }

    private fun bind(info: VodInfo) {
        if (info.name.isNotBlank()) {
            name = info.name
            findViewById<TextView>(R.id.tvName).text = info.name
        }
        ext = info.ext
        if (info.cover != null) {
            image = image ?: info.cover
            findViewById<ImageView>(R.id.ivPoster).loadImage(info.cover, R.drawable.ic_poster_placeholder)
        }
        findViewById<ImageView>(R.id.ivBackdrop).loadImage(info.backdrop ?: info.cover ?: image, R.drawable.ic_poster_placeholder)
        findViewById<TextView>(R.id.tvMeta).text = listOfNotNull(
            info.year, info.genre, minutes(info.durationSecs).ifBlank { null }, rating(info.rating)
        ).joinToString("  ·  ")
        findViewById<TextView>(R.id.tvPlot).text = info.plot ?: "Sin descripción."
        findViewById<TextView>(R.id.tvCredits).text = listOfNotNull(
            info.director?.let { "Dirección: $it" }, info.cast?.let { "Reparto: $it" }
        ).joinToString("\n")
    }

    private fun updateResume() {
        val r = Prefs.resume("vod:$id")
        if (r != null && r.pos > 30_000) {
            btnPlay.text = "▶  Continuar (${duration(r.pos)})"
            btnRestart.visibility = View.VISIBLE
        } else {
            btnPlay.text = "▶  Reproducir"
            btnRestart.visibility = View.GONE
        }
    }

    private fun updateFav() {
        btnFav.text = if (Prefs.isFav("vod", id)) "★  En favoritos" else "☆  Favorito"
    }

    private fun play(fromStart: Boolean) {
        PlayerActivity.start(this, listOf(PlayItem("vod", id.toString(), ext, name, image)), 0, fromStart)
    }
}
