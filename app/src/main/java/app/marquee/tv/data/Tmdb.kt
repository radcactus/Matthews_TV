package app.marquee.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional TMDB lookups for "Because you watched" and "More like this".
 * Accepts either a v3 API key or a v4 read access token.
 */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"
    private val imdbCache = ConcurrentHashMap<String, String>()   // "movie/123" -> tt...
    private val tmdbCache = ConcurrentHashMap<String, Int>()      // tt... -> 123
    private val recCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val gate = Semaphore(6)

    private suspend fun get(key: String, path: String): JsonObject? {
        val bearer = key.startsWith("eyJ")
        val sep = if (path.contains("?")) "&" else "?"
        val url = if (bearer) BASE + path else "$BASE$path${sep}api_key=$key"
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
        return gate.withPermit { runCatching { Http.get(url, headers) as? JsonObject }.getOrNull() }
    }

    private fun tmdbType(type: String) = if (type == "series") "tv" else "movie"

    private suspend fun tmdbId(key: String, type: String, imdbId: String): Int? {
        tmdbCache[imdbId]?.let { return it }
        val r = get(key, "/find/$imdbId?external_source=imdb_id") ?: return null
        val list = if (type == "series") r.arr("tv_results") else r.arr("movie_results")
        val id = (list?.firstOrNull() as? JsonObject)?.long("id")?.toInt() ?: return null
        tmdbCache[imdbId] = id
        return id
    }

    private suspend fun imdbFor(key: String, type: String, tmdbId: Int): String? {
        val k = "${tmdbType(type)}/$tmdbId"
        imdbCache[k]?.let { return it }
        val r = get(key, "/$k/external_ids") ?: return null
        val imdb = r.str("imdb_id")?.takeIf { it.startsWith("tt") } ?: return null
        imdbCache[k] = imdb
        return imdb
    }

    /** Recommendations for an IMDb id, mapped back to IMDb ids so addons can serve them. */
    suspend fun recommendations(key: String, type: String, imdbId: String, limit: Int = 18): List<MetaPreview> {
        recCache["$type/$imdbId"]?.let { return it }
        val id = tmdbId(key, type, imdbId) ?: return emptyList()
        val r = get(key, "/${tmdbType(type)}/$id/recommendations") ?: return emptyList()
        val results = r.arr("results")?.mapNotNull { it as? JsonObject }.orEmpty().take(limit)
        val metas = coroutineScope {
            results.map { o ->
                async {
                    val tid = o.long("id")?.toInt() ?: return@async null
                    val imdb = imdbFor(key, type, tid) ?: return@async null
                    val date = o.str("release_date") ?: o.str("first_air_date")
                    MetaPreview(
                        id = imdb,
                        type = type,
                        name = o.str("title") ?: o.str("name").orEmpty(),
                        poster = o.str("poster_path")?.let { "$IMG/w342$it" },
                        background = o.str("backdrop_path")?.let { "$IMG/w1280$it" },
                        description = o.str("overview"),
                        releaseInfo = date?.take(4),
                        imdbRating = o.double("vote_average")?.let { String.format(Locale.US, "%.1f", it) },
                    )
                }
            }.awaitAll().filterNotNull()
        }
        if (metas.isNotEmpty()) recCache["$type/$imdbId"] = metas
        return metas
    }
}
