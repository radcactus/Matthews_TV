package app.marquee.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Verdict on whether a stream will play. Lower rank sorts first. */
sealed interface Health {
    val rank: Int

    /** Probed and the server returned real video. */
    data class Ok(val ms: Long, val size: Long?) : Health { override val rank = 0 }

    /** Not probed, but the addon's own info says it should work (cached on debrid, has seeders). */
    data class Likely(val note: String) : Health { override val rank = 1 }

    data object Checking : Health { override val rank = 2 }
    data object Unknown : Health { override val rank = 2 }

    /** Debrid link that isn't cached yet: playing it would only start a download. */
    data object Uncached : Health { override val rank = 3 }

    data class Broken(val reason: String) : Health { override val rank = 4 }
}

/**
 * Tests streams before you pick one.
 *
 * Direct links get a tiny ranged request (the first 4 KB), following redirects
 * the way the player would. That catches dead hosts, expired links, error
 * pages, and the short "downloading…" / "failed" placeholder videos debrid
 * addons redirect to when a file isn't really available.
 *
 * Torrent streams can't be tested without downloading, so they're judged on
 * the seeder count the addon reports.
 */
object StreamChecker {
    private const val CACHE_MS = 10 * 60_000L
    private const val MIN_REAL_VIDEO_BYTES = 20L * 1024 * 1024   // placeholders are a few MB at most

    private val client = Http.client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gate = Semaphore(5)
    private val cache = ConcurrentHashMap<String, Pair<Long, Health>>()

    private val placeholder = Regex(
        """/(videos?|static)/[^/?]*(download|fail|error|unavailable|expired|limit|not_?found|no_?results|blocked)[^/?]*\.(mp4|mkv|webm)""",
        RegexOption.IGNORE_CASE,
    )
    private val seeders = Regex("""👤\s*(\d+)""")
    private val uncachedMarkers = listOf("download]", "⏳", "uncached", "[rd download", "[ad download", "[pm download", "[tb download")
    private val cachedMarkers = listOf("+]", "⚡", "[rd+", "[ad+", "[pm+", "[tb+", "instant")

    fun key(s: Stream): String =
        s.url ?: s.infoHash?.let { "$it/${s.fileIdx}" } ?: s.externalUrl ?: s.ytId ?: s.hashCode().toString()

    private fun text(s: Stream) = listOfNotNull(s.name, s.title, s.description).joinToString(" ").lowercase(Locale.ROOT)

    /** Verdict without touching the network, or null if the stream needs a probe. */
    fun quick(s: Stream): Health? {
        cached(key(s))?.let { return it }
        val t = text(s)
        return when {
            s.url != null -> when {
                !Repo.prefs.probeUncached && uncachedMarkers.any { it in t } -> Health.Uncached
                else -> null
            }
            s.infoHash != null -> {
                val n = seeders.find(t)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    n == null -> Health.Unknown
                    n == 0 -> Health.Broken("No seeders")
                    n < 3 -> Health.Likely("$n seeder${if (n == 1) "" else "s"}, may be slow")
                    else -> Health.Likely("$n seeders")
                }
            }
            else -> Health.Unknown
        }
    }

    /** Whether the addon labels this stream as ready on debrid (used before a probe finishes). */
    fun looksCached(s: Stream) = cachedMarkers.any { it in text(s) }

    private fun cached(k: String): Health? {
        val (t, h) = cache[k] ?: return null
        return if (System.currentTimeMillis() - t < CACHE_MS) h else null.also { cache.remove(k) }
    }

    /** Remembers a link that failed in the player so it shows as broken next time. */
    fun markBroken(url: String, reason: String) {
        cache[url] = System.currentTimeMillis() to Health.Broken(reason)
    }

    suspend fun check(s: Stream): Health {
        quick(s)?.let { return it }
        val url = s.url ?: return Health.Unknown
        val h = gate.withPermit { probe(url, s.behaviorHints?.proxyHeaders?.request.orEmpty()) }
        cache[key(s)] = System.currentTimeMillis() to h
        return h
    }

    private suspend fun probe(url: String, headers: Map<String, String>): Health = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val req = try {
            Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=0-4095")
            }.build()
        } catch (_: IllegalArgumentException) {
            return@withContext Health.Broken("Invalid link")
        }
        try {
            client.newCall(req).execute().use { res ->
                val ms = System.currentTimeMillis() - started
                val finalUrl = res.request.url.toString()
                val type = res.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                val isHls = "mpegurl" in type || finalUrl.substringBefore('?').endsWith(".m3u8")

                if (placeholder.containsMatchIn(finalUrl)) {
                    val reason = if ("download" in finalUrl.lowercase(Locale.ROOT)) "Not ready on debrid yet" else "Addon says unavailable"
                    return@use Health.Broken(reason)
                }
                if (!res.isSuccessful) {
                    return@use Health.Broken(
                        when (res.code) {
                            401, 403 -> "Link expired or blocked"
                            404, 410 -> "File not found"
                            416 -> "File is empty"
                            429 -> "Host is rate-limiting"
                            in 500..599 -> "Host error (${res.code})"
                            else -> "Error ${res.code}"
                        },
                    )
                }
                if (isHls) {
                    val head = res.body?.source()?.let { src ->
                        src.request(64)
                        src.buffer.readUtf8(minOf(64, src.buffer.size))
                    }.orEmpty()
                    return@use if (head.trimStart().startsWith("#EXTM3U")) Health.Ok(ms, null)
                    else Health.Broken("Not a video")
                }
                if (type.startsWith("text/html") || type.startsWith("application/json") || type.startsWith("text/plain")) {
                    return@use Health.Broken("Not a video")
                }
                val size = res.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    ?: res.header("Content-Length")?.toLongOrNull()?.takeIf { res.code == 200 }
                if (size != null && size in 1 until MIN_REAL_VIDEO_BYTES) {
                    return@use Health.Broken("Placeholder clip, not the real file")
                }
                Health.Ok(ms, size)
            }
        } catch (_: SocketTimeoutException) {
            Health.Broken("Not responding")
        } catch (_: UnknownHostException) {
            Health.Broken("Server not found")
        } catch (e: IOException) {
            Health.Broken("Can't connect")
        }
    }

    fun formatSize(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
    }
}
