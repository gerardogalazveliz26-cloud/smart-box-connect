package com.djchany.iptv.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.djchany.iptv.R
import com.djchany.iptv.data.Prefs

/** Modo de uso elegido al inicio: Android TV (control remoto) o celular (táctil). */
object Device {
    val isMobile: Boolean get() = Prefs.deviceMode == "mobile"
    val isTv: Boolean get() = !isMobile

    /** Detecta si el equipo parece un televisor o TV Box. */
    fun looksLikeTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val pm = ctx.packageManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.software.leanback_only") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** Las pantallas de navegación usan vertical en celular y horizontal en TV. */
    fun applyOrientation(a: Activity) {
        a.requestedOrientation = if (isMobile) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Video siempre en horizontal. */
    fun landscape(a: Activity) {
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** true si se infló el diseño vertical (layout-port). */
    fun portraitUi(ctx: Context) = ctx.resources.getBoolean(R.bool.portrait_ui)

    fun immersive(a: Activity, on: Boolean) {
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    fun visibleIf(v: View, show: Boolean) {
        v.visibility = if (show) View.VISIBLE else View.GONE
    }
}
