package com.nexumid.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Sesión activa y caché en memoria del contenido del panel. */
object Repo {
    @Volatile
    var api: XtreamApi? = null
        private set

    @Volatile
    var account: Account? = null

    fun restore() {
        val p = Prefs.current ?: return
        api = try {
            XtreamApi(p.server, p.user, p.pass)
        } catch (e: Exception) {
            null
        }
    }

    fun use(p: Prefs.Profile, acc: Account?) {
        api = XtreamApi(p.server, p.user, p.pass)
        account = acc
        clear()
    }

    fun logout() {
        api = null
        account = null
        clear()
    }

    fun requireApi(): XtreamApi = api ?: throw XtreamException("La sesión se cerró, vuelve a iniciar sesión")

    private class Cache<T : Any>(private val loader: suspend () -> T) {
        private val mutex = Mutex()

        @Volatile
        private var value: T? = null

        suspend fun get(): T = value ?: mutex.withLock { value ?: withContext(Dispatchers.Default) { loader() }.also { value = it } }
        fun peek(): T? = value
        fun clear() {
            value = null
        }
    }

    /** Equipo con poca memoria: no se guardan películas y series a la vez. */
    @Volatile
    var lowRam = false

    /** Libera lo que no se está usando cuando Android avisa que falta memoria. */
    fun trim() {
        vod.clear(); series.clear(); epg.clear()
    }

    private val liveCats = Cache { requireApi().liveCategories() }
    private val vodCats = Cache { requireApi().vodCategories() }
    private val seriesCats = Cache { requireApi().seriesCategories() }
    private val live = Cache { requireApi().liveStreams() }
    private val vod = Cache { requireApi().vodStreams() }
    private val series = Cache { requireApi().series() }
    private val epg = ConcurrentHashMap<Int, Pair<Long, List<EpgEntry>>>()

    fun clear() {
        liveCats.clear(); vodCats.clear(); seriesCats.clear()
        live.clear(); vod.clear(); series.clear()
        epg.clear()
    }

    // ---------------- control parental

    private val adultRegex = Regex("(?i)(xxx|adult|adulto|\\+18|18\\+|porn|erotic|er[oó]tico)")

    fun isAdult(c: Category) = adultRegex.containsMatchIn(c.name)

    private fun visible(list: List<Category>) = if (Prefs.hideAdult) list.filterNot(::isAdult) else list

    private fun hidden(list: List<Category>): Set<String> =
        if (Prefs.hideAdult) list.filter(::isAdult).map { it.id }.toHashSet() else emptySet()

    // ---------------- acceso

    suspend fun liveCategories(): List<Category> = visible(liveCats.get())
    suspend fun vodCategories(): List<Category> = visible(vodCats.get())
    suspend fun seriesCategories(): List<Category> = visible(seriesCats.get())

    suspend fun liveStreams(): List<LiveStream> {
        val h = hidden(liveCats.get())
        val all = live.get()
        return if (h.isEmpty()) all else all.filter { it.categoryId !in h }
    }

    suspend fun vodStreams(): List<Poster> {
        if (lowRam) series.clear()
        val h = hidden(vodCats.get())
        val all = vod.get()
        return if (h.isEmpty()) all else all.filter { it.categoryId !in h }
    }

    suspend fun series(): List<Poster> {
        if (lowRam) vod.clear()
        val h = hidden(seriesCats.get())
        val all = series.get()
        return if (h.isEmpty()) all else all.filter { it.categoryId !in h }
    }

    fun cachedLive(): List<LiveStream>? = live.peek()

    suspend fun epg(streamId: Int): List<EpgEntry> {
        val now = System.currentTimeMillis()
        epg[streamId]?.let { (t, l) ->
            if (now - t < 15 * 60_000 && (l.isEmpty() || l.last().end > now)) return l
        }
        val l = try {
            withContext(Dispatchers.Default) { requireApi().shortEpg(streamId, 6) }
        } catch (e: Exception) {
            emptyList()
        }
        epg[streamId] = now to l
        return l
    }

    fun nowNext(list: List<EpgEntry>): Pair<EpgEntry?, EpgEntry?> {
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { now >= it.start && now < it.end }
        if (i < 0) return null to list.firstOrNull { it.start > now }
        return list[i] to list.getOrNull(i + 1)
    }
}
