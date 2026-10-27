package app.marquee.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    // The TMDB <-> IMDb id pairs never change, so they're saved between launches.
    private lateinit var idPrefs: android.content.SharedPreferences
    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun init(context: android.content.Context) {
        idPrefs = context.getSharedPreferences("tmdb_ids", android.content.Context.MODE_PRIVATE)
        idPrefs.all.forEach { (k, v) ->
            when {
                k.startsWith("i:") && v is String -> imdbCache[k.removePrefix("i:")] = v
                k.startsWith("t:") && v is Int -> tmdbCache[k.removePrefix("t:")] = v
            }
        }
    }

    private fun scheduleSave() {
        if (!::idPrefs.isInitialized || !savePending.compareAndSet(false, true)) return
        Repo.scope.launch {
            kotlinx.coroutines.delay(5_000)
            savePending.set(false)
            idPrefs.edit().apply {
                imdbCache.forEach { (k, v) -> putString("i:$k", v) }
                tmdbCache.forEach { (k, v) -> putInt("t:$k", v) }
            }.apply()
        }
    }

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
        scheduleSave()
        return id
    }

    private suspend fun imdbFor(key: String, type: String, tmdbId: Int): String? {
        val k = "${tmdbType(type)}/$tmdbId"
        imdbCache[k]?.let { return it }
        val r = get(key, "/$k/external_ids") ?: return null
        val imdb = r.str("imdb_id")?.takeIf { it.startsWith("tt") } ?: return null
        imdbCache[k] = imdb
        scheduleSave()
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

    private val keywordCache = ConcurrentHashMap<String, List<MetaPreview>>()

    /** Popular, well-reviewed movies tagged with a TMDB keyword (e.g. "christmas"), as IMDb ids. */
    suspend fun moviesWithKeyword(key: String, keyword: String, limit: Int = 30): List<MetaPreview> {
        keywordCache[keyword]?.let { return it }
        val search = get(key, "/search/keyword?query=${android.net.Uri.encode(keyword)}") ?: return emptyList()
        val kw = (search.arr("results")?.firstOrNull() as? JsonObject)?.long("id") ?: return emptyList()
        val found = get(key, "/discover/movie?with_keywords=$kw&sort_by=popularity.desc&vote_count.gte=150") ?: return emptyList()
        val results = found.arr("results")?.mapNotNull { it as? JsonObject }.orEmpty().take(limit)
        val metas = coroutineScope {
            results.map { o ->
                async {
                    val tid = o.long("id")?.toInt() ?: return@async null
                    val imdb = imdbFor(key, "movie", tid) ?: return@async null
                    MetaPreview(
                        id = imdb,
                        type = "movie",
                        name = o.str("title").orEmpty(),
                        poster = o.str("poster_path")?.let { "$IMG/w342$it" },
                        background = o.str("backdrop_path")?.let { "$IMG/w1280$it" },
                        description = o.str("overview"),
                        releaseInfo = o.str("release_date")?.take(4),
                        imdbRating = o.double("vote_average")?.let { String.format(Locale.US, "%.1f", it) },
                    )
                }
            }.awaitAll().filterNotNull()
        }
        if (metas.isNotEmpty()) keywordCache[keyword] = metas
        return metas
    }

    private val runtimeCache = ConcurrentHashMap<String, Map<String, Int>>()

    /**
     * Runtime in minutes per episode video id ("tt123:2:5") for one season,
     * or for a movie under its own id. Empty if TMDB doesn't know.
     */
    suspend fun runtimes(key: String, type: String, imdbId: String, season: Int?): Map<String, Int> {
        val ck = "$type/$imdbId/$season"
        runtimeCache[ck]?.let { return it }
        val id = tmdbId(key, type, imdbId) ?: return emptyMap()
        val out = if (type == "movie") {
            val r = get(key, "/movie/$id") ?: return emptyMap()
            r.long("runtime")?.toInt()?.takeIf { it > 0 }?.let { mapOf(imdbId to it) }.orEmpty()
        } else {
            val sn = season ?: return emptyMap()
            val r = get(key, "/tv/$id/season/$sn") ?: return emptyMap()
            r.arr("episodes")?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { e ->
                val ep = e.long("episode_number")?.toInt() ?: return@mapNotNull null
                val rt = e.long("runtime")?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                "$imdbId:$sn:$ep" to rt
            }.toMap()
        }
        runtimeCache[ck] = out
        return out
    }

    private val discoverCache = ConcurrentHashMap<String, List<MetaPreview>>()

    /**
     * TMDB's discover search (genres, years, ratings, runtime…) for movies or
     * series, returned with IMDb ids so your addons can play them.
     * [query] is the URL query, e.g. "with_genres=878,53&vote_count.gte=500".
     */
    suspend fun discover(key: String, type: String, query: String, limit: Int = 40): List<MetaPreview> {
        val ck = "$type?$query"
        discoverCache[ck]?.let { return it }
        val path = "/discover/${tmdbType(type)}?$query"
        val pages = coroutineScope { (1..2).map { pg -> async { get(key, "$path&page=$pg") } }.awaitAll() }
        val results = pages.filterNotNull().flatMap { it.arr("results")?.mapNotNull { r -> r as? JsonObject }.orEmpty() }
            .take(limit)
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
        if (metas.isNotEmpty()) discoverCache[ck] = metas
        return metas
    }
}
