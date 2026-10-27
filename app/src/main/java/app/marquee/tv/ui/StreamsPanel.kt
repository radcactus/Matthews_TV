package app.marquee.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.marquee.tv.PlayTarget
import app.marquee.tv.ExternalPlayer
import app.marquee.tv.PlayerChoice
import app.marquee.tv.Playback
import app.marquee.tv.StreamKind
import app.marquee.tv.data.Addon
import app.marquee.tv.data.DeviceCaps
import app.marquee.tv.data.Health
import app.marquee.tv.data.Quality
import app.marquee.tv.data.Repo
import app.marquee.tv.data.Stream
import app.marquee.tv.data.StreamChecker
import app.marquee.tv.data.StreamQuality
import app.marquee.tv.data.StreamRank
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Good = Color(0xFF66BB6A)
private val Bad = Color(0xFFEF5350)

private class Entry(val key: String, val addon: Addon, val addonIndex: Int, val position: Int, val stream: Stream) {
    val healthKey = StreamChecker.key(stream)
    val quality = StreamQuality.parse(stream)
    val decodable = DeviceCaps.canDecode(quality.codec)
    val rank = StreamRank.qualityKey(quality, decodable)
}

/**
 * Side panel listing streams from every addon. Links are tested as they
 * arrive; working ones rise to the top and broken ones are tucked away.
 */
