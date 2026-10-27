package app.marquee.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.marquee.tv.ExternalPlayer
import app.marquee.tv.PlayerChoice
import app.marquee.tv.data.Repo

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(onSignIn: () -> Unit) {
    val signedIn by Repo.signedIn.collectAsState()
    val addons by Repo.addons.collectAsState()
    val loading by Repo.loading.collectAsState()
    val prefs = Repo.prefs
    var tmdb by remember { mutableStateOf(prefs.tmdbKey.orEmpty()) }
    var server by remember { mutableStateOf(prefs.serverUrl.orEmpty()) }
    var sync by remember { mutableStateOf(prefs.syncProgress) }
    var playerChoice by remember { mutableStateOf(prefs.playerChoice) }
    var autoplayNext by remember { mutableStateOf(prefs.autoplayNext) }
    val ctx = LocalContext.current
    val installedPlayers = remember { ExternalPlayer.installed(ctx) }
    var check by remember { mutableStateOf(prefs.checkStreams) }
    var probeUncached by remember { mutableStateOf(prefs.probeUncached) }
    var hidden by remember { mutableStateOf(prefs.hiddenCatalogs) }
    var saved by remember { mutableStateOf<String?>(null) }
    val catalogs = remember(addons) { Repo.catalogsForSettings() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("Account") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    if (signedIn) "Signed in as ${prefs.email.orEmpty()}" else "Not signed in. You'll only see Cinemeta catalogs.",
                    color = TextDim,
                )
                if (signedIn) ActionButton("Sign out", onClick = { Repo.logout() })
                else ActionButton("Sign in", primary = true, onClick = onSignIn)
                ActionButton(if (loading) "Refreshing…" else "Refresh addons & library", onClick = { Repo.refresh() })
            }
        }

        item { SectionTitle("Recommendations", Modifier.padding(top = 18.dp)) }
        item {
            Note("Add a free TMDB key to get \"Because you watched…\" rows and better \"More like this\". Get one at themoviedb.org → Settings → API. Either the API key or the read access token works.")
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(tmdb, { tmdb = it }, "TMDB API key", Modifier.width(420.dp))
                ActionButton("Save", onClick = {
                    prefs.tmdbKey = tmdb
                    saved = "TMDB key saved. Refreshing home…"
                    Repo.refresh()
                })
            }
        }

        item { SectionTitle("Playback", Modifier.padding(top = 18.dp)) }
        item {
            Toggle(
                title = "Sync watch progress to Stremio",
                desc = "Updates resume points on titles already in your Stremio library, so Stremio's Continue Watching stays in step.",
                on = sync,
            ) { sync = !sync; prefs.syncProgress = sync }
        }
        item {
            Toggle(
                title = "Check links before you pick",
                desc = "Tests every stream when you open the list. Working links go to the top, broken ones are hidden.",
                on = check,
            ) { check = !check; prefs.checkStreams = check }
        }
        item {
            Toggle(
                title = "Also test uncached debrid links",
                desc = "Off by default: testing an uncached link makes your debrid service start downloading it.",
                on = probeUncached,
            ) { probeUncached = !probeUncached; prefs.probeUncached = probeUncached }
        }
        item {
            Toggle(
                title = "Autoplay next episode",
                desc = "When the credits start, a 15-second countdown plays the next episode with the best working link. Press Back or \"Watch credits\" to stay.",
                on = autoplayNext,
            ) { autoplayNext = !autoplayNext; prefs.autoplayNext = autoplayNext }
        }
        item {
            Note("Player. Both players are built into Matthews TV, so nothing opens another app. VLC plays almost any file; ExoPlayer is Android's own engine. If one can't start a file, Matthews TV tries the other automatically. Long-press any stream to pick a player just for that one. Video apps installed on this TV are listed too, if you ever want them.")
        }
        item {
            val options = listOf(PlayerChoice.VLC, PlayerChoice.BUILTIN, PlayerChoice.ASK) + installedPlayers.map { it.id }
            PillRow {
                options.forEach { id ->
                    Pill(PlayerChoice.label(id), playerChoice == id, { playerChoice = id; prefs.playerChoice = id })
                }
            }
        }
        item {
            Note("Optional streaming server for torrent-based streams, e.g. http://192.168.1.20:11470 (Stremio's server running on a computer on your network). Without one, those streams open in the Stremio app.")
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(server, { server = it }, "http://192.168.x.x:11470", Modifier.width(420.dp))
                ActionButton("Save", onClick = { prefs.serverUrl = server; saved = "Streaming server saved." })
            }
        }
        saved?.let { msg -> item { Text(msg, color = Accent, fontSize = 13.sp) } }

        item { SectionTitle("Home rows", Modifier.padding(top = 18.dp)) }
        item { Note("Choose which addon catalogs appear on Home. Your personal rows always show.") }
        items(catalogs, key = { (a, c) -> a.catalogKey(c) }) { (addon, cat) ->
            val key = addon.catalogKey(cat)
            Toggle(
                title = "${cat.name} · ${Repo.typeLabel(cat.type)}",
                desc = addon.name,
                on = key !in hidden,
            ) {
                val hide = key !in hidden
                hidden = if (hide) hidden + key else hidden - key
                Repo.setCatalogHidden(key, hide)
            }
        }

        item { SectionTitle("Installed addons", Modifier.padding(top = 18.dp)) }
        item { Note("Matthews TV uses the addons installed on your Stremio account, in the same order. Install or remove them in Stremio, then press Refresh.") }
        items(addons, key = { "addon/${it.transportUrl}" }) { a ->
            Text("• ${a.name}", color = TextDim, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = TextFaint, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth(0.75f))
}

@Composable
private fun Toggle(title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(0.75f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Panel,
            contentColor = Color.White,
            focusedContainerColor = Raised,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(desc, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(if (on) "On" else "Off", color = if (on) Accent else TextFaint, fontWeight = FontWeight.Bold)
        }
    }
}
