package com.nexumid.iptv

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** Capturas del diseño (TV y celular) para revisarlo sin un equipo. */
class DesignPreviewTest {

    private val tv = DeviceConfig.PIXEL_5.copy(
        screenWidth = 1920, screenHeight = 1080, xdpi = 320, ydpi = 320,
        density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
    )
    private val phone = DeviceConfig.PIXEL_5

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = tv, theme = "Theme.Nexum", maxPercentDifference = 100.0)

    private fun fillHome(root: View) {
        fun hero(id: Int, icon: Int, img: Int, frac: Float, title: String, sub: String) {
            val v = root.findViewById<View>(id)
            v.findViewById<ImageView>(R.id.heroIcon).setImageResource(icon)
            v.findViewById<com.nexumid.iptv.ui.HeroImageView>(R.id.heroImage).apply { setImageResource(img); fraction = frac }
            v.findViewById<TextView>(R.id.heroTitle).text = title
            v.findViewById<TextView>(R.id.heroSub).text = sub
            v.findViewById<View>(R.id.heroCard).clipToOutline = true
        }
        fun tile(id: Int, icon: Int, title: String) {
            val v = root.findViewById<View>(id)
            v.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
            v.findViewById<TextView>(R.id.tileText).text = title
        }
        hero(R.id.tileLive, R.drawable.ic_hero_live, R.drawable.hero_live, 0.70f, "TV en vivo", "Canales y deportes")
        hero(R.id.tileMovies, R.drawable.ic_hero_movie, R.drawable.hero_movies, 0.50f, "Películas", "Estrenos y clásicos")
        hero(R.id.tileSeries, R.drawable.ic_hero_series, R.drawable.hero_series, 0.62f, "Series", "Temporadas completas")
        tile(R.id.tileSearch, R.drawable.ic_search, "Buscar")
        tile(R.id.tileFav, R.drawable.ic_star, "Favoritos")
        tile(R.id.tileAccounts, R.drawable.ic_list, "Listas")
        tile(R.id.tileSettings, R.drawable.ic_settings, "Ajustes")
        root.findViewById<TextView>(R.id.tvAccount).text = "Mi lista\nVence 30/12/2026 · 1/2 conexiones"
        root.findViewById<View>(R.id.bellDot).visibility = View.VISIBLE
        root.findViewById<View>(R.id.recentHeader).visibility = View.VISIBLE
        // El primer acceso con el halo de foco, como en el diseño.
        root.findViewById<View>(R.id.tileLive).apply { isFocusable = true; isFocusableInTouchMode = true; requestFocus() }

        val names = listOf("02 | DISNEY + (ESP)", "01 | DISNEY + (ESP)", "15 | TNT (ESP)", "20 | HBO (ESP)", "25 | CARTOON (ESP)")
        val colors = listOf(
            intArrayOf(0xFF6A2BD9.toInt(), 0xFF23104F.toInt()), intArrayOf(0xFFD3162F.toInt(), 0xFF4E0812.toInt()),
            intArrayOf(0xFF1E6FE0.toInt(), 0xFF0A2459.toInt()), intArrayOf(0xFF5B2BD0.toInt(), 0xFF1A0E47.toInt()),
            intArrayOf(0xFF0F8FB8.toInt(), 0xFF07324A.toInt()),
        )
        val rv = root.findViewById<RecyclerView>(R.id.rvRecent)
        val portrait = root.resources.getBoolean(R.bool.portrait_ui)
        val width = if (portrait) ((root.resources.displayMetrics.widthPixels - 24 * root.resources.displayMetrics.density) / 2 - 4 * root.resources.displayMetrics.density).toInt()
        else (196 * root.resources.displayMetrics.density).toInt()
        rv.layoutManager = LinearLayoutManager(root.context, RecyclerView.HORIZONTAL, false)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
                android.view.LayoutInflater.from(p.context).inflate(R.layout.item_recent, p, false)
            ) {}
            override fun getItemCount() = names.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val v = h.itemView
                v.layoutParams = v.layoutParams.also { it.width = width }
                v.findViewById<View>(R.id.recentCard).clipToOutline = true
                v.findViewById<View>(R.id.recentTop).background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors[i])
                v.findViewById<ImageView>(R.id.ivLogo).setImageResource(R.drawable.ic_tv_placeholder)
                v.findViewById<TextView>(R.id.tvName).text = names[i]
                v.findViewById<ProgressBar>(R.id.pbNow).progress = 300 + i * 120
            }
        }
    }

    @Test
    fun home_tv() {
        val root = paparazzi.inflate<View>(R.layout.activity_home)
        fillHome(root)
        paparazzi.snapshot(root)
    }

    @Test
    fun home_phone() {
        paparazzi.unsafeUpdateConfig(deviceConfig = phone)
        val root = paparazzi.inflate<View>(R.layout.activity_home)
        fillHome(root)
        paparazzi.snapshot(root)
    }

    @Test
    fun login_tv() {
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_login))
    }

    @Test
    fun login_phone() {
        paparazzi.unsafeUpdateConfig(deviceConfig = phone)
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_login))
    }

    @Test
    fun activation_tv() {
        val root = paparazzi.inflate<View>(R.layout.activity_activation)
        root.findViewById<TextView>(R.id.tvCode).text = "NX-7K4Q9-XMP2T"
        paparazzi.snapshot(root)
    }

    @Test
    fun settings_tv() {
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_settings))
    }

    @Test
    fun browse_tv() {
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_browse))
    }

    @Test
    fun live_tv() {
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_live))
    }
}
