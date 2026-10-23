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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

    val playFocus = remember { FocusRequester() }
    val episodeFocus = remember(id) { mutableMapOf<String, FocusRequester>() }
    var returnFocusTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        delay(80)
        runCatching { playFocus.requestFocus() }
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
                        m?.runtime,
                        m?.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it IMDb" },
                        if (isSeries) "${seasons.count { it != 0 }} seasons" else null,
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString("   "), color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    m?.allGenres?.takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
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
                items(episodes.filter { it.season == season }, key = { "ep/${it.id}" }) { ep ->
                    val req = episodeFocus.getOrPut(ep.id) { FocusRequester() }
                    EpisodeRow(
                        ep = ep,
                        progress = if (resume.first == ep.id && resume.second > 0) {
                            progressMap[id]?.takeIf { it.duration > 0 }?.let { it.position.toFloat() / it.duration }
                                ?: library.firstOrNull { it.id == id }?.takeIf { it.duration > 0 }?.let { it.timeOffset.toFloat() / it.duration }
                        } else null,
                        isNext = resume.first == ep.id,
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

        target?.let { t ->
            StreamsPanel(
                target = t,
                onDismiss = {
                    target = null
                    val back = returnFocusTo?.let { episodeFocus[it] } ?: playFocus
                    runCatching { back.requestFocus() }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
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
                ep.released?.take(10)?.let {
                    Text(it, color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
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
