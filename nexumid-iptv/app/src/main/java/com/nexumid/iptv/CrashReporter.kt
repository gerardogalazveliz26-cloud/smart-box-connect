package com.nexumid.iptv

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nexumid.iptv.data.Prefs
import com.nexumid.iptv.data.RemoteConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder

/**
 * Si la app se cierra por un error, guarda el detalle y, al abrirla de nuevo, ofrece enviarlo al
 * proveedor por WhatsApp para poder corregirlo.
 */
object CrashReporter {
    private const val FILE = "ultimo_error.txt"
    private const val SP = "nexumid_diag"
    private lateinit var app: Application

    /** Último paso que hizo la app (sobrevive aunque Android la cierre sin aviso). */
    fun step(what: String) {
        if (!::app.isInitialized) return
        val rt = Runtime.getRuntime()
        val mem = "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576}/${rt.maxMemory() / 1_048_576} MB"
        runCatching { app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString("step", "$what · $mem").apply() }
    }

    private fun lastStep(): String = runCatching {
        app.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("step", null)
    }.getOrNull() ?: "-"

    private fun header(): String {
        val rt = Runtime.getRuntime()
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return buildString {
            appendLine("NexumID IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("RAM equipo: ${mi.totalMem / 1_048_576} MB · clase ${am.memoryClass}/${am.largeMemoryClass} MB${if (am.isLowRamDevice) " · gama baja" else ""}${if (com.nexumid.iptv.data.Repo.lowRam) " · modo liviano" else ""}")
            appendLine("Memoria app: usada ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} de ${rt.maxMemory() / 1_048_576} MB")
            appendLine("Modo: ${runCatching { Prefs.deviceMode }.getOrNull() ?: "-"} · Pantalla: ${com.nexumid.iptv.ui.Screen.info()}")
            appendLine("Último paso: ${lastStep()}")
        }
    }

    /**
     * Cierres que no son una excepción de Java (la app se congeló, falló el código nativo o Android la
     * cerró por falta de memoria): desde Android 11 el sistema guarda el motivo y se lee al volver a abrir.
     */
    private fun checkSystemExit() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull() ?: return
            val seen = sp.getLong("exit_seen", 0L)
            sp.edit().putLong("exit_seen", info.timestamp).apply()
            if (seen == 0L || info.timestamp <= seen) return // primera vez: solo se anota
            val reason = when (info.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "La app se congeló (ANR)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Error en código nativo (video/audio)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android la cerró por falta de memoria"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Uso excesivo de recursos"
                android.app.ApplicationExitInfo.REASON_SIGNALED ->
                    if (info.status == 9) "Android la cerró (señal 9, normalmente falta de memoria)" else "Señal ${info.status}"
                else -> return // cierres normales o errores de Java (esos ya se guardan aparte)
            }
            if (File(app.filesDir, FILE).exists()) return
            // Solo interesa si el usuario la estaba usando (no si Android limpió la app en segundo plano).
            if (info.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
                info.reason != android.app.ApplicationExitInfo.REASON_ANR
            ) return
            val trace = runCatching {
                info.traceInputStream?.bufferedReader()?.use { r ->
                    // De la traza del congelamiento basta el hilo principal.
                    val all = r.readText()
                    val i = all.indexOf("\"main\"")
                    (if (i >= 0) all.substring(i) else all).take(3000)
                }
            }.getOrNull()
            val text = buildString {
                append(header())
                appendLine("Motivo: $reason · memoria del proceso ${info.pss / 1024} MB (RSS ${info.rss / 1024} MB)")
                info.description?.let { appendLine("Detalle: $it") }
                appendLine()
                trace?.let { append(it) }
            }
            File(app.filesDir, FILE).writeText(text.take(8000))
        } catch (ignored: Throwable) {
        }
    }

    /** true si la sesión anterior terminó en un cierre: esta vez se evita la precarga pesada. */
    @Volatile
    var recovering = false
        private set

    /**
     * Para Android < 11 (y como respaldo): si la app estaba en pantalla y el proceso murió sin pasar por
     * segundo plano, Android la cerró (casi siempre por falta de memoria).
     */
    private fun checkSilentExit() {
        try {
            val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
            if (sp.getBoolean("running", false) && !File(app.filesDir, FILE).exists()) {
                val text = buildString {
                    append(header())
                    appendLine("Motivo: la app se cerró sin aviso mientras estaba en pantalla (posible falta de memoria)")
                }
                File(app.filesDir, FILE).writeText(text)
            }
            sp.edit().putBoolean("running", false).commit()
        } catch (ignored: Throwable) {
        }
    }

    private fun trackForeground() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            private fun mark(on: Boolean) = runCatching {
                app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean("running", on).commit()
            }
            override fun onActivityStarted(a: android.app.Activity) {
                if (started++ == 0) mark(true)
            }
            override fun onActivityStopped(a: android.app.Activity) {
                if (--started <= 0) {
                    started = 0
                    mark(false)
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = com.nexumid.iptv.ui.Screen.adapt(a)
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    fun hasPending(ctx: Context): Boolean = File(ctx.filesDir, FILE).exists()

    fun install(app: Application) {
        this.app = app
        checkSystemExit()
        checkSilentExit()
        recovering = File(app.filesDir, FILE).exists()
        trackForeground()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append(header())
                    appendLine("Hilo: ${thread.name}")
                    appendLine()
                    append(sw.toString())
                }
                File(app.filesDir, FILE).writeText(text.take(8000))
                // Un error de Java ya quedó guardado: no contarlo además como cierre silencioso.
                app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean("running", false).commit()
            } catch (ignored: Throwable) {
                // Nunca fallar al guardar el reporte.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun pending(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()

    /** Resumen corto (para WhatsApp): datos del equipo, el error y las primeras líneas de la traza. */
    private fun summary(report: String): String {
        val lines = report.lines()
        val head = lines.takeWhile { it.isNotBlank() }
        val trace = lines.drop(head.size).filter { it.isNotBlank() }
        val important = trace.filterIndexed { i, l -> i < 14 || l.startsWith("Caused by") || (l.contains("com.nexumid") && i < 60) }
        return (head + "" + important.distinct().take(28)).joinToString("\n").take(1800)
    }

    private const val SEEN = "ultimo_error_visto.txt"

    /** Resumen muy corto para el QR (debe caber y escanearse bien desde la TV). */
    private fun qrText(report: String): String {
        val lines = report.lines()
        val head = lines.takeWhile { it.isNotBlank() }
        val trace = lines.drop(head.size).filter { it.isNotBlank() }
        val key = trace.take(3) + trace.filter { it.startsWith("Caused by") || it.contains("com.nexumid") }.take(4)
        return ("Reporte NexumID:\n" + (head + key.distinct()).joinToString("\n") { it.trim() }).take(650)
    }

    private fun waUrl(text: String) =
        "https://wa.me/${RemoteConfig.whatsapp}?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun qrBitmap(text: String, size: Int): android.graphics.Bitmap? = try {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            com.google.zxing.EncodeHintType.MARGIN to 2,
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.RGB_565)
    } catch (e: Throwable) {
        null
    }

    /** Muestra el último reporte guardado (desde Ajustes). */
    fun showLast(activity: AppCompatActivity) {
        val report = File(activity.filesDir, SEEN).takeIf { it.exists() }?.readText()
        if (report == null) {
            android.widget.Toast.makeText(activity, "No hay cierres registrados", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        show(activity, report, "Último reporte de error", null, null)
    }

    /**
     * Ofrece enviar el reporte. [then] se llama al cerrar el aviso con "Continuar" (para seguir abriendo
     * la app); si eligió enviarlo por WhatsApp desde el mismo equipo, se llama [sent].
     */
    fun offer(activity: AppCompatActivity, then: (() -> Unit)? = null, sent: (() -> Unit)? = null) {
        val report = pending(activity) ?: return run { then?.invoke() }
        val f = File(activity.filesDir, FILE)
        runCatching { File(activity.filesDir, SEEN).writeText(report) }
        f.delete()
        if (activity.isFinishing) return
        show(activity, report, "La app se cerró inesperadamente", then, sent)
    }

    /**
     * Reporte en pantalla: un QR para escanear con el celular (abre WhatsApp con el reporte escrito, sirve en
     * TV box sin WhatsApp) y el texto completo para leerlo o sacarle foto.
     */
    private fun show(activity: AppCompatActivity, report: String, title: String, then: (() -> Unit)?, sent: (() -> Unit)?) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val tv = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val qrUrl = waUrl(qrText(report))
        val root = android.widget.LinearLayout(activity).apply {
            orientation = if (tv) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val qrSize = if (tv) dp(230) else dp(220)
        val left = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        qrBitmap(qrUrl, qrSize)?.let { bmp ->
            left.addView(android.widget.ImageView(activity).apply { setImageBitmap(bmp) }, android.widget.LinearLayout.LayoutParams(qrSize, qrSize))
            left.addView(android.widget.TextView(activity).apply {
                text = "Escanéalo con tu celular:\nse abre WhatsApp con el reporte"
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                setPadding(0, dp(6), 0, dp(8))
            })
        }
        root.addView(left)
        val scroll = android.widget.ScrollView(activity).apply {
            isFocusable = true // con el control remoto se baja con las flechas
            setPadding(if (tv) dp(16) else 0, 0, 0, 0)
        }
        scroll.addView(android.widget.TextView(activity).apply {
            text = report.take(6000)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(!tv)
        })
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(
            if (tv) 0 else android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            if (tv) dp(300) else dp(220),
        ).apply { if (tv) weight = 1f })

        var sending = false
        val b = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setCancelable(then == null)
            .setOnDismissListener { if (sending) sent?.invoke() else then?.invoke() }
            .setPositiveButton(if (then != null) "Continuar" else "Cerrar", null)
        val canWhatsapp = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/1")).setPackage("com.whatsapp").resolveActivity(activity.packageManager) != null ||
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/1")).setPackage("com.whatsapp.w4b").resolveActivity(activity.packageManager) != null
        }.getOrDefault(false)
        if (canWhatsapp) b.setNeutralButton("Enviar por WhatsApp") { _, _ ->
            sending = sent != null
            val text = "Reporte de error de NexumID IPTV:\n\n" + summary(report)
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waUrl(text))))
            } catch (e: Exception) {
                activity.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Enviar reporte")
                )
            }
        }
        b.show().also { dlg ->
            if (tv) dlg.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.9f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
    }
}
