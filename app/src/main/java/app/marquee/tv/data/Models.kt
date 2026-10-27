package app.marquee.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---- Addon protocol: catalog / meta / stream payloads --------------------

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
) {
    val allGenres: List<String> get() = (genres ?: genre).orEmpty()
    val rating: Double get() = imdbRating?.toDoubleOrNull() ?: 0.0

    /** Wide image for the hero area; Cinemeta ids can fall back to MetaHub. */
    val backdrop: String?
        get() = background ?: if (id.startsWith("tt"))
            "https://images.metahub.space/background/medium/$id/img" else null

    val posterUrl: String?
        get() = poster ?: if (id.startsWith("tt"))
            "https://images.metahub.space/poster/medium/$id/img" else null
}

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val runtime: String? = null,
) {
    val label: String get() = if (season != null && episode != null) "S$season E$episode" else ""
    val displayTitle: String get() = title ?: name ?: label
}

@Serializable
data class MetaDetail(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val runtime: String? = null,
    val year: String? = null,
    val videos: List<Video> = emptyList(),
) {
    val allGenres: List<String> get() = (genres ?: genre).orEmpty()

    fun toPreview() = MetaPreview(
        id = id, type = type, name = name, poster = poster, background = background,
        logo = logo, description = description, releaseInfo = releaseInfo,
        imdbRating = imdbRating, genres = allGenres,
    )

    /** Episodes in watch order, specials (season 0) last. */
    val episodes: List<Video>
        get() = videos.filter { it.season != null }
            .sortedWith(compareBy<Video>({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode ?: 0 }))
}

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val behaviorHints: StreamHints? = null,
) {
    val info: String get() = (description ?: title).orEmpty()
}

/** A subtitle file from a subtitles addon (OpenSubtitles etc.). */
@Serializable
data class SubtitleFile(
    val id: String = "",
    val url: String,
    val lang: String = "",
)

@Serializable
data class StreamHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class ProxyHeaders(val request: Map<String, String>? = null)

// ---- Addons --------------------------------------------------------------

data class ExtraDef(val name: String, val isRequired: Boolean, val options: List<String>)

data class CatalogDef(
    val type: String,
    val id: String,
    val name: String,
    val extras: List<ExtraDef>,
) {
    val needsExtra: Boolean get() = extras.any { it.isRequired }
    fun supports(extra: String) = extras.any { it.name == extra }
    fun genres(): List<String> = extras.firstOrNull { it.name == "genre" }?.options.orEmpty()
}

data class ResourceDef(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class Addon(
    val transportUrl: String,
    val id: String,
    val name: String,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val resources: List<ResourceDef>,
    val catalogs: List<CatalogDef>,
) {
    val base: String get() = transportUrl.removeSuffix("/manifest.json").removeSuffix("/")

    fun supports(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val t = r.types ?: types
        val p = r.idPrefixes ?: idPrefixes
        return type in t && (p == null || p.isEmpty() || p.any { id.startsWith(it) })
    }

    fun catalogKey(c: CatalogDef) = "$id/${c.type}/${c.id}"

    companion object {
        fun parse(transportUrl: String, m: JsonObject): Addon? {
            val id = m.str("id") ?: return null
            val resources = m.arr("resources").orEmpty().mapNotNull { r ->
                when (r) {
                    is JsonPrimitive -> r.contentOrNull?.let { ResourceDef(it, null, null) }
                    is JsonObject -> r.str("name")?.let {
                        ResourceDef(it, r.strList("types"), r.strList("idPrefixes"))
                    }
                    else -> null
                }
            }
            val catalogs = m.arr("catalogs").orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val required = o.strList("extraRequired").orEmpty()
                val extras = o.arr("extra")?.mapNotNull { e ->
                    val eo = e as? JsonObject ?: return@mapNotNull null
                    val n = eo.str("name") ?: return@mapNotNull null
                    ExtraDef(
                        n,
                        eo.bool("isRequired") == true || n in required,
                        eo.strList("options").orEmpty(),
                    )
                } ?: o.strList("extraSupported").orEmpty().map { ExtraDef(it, it in required, emptyList()) }
                CatalogDef(
                    type = o.str("type") ?: return@mapNotNull null,
                    id = o.str("id") ?: return@mapNotNull null,
                    name = o.str("name") ?: o.str("id").orEmpty(),
                    extras = extras,
                )
            }
            return Addon(
                transportUrl = transportUrl,
                id = id,
                name = m.str("name") ?: id,
                types = m.strList("types").orEmpty(),
                idPrefixes = m.strList("idPrefixes"),
                resources = resources,
                catalogs = catalogs,
            )
        }
    }
}

// ---- Stremio library item (kept raw so we never drop fields on write) ----

class LibItem(val raw: JsonObject) {
    private val state = raw.obj("state")
    val id = raw.str("_id").orEmpty()
    val type = raw.str("type") ?: "movie"
    val name = raw.str("name").orEmpty()
    val poster = raw.str("poster")
    val removed = raw.bool("removed") ?: false
    val temp = raw.bool("temp") ?: false
    val mtime = Dates.parse(raw.str("_mtime"))
    val lastWatched = Dates.parse(state?.str("lastWatched"))
    val timeOffset = state?.long("timeOffset") ?: 0L
    val duration = state?.long("duration") ?: 0L
    val videoId = state?.str("video_id")
    val timesWatched = state?.long("timesWatched") ?: 0L
    val flaggedWatched = (state?.long("flaggedWatched") ?: 0L) > 0

    /** In the user's library (not just something they watched once). */
    val inLibrary get() = !removed && !temp
    val everWatched get() = timesWatched > 0 || flaggedWatched || timeOffset > 0 || lastWatched > 0

    fun preview() = MetaPreview(id = id, type = type, name = name, poster = poster)
}

// ---- Local watch progress ------------------------------------------------

@Serializable
data class Progress(
    val id: String,
    val type: String,
    val videoId: String,
    val name: String,
    val poster: String? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val updatedAt: Long = 0,
    val upNext: Boolean = false,
    val done: Boolean = false,
    val label: String? = null,
)

// ---- UI-facing rows ------------------------------------------------------

@Serializable
data class RowItem(
    val meta: MetaPreview,
    val progress: Float? = null,
    val caption: String? = null,
)

@Serializable
data class HomeRow(val key: String, val title: String, val items: List<RowItem>)

fun episodeLabel(videoId: String?): String? {
    val parts = videoId?.split(":") ?: return null
    if (parts.size < 3) return null
    return "S${parts[parts.size - 2]} E${parts.last()}"
}
