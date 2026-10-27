package app.marquee.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.marquee.tv.PlayTarget
import app.marquee.tv.Playback
import app.marquee.tv.data.HomeRow
import app.marquee.tv.data.MetaDetail
import app.marquee.tv.data.MetaPreview
import app.marquee.tv.data.Recommender
import app.marquee.tv.data.Repo
import app.marquee.tv.data.RowItem
import app.marquee.tv.data.Video
import app.marquee.tv.data.Rating
import app.marquee.tv.data.Tmdb
import app.marquee.tv.data.WatchStore
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(seed: MetaPreview, onOpen: (MetaPreview) -> Unit) {
    val ctx = LocalContext.current
    val type = seed.type
    val id = seed.id
    var meta by remember(id) { mutableStateOf<MetaDetail?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var more by remember(id) { mutableStateOf<List<MetaPreview>>(emptyList()) }
    val progressMap by Repo.progress.collectAsState()
    val library by Repo.library.collectAsState()
    val resume = remember(progressMap, library, id) { Repo.resumePoint(id) }

    LaunchedEffect(id) {
        meta = Repo.meta(type, id)
        failed = meta == null
        meta?.let { if (it.episodes.isNotEmpty()) WatchStore.setSeriesTotal(it) }
    }
    LaunchedEffect(meta) {
        meta?.let { more = runCatching { Recommender.moreLike(it) }.getOrDefault(emptyList()) }
    }

    val m = meta
    val episodes = m?.episodes.orEmpty()
    val isSeries = episodes.isNotEmpty()
    val seasons = episodes.mapNotNull { it.season }.distinct()
    val resumeEpisode = episodes.firstOrNull { it.id == resume.first }
    var season by remember(id, isSeries) {
        mutableStateOf(resumeEpisode?.season ?: seasons.firstOrNull { it != 0 } ?: seasons.firstOrNull())
    }
    var target by remember { mutableStateOf<PlayTarget?>(null) }
    var rating by remember { mutableStateOf(false) }      // rating screen open
    var rateClosed by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val watchVersion = WatchStore.version
    val myRating = WatchStore.rating(id)
    val fullyWatched = WatchStore.isWatched(id, type)

    // Runtimes: TMDB per episode / movie when a key is set, else what the addon says.
    var runtimes by remember(id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(m, season) {
        val key = Repo.prefs.tmdbKey ?: return@LaunchedEffect
        if (m == null || !id.startsWith("tt")) return@LaunchedEffect
        val r = runCatching { Tmdb.runtimes(key, type, id, if (isSeries) season else null) }.getOrDefault(emptyMap())
        if (r.isNotEmpty()) runtimes = runtimes + r
    }
    val titleRuntime = if (isSeries) m?.let { runtimeLabel(it) } else
        (runtimes[id]?.let { minutesLabel(it) } ?: m?.let { runtimeLabel(it) })

    val playFocus = remember { FocusRequester() }
    val rateFocus = remember { FocusRequester() }
    val episodeFocus = remember(id) { mutableMapOf<String, FocusRequester>() }
    // The episode you last picked (null = the Play button). Focus goes back
    // there when the streams list closes and when you come back from the player.
    var returnFocusTo by remember { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableIntStateOf(1) }

    LaunchedEffect(restoreFocus, target == null) {
        if (target != null || restoreFocus == 0) return@LaunchedEffect
        delay(120)   // let the streams list leave the screen first
        val ep = returnFocusTo?.let { episodeFocus[it] }
        val ok = ep != null && runCatching { ep.requestFocus() }.isSuccess
        if (!ok) runCatching { playFocus.requestFocus() }
    }

    // Returning from the player: put focus back on this page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (target == null) restoreFocus++
    }

    fun targetFor(video: Video?): PlayTarget {
        val videoId = video?.id ?: id
        val idx = episodes.indexOfFirst { it.id == videoId }
        val next = if (idx >= 0) episodes.getOrNull(idx + 1)?.takeIf { it.season != 0 } else null
        return PlayTarget(
            id = id,
            type = type,
            videoId = videoId,
            name = m?.name ?: seed.name,
            poster = m?.poster ?: seed.poster,
            label = video?.label?.ifBlank { null },
            nextVideoId = next?.id,
            nextLabel = next?.label,
            startMs = if (resume.first == videoId || (!isSeries && resume.first == null)) resume.second else 0L,
            episodeTitle = (video?.title ?: video?.name)?.takeIf { it.isNotBlank() },
        )
    }

    val primaryVideo: Video? = if (isSeries) {
        resumeEpisode ?: episodes.firstOrNull { it.season != 0 } ?: episodes.firstOrNull()
    } else null
    val primaryLabel = when {
        isSeries && primaryVideo != null && resume.first == primaryVideo.id ->
            if (resume.second > 0) "Resume ${primaryVideo.label}" else "Play ${primaryVideo.label}"
        isSeries && primaryVideo != null -> "Play ${primaryVideo.label}"
        !isSeries && resume.second > 0 -> "Resume · ${formatTime(resume.second)}"
        else -> "Play"
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Backdrop((m?.toPreview() ?: seed).backdrop, strength = 0.7f)

        LazyColumn(
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(start = 48.dp, top = 56.dp, end = 48.dp, bottom = 20.dp)) {
                    Text(
                        m?.name ?: seed.name,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                    val facts = listOfNotNull(
                        m?.releaseInfo ?: m?.year,
                        titleRuntime?.let { if (isSeries) "$it episodes" else it },
                        m?.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it IMDb" },
                        if (isSeries) "${seasons.count { it != 0 }} seasons" else null,
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString("   "), color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        m?.allGenres?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), color = Accent, fontSize = 14.sp, modifier = Modifier.padding(end = 14.dp))
                        }
                        myRating?.let { r ->
                            RatingIcon(r, Modifier.size(16.dp), tint = ratingColor(r))
                            Text("You: ${r.label}", color = ratingColor(r), fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp, end = 12.dp))
                        }
                        if (fullyWatched) WatchedTag()
                    }
                    Text(
                        m?.description ?: seed.description ?: if (failed) "Couldn't load details for this title." else "",
                        color = Color(0xFFDADDE3),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(0.6f),
                    )
                    val people = listOfNotNull(
                        m?.director?.takeIf { it.isNotEmpty() }?.let { "Director: ${it.take(2).joinToString(", ")}" },
                        m?.cast?.takeIf { it.isNotEmpty() }?.let { "Cast: ${it.take(4).joinToString(", ")}" },
                    )
                    if (people.isNotEmpty()) {
                        Text(people.joinToString("    "), color = TextDim, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).fillMaxWidth(0.7f))
                    }
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ActionButton(
                            text = primaryLabel,
                            primary = true,
                            onClick = {
                                if (m != null || type == "movie") {
                                    returnFocusTo = null
                                    target = targetFor(primaryVideo)
                                }
                            },
                            modifier = Modifier.focusRequester(playFocus),
                            leading = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        )
                        ActionButton(
                            text = when {
                                myRating != null -> "Your rating"
                                fullyWatched -> "Rate it"
                                else -> "Already watched"
                            },
                            onClick = { rating = true },
                            modifier = Modifier.focusRequester(rateFocus),
                            leading = {
                                if (myRating != null) RatingIcon(myRating, Modifier.size(18.dp))
                                else Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                        ActionButton(
                            text = "Open in Stremio",
                            onClick = { Playback.openInStremio(ctx, type, id, primaryVideo?.id) },
                        )
                    }
                }
            }

            if (isSeries && seasons.size > 1) {
                item(key = "seasons") {
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(seasons, key = { it }) { s ->
                            Pill(if (s == 0) "Specials" else "Season $s", s == season, { season = s })
                        }
                    }
                }
            }

            if (isSeries) {
                item(key = "ep-hint") {
                    Text(
                        "Long-press an episode to mark it watched or unwatched.",
                        color = TextFaint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 52.dp, bottom = 4.dp),
                    )
                }
                items(episodes.filter { it.season == season }, key = { "ep/${it.id}" }) { ep ->
                    val req = episodeFocus.getOrPut(ep.id) { FocusRequester() }
                    EpisodeRow(
                        ep = ep,
                        progress = if (resume.first == ep.id && resume.second > 0) {
                            progressMap[id]?.takeIf { it.duration > 0 }?.let { it.position.toFloat() / it.duration }
                                ?: library.firstOrNull { it.id == id }?.takeIf { it.duration > 0 }?.let { it.timeOffset.toFloat() / it.duration }
                        } else null,
                        isNext = resume.first == ep.id,
                        watched = WatchStore.episodeWatched(id, ep.id),
                        runtime = runtimes[ep.id]?.let { minutesLabel(it) } ?: ep.runtimeMinutes()?.let { minutesLabel(it) }
                            ?: m?.let { runtimeLabel(it) },
                        onLongClick = {
                            val now = !WatchStore.episodeWatched(id, ep.id)
                            WatchStore.setEpisodeWatched(id, ep.id, now, (m?.toPreview() ?: seed))
                            Repo.refreshPersonalRows()
                        },
                        modifier = Modifier.focusRequester(req),
                        onClick = {
                            returnFocusTo = ep.id
                            target = targetFor(ep)
                        },
                    )
                }
            }

            if (more.isNotEmpty()) {
                item(key = "more") {
                    Column(Modifier.padding(top = 20.dp)) {
                        ContentRow(
                            row = HomeRow("more/$id", "More like this", more.map { RowItem(it) }),
                            onOpen = { onOpen(it.meta) },
                            startPadding = 48.dp,
                        )
                    }
                }
            }
        }

        if (rating) {
            RatingScreen(
                title = m?.name ?: seed.name,
                isSeries = isSeries || type == "series",
                current = myRating,
                watched = fullyWatched,
                onRate = { r ->
                    WatchStore.rate(m?.toPreview() ?: seed, r, m?.allGenres ?: seed.allGenres)
                    Repo.refreshPersonalRows()
                    rating = false
                    rateClosed++
                },
                onUnwatch = {
                    WatchStore.markUnwatched(id)
                    Repo.refreshPersonalRows()
                    rating = false
                    rateClosed++
                },
                onDismiss = { rating = false; rateClosed++ },
            )
        }
        LaunchedEffect(rateClosed) {
            if (rateClosed > 0) { delay(80); runCatching { rateFocus.requestFocus() } }
        }

        target?.let { t ->
            StreamsPanel(
                target = t,
                onDismiss = {
                    target = null
                    restoreFocus++
                },
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Video,
    progress: Float?,
    isNext: Boolean,
    watched: Boolean,
    runtime: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .padding(horizontal = 48.dp, vertical = 5.dp)
            .fillMaxWidth(0.78f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x661A1C22),
            contentColor = Color.White,
            focusedContainerColor = Raised,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(160.dp)
                    .height(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Panel),
            ) {
                ep.thumbnail?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(4.dp).background(Accent))
                    }
                }
                if (watched) WatchedTag(Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ep.episode ?: ""}. ${ep.displayTitle}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isNext) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Accent, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                        Text(if (progress != null) "In progress" else "Up next", color = Accent, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                (ep.overview ?: ep.description)?.let {
                    Text(it, color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                val facts = listOfNotNull(runtime, ep.released?.take(10))
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString("  ·  "), color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** "1h 52m" or "45m" from minutes. */
fun minutesLabel(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

/** Parses an addon runtime string ("142 min", "2h 22min", "45") into minutes. */
fun parseRuntime(s: String?): Int? {
    if (s.isNullOrBlank()) return null
    val t = s.lowercase()
    val h = Regex("""(\d+)\s*h""").find(t)?.groupValues?.get(1)?.toIntOrNull()
    val m = Regex("""(\d+)\s*m""").find(t)?.groupValues?.get(1)?.toIntOrNull()
    if (h != null || m != null) return (h ?: 0) * 60 + (m ?: 0)
    return Regex("""\d+""").find(t)?.value?.toIntOrNull()
}

fun runtimeLabel(m: MetaDetail): String? = parseRuntime(m.runtime)?.takeIf { it > 0 }?.let { minutesLabel(it) }

fun Video.runtimeMinutes(): Int? = parseRuntime(runtime)

/** The "Already watched" screen: rate it, which also marks it watched. */
@Composable
private fun RatingScreen(
    title: String,
    isSeries: Boolean,
    current: Rating?,
    watched: Boolean,
    onRate: (Rating) -> Unit,
    onUnwatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onDismiss() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    FocusTrap(Modifier.fillMaxSize().background(Color(0xCC000000))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(520.dp)
                    .background(Panel, RoundedCornerShape(16.dp))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("How was $title?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(
                    if (isSeries) "Rating it marks the whole series as watched and tunes your recommendations."
                    else "Rating it marks it as watched and tunes your recommendations.",
                    color = TextDim,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Rating.entries.forEach { r ->
                        ActionButton(
                            r.label,
                            primary = r == current,
                            onClick = { onRate(r) },
                            modifier = if (r == (current ?: Rating.UP)) Modifier.focusRequester(first) else Modifier,
                            leading = { RatingIcon(r, Modifier.size(20.dp)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (current != null || watched) {
                        ActionButton("Mark as not watched", onClick = onUnwatch)
                    }
                    ActionButton("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}
