package app.marquee.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/** Your verdict on a title. */
enum class Rating(val value: Int, val label: String) {
    UP(1, "Thumbs up"),
    MEH(0, "Meh"),
    DOWN(-1, "Thumbs down");

    companion object {
        fun of(v: Int?) = entries.firstOrNull { it.value == v }
    }
}

@Serializable
data class TitleInfo(
    val type: String,
    val name: String,
    val poster: String? = null,
    val genres: List<String> = emptyList(),
    val at: Long = 0,
)

@Serializable
private data class WatchData(
    /** Title id -> rating value (1, 0, -1). */
    val ratings: Map<String, Int> = emptyMap(),
    /** Movies you finished or marked watched: id -> when. */
    val movies: Map<String, Long> = emptyMap(),
    /** Series id -> episode video ids you finished. */
    val episodes: Map<String, Set<String>> = emptyMap(),
    /** Series you marked as fully watched: id -> when. */
    val seriesMarked: Map<String, Long> = emptyMap(),
    /** Series id -> number of released regular episodes, as of the last time we looked. */
    val seriesTotals: Map<String, Int> = emptyMap(),
    /** Name, poster and genres for everything above, so rows can be drawn without a lookup. */
    val titles: Map<String, TitleInfo> = emptyMap(),
)

/**
 * What you've watched and how you rated it, kept on this TV.
 *
 * A movie counts as watched when you finish it (92%+), mark it, rate it, or
 * Stremio has it as watched. A series counts as watched only when every
 * released episode is watched, or you mark or rate the whole series.
 */
object WatchStore {
    private const val KEY = "watchData"
    private lateinit var sp: android.content.SharedPreferences
    private var data = WatchData()
    private var stremioMovies: Set<String> = emptySet()

