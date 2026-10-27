package app.marquee.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.marquee.tv.data.MetaPreview
import app.marquee.tv.data.Repo

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Browse("Browse", Icons.Filled.Menu),
    Search("Search", Icons.Filled.Search),
    Library("Library", Icons.Filled.Favorite),
    Settings("Settings", Icons.Filled.Settings),
}

private sealed interface Layer {
    data class Detail(val meta: MetaPreview) : Layer
    data object Login : Layer
}

private val RailCollapsed = 64.dp

@Composable
fun MarqueeApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val layers = remember { mutableStateListOf<Layer>() }
    var baseFocusKey by remember { mutableStateOf<String?>(null) }

    // First launch: ask for the Stremio account.
    remember {
        if (Repo.prefs.authKey == null && !Repo.prefs.skippedLogin) layers.add(Layer.Login)
        true
    }

    fun open(meta: MetaPreview) {
        if (layers.isEmpty()) baseFocusKey = FocusMemory.last
        layers.add(Layer.Detail(meta))
    }

    fun pop() {
        if (layers.isEmpty()) return
        layers.removeAt(layers.lastIndex)
        if (layers.isEmpty()) FocusMemory.pending = baseFocusKey
    }

    // Opened from the TV home screen: go straight to that title.
    val link by Repo.deepLink.collectAsState()
    LaunchedEffect(link) {
        val m = link ?: return@LaunchedEffect
        Repo.deepLink.value = null
        layers.removeAll { it is Layer.Detail }
        open(m)
    }

    BackHandler(enabled = layers.isEmpty() && tab != Tab.Home) { tab = Tab.Home }
    BackHandler(enabled = layers.isNotEmpty()) { pop() }

    // While a page is open on top, the tab underneath is taken out of the
    // screen entirely so the remote can't wander into it. Its scroll position
    // and filters are saved and come back when you press Back.
    val saved = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize()) {
        if (layers.isEmpty()) {
            saved.SaveableStateProvider(tab.name) {
                Box(Modifier.fillMaxSize().padding(start = RailCollapsed)) {
                    when (tab) {
                        Tab.Home -> HomeScreen(onOpen = ::open)
                        Tab.Browse -> BrowseScreen(onOpen = ::open)
                        Tab.Search -> SearchScreen(onOpen = ::open)
                        Tab.Library -> LibraryScreen(onOpen = ::open)
                        Tab.Settings -> SettingsScreen(onSignIn = { layers.add(Layer.Login) })
                    }
                }
            }
            NavRail(current = tab, onSelect = { tab = it })
        }

        when (val top = layers.lastOrNull()) {
            is Layer.Detail -> FocusTrap(Modifier.fillMaxSize()) {
                // Keyed so "More like this" opens a fresh page.
                androidx.compose.runtime.key(top.meta.id, layers.size) {
                    DetailScreen(seed = top.meta, onOpen = ::open)
                }
            }
            Layer.Login -> FocusTrap(Modifier.fillMaxSize()) {
                LoginScreen(onDone = { pop() })
            }
            null -> Unit
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NavRail(current: Tab, onSelect: (Tab) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 196.dp else RailCollapsed, label = "rail")
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) Brush.horizontalGradient(0.7f to Panel, 1f to Panel.copy(alpha = 0.92f))
                else Brush.horizontalGradient(0f to Bg, 1f to Bg.copy(alpha = 0.6f)),
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusRestorer()
            .focusGroup()
            .padding(horizontal = 10.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (expanded) "Matthews TV" else "M",
            color = Accent,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 12.dp, bottom = 18.dp),
        )
        Tab.entries.forEach { t ->
            val selected = t == current
            Surface(
                onClick = { onSelect(t) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) Raised else Color.Transparent,
                    contentColor = if (selected) Accent else TextDim,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(20.dp))
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Text(t.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
