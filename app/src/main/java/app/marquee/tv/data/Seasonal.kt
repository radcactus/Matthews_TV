package app.marquee.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar

/**
 * A holiday row near the top of Home that changes with the calendar:
 * scary movies through October, Thanksgiving, then Christmas, and so on.
 *
 * Titles come from TMDB's holiday tags when a TMDB key is set, plus a title
 * search and a fitting genre from Cinemeta, so it works without a key too.
 */
object Seasonal {
    private data class Holiday(
        val title: String,
        val tmdbKeyword: String?,
        val searches: List<String>,
        val genre: String?,
    )

    /** Which holiday row to show on a given date, or null outside every season. */
    private fun holidayFor(cal: Calendar): Holiday? {
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val thanksgiving = thanksgivingDay(cal.get(Calendar.YEAR))
        return when {
            m == 10 -> Holiday("Halloween: something scary", "halloween", listOf("halloween"), "Horror")
            m == 11 && d <= thanksgiving -> Holiday("Thanksgiving movies", "thanksgiving", listOf("thanksgiving"), "Family")
            (m == 11 && d > thanksgiving) || (m == 12 && d <= 26) ->
                Holiday("Christmas movies", "christmas", listOf("christmas", "santa"), null)
            (m == 12 && d >= 27) || (m == 1 && d <= 2) ->
                Holiday("New Year's Eve", "new year's eve", listOf("new year"), "Comedy")
            m == 2 && d <= 14 -> Holiday("Valentine's: romance", "valentine's day", listOf("valentine"), "Romance")
            m == 3 && d in 10..17 -> Holiday("St. Patrick's Day", "ireland", listOf("irish"), null)
            (m == 6 && d >= 28) || (m == 7 && d <= 4) ->
                Holiday("Fourth of July", "independence day", listOf("independence day", "america"), "Action")
            m in 6..8 -> Holiday("Summer blockbusters", null, emptyList(), "Action")
            else -> null
        }
    }

    /** Day of month of US Thanksgiving (fourth Thursday of November). */
    private fun thanksgivingDay(year: Int): Int {
        val c = Calendar.getInstance().apply { clear(); set(year, Calendar.NOVEMBER, 1) }
        val firstThursday = 1 + (Calendar.THURSDAY - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
        return firstThursday + 21
    }

    suspend fun build() = coroutineScope {
        val h = holidayFor(Calendar.getInstance())
        if (h == null) {
            Repo.putHomeRow(Repo.SLOT_HOLIDAY, null)
            return@coroutineScope
        }
        val cinemeta = Repo.cinemeta
        val tmdbKey = Repo.prefs.tmdbKey
        val fromTmdb = async {
            if (tmdbKey != null && h.tmdbKeyword != null) Tmdb.moviesWithKeyword(tmdbKey, h.tmdbKeyword) else emptyList()
        }
        val fromSearch = h.searches.map { q ->
            async { runCatching { Repo.catalog(cinemeta, "movie", "top", mapOf("search" to q)) }.getOrDefault(emptyList()) }
        }
        val fromGenre = async {
            h.genre?.let { g -> runCatching { Repo.catalog(cinemeta, "movie", "top", mapOf("genre" to g)) }.getOrNull() }.orEmpty()
        }
        val searched = fromSearch.awaitAll().flatten()
            .filter { (it.rating == 0.0 || it.rating >= 5.5) }      // skip the worst-rated name matches
        val items = (fromTmdb.await() + searched + fromGenre.await().take(20))
            .distinctBy { it.id }
            .take(40)
        Repo.putHomeRow(Repo.SLOT_HOLIDAY, HomeRow("holiday", h.title, items.map { RowItem(it) }))
    }
}
