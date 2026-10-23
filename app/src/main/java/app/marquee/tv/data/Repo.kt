package app.marquee.tv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

object Repo {
    private const val TAG = "Marquee"
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    lateinit var prefs: Prefs
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val addons = MutableStateFlow<List<Addon>>(emptyList())
    val library = MutableStateFlow<List<LibItem>>(emptyList())
    val progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val home = MutableStateFlow<List<HomeRow>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val signedIn = MutableStateFlow(false)

    /** A title to open, set when the app is launched from the TV home screen. */
    val deepLink = MutableStateFlow<MetaPreview?>(null)

    private val metaCache = ConcurrentHashMap<String, MetaDetail>()
    private val catalogCache = ConcurrentHashMap<String, Pair<Long, List<MetaPreview>>>()
    private val gate = Semaphore(8)
    private val homeSlots = ConcurrentSkipListMap<Int, HomeRow>()
    private var refreshJob: Job? = null

    // Slot order on the home screen.
    private const val SLOT_CONTINUE = 0
    const val SLOT_HOLIDAY = 5
    const val SLOT_PICKS = 10
    const val SLOT_BECAUSE = 20      // 20..29
    const val SLOT_GENRE = 40        // 40..59
    private const val SLOT_CATALOGS = 100
    private const val SLOT_WATCHED = 9_000
    private const val SLOT_LIBRARY = 10_000

    /** Rows that may repeat titles shown elsewhere (they're your own lists). */
    private val exemptFromDedupe = setOf("continue", "library", "watched")

    fun init(context: Context) {
        prefs = Prefs(context)
        DeviceCaps.init(context)
        WatchStore.init(context)
        WatchNext.init(context)
        signedIn.value = prefs.authKey != null
        progress.value = prefs.loadProgress()
        prefs.addonsJson?.let { json ->
            runCatching { addons.value = parseCollection(Http.json.parseToJsonElement(json) as JsonArray) }
        }
        refresh()
    }

    // ---- account & sync ---------------------------------------------------

    suspend fun login(email: String, password: String) {
        val key = StremioApi.login(email.trim(), password)
        prefs.authKey = key
        prefs.email = email.trim()
        signedIn.value = true
        homeSlots.clear()
        refresh()
    }

    fun logout() {
        val key = prefs.authKey
        prefs.authKey = null
        prefs.email = null
        prefs.addonsJson = null
        WatchNext.clearAll()
        signedIn.value = false
        library.value = emptyList()
        homeSlots.clear()
        home.value = emptyList()
        scope.launch { key?.let { StremioApi.logout(it) } }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            loading.value = true
            error.value = null
            try {
                val key = prefs.authKey
                if (key != null) {
                    val raw = StremioApi.addonCollection(key)
                    prefs.addonsJson = raw.toString()
                    addons.value = parseCollection(raw)
                    library.value = StremioApi.library(key).map(::LibItem)
                    WatchStore.setStremioWatched(library.value)
                } else {
                    addons.value = listOfNotNull(
                        runCatching { AddonClient.manifest(CINEMETA) }.getOrNull() ?: fallbackCinemeta()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
                error.value = "Couldn't reach Stremio (${e.message}). Showing what's cached."
                if (addons.value.isEmpty()) addons.value = listOf(fallbackCinemeta())
            }
            catalogCache.clear()
            loadHome()
            loading.value = false
        }
    }

    private fun parseCollection(raw: JsonArray): List<Addon> = raw.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val url = o.str("transportUrl") ?: return@mapNotNull null
        val manifest = o.obj("manifest") ?: return@mapNotNull null
        Addon.parse(url, manifest)
    }

    private fun fallbackCinemeta() = Addon(
        transportUrl = CINEMETA,
        id = "com.linvo.cinemeta",
        name = "Cinemeta",
        types = listOf("movie", "series"),
        idPrefixes = listOf("tt"),
        resources = listOf(ResourceDef("catalog", null, null), ResourceDef("meta", null, null)),
        catalogs = listOf("movie", "series").map {
            CatalogDef(it, "top", "Popular", listOf(ExtraDef("genre", false, emptyList()), ExtraDef("search", false, emptyList()), ExtraDef("skip", false, emptyList())))
        },
    )

