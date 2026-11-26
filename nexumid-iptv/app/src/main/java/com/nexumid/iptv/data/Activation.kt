package com.nexumid.iptv.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Activación remota: el proveedor carga las listas de este equipo desde su panel.
 *
 * Cada equipo tiene un código propio. El panel guarda un archivo
 * activaciones/<id>.json en el repositorio, cifrado con AES-256-GCM y una clave
 * derivada del código (PBKDF2-SHA256). Sin el código el archivo no se puede leer.
 */
object Activation {
    private const val OWNER = "gerardogalazveliz26-cloud"
    private const val REPO = "smart-box-connect"
    private const val BRANCH = "main"
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val CODE_LEN = 10

    data class RemoteList(val name: String, val server: String, val user: String, val pass: String)
    data class Payload(val lists: List<RemoteList>, val updated: Long, val message: String?)

    sealed class Result {
        data class Found(val payload: Payload) : Result()
        object NotFound : Result()
        data class Error(val reason: String) : Result()
    }

    /** Código de este equipo (se genera una sola vez). */
    val code: String
        get() = Prefs.deviceCode ?: generate().also { Prefs.deviceCode = it }

    fun display(c: String = code) = "NX-${c.substring(0, 5)}-${c.substring(5)}"

    private fun generate(): String {
        val r = SecureRandom()
        return (1..CODE_LEN).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
    }

    fun fileId(c: String = code): String =
        MessageDigest.getInstance("SHA-256").digest("nexumid-activacion:$c".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)

    private var lastBody: String? = null
    private var lastPayload: Payload? = null

    /** Busca la activación de este equipo. Usa la API de GitHub (al instante) y, si se agota su límite, el CDN. */
    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        val path = "activaciones/${fileId()}.json"
        val api = "https://api.github.com/repos/$OWNER/$REPO/contents/$path?ref=$BRANCH"
        val raw = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$path?t=${System.currentTimeMillis()}"
        val body: String? = try {
            Net.client.newCall(
                Request.Builder().url(api)
                    .header("Accept", "application/vnd.github.raw+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
            ).execute().use { r ->
                when {
                    r.code == 404 -> return@withContext Result.NotFound
                    r.isSuccessful -> r.body?.string()
                    else -> null // límite de la API: probar con el CDN
                }
            }
        } catch (e: Exception) {
            null
        }
        var text = body
        if (text == null) {
            text = try {
                Net.client.newCall(Request.Builder().url(raw).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    when {
                        r.code == 404 -> return@withContext Result.NotFound
                        r.isSuccessful -> r.body?.string()
                        else -> return@withContext Result.Error("HTTP ${r.code}")
                    }
                }
            } catch (e: Exception) {
                return@withContext Result.Error(e.message ?: "sin conexión")
            }
        }
        if (text.isNullOrBlank()) return@withContext Result.Error("respuesta vacía")

        if (text == lastBody) lastPayload?.let { return@withContext Result.Found(it) }
        try {
            val p = decrypt(text.orEmpty(), code)
            lastBody = text
            lastPayload = p
            Result.Found(p)
        } catch (e: Exception) {
            Result.Error("no se pudo leer la activación")
        }
    }

    /** Guarda las listas recibidas. Devuelve true si cambió la lista activa. */
    fun apply(p: Payload): Boolean {
        val before = Prefs.current
        val profiles = p.lists.mapNotNull { l ->
            var server = l.server
            var user = l.user
            var pass = l.pass
            if (user.isBlank()) XtreamApi.parseM3uLink(server)?.let { (s, u, pw) -> server = s; user = u; pass = pw }
            val base = XtreamApi.normalizeServer(server) ?: return@mapNotNull null
            if (user.isBlank() || pass.isBlank()) return@mapNotNull null
            Prefs.Profile(l.name.ifBlank { user }, base.toString().trimEnd('/'), user, pass, remote = true)
        }
        if (profiles.isEmpty()) return false
        Prefs.replaceRemoteProfiles(profiles)
        Prefs.activated = true
        Prefs.activationStamp = p.updated
        val after = Prefs.current
        if (after != null && after != before) {
            Repo.use(after, null)
            return true
        }
        return false
    }

    /** El proveedor dio de baja el equipo: se quitan las listas remotas. */
    fun revoke() {
        Prefs.replaceRemoteProfiles(emptyList())
        Prefs.activated = false
        Prefs.activationStamp = 0
        if (Prefs.current == null) Repo.logout() else Prefs.current?.let { Repo.use(it, null) }
    }

    // ---------------- cifrado (compatible con WebCrypto del panel)

    fun decrypt(json: String, c: String): Payload {
        val o = JSONObject(json)
        val salt = Base64.decode(o.getString("salt"), Base64.DEFAULT)
        val iv = Base64.decode(o.getString("iv"), Base64.DEFAULT)
        val data = Base64.decode(o.getString("data"), Base64.DEFAULT)
        val iter = o.optInt("iter", 60_000)
        val key = pbkdf2(c.toByteArray(Charsets.UTF_8), salt, iter, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
        val arr = plain.optJSONArray("lists")
        val lists = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            arr?.optJSONObject(i)?.let {
                RemoteList(it.optString("name"), it.optString("server"), it.optString("user"), it.optString("pass"))
            }
        }
        return Payload(lists, plain.optLong("updated"), plain.optString("message").ifBlank { null })
    }

    /** PBKDF2-HMAC-SHA256 propio: SecretKeyFactory solo lo trae desde Android 8. */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(keyLen)
        val blocks = (keyLen + hLen - 1) / hLen
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (n in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val off = (i - 1) * hLen
            System.arraycopy(t, 0, out, off, minOf(hLen, keyLen - off))
        }
        return out
    }
}