    /** isWatched() answers, cleared on every change (it's asked for every poster on screen). */
    private val watchedMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Bumped on every change so the UI redraws tags and rows. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("marquee", Context.MODE_PRIVATE)
        data = runCatching {
            Http.json.decodeFromString(WatchData.serializer(), sp.getString(KEY, null) ?: "{}")
        }.getOrDefault(WatchData())
    }

    private fun update(change: (WatchData) -> WatchData) {
        synchronized(this) {
            data = change(data)
            sp.edit().putString(KEY, Http.json.encodeToString(WatchData.serializer(), data)).apply()
            watchedMemo.clear()
        }
        version++
    }

    /** Movies Stremio itself has as watched (from your library). */
    fun setStremioWatched(library: List<LibItem>) {
        stremioMovies = library.filter { it.type == "movie" && (it.timesWatched > 0 || it.flaggedWatched) }
            .map { it.id }.toSet()
        watchedMemo.clear()
        version++
    }

    private fun info(meta: MetaPreview, genres: List<String> = meta.allGenres) =
        TitleInfo(meta.type, meta.name, meta.poster ?: meta.posterUrl, genres, System.currentTimeMillis())

    // ---- ratings ------------------------------------------------------------

    fun rating(id: String): Rating? = Rating.of(data.ratings[id])

    fun ratings(): Map<String, Pair<Rating, TitleInfo?>> =
        data.ratings.mapNotNull { (id, v) -> Rating.of(v)?.let { id to (it to data.titles[id]) } }.toMap()

    /** Rates a title and marks it watched (for a series: the whole series). */
    fun rate(meta: MetaPreview, rating: Rating, genres: List<String> = meta.allGenres) = update { d ->
        val now = System.currentTimeMillis()
        d.copy(
            ratings = d.ratings + (meta.id to rating.value),
            movies = if (meta.type == "movie") d.movies + (meta.id to now) else d.movies,
            seriesMarked = if (meta.type != "movie") d.seriesMarked + (meta.id to now) else d.seriesMarked,
            titles = d.titles + (meta.id to info(meta, genres)),
        )
    }

    /** Clears the rating and the watched mark (episodes you finished stay). */
    fun markUnwatched(id: String) = update { d ->
        d.copy(
            ratings = d.ratings - id,
            movies = d.movies - id,
            seriesMarked = d.seriesMarked - id,
        )
    }

    // ---- watched ------------------------------------------------------------

    /** Called when the player reaches the end of a movie or episode. */
    fun markFinished(id: String, type: String, videoId: String, name: String, poster: String?) = update { d ->
        val now = System.currentTimeMillis()
        val title = d.titles[id]?.copy(at = now) ?: TitleInfo(type, name, poster, emptyList(), now)
        if (type == "movie" || videoId == id) {
            d.copy(movies = d.movies + (id to now), titles = d.titles + (id to title))
        } else {
            val eps = d.episodes[id].orEmpty() + videoId
            d.copy(episodes = d.episodes + (id to eps), titles = d.titles + (id to title))
        }
    }

    fun setEpisodeWatched(seriesId: String, videoId: String, watched: Boolean, meta: MetaPreview) = update { d ->
        val eps = d.episodes[seriesId].orEmpty().let { if (watched) it + videoId else it - videoId }
        d.copy(
            episodes = d.episodes + (seriesId to eps),
            // Un-watching an episode means the series isn't fully watched any more.
            seriesMarked = if (watched) d.seriesMarked else d.seriesMarked - seriesId,
            titles = if (d.titles.containsKey(seriesId)) d.titles else d.titles + (seriesId to info(meta)),
        )
    }

    /** Remembers how many regular episodes a series has released so far. */
    fun setSeriesTotal(meta: MetaDetail) {
        val now = System.currentTimeMillis()
        val released = meta.episodes.count { v ->
            (v.season ?: 0) > 0 && (v.released == null || Dates.parse(v.released).let { it == 0L || it <= now })
        }
        if (released <= 0 || data.seriesTotals[meta.id] == released) return
        update { d -> d.copy(seriesTotals = d.seriesTotals + (meta.id to released)) }
    }

    fun episodeWatched(seriesId: String, videoId: String): Boolean =
        data.seriesMarked.containsKey(seriesId) || data.episodes[seriesId]?.contains(videoId) == true

    private fun regularEpisodesWatched(seriesId: String): Int =
        data.episodes[seriesId].orEmpty().count { v ->
            v.split(":").let { p -> p.size >= 3 && (p[p.size - 2].toIntOrNull() ?: 0) > 0 }
        }

    fun isWatched(id: String, type: String): Boolean = watchedMemo.getOrPut("$type/$id") { computeWatched(id, type) }

    private fun computeWatched(id: String, type: String): Boolean = when {
        type == "movie" -> data.movies.containsKey(id) || id in stremioMovies
        data.seriesMarked.containsKey(id) -> true
        else -> data.seriesTotals[id]?.let { total -> total > 0 && regularEpisodesWatched(id) >= total } ?: false
    }

    /** Everything fully watched, best rated and most recent first; thumbs-down titles left out. */
    fun watchedTitles(): List<MetaPreview> {
        val local = data.titles.filter { (id, t) -> isWatched(id, t.type) && data.ratings[id] != -1 }
            .map { (id, t) -> MetaPreview(id = id, type = t.type, name = t.name, poster = t.poster, genres = t.genres) to t.at }
        val fromStremio = Repo.library.value
            .filter { it.id in stremioMovies && it.id !in data.titles && data.ratings[it.id] != -1 }
            .map { it.preview() to it.lastWatched }
        return (local + fromStremio)
            .sortedWith(compareByDescending<Pair<MetaPreview, Long>> { data.ratings[it.first.id] ?: 0 }.thenByDescending { it.second })
            .map { it.first }
            .distinctBy { it.id }
    }

    /** Ids to keep out of recommendations: anything rated or fully watched. */
    fun excludedIds(): Set<String> = data.ratings.keys + data.movies.keys + data.seriesMarked.keys + stremioMovies
}