    val cinemeta: Addon
        get() = addons.value.firstOrNull { it.id == "com.linvo.cinemeta" } ?: fallbackCinemeta()

    // ---- addon fetches (cached) -------------------------------------------

    suspend fun catalog(addon: Addon, type: String, id: String, extra: Map<String, String> = emptyMap()): List<MetaPreview> {
        val key = "${addon.base}|$type|$id|$extra"
        catalogCache[key]?.let { (t, v) -> if (System.currentTimeMillis() - t < 30 * 60_000) return v }
        val v = gate.withPermit { AddonClient.catalog(addon, type, id, extra) }.distinctBy { it.id }
        catalogCache[key] = System.currentTimeMillis() to v
        return v
    }

    suspend fun meta(type: String, id: String): MetaDetail? {
        metaCache["$type/$id"]?.let { return it }
        val candidates = addons.value.filter { it.supports("meta", type, id) }
            .ifEmpty { if (id.startsWith("tt")) listOf(cinemeta) else emptyList() }
        for (a in candidates) {
            val m = runCatching { gate.withPermit { AddonClient.meta(a, type, id) } }.getOrNull()
            if (m != null) {
                metaCache["$type/$id"] = m
                return m
            }
        }
        return null
    }

    /** Streams from every addon that serves them, emitted as each addon answers. */
    fun streams(type: String, videoId: String): Flow<Pair<Addon, List<Stream>>> = channelFlow {
        val sources = addons.value.filter { it.supports("stream", type, videoId) }
        for (a in sources) {
            launch {
                val s = runCatching { AddonClient.streams(a, type, videoId) }.getOrDefault(emptyList())
                send(a to s)
            }
        }
    }

    fun streamSourceCount(type: String, videoId: String) =
        addons.value.count { it.supports("stream", type, videoId) }

    /** One row per catalog that supports search. */
    fun search(query: String): Flow<HomeRow> = channelFlow {
        addons.value.forEach { a ->
            a.catalogs.filter { it.supports("search") }.forEach { c ->
                launch {
                    val items = runCatching { catalog(a, c.type, c.id, mapOf("search" to query)) }.getOrDefault(emptyList())
                    if (items.isNotEmpty()) {
                        send(HomeRow("${a.catalogKey(c)}?q=$query", "${c.name} · ${typeLabel(c.type)} · ${a.name}", items.map { RowItem(it) }))
                    }
                }
            }
        }
    }

    fun typeLabel(type: String) = when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        "tv" -> "TV"
        "channel" -> "Channels"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    // ---- home -------------------------------------------------------------

    /**
     * Each title appears in only one row: the first one it shows up in, top
     * to bottom. Your own lists (Continue watching, Watch again, Library) are
     * left whole. Rows left with only a handful of titles are dropped.
     */
    private fun publish() {
        val seen = HashSet<String>()
        val rows = mutableListOf<HomeRow>()
        for (row in homeSlots.values.distinctBy { it.key }) {
            if (row.items.isEmpty()) continue
            if (row.key in exemptFromDedupe) {
                rows += row
                if (row.key == "continue") row.items.forEach { seen += it.meta.id }
                continue
            }
            val fresh = row.items.filter { it.meta.id !in seen }
            if (fresh.size < minOf(5, row.items.size)) continue
            fresh.forEach { seen += it.meta.id }
            rows += row.copy(items = fresh)
        }
        home.value = rows
    }

    /** Rebuilds the rows that depend on what you've watched and rated. */
    fun refreshPersonalRows() {
        putHomeRow(SLOT_CONTINUE, continueWatching())
        putHomeRow(SLOT_WATCHED, watchAgain())
        scope.launch { runCatching { Recommender.build() }.onFailure { Log.w(TAG, "recs failed", it) } }
    }

