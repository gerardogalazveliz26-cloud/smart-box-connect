package com.nexumid.iptv.data

data class Category(val id: String, val name: String)

data class LiveStream(
    val id: Int,
    val num: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val epgId: String?,
    val archive: Boolean,
)

/** Película o serie, tal como se muestra en las grillas. */
data class Poster(
    val id: Int,
    val name: String,
    val image: String?,
    val categoryId: String?,
    val rating: Double,
    val added: Long,
    val ext: String,
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int,
)

data class SeriesInfo(
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val year: String?,
    val rating: Double,
    val episodes: List<Episode>,
) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
}

data class VodInfo(
    val id: Int,
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val year: String?,
    val durationSecs: Int,
    val rating: Double,
    val ext: String,
)

data class EpgEntry(val title: String, val desc: String, val start: Long, val end: Long)

data class Account(
    val username: String,
    val status: String,
    val expDate: Long?,
    val activeCons: Int,
    val maxCons: Int,
    val isTrial: Boolean,
)
