package app.marquee.tv.data

import app.marquee.tv.Playback
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one place that decides which stream is "best", used by the streams
 * list and by autoplay so they always agree.
 */
object StreamRank {
    /**
     * Sort key, lower is better: codecs this TV can decode, then not a camera
     * recording, then resolution (2160p > 1080p > 720p…), then compression
     * within that resolution (AV1 > HEVC > VP9 > H.264 > older), then source.
     */
    fun qualityKey(q: Quality, decodable: Boolean): Long =
        (if (decodable) 0L else 1L) * 1_000_000_000L +
            (if (q.source == Source.CAM) 1L else 0L) * 100_000_000L +
            (10_000L - q.height.coerceIn(0, 9_999)) * 1_000L +
            q.codec.rank * 10L +
            q.source.rank

    fun qualityKey(s: Stream): Long {
        val q = StreamQuality.parse(s)
        return qualityKey(q, DeviceCaps.canDecode(q.codec))
    }

    private class Candidate(val stream: Stream, val addonIndex: Int, val position: Int) {
        val key = qualityKey(stream)
    }

    /**
     * Finds the best working stream for a video without asking you: same
     * release group as what you just watched first (like Stremio's binge
     * groups, so quality and audio stay consistent), otherwise the best
     * quality. The top candidates are checked in parallel.
     */
    suspend fun bestStream(type: String, videoId: String, bingeGroup: String?, timeoutMs: Long = 20_000): Stream? =
        coroutineScope {
            val order = Repo.addons.value
            val found = mutableListOf<Candidate>()
            withTimeoutOrNull(timeoutMs) {
                Repo.streams(type, videoId).collect { (addon, streams) ->
                    val ai = order.indexOfFirst { it.transportUrl == addon.transportUrl }.let { if (it < 0) 999 else it }
                    streams.forEachIndexed { i, s -> found += Candidate(s, ai, i) }
                }
            }
            val playable = found
                .filter { Playback.usesPlayer(it.stream) }
                .filter { c -> StreamChecker.quick(c.stream).let { it !is Health.Broken && it != Health.Uncached } }
                .sortedWith(compareBy<Candidate>({ it.key }, { it.addonIndex }, { it.position }))
            val sameGroup = bingeGroup?.let { g -> playable.filter { it.stream.behaviorHints?.bingeGroup == g } }.orEmpty()

            for (group in listOf(sameGroup, playable)) {
                if (group.isEmpty()) continue
                val checked = group.take(5).map { c -> async { c to StreamChecker.check(c.stream) } }.awaitAll()
                checked.firstOrNull { it.second is Health.Ok || it.second is Health.Likely }?.let { return@coroutineScope it.first.stream }
            }
            null
        }
}
