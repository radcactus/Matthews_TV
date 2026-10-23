package app.marquee.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

/**
 * Themed rows that mix genres, eras and moods, Netflix style:
 * "Mind-Bending Sci-Fi Thrillers", "Feel-Good Comedies from the '90s"…
 *
 * Every day a new set of 8 is picked: mostly ones that fit your taste
 * (from your history and ratings), plus a couple of wildcards, and the titles
 * inside each row are reshuffled. Genres you gave a thumbs-down are skipped,
 * and anything you've watched or rated is left out.
 *
 * With a TMDB key the rows come from TMDB's discover search (which can also
 * filter by runtime and ratings); without one they're built by crossing
 * Cinemeta's genre lists.
 */
object Blends {
    private const val ROWS_PER_DAY = 8
    private const val WILDCARDS = 2

    private data class Blend(
        val id: String,
        val name: String,
        val type: String,
        /** Cinemeta genre names; a title must have all of them. */
        val genres: List<String>,
        val without: List<String> = emptyList(),
        val years: IntRange? = null,
        val minRating: Double = 0.0,
        /** Extra TMDB discover parameters, added to the genre/year/rating filters. */
        val tmdbExtra: String = "",
        /** Only possible with TMDB (e.g. runtime filters). */
        val tmdbOnly: Boolean = false,
    )

    private val all = listOf(
        // ---- movies
        Blend("scifi-thrill", "Mind-Bending Sci-Fi Thrillers", "movie", listOf("Sci-Fi", "Thriller"), minRating = 6.5),
        Blend("90s-comedy", "Feel-Good Comedies from the '90s", "movie", listOf("Comedy"), without = listOf("Horror", "Thriller"), years = 1990..1999, minRating = 6.3),
        Blend("crime-thrill", "Edge-of-Your-Seat Crime Thrillers", "movie", listOf("Crime", "Thriller"), minRating = 6.8),
        Blend("romcom", "Witty Romantic Comedies", "movie", listOf("Romance", "Comedy"), minRating = 6.3),
        Blend("fantasy-quest", "Epic Fantasy Quests", "movie", listOf("Fantasy", "Adventure"), minRating = 6.5),
        Blend("westerns", "Gritty Westerns", "movie", listOf("Western"), minRating = 6.8),
        Blend("acclaimed-drama", "Critically Acclaimed Dramas", "movie", listOf("Drama"), minRating = 8.0, tmdbExtra = "&vote_count.gte=3000"),
        Blend("supernatural", "Spine-Chilling Supernatural Horror", "movie", listOf("Horror", "Mystery"), minRating = 6.0),
        Blend("adult-animation", "Animated Movies for Grown-Ups", "movie", listOf("Animation"), without = listOf("Family"), minRating = 6.8),
        Blend("80s-action", "Explosive '80s Action", "movie", listOf("Action"), years = 1980..1989, minRating = 6.3),
        Blend("true-stories", "True Stories That Hit Hard", "movie", listOf("Biography", "Drama"), minRating = 7.0),
        Blend("sports", "Underdog Sports Stories", "movie", listOf("Sport", "Drama"), minRating = 6.8),
        Blend("dark-mystery", "Dark & Twisty Mysteries", "movie", listOf("Mystery", "Thriller"), minRating = 6.8),
        Blend("war-epics", "Sweeping War Epics", "movie", listOf("War", "Drama"), minRating = 7.2),
        Blend("noir", "Smoky Film Noir Classics", "movie", listOf("Film-Noir"), minRating = 7.0),
        Blend("00s-classics", "Modern Classics from the 2000s", "movie", listOf("Drama"), years = 2000..2009, minRating = 7.6),
        Blend("musicals", "Musicals You'll Hum for Days", "movie", listOf("Musical"), minRating = 6.5),
        Blend("docs", "Brainy Documentaries", "movie", listOf("Documentary"), minRating = 7.5),
        Blend("family-adventure", "Family Movie Night Adventures", "movie", listOf("Family", "Adventure"), minRating = 6.5),
        Blend("action-comedy", "Action-Packed Buddy Comedies", "movie", listOf("Action", "Comedy"), minRating = 6.3),
        Blend("space", "Lost in Space", "movie", listOf("Sci-Fi", "Adventure"), minRating = 6.8),
        Blend("quick-thrills", "Quick Thrills Under 90 Minutes", "movie", listOf("Thriller"), minRating = 6.3,
            tmdbExtra = "&with_runtime.lte=90&with_runtime.gte=70", tmdbOnly = true),
        Blend("hidden-gems", "Hidden Gems You Probably Missed", "movie", emptyList(), minRating = 7.3,
            tmdbExtra = "&vote_count.gte=200&vote_count.lte=1500&sort_by=vote_average.desc", tmdbOnly = true),
        Blend("world-cinema", "Subtitled & Worth It", "movie", listOf("Drama"), minRating = 7.4,
            tmdbExtra = "&without_original_language=en&vote_count.gte=800", tmdbOnly = true),
        // ---- series
        Blend("crime-series", "Bingeable Crime Dramas", "series", listOf("Crime", "Drama"), minRating = 7.8),
        Blend("sitcoms", "Laugh-Out-Loud Sitcoms", "series", listOf("Comedy"), without = listOf("Drama", "Animation"), minRating = 7.3),
        Blend("scifi-series", "Sci-Fi Series Worth the Binge", "series", listOf("Sci-Fi"), minRating = 7.5),
        Blend("prestige", "Prestige TV Everyone Talks About", "series", listOf("Drama"), minRating = 8.4),
        Blend("mystery-series", "Twisty Mystery Series", "series", listOf("Mystery", "Thriller"), minRating = 7.4),
        Blend("adult-anim-series", "Animated Series for Adults", "series", listOf("Animation", "Comedy"), without = listOf("Family"), minRating = 7.3),
        Blend("reality", "Reality TV Guilty Pleasures", "series", listOf("Reality-TV"), minRating = 6.5),
        Blend("docuseries", "Docuseries That Hook You", "series", listOf("Documentary"), minRating = 7.8),
        Blend("fantasy-series", "Swords, Dragons & Sorcery", "series", listOf("Fantasy"), minRating = 7.4),
    )

