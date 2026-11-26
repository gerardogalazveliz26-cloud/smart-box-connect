package com.nexumid.iptv.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class XtreamException(msg: String) : IOException(msg)

/** Cliente de la API player_api.php de los paneles Xtream Codes. */
class XtreamApi(server: String, val user: String, val pass: String) {

    val base: HttpUrl = normalizeServer(server) ?: throw XtreamException("La dirección del servidor no es válida")
    val baseString: String = base.toString().trimEnd('/')

    companion object {
        /** Si [input] es un enlace M3U/Xtream (get.php?username=..&password=..) devuelve servidor, usuario y contraseña. */
        fun parseM3uLink(input: String): Triple<String, String, String>? {
            var s = input.trim()
            if (!s.contains("username=", true)) return null
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val u = s.toHttpUrlOrNull() ?: return null
            val user = u.queryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
            val pass = u.queryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
            val server = HttpUrl.Builder().scheme(u.scheme).host(u.host).port(u.port).build().toString().trimEnd('/')
            return Triple(server, user, pass)
        }

        /** Acepta "dominio:puerto", "http://dominio:puerto/login.php", etc. y deja solo esquema, host y puerto. */
        fun normalizeServer(input: String): HttpUrl? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val u = s.toHttpUrlOrNull() ?: return null
            return HttpUrl.Builder().scheme(u.scheme).host(u.host).port(u.port).build()
        }
    }

    private suspend fun call(action: String?, vararg params: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            val b = base.newBuilder()
                .addPathSegment("player_api.php")
                .addQueryParameter("username", user)
                .addQueryParameter("password", pass)
            if (action != null) b.addQueryParameter("action", action)
            params.forEach { b.addQueryParameter(it.first, it.second) }
            val req = Request.Builder().url(b.build()).build()
            Net.client.newCall(req).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw XtreamException("Usuario o contraseña incorrectos")
                if (r.code == 404) throw XtreamException(
                    "Ese servidor no tiene la API de Xtream (error 404). Usa el DNS y los datos de una LÍNEA " +
                        "(los del enlace M3U), no los del panel de revendedor. También puedes pegar el enlace M3U completo en el campo Servidor."
                )
                if (!r.isSuccessful) throw XtreamException("El servidor respondió con error ${r.code}")
                r.body?.string().orEmpty()
            }
        }

    // ---------------- cuenta

    suspend fun login(): Account {
        val t = call(null).trim()
        if (!t.startsWith("{")) throw XtreamException("El servidor no respondió como un panel Xtream Codes")
        val o = JSONObject(t)
        val u = o.optJSONObject("user_info") ?: throw XtreamException("Usuario o contraseña incorrectos")
        if (u.int("auth") != 1) throw XtreamException("Usuario o contraseña incorrectos")
        return Account(
            username = u.str("username") ?: user,
            status = u.str("status") ?: "Active",
            expDate = u.long("exp_date").takeIf { it > 0 }?.times(1000),
            activeCons = u.int("active_cons"),
            maxCons = u.int("max_connections"),
            isTrial = u.str("is_trial") == "1",
        )
    }

    // ---------------- categorías y listas

    private suspend fun categories(action: String): List<Category> =
        parseArray(call(action)).mapObjects { o ->
            val id = o.str("category_id") ?: return@mapObjects null
            Category(id, o.str("category_name") ?: "Sin nombre")
        }

    suspend fun liveCategories() = categories("get_live_categories")
    suspend fun vodCategories() = categories("get_vod_categories")
    suspend fun seriesCategories() = categories("get_series_categories")

    suspend fun liveStreams(): List<LiveStream> = parseArray(call("get_live_streams")).mapObjects { o ->
        val id = o.int("stream_id", -1)
        if (id < 0) return@mapObjects null
        LiveStream(
            id = id,
            num = o.int("num"),
            name = o.str("name") ?: "Canal $id",
            icon = o.str("stream_icon"),
            categoryId = o.str("category_id"),
            epgId = o.str("epg_channel_id"),
            archive = o.int("tv_archive") == 1,
        )
    }

    suspend fun vodStreams(): List<Poster> = parseArray(call("get_vod_streams")).mapObjects { o ->
        val id = o.int("stream_id", -1)
        if (id < 0) return@mapObjects null
        Poster(
            id = id,
            name = o.str("name") ?: "Película $id",
            image = o.str("stream_icon"),
            categoryId = o.str("category_id"),
            rating = o.double("rating"),
            added = o.long("added"),
            ext = o.str("container_extension") ?: "mp4",
        )
    }

    suspend fun series(): List<Poster> = parseArray(call("get_series")).mapObjects { o ->
        val id = o.int("series_id", -1)
        if (id < 0) return@mapObjects null
        Poster(
            id = id,
            name = o.str("name") ?: "Serie $id",
            image = o.str("cover"),
            categoryId = o.str("category_id"),
            rating = o.double("rating"),
            added = o.long("last_modified"),
            ext = "",
        )
    }

    // ---------------- detalle

    suspend fun vodInfo(id: Int): VodInfo {
        val t = call("get_vod_info", "vod_id" to id.toString()).trim()
        val o = if (t.startsWith("{")) JSONObject(t) else JSONObject()
        val info = o.optJSONObject("info") ?: JSONObject()
        val md = o.optJSONObject("movie_data") ?: JSONObject()
        return VodInfo(
            id = id,
            name = md.str("name") ?: info.str("name") ?: "",
            cover = info.str("movie_image") ?: info.str("cover_big"),
            backdrop = firstImage(info, "backdrop_path"),
            plot = info.str("plot") ?: info.str("description"),
            cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"),
            genre = info.str("genre"),
            year = (info.str("releasedate") ?: info.str("release_date") ?: info.str("year"))?.take(4),
            durationSecs = info.int("duration_secs").takeIf { it > 0 } ?: parseDuration(info.str("duration")),
            rating = info.double("rating"),
            ext = md.str("container_extension") ?: "mp4",
        )
    }

    suspend fun seriesInfo(id: Int): SeriesInfo {
        val t = call("get_series_info", "series_id" to id.toString()).trim()
        val o = if (t.startsWith("{")) JSONObject(t) else JSONObject()
        val info = o.optJSONObject("info") ?: JSONObject()
        val eps = ArrayList<Episode>()
        when (val e = o.opt("episodes")) {
            is JSONObject -> e.keys().forEach { k ->
                e.optJSONArray(k)?.let { arr -> addEpisodes(arr, k.toIntOrNull() ?: 0, eps) }
            }
            is JSONArray -> for (i in 0 until e.length()) {
                val arr = e.optJSONArray(i)
                if (arr != null) addEpisodes(arr, 0, eps) else e.optJSONObject(i)?.let { addEpisode(it, 0, eps) }
            }
        }
        eps.sortWith(compareBy<Episode>({ it.season }, { it.num }))
        return SeriesInfo(
            name = info.str("name") ?: "",
            cover = info.str("cover"),
            backdrop = firstImage(info, "backdrop_path"),
            plot = info.str("plot"),
            cast = info.str("cast"),
            director = info.str("director"),
            genre = info.str("genre"),
            year = (info.str("releaseDate") ?: info.str("release_date") ?: info.str("year"))?.take(4),
            rating = info.double("rating"),
            episodes = eps,
        )
    }

    private fun addEpisodes(arr: JSONArray, seasonHint: Int, out: MutableList<Episode>) {
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, seasonHint, out) }
    }

    private fun addEpisode(o: JSONObject, seasonHint: Int, out: MutableList<Episode>) {
        val id = o.str("id") ?: return
        val inf = o.optJSONObject("info")
        val season = o.str("season")?.toDoubleOrNull()?.toInt() ?: if (seasonHint > 0) seasonHint else 1
        val num = o.int("episode_num")
        out.add(
            Episode(
                id = id,
                season = season,
                num = num,
                title = o.str("title") ?: "Episodio $num",
                ext = o.str("container_extension") ?: "mp4",
                plot = inf?.str("plot"),
                image = inf?.str("movie_image"),
                durationSecs = inf?.int("duration_secs")?.takeIf { it > 0 } ?: parseDuration(inf?.str("duration")),
            )
        )
    }

    // ---------------- guía (EPG)

    suspend fun shortEpg(streamId: Int, limit: Int = 6): List<EpgEntry> {
        val t = call("get_short_epg", "stream_id" to streamId.toString(), "limit" to limit.toString()).trim()
        if (!t.startsWith("{")) return emptyList()
        val arr = JSONObject(t).optJSONArray("epg_listings") ?: return emptyList()
        return arr.mapObjects { o ->
            val s = o.long("start_timestamp")
            val e = o.long("stop_timestamp")
            if (s <= 0 || e <= s) return@mapObjects null
            EpgEntry(decode(o.str("title")), decode(o.str("description")), s * 1000, e * 1000)
        }.sortedBy { it.start }
    }

    // ---------------- URLs de reproducción

    fun liveUrl(id: Int, format: String) = "$baseString/live/${enc(user)}/${enc(pass)}/$id.$format"
    fun vodUrl(id: String, ext: String) = "$baseString/movie/${enc(user)}/${enc(pass)}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "$baseString/series/${enc(user)}/${enc(pass)}/$id.$ext"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------- utilidades JSON

    private val base64Regex = Regex("^[A-Za-z0-9+/=\\s]+$")

    private fun decode(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        if (!base64Regex.matches(s)) return s
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            s
        }
    }

    private fun parseDuration(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    private fun firstImage(o: JSONObject, key: String): String? = when (val v = o.opt(key)) {
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http") }
        is String -> v.takeIf { it.startsWith("http") }
        else -> null
    }

    private fun parseArray(body: String): JSONArray {
        val t = body.trim()
        return when {
            t.startsWith("[") -> JSONArray(t)
            t.startsWith("{") -> {
                val o = JSONObject(t)
                JSONArray().also { a -> o.keys().forEach { k -> o.optJSONObject(k)?.let { a.put(it) } } }
            }
            else -> JSONArray()
        }
    }

    private inline fun <T> JSONArray.mapObjects(f: (JSONObject) -> T?): List<T> {
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            f(o)?.let { out.add(it) }
        }
        return out
    }

    private fun JSONObject.str(k: String): String? {
        if (!has(k) || isNull(k)) return null
        val v = opt(k)
        if (v is JSONArray || v is JSONObject) return null
        val s = v?.toString()?.trim()
        return if (s.isNullOrEmpty() || s == "null") null else s
    }

    private fun JSONObject.int(k: String, def: Int = 0): Int = str(k)?.toDoubleOrNull()?.toInt() ?: def
    private fun JSONObject.long(k: String): Long = str(k)?.toDoubleOrNull()?.toLong() ?: 0L
    private fun JSONObject.double(k: String): Double = str(k)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
}
