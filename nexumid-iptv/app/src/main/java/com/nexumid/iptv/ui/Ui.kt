package com.nexumid.iptv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun View.focusZoom(scale: Float = 1.06f) {
    setOnFocusChangeListener { v, has -> v.zoom(has, scale) }
}

fun View.zoom(focused: Boolean, scale: Float) {
    animate().scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f)
        .translationZ(if (focused) 12f else 0f).setDuration(130).start()
}

fun ImageView.loadImage(url: String?, @DrawableRes placeholder: Int) {
    load(url?.trim()?.takeIf { it.startsWith("http") }) {
        placeholder(placeholder)
        error(placeholder)
        fallback(placeholder)
    }
}

fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
fun date(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(ms))

fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun minutes(secs: Int): String = if (secs <= 0) "" else if (secs >= 3600) "${secs / 3600} h ${(secs % 3600) / 60} min" else "${secs / 60} min"

private val marks = Regex("\\p{Mn}+")
fun normalize(s: String): String = marks.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

fun rating(r: Double): String? = when {
    r <= 0 -> null
    r > 5 -> String.format(Locale.US, "★ %.1f", r)
    else -> String.format(Locale.US, "★ %.1f", r * 2)
}

/** Ejecuta solo el último bloque recibido tras [ms] milisegundos. */
class Debouncer(private val scope: CoroutineScope, private val ms: Long) {
    private var job: Job? = null
    fun run(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(ms)
            block()
        }
    }
}

/** Contenedor 16:9 para la vista previa del canal. */
class RatioFrameLayout @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY),
        )
    }
}

/** Imagen con proporción de póster (2:3). */
class PosterImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 3 / 2)
    }
}
