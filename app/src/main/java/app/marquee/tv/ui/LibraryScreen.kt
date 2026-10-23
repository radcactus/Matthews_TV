package app.marquee.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(onOpen: (MetaPreview) -> Unit) {
    val library by Repo.library.collectAsState()
    val signedIn by Repo.signedIn.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("recent") }

    val items = remember(library, filter, sort) {
        library.filter { it.inLibrary && (filter == "all" || it.type == filter) }
            .distinctBy { it.id }
            .let { list ->
                when (sort) {
                    "az" -> list.sortedBy { it.name.lowercase() }
                    "watched" -> list.sortedByDescending { it.lastWatched }
                    else -> list.sortedByDescending { it.mtime }
                }
            }
    }

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PillRow {
            Pill("All", filter == "all", { filter = "all" }, Modifier.padding(start = 40.dp))
            Pill("Movies", filter == "movie", { filter = "movie" })
            Pill("Series", filter == "series", { filter = "series" })
            Pill("Recently added", sort == "recent", { sort = "recent" }, Modifier.padding(start = 24.dp))
            Pill("Recently watched", sort == "watched", { sort = "watched" })
            Pill("A–Z", sort == "az", { sort = "az" })
        }
        when {
            !signedIn -> Centered("Sign in with your Stremio account in Settings to see your library.")
            items.isEmpty() -> Centered("Nothing here yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(PosterWidth + 14.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.id }) { li ->
                    PosterCard(
                        item = RowItem(li.preview()),
                        focusKey = "lib/${li.id}",
                        onClick = { onOpen(li.preview()) },
                    )
                }
            }
        }
    }
}
