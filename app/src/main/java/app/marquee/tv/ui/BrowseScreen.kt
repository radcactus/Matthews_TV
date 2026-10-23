package app.marquee.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import app.marquee.tv.data.MetaPreview
import app.marquee.tv.data.Repo
import app.marquee.tv.data.RowItem

/**
 * Browse any addon catalog by type and genre, as a scrollable grid that keeps
 * loading as you scroll.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowseScreen(onOpen: (MetaPreview) -> Unit) {
    val addons by Repo.addons.collectAsState()
    var type by rememberSaveable { mutableStateOf("movie") }
    val sources = remember(addons, type) {
        addons.flatMap { a ->
            a.catalogs.filter { c -> c.type == type && c.extras.none { it.isRequired && it.name != "genre" } }
                .map { a to it }
        }
    }
    var sourceIndex by rememberSaveable(type) { mutableStateOf(0) }
    val source = sources.getOrNull(sourceIndex)
    val genres = source?.second?.genres().orEmpty()
    val genreRequired = source?.second?.extras?.any { it.name == "genre" && it.isRequired } == true
    var genre by rememberSaveable(type, sourceIndex) { mutableStateOf<String?>(null) }
    val effectiveGenre = genre ?: if (genreRequired) genres.firstOrNull() else null

    // Kept outside the screen so opening a title and pressing Back returns to
    // the same results and scroll position instead of reloading from the top.
    val session = remember(type, sourceIndex, effectiveGenre) { BrowseMemory.get("$type|$sourceIndex|$effectiveGenre") }
    val items = session.items
    var exhausted by session.exhausted
    var loadingMore by remember(type, sourceIndex, effectiveGenre) { mutableStateOf(false) }
    var page by remember(type, sourceIndex, effectiveGenre) { mutableStateOf(0) }
    val grid = session.grid
    val nearEnd by remember(grid) {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 12
        }
    }

    LaunchedEffect(type, sourceIndex, effectiveGenre, page) {
        val (addon, cat) = source ?: return@LaunchedEffect
        if (exhausted) return@LaunchedEffect
        if (page == 0 && items.isNotEmpty()) return@LaunchedEffect   // restored from BrowseMemory
        loadingMore = true
        val extra = buildMap {
            effectiveGenre?.let { put("genre", it) }
            if (items.isNotEmpty() && cat.supports("skip")) put("skip", items.size.toString())
        }
        val next = runCatching { Repo.catalog(addon, type, cat.id, extra) }.getOrDefault(emptyList())
        val known = items.map { it.id }.toHashSet()
        val fresh = next.filter { it.id !in known }
        if (fresh.isEmpty() || !cat.supports("skip")) exhausted = true
        items.addAll(fresh)
        loadingMore = false
    }
    LaunchedEffect(nearEnd, loadingMore, exhausted) {
        if (nearEnd && !loadingMore && !exhausted && items.isNotEmpty()) page++
    }

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Pill("Movies", type == "movie", { type = "movie" }) }
            item { Pill("Series", type == "series", { type = "series" }) }
            item { Spacer(Modifier.width(18.dp)) }
            itemsIndexed(sources) { i, (addon, cat) ->
                val label = if (sources.count { it.second.name == cat.name } > 1) "${cat.name} · ${addon.name}" else cat.name
                Pill(label, i == sourceIndex, { sourceIndex = i })
            }
        }
        if (genres.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!genreRequired) item { Pill("All genres", effectiveGenre == null, { genre = null }) }
                itemsIndexed(genres) { _, g -> Pill(g, g == effectiveGenre, { genre = g }) }
            }
        }
        when {
            source == null -> Centered("None of your addons have a ${Repo.typeLabel(type).lowercase()} catalog.")
            items.isEmpty() && loadingMore -> Centered("Loading…")
            items.isEmpty() -> Centered("Nothing in this catalog.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(PosterWidth + 14.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(items, key = { _, m -> m.id }) { _, m ->
                    PosterCard(
                        item = RowItem(m),
                        focusKey = "browse/$type/${m.id}",
                        onClick = { onOpen(m) },
                    )
                }
            }
        }
    }
}

private class BrowseSession {
    val items = mutableStateListOf<MetaPreview>()
    val exhausted = mutableStateOf(false)
    val grid = LazyGridState()
}

/** Remembers the last Browse results while a title page is open on top. */
private object BrowseMemory {
    private var key: String? = null
    private var session: BrowseSession? = null

    fun get(k: String): BrowseSession {
        val existing = session
        if (k == key && existing != null) return existing
        return BrowseSession().also { key = k; session = it }
    }
}