    // TMDB genre ids for Cinemeta genre names.
    private val tmdbMovieGenres = mapOf(
        "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
        "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27, "Musical" to 10402,
        "Mystery" to 9648, "Romance" to 10749, "Sci-Fi" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37,
    )
    private val tmdbTvGenres = mapOf(
        "Action" to 10759, "Adventure" to 10759, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
        "Drama" to 18, "Family" to 10751, "Fantasy" to 10765, "Sci-Fi" to 10765, "Mystery" to 9648,
        "Thriller" to 9648, "Reality-TV" to 10764, "War" to 10768, "Western" to 37,
    )

    private fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    suspend fun build(taste: Map<String, Map<String, Double>>) = coroutineScope {
        val day = today()
        val rnd = Random(day)
        val tmdbKey = Repo.prefs.tmdbKey
        val usable = all.filter { !it.tmdbOnly || tmdbKey != null }

        // Score each blend against your taste; skip ones built on genres you dislike.
        fun fit(b: Blend): Double? {
            val t = taste[b.type].orEmpty()
            if (b.genres.any { (t[it] ?: 0.0) < -0.5 }) return null
            val max = t.values.maxOrNull()?.takeIf { it > 0 } ?: return 0.0
            return b.genres.sumOf { (t[it] ?: 0.0).coerceAtLeast(0.0) } / max / b.genres.size.coerceAtLeast(1)
        }
        val scored = usable.mapNotNull { b -> fit(b)?.let { b to it + rnd.nextDouble() * 0.5 } }
        val best = scored.sortedByDescending { it.second }.map { it.first }.take(ROWS_PER_DAY - WILDCARDS)
        val wild = scored.map { it.first }.filter { it !in best }.shuffled(rnd).take(WILDCARDS)
        val chosen = (best + wild).shuffled(rnd)

        val exclude = Repo.watchedIds()
        chosen.forEachIndexed { i, b ->
            launch {
                val items = runCatching { load(b, tmdbKey) }.getOrDefault(emptyList())
                    .filter { it.id !in exclude }
                    .distinctBy { it.id }
                    .take(45)
                    .shuffled(Random(day + b.id.hashCode()))     // fresh order every day
                val row = if (items.size >= 8) HomeRow("blend/${b.id}", b.name, items.take(30).map { RowItem(it) }) else null
                Repo.putHomeRow(Repo.SLOT_BLENDS + i, row)
            }
        }
        Repo.clearSlots(Repo.SLOT_BLENDS + chosen.size, Repo.SLOT_BLENDS + 20)
    }

