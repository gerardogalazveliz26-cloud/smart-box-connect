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

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                val text = buildString {
                    appendLine("NexumID IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Memoria: usada ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} de ${rt.maxMemory() / 1_048_576} MB")
                    appendLine("Modo: ${runCatching { Prefs.deviceMode }.getOrNull() ?: "-"} · Hilo: ${thread.name}")
                    appendLine()
                    append(sw.toString())
                }
                File(app.filesDir, FILE).writeText(text.take(8000))
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

    fun offer(activity: AppCompatActivity) {
        val report = pending(activity) ?: return
        File(activity.filesDir, FILE).delete()
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("La app se cerró inesperadamente")
            .setMessage("¿Quieres enviar el reporte del error a soporte por WhatsApp? Así podemos solucionarlo.")
            .setPositiveButton("Enviar por WhatsApp") { _, _ ->
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
