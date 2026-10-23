package app.marquee.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.draw.rotate
import androidx.tv.material3.Icon
import app.marquee.tv.data.Rating
import app.marquee.tv.data.WatchStore
import app.marquee.tv.data.HomeRow
import app.marquee.tv.data.MetaPreview
import app.marquee.tv.data.RowItem
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay

/** Remembers which card opened a detail page so focus returns there on Back. */
object FocusMemory {
    var last: String? = null
    var pending by mutableStateOf<String?>(null)
}

val PosterWidth = 116.dp

@Composable
fun PosterCard(
    item: RowItem,
    focusKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = PosterWidth,
    onFocus: (RowItem) -> Unit = {},
) {
    val requester = remember { FocusRequester() }
    val pending = FocusMemory.pending
    LaunchedEffect(pending) {
        if (pending == focusKey) {
            delay(50)
            runCatching { requester.requestFocus() }
            FocusMemory.pending = null
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.width(width)) {
        Card(
            onClick = { FocusMemory.last = focusKey; onClick() },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocus(item) },
            shape = CardDefaults.shape(shape = shape),
            colors = CardDefaults.colors(containerColor = Raised),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(
                focusedBorder = Border(border = BorderStroke(3.dp, Color.White), shape = shape),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    item.meta.name,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    textAlign = TextAlign.Center,
                    color = TextDim,
                    fontSize = 13.sp,
                    maxLines = 4,
                )
                item.meta.posterUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = item.meta.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Reading the version makes the tags update as soon as you rate or finish something.
                @Suppress("UNUSED_VARIABLE") val v = WatchStore.version
                if (WatchStore.isWatched(item.meta.id, item.meta.type)) {
                    WatchedTag(Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                WatchStore.rating(item.meta.id)?.let { r ->
                    RatingBadge(r, Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
                item.progress?.let { p ->
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x99000000)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(p.coerceIn(0.02f, 1f))
                                .height(4.dp)
                                .background(Accent),
                        )
                    }
                }
            }
        }
        item.caption?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentRow(
    row: HomeRow,
    onOpen: (RowItem) -> Unit,
    onFocus: (RowItem) -> Unit = {},
    startPadding: Dp = 40.dp,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = startPadding, top = 6.dp),
        )
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(start = startPadding, end = 40.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(row.items, key = { it.meta.id }) { item ->
                PosterCard(
                    item = item,
                    focusKey = "${row.key}/${item.meta.id}",
                    onClick = { onOpen(item) },
                    onFocus = onFocus,
                )
            }
        }
    }
}

/** Full-bleed artwork with gradients so text stays readable. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier, strength: Float = 0.6f) {
    Box(modifier.fillMaxSize()) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(url).crossfade(400).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(strength),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg, 0.45f to Bg.copy(alpha = 0.75f), 1f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Bg.copy(alpha = 0.9f), 1f to Bg),
            ),
        )
    }
}

/** Title, facts and synopsis for whatever is focused. */
@Composable
fun HeroInfo(meta: MetaPreview?, modifier: Modifier = Modifier, runtime: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        if (meta == null) return@Column
        Text(
            meta.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val facts = listOfNotNull(
            meta.releaseInfo,
            runtime,
            meta.imdbRating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" },
            meta.allGenres.take(3).joinToString(" · ").ifBlank { null },
        )
        @Suppress("UNUSED_VARIABLE") val v = WatchStore.version
        val rating = WatchStore.rating(meta.id)
        val watched = WatchStore.isWatched(meta.id, meta.type)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (facts.isNotEmpty()) {
                Text(facts.joinToString("   "), color = TextDim, fontSize = 14.sp)
            }
            if (rating != null) {
                RatingIcon(rating, Modifier.padding(start = 14.dp).size(16.dp), tint = ratingColor(rating))
                Text("You: ${rating.label}", color = ratingColor(rating), fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
            }
            if (watched) WatchedTag(Modifier.padding(start = 12.dp))
        }
        meta.description?.let {
            Text(
                it,
                color = TextDim,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Accent.copy(alpha = 0.22f) else Raised,
            contentColor = if (selected) Accent else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Accent else Raised,
            contentColor = if (primary) Color.Black else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        leading?.invoke(this)
        if (leading != null) Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** Text field that works with a D-pad: OK opens the on-screen keyboard. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(Accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) {
                    keyboard?.show(); true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), shape)
                    .border(2.dp, if (focused) Accent else Color.Transparent, shape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = TextFaint, fontSize = 18.sp)
                inner()
            }
        },
    )
}

/** Full-screen layer that keeps D-pad focus inside itself. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FocusTrap(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) { content() }
}

@Composable
fun Centered(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextDim, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

@Composable
fun PillRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Small "Watched" label for posters and episodes. */
@Composable
fun WatchedTag(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0xE6000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(12.dp))
        Text("Watched", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 3.dp))
    }
}

/** Thumbs up / meh / thumbs down icon. Meh is the thumb turned sideways. */
@Composable
fun RatingIcon(r: Rating, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    Icon(
        Icons.Filled.ThumbUp,
        contentDescription = r.label,
        tint = tint,
        modifier = modifier.rotate(
            when (r) {
                Rating.UP -> 0f
                Rating.MEH -> -90f
                Rating.DOWN -> 180f
            },
        ),
    )
}

fun ratingColor(r: Rating) = when (r) {
    Rating.UP -> Color(0xFF81C784)
    Rating.MEH -> Color(0xFFBDBDBD)
    Rating.DOWN -> Color(0xFFE57373)
}

@Composable
fun RatingBadge(r: Rating, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .background(Color(0xE6000000), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        RatingIcon(r, Modifier.size(13.dp), tint = ratingColor(r))
    }
}