    private suspend fun load(b: Blend, tmdbKey: String?): List<MetaPreview> {
        if (tmdbKey != null) {
            tmdbQuery(b)?.let { q ->
                val r = Tmdb.discover(tmdbKey, b.type, q)
                if (r.size >= 8 || b.tmdbOnly) return r
            }
        }
        return if (b.tmdbOnly) emptyList() else fromCinemeta(b)
    }

    /** TMDB discover query for a blend, or null if a genre has no TMDB equivalent. */
    private fun tmdbQuery(b: Blend): String? {
        val map = if (b.type == "movie") tmdbMovieGenres else tmdbTvGenres
        val with = b.genres.map { map[it] ?: return null }.distinct()
        val without = b.without.mapNotNull { map[it] }.filter { it !in with }.distinct()
        val dateKey = if (b.type == "movie") "primary_release_date" else "first_air_date"
        return buildString {
            append("include_adult=false")
            if (with.isNotEmpty()) append("&with_genres=").append(with.joinToString(","))
            if (without.isNotEmpty()) append("&without_genres=").append(without.joinToString(","))
            b.years?.let { append("&$dateKey.gte=${it.first}-01-01&$dateKey.lte=${it.last}-12-31") }
            if (b.minRating > 0) append("&vote_average.gte=${b.minRating}")
            if ("vote_count" !in b.tmdbExtra) append(if (b.type == "movie") "&vote_count.gte=400" else "&vote_count.gte=150")
            if ("sort_by" !in b.tmdbExtra) append("&sort_by=popularity.desc")
            append(b.tmdbExtra)
        }
    }

    /** Without TMDB: titles that appear in every one of the blend's Cinemeta genre lists. */
    private suspend fun fromCinemeta(b: Blend): List<MetaPreview> = coroutineScope {
        if (b.genres.isEmpty()) return@coroutineScope emptyList()
        val cinemeta = Repo.cinemeta
        suspend fun genreList(g: String): List<MetaPreview> = listOf(0, 100).map { skip ->
            async {
                val extra = if (skip == 0) mapOf("genre" to g) else mapOf("genre" to g, "skip" to "$skip")
                runCatching { Repo.catalog(cinemeta, b.type, "top", extra) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        val lists = b.genres.map { g -> async { genreList(g) } }.awaitAll()
        val excluded = b.without.map { g -> async { genreList(g) } }.awaitAll().flatten().map { it.id }.toHashSet()
        val others = lists.drop(1).map { l -> l.map { it.id }.toHashSet() }
        lists.first()
            .filter { m -> others.all { m.id in it } && m.id !in excluded }
            .filter { m -> b.minRating <= 0 || m.rating == 0.0 || m.rating >= b.minRating }
            .filter { m ->
                val y = b.years ?: return@filter true
                val year = m.releaseInfo?.take(4)?.toIntOrNull() ?: return@filter false
                year in y
            }
            .sortedByDescending { it.rating }
    }
}
