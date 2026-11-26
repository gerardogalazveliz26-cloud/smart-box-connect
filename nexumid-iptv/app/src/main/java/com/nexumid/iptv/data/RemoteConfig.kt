package com.nexumid.iptv.data

import com.nexumid.iptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Datos que el proveedor puede cambiar sin publicar otra versión (updates/nexumid-config.json):
 * el WhatsApp y los textos para pedir una prueba.
 */
object RemoteConfig {
    private const val DEFAULT_MESSAGE = "Hola, quiero solicitar una prueba de NexumID IPTV"
    private const val DEFAULT_TITLE = "¿Quieres probar NexumID?"
    private const val DEFAULT_TEXT = "Solicita tu prueba gratis escaneando el código QR"

    val whatsapp: String get() = (Prefs.cfg("whatsapp") ?: BuildConfig.WHATSAPP).filter { it.isDigit() }
    val trialMessage: String get() = Prefs.cfg("trialMessage") ?: DEFAULT_MESSAGE
    val trialTitle: String get() = Prefs.cfg("trialTitle") ?: DEFAULT_TITLE
    val trialText: String get() = Prefs.cfg("trialText") ?: DEFAULT_TEXT

    val hasWhatsapp: Boolean get() = whatsapp.length >= 8

    fun whatsappUrl(): String =
        "https://wa.me/$whatsapp?text=" + URLEncoder.encode(trialMessage, "UTF-8").replace("+", "%20")

    /** Número legible, por ejemplo +56 9 2609 7948. */
    fun whatsappDisplay(): String {
        val d = whatsapp
        return if (d.startsWith("569") && d.length == 11) "+56 9 ${d.substring(3, 7)} ${d.substring(7)}" else "+$d"
    }

    /** Descarga la configuración; devuelve true si algo cambió. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(BuildConfig.CONFIG_URL + "?t=" + System.currentTimeMillis()).build()
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use false
                val o = JSONObject(r.body?.string().orEmpty())
                var changed = false
                for (key in listOf("whatsapp", "trialMessage", "trialTitle", "trialText")) {
                    val v = o.optString(key).trim().takeIf { it.isNotEmpty() }
                    if (v != null && v != Prefs.cfg(key)) {
                        Prefs.setCfg(key, v)
                        changed = true
                    }
                }
                changed
            }
        } catch (e: Exception) {
            false
        }
    }
}
