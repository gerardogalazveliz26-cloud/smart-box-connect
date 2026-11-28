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
            appendLine("RAM equipo: ${mi.totalMem / 1_048_576} MB · clase ${am.memoryClass}/${am.largeMemoryClass} MB${if (am.isLowRamDevice) " · gama baja" else ""}")
            appendLine("Memoria app: usada ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} de ${rt.maxMemory() / 1_048_576} MB")
            appendLine("Modo: ${runCatching { Prefs.deviceMode }.getOrNull() ?: "-"}")
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
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
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

    /**
     * Ofrece enviar el reporte. [then] se llama al cerrar el aviso con "Ahora no" (para seguir abriendo
     * la app); si eligió enviarlo, se llama [sent].
     */
    fun offer(activity: AppCompatActivity, then: (() -> Unit)? = null, sent: (() -> Unit)? = null) {
        val report = pending(activity) ?: return run { then?.invoke() }
        File(activity.filesDir, FILE).delete()
        if (activity.isFinishing) return
        var sending = false
        AlertDialog.Builder(activity)
            .setCancelable(then == null)
            .setOnDismissListener { if (sending) sent?.invoke() else then?.invoke() }
            .setTitle("La app se cerró inesperadamente")
            .setMessage("¿Quieres enviar el reporte del error a soporte por WhatsApp? Así podemos solucionarlo.")
            .setPositiveButton("Enviar por WhatsApp") { _, _ ->
                sending = true
                val text = "Reporte de error de NexumID IPTV:\n\n" + summary(report)
                val url = "https://wa.me/${RemoteConfig.whatsapp}?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    activity.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Enviar reporte")
                    )
                }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }
}