    private fun watchAgain() =
        HomeRow("watched", "Watch again", WatchStore.watchedTitles().take(40).map { RowItem(it) })

    /** Drops rows in [from, until) left over from a previous refresh. */
    fun clearSlots(from: Int, end: Int) {
        homeSlots.keys.filter { it in from until end }.forEach { homeSlots.remove(it) }
        publish()
    }

    fun putHomeRow(slot: Int, row: HomeRow?) {
        if (row == null || row.items.isEmpty()) homeSlots.remove(slot) else homeSlots[slot] = row
        publish()
    }

    private suspend fun loadHome() = coroutineScope {
        // Clear catalog slots that may no longer exist (hidden, uninstalled).
        homeSlots.keys.filter { it in SLOT_CATALOGS until SLOT_LIBRARY }.forEach { homeSlots.remove(it) }
        putHomeRow(SLOT_CONTINUE, continueWatching())

        val jobs = mutableListOf<Job>()
        var slot = SLOT_CATALOGS
        val hidden = prefs.hiddenCatalogs
        val seen = HashSet<String>()
        for (addon in addons.value) for (cat in addon.catalogs) {
            if (cat.needsExtra || addon.catalogKey(cat) in hidden || !seen.add(addon.catalogKey(cat))) continue
            val s = slot++
            jobs += launch {
                val items = runCatching { catalog(addon, cat.type, cat.id) }.getOrDefault(emptyList())
                putHomeRow(s, HomeRow(addon.catalogKey(cat), "${cat.name} · ${typeLabel(cat.type)}", items.map { RowItem(it) }))
            }
        }
        jobs += launch { runCatching { Recommender.build() }.onFailure { Log.w(TAG, "recs failed", it) } }
        jobs += launch { runCatching { Seasonal.build() }.onFailure { Log.w(TAG, "seasonal failed", it) } }
        putHomeRow(SLOT_WATCHED, watchAgain())

        val lib = library.value.filter { it.inLibrary }.sortedByDescending { it.mtime }.take(40)
        putHomeRow(SLOT_LIBRARY, HomeRow("library", "Your library", lib.map { RowItem(it.preview()) }))
        jobs.joinAll()
    }

    fun catalogsForSettings(): List<Pair<Addon, CatalogDef>> =
        addons.value.flatMap { a -> a.catalogs.filter { !it.needsExtra }.map { a to it } }
            .distinctBy { (a, c) -> a.catalogKey(c) }

    fun setCatalogHidden(key: String, hidden: Boolean) {
        prefs.hiddenCatalogs = if (hidden) prefs.hiddenCatalogs + key else prefs.hiddenCatalogs - key
        scope.launch { loadHome() }
    }

    // ---- watch progress ---------------------------------------------------

    private data class Watch(
        val id: String, val type: String, val name: String, val poster: String?,
        val videoId: String?, val position: Long, val duration: Long, val updated: Long,
        val upNext: Boolean, val done: Boolean, val label: String?,
    )

    private fun watches(): List<Watch> {
        val fromLib = library.value.filter { it.timeOffset > 0 && (!it.removed || it.temp) }.map {
            Watch(it.id, it.type, it.name, it.poster, it.videoId, it.timeOffset, it.duration, it.lastWatched, false, false, null)
        }
        val local = progress.value.values.map {
            Watch(it.id, it.type, it.name, it.poster, it.videoId, it.position, it.duration, it.updatedAt, it.upNext, it.done, it.label)
        }
        return (fromLib + local).groupBy { it.id }.values.map { it.maxBy { w -> w.updated } }
    }

