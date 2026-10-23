package app.marquee.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Personal rows built from your own watch history, not Stremio's generic lists.
 *
 * 1. Genre taste: recent titles weigh more (decay per position in history).
 * 2. "Picked for you": candidates from Cinemeta's genre catalogs, scored by
 *    how well their genres match your taste plus IMDb rating, minus anything
 *    you've already watched or saved.
 * 3. "Top <genre>" rows for your strongest genres per type.
 * 4. With a TMDB key: "Because you watched <title>" for your latest few titles.
 *
 * Your ratings steer all of it: thumbs-up titles count double and are the
 * first "Because you watched" seeds, meh counts a little, and thumbs-down
 * pushes those genres down. Anything rated or watched is never recommended.
 * Each title shows up in only one row (see Repo.publish).
 */
object Recommender {
    private const val DECAY = 0.9

    suspend fun build() = coroutineScope {
        val ratings = WatchStore.ratings()
        val history = Repo.historySeeds()
        val rated = ratings.mapNotNull { (id, r) ->
            r.second?.let { t -> MetaPreview(id = id, type = t.type, name = t.name, poster = t.poster, genres = t.genres) }
        }.filter { it.id.startsWith("tt") && (it.type == "movie" || it.type == "series") }
        val seeds = (history + rated).distinctBy { it.id }
        if (seeds.isEmpty()) {
            Repo.putHomeRow(Repo.SLOT_PICKS, null)
            Repo.clearSlots(Repo.SLOT_BECAUSE, Repo.SLOT_GENRE + 20)
            return@coroutineScope
        }
        val exclude = Repo.watchedIds()

        // TMDB rows run in parallel with the genre work.
        val tmdbKey = Repo.prefs.tmdbKey
        // Best seeds for "Because you watched": thumbs-up first, then recent
        // history, never meh or thumbs-down titles.
        val becauseSeeds = (
            rated.filter { ratings[it.id]?.first == Rating.UP }
                .sortedByDescending { ratings[it.id]?.second?.at ?: 0 } +
                history.filter { WatchStore.rating(it.id) == null }
            ).distinctBy { it.id }
        if (tmdbKey != null) {
            becauseSeeds.take(3).forEachIndexed { i, seed ->
                launch {
                    val recs = Tmdb.recommendations(tmdbKey, seed.type, seed.id)
                        .filter { it.id !in exclude }.distinctBy { it.id }
                    Repo.putHomeRow(
                        Repo.SLOT_BECAUSE + i,
                        HomeRow("because/${seed.id}", "Because you watched ${seed.name}", recs.map { RowItem(it) }),
                    )
                }
            }
        } else {
            (0 until 3).forEach { Repo.putHomeRow(Repo.SLOT_BECAUSE + it, null) }
        }

        // Genre taste per type.
        val metas = history.map { s -> async { Repo.meta(s.type, s.id) } }.awaitAll()
        val taste = mutableMapOf<String, MutableMap<String, Double>>()
        fun add(type: String, genres: List<String>, w: Double) {
            val byType = taste.getOrPut(type) { mutableMapOf() }
            genres.forEach { g -> byType[g] = (byType[g] ?: 0.0) + w }
        }
        metas.forEachIndexed { i, m ->
            if (m == null || WatchStore.rating(m.id) != null) return@forEachIndexed   // rated ones are added below
            add(m.type, m.allGenres, DECAY.pow(i))
        }
        ratings.forEach { (_, r) ->
            val t = r.second ?: return@forEach
            add(t.type, t.genres, when (r.first) { Rating.UP -> 2.0; Rating.MEH -> 0.3; Rating.DOWN -> -1.5 })
        }

        val cinemeta = Repo.cinemeta
        val picks = mutableListOf<Pair<MetaPreview, Double>>()
        var slot = Repo.SLOT_GENRE
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        for (type in listOf("movie", "series")) {
            val genres = taste[type] ?: continue
            val maxW = genres.values.maxOrNull()?.takeIf { it > 0 } ?: continue
            val top = genres.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(3).map { it.key }
            val catalog = cinemeta.catalogs.firstOrNull { it.type == type && it.id == "top" } ?: continue
            val known = catalog.genres()
            for (g in top) {
                if (known.isNotEmpty() && g !in known) continue
                val s = slot++
                jobs += async {
                    val items = runCatching { Repo.catalog(cinemeta, type, "top", mapOf("genre" to g)) }
                        .getOrDefault(emptyList())
                        .filter { it.id !in exclude }
                    val scored = items.map { m ->
                        val gs = (m.allGenres + g).distinct()
                        val affinity = gs.sumOf { genres[it] ?: 0.0 } / maxW
                        m to (0.55 * (affinity.coerceIn(-1.0, 2.0) / 2.0) + 0.45 * (m.rating / 10.0))
                    }
                    synchronized(picks) { picks += scored }
                    // Extra items so the row still fills after titles shown higher up are removed.
                    val row = scored.sortedByDescending { it.second }.map { RowItem(it.first) }.take(45)
                    Repo.putHomeRow(s, HomeRow("genre/$type/$g", "Top $g ${Repo.typeLabel(type).lowercase()} for you", row))
                }
            }
        }
        jobs.awaitAll()
        Repo.clearSlots(slot, Repo.SLOT_GENRE + 20)

        // Blend movies and series, best first, without repeats.
        val best = picks.groupBy { it.first.id }.map { (_, v) -> v.maxBy { it.second } }
        val movies = best.filter { it.first.type == "movie" }.sortedByDescending { it.second }.map { it.first }
        val series = best.filter { it.first.type == "series" }.sortedByDescending { it.second }.map { it.first }
        val blended = buildList {
            val n = maxOf(movies.size, series.size)
            for (i in 0 until n) {
                movies.getOrNull(i)?.let { add(it) }
                series.getOrNull(i)?.let { add(it) }
            }
        }.take(20)
        Repo.putHomeRow(Repo.SLOT_PICKS, HomeRow("picks", "Picked for you", blended.map { RowItem(it) }))
    }

    /** "More like this" on the detail screen. */
    suspend fun moreLike(meta: MetaDetail): List<MetaPreview> {
        val exclude = Repo.watchedIds() + meta.id
        val key = Repo.prefs.tmdbKey
        if (key != null && meta.id.startsWith("tt")) {
            val recs = Tmdb.recommendations(key, meta.type, meta.id).filter { it.id !in exclude }
            if (recs.isNotEmpty()) return recs
        }
        val genres = meta.allGenres.take(2)
        if (genres.isEmpty()) return emptyList()
        val cinemeta = Repo.cinemeta
        return coroutineScope {
            genres.map { g ->
                async { runCatching { Repo.catalog(cinemeta, meta.type, "top", mapOf("genre" to g)) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.flatten()
            .filter { it.id !in exclude }
            .groupBy { it.id }
            .map { (_, v) -> v.first() to v.size }               // in both genres ranks higher
            .sortedWith(compareByDescending<Pair<MetaPreview, Int>> { it.second }.thenByDescending { it.first.rating })
            .map { it.first }
            .take(24)
    }
}
