package com.djchany.iptv.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.djchany.iptv.BuildConfig
import com.djchany.iptv.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Actualizaciones automáticas desde updates/djchany-iptv.json del repositorio. */
object Updater {
    data class Info(val code: Int, val name: String, val url: String, val sha256: String?, val notes: String)

    private var promptedThisSession = false

    private suspend fun fetch(): Info? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(BuildConfig.UPDATE_URL).header("Cache-Control", "no-cache").build()
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string().orEmpty())
                if (!o.optBoolean("enabled", true)) return@use null
                Info(
                    o.optInt("versionCode"), o.optString("versionName"), o.optString("url"),
                    o.optString("sha256").ifBlank { null }, o.optString("notes"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun check(activity: AppCompatActivity, manual: Boolean) {
        if (!manual && promptedThisSession) return
        activity.lifecycleScope.launch {
            val info = fetch()
            if (info == null || info.code <= BuildConfig.VERSION_CODE || info.url.isBlank()) {
                if (manual) activity.toast("Ya tienes la última versión (${BuildConfig.VERSION_NAME})")
                return@launch
            }
            promptedThisSession = true
            if (activity.isFinishing) return@launch
            AlertDialog.Builder(activity)
                .setTitle("Nueva versión ${info.name}")
                .setMessage(info.notes.ifBlank { "Hay una actualización disponible de DJ Chany IPTV." })
                .setPositiveButton("Actualizar") { _, _ -> download(activity, info) }
                .setNegativeButton("Más tarde", null)
                .show()
        }
    }

    private fun download(activity: AppCompatActivity, info: Info) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(48, 32, 48, 32)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Descargando ${info.name}…")
            .setView(bar)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { downloadTo(activity, info, bar) }
            } catch (e: Exception) {
                null
            }
            dialog.dismiss()
            if (file == null) activity.toast("No se pudo descargar la actualización") else install(activity, file)
        }
    }

    private fun downloadTo(activity: Activity, info: Info, bar: ProgressBar): File {
        val dir = if (Build.VERSION.SDK_INT >= 24) File(activity.cacheDir, "updates")
        else File(activity.externalCacheDir ?: activity.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "djchany-iptv-${info.name}.apk")
        val md = MessageDigest.getInstance("SHA-256")
        Net.client.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("Sin contenido")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) bar.progress = (done * 100 / total).toInt()
                    }
                }
            }
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && !hash.equals(info.sha256, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException("La descarga está dañada")
        }
        if (Build.VERSION.SDK_INT < 24) out.setReadable(true, false)
        return out
    }

    private fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
                activity.toast("Permite instalar apps de esta fuente y vuelve a pulsar Actualizar")
                promptedThisSession = false
                return
            } catch (e: ActivityNotFoundException) {
                // Algunos TV box no tienen esa pantalla: se intenta instalar igual.
            }
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        } else Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.toast("No se pudo abrir el instalador")
        }
    }
}