    fun continueWatching(): HomeRow {
        val items = watches()
            .filter { !it.done && (it.upNext || (it.duration > 0 && it.position.toFloat() / it.duration < 0.95f)) }
            .sortedByDescending { it.updated }
            .take(30)
            .map { w ->
                val ep = w.label ?: episodeLabel(w.videoId)
                RowItem(
                    meta = MetaPreview(id = w.id, type = w.type, name = w.name, poster = w.poster),
                    progress = if (w.upNext || w.duration <= 0) null else w.position.toFloat() / w.duration,
                    caption = when {
                        w.upNext -> "Up next · ${ep.orEmpty()}"
                        ep != null -> ep
                        else -> null
                    },
                )
            }
        return HomeRow("continue", "Continue watching", items)
    }

    /** Where to resume a title: (videoId, positionMs). */
    fun resumePoint(id: String): Pair<String?, Long> {
        val w = watches().firstOrNull { it.id == id } ?: return null to 0L
        if (w.done) return null to 0L
        val finished = w.duration > 0 && w.position.toFloat() / w.duration >= 0.95f
        return w.videoId to (if (w.upNext || finished) 0L else w.position)
    }

    fun watchedIds(): Set<String> =
        library.value.filter { it.everWatched || it.inLibrary }.map { it.id }.toSet() + progress.value.keys +
            WatchStore.excludedIds()

    /** Seeds for recommendations: recently watched titles, newest first. */
    fun historySeeds(limit: Int = 25): List<MetaPreview> {
        val lib = library.value.filter { it.everWatched || it.inLibrary }
            .map { it.preview() to maxOf(it.lastWatched, it.mtime) }
        val local = progress.value.values.map { MetaPreview(it.id, it.type, it.name, it.poster) to it.updatedAt }
        return (lib + local).sortedByDescending { it.second }.map { it.first }
            .filter { it.id.startsWith("tt") && (it.type == "movie" || it.type == "series") }
            .distinctBy { it.id }.take(limit)
    }

    fun saveProgress(p: Progress, finished: Boolean, nextVideoId: String?, nextLabel: String?, sync: Boolean) {
        val map = progress.value.toMutableMap()
        val now = System.currentTimeMillis()
        map[p.id] = when {
            finished && nextVideoId != null ->
                p.copy(videoId = nextVideoId, position = 0, upNext = true, label = nextLabel, updatedAt = now)
            finished -> p.copy(done = true, updatedAt = now)
            else -> p.copy(updatedAt = now, upNext = false, done = false)
        }
        progress.value = map
        prefs.saveProgress(map)
        if (finished) {
            WatchStore.markFinished(p.id, p.type, p.videoId, p.name, p.poster)
            putHomeRow(SLOT_WATCHED, watchAgain())
        }
        putHomeRow(SLOT_CONTINUE, continueWatching())
        WatchNext.update(map[p.id]!!)
        if (sync) scope.launch { syncToStremio(p, finished) }
    }

    /** Patches progress onto an existing Stremio library item; never creates new ones. */
    private suspend fun syncToStremio(p: Progress, finished: Boolean) {
        val key = prefs.authKey ?: return
        if (!prefs.syncProgress) return
        val item = library.value.firstOrNull { it.id == p.id } ?: return
        val now = Dates.iso(System.currentTimeMillis())
        val state = item.raw.obj("state")?.toMutableMap() ?: return
        state["timeOffset"] = JsonPrimitive(if (finished) 0 else p.position)
        state["duration"] = JsonPrimitive(p.duration)
        state["video_id"] = JsonPrimitive(p.videoId)
        state["lastWatched"] = JsonPrimitive(now)
        if (finished) state["timesWatched"] = JsonPrimitive((state.let { JsonObject(it) }.long("timesWatched") ?: 0) + 1)
        val updated = item.raw.toMutableMap()
        updated["state"] = JsonObject(state)
        updated["_mtime"] = JsonPrimitive(now)
        val newItem = JsonObject(updated)
        runCatching { StremioApi.putLibrary(key, listOf(newItem)) }
            .onSuccess { library.value = library.value.map { if (it.id == p.id) LibItem(newItem) else it } }
            .onFailure { Log.w(TAG, "sync failed", it) }
    }
}
