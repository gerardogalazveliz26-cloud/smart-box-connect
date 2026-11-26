package com.nexumid.iptv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) NexumIDIPTV/1.0"

    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("nexumid", Context.MODE_PRIVATE)
    }

    // ---------------- cuentas / listas

    /** [remote] = cargada por el proveedor con el código de activación. */
    data class Profile(val name: String, val server: String, val user: String, val pass: String, val remote: Boolean = false) {
        val key: String get() = "${server.lowercase()}|$user"
        fun toJson(): JSONObject = JSONObject().put("name", name).put("server", server).put("user", user)
            .put("pass", pass).put("remote", remote)
    }

    var profiles: List<Profile>
        get() = try {
            val a = JSONArray(sp.getString("profiles", "[]"))
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    Profile(o.optString("name"), o.optString("server"), o.optString("user"), o.optString("pass"), o.optBoolean("remote"))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        private set(v) {
            val a = JSONArray()
            v.forEach { a.put(it.toJson()) }
            sp.edit().putString("profiles", a.toString()).apply()
        }

    var activeKey: String?
        get() = sp.getString("active", null)
        set(v) = sp.edit().putString("active", v).apply()

    val current: Profile? get() = activeKey?.let { k -> profiles.firstOrNull { it.key == k } }

    fun saveProfile(p: Profile) {
        profiles = listOf(p) + profiles.filter { it.key != p.key }
        activeKey = p.key
    }

    /** Reemplaza las listas cargadas a distancia por [list], conservando las ingresadas a mano. */
    fun replaceRemoteProfiles(list: List<Profile>) {
        val keys = list.map { it.key }.toSet()
        profiles = list + profiles.filter { !it.remote && it.key !in keys }
        val active = activeKey
        if (active == null || profiles.none { it.key == active }) activeKey = profiles.firstOrNull()?.key
    }

    fun deleteProfile(p: Profile) {
        profiles = profiles.filter { it.key != p.key }
        if (activeKey == p.key) activeKey = null
    }

    /** Prefijo para que favoritos e historial sean propios de cada lista. */
    private val scope: String get() = Integer.toHexString((current?.key ?: "none").hashCode())

    // ---------------- activación remota

    var deviceCode: String?
        get() = sp.getString("device_code", null)
        set(v) = sp.edit().putString("device_code", v).apply()

    var activated: Boolean
        get() = sp.getBoolean("activated", false)
        set(v) = sp.edit().putBoolean("activated", v).apply()

    var activationStamp: Long
        get() = sp.getLong("activation_stamp", 0L)
        set(v) = sp.edit().putLong("activation_stamp", v).apply()

    // ---------------- tipo de equipo

    /** null = aún no elegido, "tv" o "mobile". */
    var deviceMode: String?
        get() = sp.getString("device_mode", null)
        set(v) = sp.edit().putString("device_mode", v).apply()

    // ---------------- reproducción

    /** "ts" o "m3u8" */
    var liveFormat: String
        get() = sp.getString("live_fmt", "ts") ?: "ts"
        set(v) = sp.edit().putString("live_fmt", v).apply()

    /** 0 normal, 1 alto, 2 máximo */
    var bufferMode: Int
        get() = sp.getInt("buffer", 0)
        set(v) = sp.edit().putInt("buffer", v).apply()

    var userAgent: String
        get() = sp.getString("ua", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
        set(v) = sp.edit().putString("ua", v).apply()

    /** 0 = FFmpeg primero (máxima compatibilidad de audio), 1 = decodificador del equipo primero. */
    var audioMode: Int
        get() = sp.getInt("audio_mode", 0)
        set(v) = sp.edit().putInt("audio_mode", v).apply()

    var autoplayNext: Boolean
        get() = sp.getBoolean("autoplay_next", true)
        set(v) = sp.edit().putBoolean("autoplay_next", v).apply()

    // ---------------- control parental

    var hideAdult: Boolean
        get() = sp.getBoolean("hide_adult", true)
        set(v) = sp.edit().putBoolean("hide_adult", v).apply()

    var pin: String
        get() = sp.getString("pin", "0000") ?: "0000"
        set(v) = sp.edit().putString("pin", v).apply()

    // ---------------- TV en vivo

    var lastLiveCategory: String?
        get() = sp.getString("last_cat_$scope", null)
        set(v) = sp.edit().putString("last_cat_$scope", v).apply()

    fun recentLive(): List<Int> =
        sp.getString("recent_$scope", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    fun pushRecentLive(id: Int) {
        val l = (listOf(id) + recentLive().filter { it != id }).take(30)
        sp.edit().putString("recent_$scope", l.joinToString(",")).apply()
    }

    // ---------------- favoritos: type = "live" | "vod" | "series"

    private fun favKey(type: String) = "fav_${scope}_$type"

    fun favorites(type: String): Set<String> = sp.getStringSet(favKey(type), null)?.toSet() ?: emptySet()

    fun isFav(type: String, id: Any) = favorites(type).contains(id.toString())

    /** Devuelve true si quedó marcado como favorito. */
    fun toggleFav(type: String, id: Any): Boolean {
        val s = favorites(type).toMutableSet()
        val k = id.toString()
        val added = if (s.remove(k)) false else s.add(k)
        sp.edit().putStringSet(favKey(type), s).apply()
        return added
    }

    // ---------------- seguir viendo

    data class Resume(
        val key: String,          // "vod:123" | "ep:456"
        val type: String,         // "vod" | "ep"
        val id: String,
        val ext: String,
        val title: String,
        val image: String?,
        val pos: Long,
        val dur: Long,
        val time: Long,
        val seriesId: Int = 0,
        val seriesName: String? = null,
        val season: Int = 0,
        val episode: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("key", key).put("type", type).put("id", id).put("ext", ext).put("title", title)
            .put("image", image ?: "").put("pos", pos).put("dur", dur).put("time", time)
            .put("seriesId", seriesId).put("seriesName", seriesName ?: "").put("season", season).put("episode", episode)

        companion object {
            fun from(o: JSONObject) = Resume(
                key = o.optString("key"), type = o.optString("type"), id = o.optString("id"),
                ext = o.optString("ext"), title = o.optString("title"),
                image = o.optString("image").ifBlank { null }, pos = o.optLong("pos"), dur = o.optLong("dur"),
                time = o.optLong("time"), seriesId = o.optInt("seriesId"),
                seriesName = o.optString("seriesName").ifBlank { null },
                season = o.optInt("season"), episode = o.optInt("episode"),
            )
        }
    }

    private fun resumeObj(): JSONObject = try {
        JSONObject(sp.getString("resume_$scope", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun saveResumeObj(o: JSONObject) = sp.edit().putString("resume_$scope", o.toString()).apply()

    fun resume(key: String): Resume? = resumeObj().optJSONObject(key)?.let { Resume.from(it) }

    fun resumes(): List<Resume> {
        val o = resumeObj()
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it)?.let(Resume::from) }
            .sortedByDescending { it.time }.toList()
    }

    fun saveResume(r: Resume) {
        val o = resumeObj()
        // Una sola entrada por serie: la del último episodio visto.
        if (r.type == "ep" && r.seriesId != 0) {
            o.keys().asSequence().toList().forEach { k ->
                val e = o.optJSONObject(k)
                if (k != r.key && e != null && e.optString("type") == "ep" && e.optInt("seriesId") == r.seriesId) o.remove(k)
            }
        }
        o.put(r.key, r.toJson())
        // Mantener como máximo 40 entradas.
        val all = o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { k to it.optLong("time") } }
            .sortedByDescending { it.second }.toList()
        all.drop(40).forEach { o.remove(it.first) }
        saveResumeObj(o)
    }

    fun removeResume(key: String) {
        val o = resumeObj()
        o.remove(key)
        saveResumeObj(o)
    }

    fun clearResumes() = saveResumeObj(JSONObject())
}