@Composable
fun StreamsPanel(target: PlayTarget, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val checking = Repo.prefs.checkStreams
    val entries = remember(target.videoId) { mutableStateListOf<Entry>() }
    val health = remember(target.videoId) { mutableStateMapOf<String, Health>() }
    var answered by remember(target.videoId) { mutableStateOf(0) }
    var done by remember(target.videoId) { mutableStateOf(false) }
    val total = remember(target.videoId) { Repo.streamSourceCount(target.type, target.videoId) }
    var showBroken by remember(target.videoId) { mutableStateOf(false) }

    // After the user starts moving through the list, stop jumping focus to the top.
    var userMoved by remember(target.videoId) { mutableStateOf(false) }
    var focusedIndex by remember(target.videoId) { mutableIntStateOf(0) }
    val headerFocus = remember { FocusRequester() }
    val rowFocus = remember(target.videoId) { mutableMapOf<String, FocusRequester>() }
    var focusedRow by remember(target.videoId) { mutableStateOf<String?>(null) }

    // Stream waiting for a "Play with" choice (default set to Ask, or long-press).
    var picking by remember(target.videoId) { mutableStateOf<Entry?>(null) }

    BackHandler { onDismiss() }
    BackHandler(enabled = picking != null) { picking = null }

    LaunchedEffect(target.videoId) {
        runCatching { headerFocus.requestFocus() }
        val order = Repo.addons.value
        Repo.streams(target.type, target.videoId).collect { (addon, streams) ->
            answered++
            val ai = order.indexOfFirst { it.transportUrl == addon.transportUrl }.let { if (it < 0) 999 else it }
            val fresh = streams.mapIndexed { i, s -> Entry("${addon.transportUrl}#$i", addon, ai, i, s) }
            entries.addAll(fresh)
            for (e in fresh) {
                if (!checking) { health[e.healthKey] = StreamChecker.quick(e.stream) ?: Health.Unknown; continue }
                val q = StreamChecker.quick(e.stream)
                if (q != null) { health[e.healthKey] = q; continue }
                if (health[e.healthKey] != null) continue
                health[e.healthKey] = Health.Checking
                launch {
                    health[e.healthKey] = StreamChecker.check(e.stream)
                }
            }
        }
        done = true
    }

    /**
     * 0 = playable or still being checked, 1 = not cached on debrid, 2 = broken.
     * Links still being checked rank with the working ones, so the order
     * is decided by quality, not by which check happened to finish first.
     */
    fun tier(e: Entry): Int = when (health[e.healthKey]) {
        is Health.Broken -> 2
        Health.Uncached -> 1
        else -> 0
    }

    // Resolution first (2160p, 1080p, 720p…), then compression within each
    // resolution (AV1, HEVC, VP9, H.264, older), then source.
    val ordered = entries.sortedWith(
        compareBy<Entry>(
            { tier(it) },
            { it.rank },                                              // see StreamRank.qualityKey
            { if (health[it.healthKey] is Health.Ok) 0 else 1 },      // verified before not-yet-checked
            { it.addonIndex },
            { it.position },
        ),
    )
    val broken = ordered.filter { health[it.healthKey] is Health.Broken }
    val visible = if (showBroken) ordered else ordered.filter { health[it.healthKey] !is Health.Broken }

    val okCount = entries.count { health[it.healthKey] is Health.Ok }
    val pending = entries.count { health[it.healthKey] == Health.Checking }

    // Keep focus on the best link until the user takes over.
    val best = visible.firstOrNull()?.key
    LaunchedEffect(best, userMoved) {
        if (!userMoved && best != null) {
            runCatching { rowFocus[best]?.requestFocus() }
        }
    }
    // If the highlighted link turns out broken and drops out, move to the one now in its place.
    val visibleKeys = visible.map { it.key }
    LaunchedEffect(visibleKeys) {
        val f = focusedRow ?: return@LaunchedEffect
        if (f !in visibleKeys && visibleKeys.isNotEmpty()) {
            val k = visibleKeys[focusedIndex.coerceIn(0, visibleKeys.lastIndex)]
            delay(30)
            runCatching { rowFocus[k]?.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.42f).fillMaxHeight().background(Color(0x99000000)))
        FocusTrap(Modifier.weight(0.58f).fillMaxHeight().background(Panel)) {
            Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
                Column(Modifier.padding(horizontal = 28.dp)) {
                    Text(
                        listOfNotNull(target.name, target.label).joinToString(" · "),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val status = when {
                        total == 0 -> "None of your addons provide streams for this."
                        !done && entries.isEmpty() -> "Asking your addons… $answered of $total answered"
                        done && entries.isEmpty() -> "No streams found."
                        !checking -> "${entries.size} streams"
                        else -> buildList {
                            add("$okCount working")
                            if (broken.isNotEmpty()) add("${broken.size} broken")
                            if (pending > 0) add("checking $pending…")
                            if (!done) add("$answered of $total addons answered")
                        }.joinToString(" · ")
                    }
                    Text(status, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    if (checking && done && pending == 0 && entries.isNotEmpty() && visible.isEmpty()) {
                        Text(
                            "Every link failed its check. Stremio might still have luck, or try again later.",
                            color = Accent,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    ActionButton(
                        text = "Open in Stremio instead",
                        onClick = { Playback.openInStremio(ctx, target.type, target.id, target.videoId) },
                        modifier = Modifier.focusRequester(headerFocus),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && focusedRow != null) userMoved = true
                            false
                        },
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(visible, key = { _, e -> e.key }) { index, e ->
                        val req = rowFocus.getOrPut(e.key) { FocusRequester() }
                        StreamRow(
                            stream = e.stream,
                            addonName = e.addon.name,
                            quality = e.quality,
                            decodable = e.decodable,
                            health = health[e.healthKey] ?: Health.Checking,
                            modifier = Modifier
                                .focusRequester(req)
                                .onFocusChanged { if (it.isFocused) { focusedRow = e.key; focusedIndex = index } },
                            onClick = {
                                if (Playback.usesPlayer(e.stream) && Repo.prefs.playerChoice == PlayerChoice.ASK) {
                                    picking = e
                                } else {
                                    Playback.play(ctx, e.stream, target)
                                    onDismiss()
                                }
                            },
                            onLongClick = { if (Playback.usesPlayer(e.stream)) picking = e },
                        )
                    }
                    if (broken.isNotEmpty()) {
                        item(key = "toggle-broken") {
                            Box(Modifier.padding(top = 8.dp, start = 4.dp)) {
                                Pill(
                                    if (showBroken) "Hide ${broken.size} broken links" else "Show ${broken.size} broken links",
                                    selected = false,
                                    onClick = { showBroken = !showBroken },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    picking?.let { e ->
        PlayWithPicker(
            onPick = { choice ->
                picking = null
                Playback.play(ctx, e.stream, target, choice)
                onDismiss()
            },
        )
    }
    }
}

/** "Play with" chooser: the built-in player plus every supported player installed on this TV. */
@Composable
private fun PlayWithPicker(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val installed = remember { ExternalPlayer.installed(ctx) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    FocusTrap(Modifier.fillMaxSize().background(Color(0xB3000000))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .background(Panel, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Play with", style = MaterialTheme.typography.titleLarge)
                ActionButton(
                    "VLC (built in)",
                    primary = true,
                    onClick = { onPick(PlayerChoice.VLC) },
                    modifier = Modifier.focusRequester(first),
                )
                ActionButton("ExoPlayer (built in)", onClick = { onPick(PlayerChoice.BUILTIN) })
                if (installed.isNotEmpty()) {
                    Text("Other apps on this TV", color = TextDim, fontSize = 12.sp)
                    installed.forEach { p -> ActionButton(p.label, onClick = { onPick(p.id) }) }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(
    stream: Stream,
    onLongClick: () -> Unit,
    addonName: String,
    quality: Quality,
    decodable: Boolean,
    health: Health,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = Playback.kind(stream)
    val isBroken = health is Health.Broken
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Raised,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (isBroken) 0.55f else 1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthBadge(health)
                Text(
                    stream.name?.replace("\n", " ")?.trim().orEmpty().ifBlank { "Stream" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
                )
                val badge = when (kind) {
                    StreamKind.Direct -> null
                    StreamKind.Server -> "via your streaming server"
                    StreamKind.External -> "opens externally"
                    StreamKind.Stremio -> "opens in Stremio"
                }
                badge?.let {
                    Text(it, fontSize = 11.sp, color = Accent, modifier = Modifier.padding(start = 10.dp))
                }
                Text(addonName, fontSize = 11.sp, color = TextFaint, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
            }
            if (quality.label.isNotBlank()) {
                Text(quality.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
            healthText(health)?.let { (msg, color) ->
                Text(msg, fontSize = 12.sp, color = color, modifier = Modifier.padding(top = 2.dp))
            }
            if (!decodable) {
                Text(
                    "This TV has no hardware ${quality.codec.label} decoder, may stutter or not play",
                    fontSize = 12.sp,
                    color = Accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (stream.info.isNotBlank()) {
                Text(
                    stream.info.trim(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).alpha(0.75f),
                )
            }
        }
    }
}

@Composable
private fun HealthBadge(h: Health) {
    val color = when (h) {
        is Health.Ok -> Good
        is Health.Likely -> Good.copy(alpha = 0.55f)
        is Health.Broken -> Bad
        Health.Uncached -> Accent
        Health.Checking, Health.Unknown -> TextFaint
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private fun healthText(h: Health): Pair<String, Color>? = when (h) {
    is Health.Ok -> buildList {
        add("Working")
        h.size?.let { add(StreamChecker.formatSize(it)) }
        add(if (h.ms < 1000) "responds in ${h.ms} ms" else "responds in ${"%.1f".format(h.ms / 1000.0)} s")
    }.joinToString(" · ") to Good
    is Health.Likely -> h.note to Good.copy(alpha = 0.8f)
    is Health.Broken -> "Broken: ${h.reason}" to Bad
    Health.Uncached -> "Not cached on your debrid service, will need to download first" to Accent
    Health.Checking -> "Checking…" to TextFaint
    Health.Unknown -> null
}
