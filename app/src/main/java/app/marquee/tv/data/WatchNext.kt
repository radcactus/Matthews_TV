package app.marquee.tv.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.launch

/**
 * Puts what you're in the middle of on the TV's own home screen ("Continue
 * watching" / "Play next"), using Android TV's Watch Next list. Picking one
 * there opens Matthews TV on that title.
 *
 * Google TV decides what it shows from this list; Fire TV ignores it.
 */
object WatchNext {
    private const val TAG = "WatchNext"
    private lateinit var appContext: Context
    private val prefs by lazy { appContext.getSharedPreferences("watchnext", Context.MODE_PRIVATE) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val supported get() = Build.VERSION.SDK_INT >= 26 && ::appContext.isInitialized

    fun deepLink(type: String, id: String): Uri = Uri.parse("matthewstv://detail/$type/${Uri.encode(id)}")

    /** Adds, updates or removes the TV home-screen entry for this title. */
    fun update(p: Progress) {
        if (!supported) return
        Repo.scope.launch {
            runCatching { write(p) }.onFailure { Log.w(TAG, "watch next update failed", it) }
        }
    }

    private fun write(p: Progress) {
        val resolver = appContext.contentResolver
        val existing = prefs.getLong(p.id, -1L)
        if (p.done) {
            if (existing >= 0) resolver.delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null)
            prefs.edit().remove(p.id).apply()
            return
        }
        // Only list things actually started, or the next episode after one you finished.
        if (!p.upNext && p.position < 60_000) return

        val parts = p.videoId.split(":")
        val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
        val episode = parts.lastOrNull()?.toIntOrNull()
        val isEpisode = p.type != "movie" && parts.size >= 3 && season != null && episode != null
        val art = if (p.id.startsWith("tt")) "https://images.metahub.space/background/medium/${p.id}/img" else p.poster

        val builder = WatchNextProgram.Builder()
            .setType(if (isEpisode) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setWatchNextType(
                if (p.upNext) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
            )
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setTitle(p.name)
            .setInternalProviderId(p.id)
            .setIntentUri(deepLink(p.type, p.id))
        art?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9) }
        if (isEpisode) {
            builder.setSeasonNumber(season!!).setEpisodeNumber(episode!!)
            p.label?.let { builder.setEpisodeTitle(it) }
        }
        if (!p.upNext && p.duration > 0) {
            builder.setLastPlaybackPositionMillis(p.position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis(p.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        val values = builder.build().toContentValues()

        val updated = existing >= 0 &&
            resolver.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null) > 0
        if (!updated) {
            val uri = resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) ?: return
            prefs.edit().putLong(p.id, ContentUris.parseId(uri)).apply()
        }
    }

    /** Removes everything this app put on the TV home screen (used on sign-out). */
    fun clearAll() {
        if (!supported) return
        Repo.scope.launch {
            runCatching {
                prefs.all.values.filterIsInstance<Long>().forEach { id ->
                    appContext.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
                }
                prefs.edit().clear().apply()
            }
        }
    }
}
