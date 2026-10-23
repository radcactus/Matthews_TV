package app.marquee.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.marquee.tv.data.HomeRow
import app.marquee.tv.data.MetaPreview
import app.marquee.tv.data.Repo
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(onOpen: (MetaPreview) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember { mutableStateListOf<HomeRow>() }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2 || q == searched) return@LaunchedEffect
        delay(600)   // wait for typing to pause
        rows.clear()
        searching = true
        searched = q
        Repo.search(q).collect { row ->
            // Movies before series, then by addon order of arrival.
            val sorted = (rows + row).sortedBy { if (it.title.contains("Movies")) 0 else 1 }
            rows.clear()
            rows.addAll(sorted)
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        TvTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search movies and series",
            imeAction = ImeAction.Search,
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .fillMaxWidth(0.6f)
                .focusRequester(field),
        )
        when {
            query.trim().length < 2 -> Centered("Type at least two letters.\nPress OK on the box to open the keyboard.")
            rows.isEmpty() && searching -> Centered("Searching your addons…")
            rows.isEmpty() && searched.isNotEmpty() -> Centered("No results for “$searched”.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    ContentRow(row = row, onOpen = { onOpen(it.meta) })
                }
            }
        }
    }
}
