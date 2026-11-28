package com.nexumid.iptv.ui

import android.app.Activity
import android.content.res.Resources

/**
 * Escala de pantalla en TV: el diseño está pensado para una TV de 960 × 540 dp (lo normal en Android TV).
 * Algunos TV box baratos informan una densidad demasiado alta (p. ej. 1280 × 720 a 320 dpi = 640 × 360 dp)
 * y todo se ve gigante y cortado. En esos equipos se ajusta la densidad para que el ancho sea 960 dp.
 */
object Screen {
    private const val TARGET_DP = 960f

    fun adapt(a: Activity) {
        try {
            if (!Device.isTv || !Device.looksLikeTv(a)) return
            val sys = Resources.getSystem().displayMetrics
            val longPx = maxOf(sys.widthPixels, sys.heightPixels)
            if (longPx <= 0 || longPx / sys.density >= 900f) return
            val d = longPx / TARGET_DP
            val font = sys.scaledDensity / sys.density
            listOf(a.resources.displayMetrics, a.application.resources.displayMetrics).forEach { m ->
                m.density = d
                m.scaledDensity = d * font
                m.densityDpi = (d * 160).toInt()
            }
        } catch (ignored: Throwable) {
        }
    }

    /** Para el reporte y Ajustes: resolución, densidad y tamaño en dp. */
    fun info(): String {
        val m = Resources.getSystem().displayMetrics
        val w = maxOf(m.widthPixels, m.heightPixels)
        val h = minOf(m.widthPixels, m.heightPixels)
        return "${w}×$h px · ${m.densityDpi} dpi · ${(w / m.density).toInt()}×${(h / m.density).toInt()} dp"
    }
}
