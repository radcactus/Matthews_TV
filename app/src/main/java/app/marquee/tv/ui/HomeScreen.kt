package app.marquee.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.marquee.tv.data.MetaPreview
import app.marquee.tv.data.Repo
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(onOpen: (MetaPreview) -> Unit) {
    val rows by Repo.home.collectAsState()
    val loading by Repo.loading.collectAsState()
    val error by Repo.error.collectAsState()
    var focused by remember { mutableStateOf<MetaPreview?>(null) }
    var hero by remember { mutableStateOf<MetaPreview?>(null) }
    var heroRuntime by remember { mutableStateOf<String?>(null) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    // Wait for the D-pad to settle before swapping artwork.
    LaunchedEffect(focused) {
        delay(220)
        val f = focused ?: return@LaunchedEffect
        hero = f
        heroRuntime = null
        // Fill in synopsis (library and history items arrive without one) and runtime.
        Repo.meta(f.type, f.id)?.let { m ->
            if (f.description == null) hero = m.toPreview().copy(poster = f.poster ?: m.poster)
            heroRuntime = runtimeLabel(m)
        }
    }
    LaunchedEffect(rows.isNotEmpty()) {
        if (!initialFocusDone && rows.isNotEmpty()) {
            val first = rows.first()
            first.items.firstOrNull()?.let { FocusMemory.pending = "${first.key}/${it.meta.id}" }
            initialFocusDone = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(hero?.backdrop)
        Column(Modifier.fillMaxSize()) {
            HeroInfo(
                hero,
                runtime = heroRuntime,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .padding(start = 40.dp, top = 28.dp, end = 40.dp),
            )
            when {
                rows.isEmpty() && loading -> Centered("Loading your catalogs…")
                rows.isEmpty() -> Centered(error ?: "Nothing here yet. Check your addons in Settings.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    error?.let { msg ->
                        item(key = "error") {
                            Text(msg, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 40.dp, bottom = 6.dp))
                        }
                    }
                    items(rows, key = { it.key }) { row ->
                        ContentRow(
                            row = row,
                            onOpen = { onOpen(it.meta) },
                            onFocus = { focused = it.meta },
                        )
                    }
                }
            }
        }
    }
}
