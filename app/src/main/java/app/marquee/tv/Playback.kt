package app.marquee.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.marquee.tv.data.Repo
import app.marquee.tv.data.Stream
import kotlinx.serialization.Serializable

/** Everything the player needs to save progress for one video. */
@Serializable
data class PlayTarget(
    val id: String,
    val type: String,
    val videoId: String,
    val name: String,
    val poster: String?,
    val label: String?,          // "S1 E3" for episodes
    val nextVideoId: String?,
    val nextLabel: String?,
    val startMs: Long,
)

enum class StreamKind { Direct, Server, External, Stremio }

object Playback {
    fun kind(s: Stream): StreamKind = when {
        s.url != null -> StreamKind.Direct
        s.infoHash != null && s.fileIdx != null && Repo.prefs.serverUrl != null -> StreamKind.Server
        s.ytId != null || s.externalUrl != null -> StreamKind.External
        else -> StreamKind.Stremio
    }

    /** Whether this stream goes to a video player (built-in or VLC etc.) at all. */
    fun usesPlayer(s: Stream) = kind(s).let { it == StreamKind.Direct || it == StreamKind.Server }

    private fun urlFor(s: Stream): Pair<String, Map<String, String>>? = when (kind(s)) {
        StreamKind.Direct -> s.url!! to s.behaviorHints?.proxyHeaders?.request.orEmpty()
        StreamKind.Server -> "${Repo.prefs.serverUrl}/${s.infoHash}/${s.fileIdx}" to emptyMap()
        else -> null
    }

    /**
     * Plays a stream. [player] is a [PlayerChoice] id or an [ExternalPlayer] id;
     * null uses the default from Settings ("ask" is resolved by the UI first).
     */
    fun play(ctx: Context, s: Stream, t: PlayTarget, player: String? = null) {
        val (url, headers) = urlFor(s) ?: run {
            when (kind(s)) {
                StreamKind.External -> open(ctx, Uri.parse(s.externalUrl ?: "https://www.youtube.com/watch?v=${s.ytId}"))
                else -> openInStremio(ctx, t.type, t.id, t.videoId)
            }
            return
        }
        val choice = player ?: Repo.prefs.playerChoice
        when (choice) {
            PlayerChoice.BUILTIN -> ctx.startActivity(PlayerActivity.intent(ctx, url, headers, t))
            PlayerChoice.VLC, PlayerChoice.ASK -> ctx.startActivity(VlcPlayerActivity.intent(ctx, url, headers, t))
            else -> {
                // Another app, only when chosen in Settings or with a long-press.
                val external = ExternalPlayer.byId(choice)
                if (external != null && external.installedPackage(ctx) != null) {
                    ctx.startActivity(ExternalPlayerActivity.intent(ctx, external, url, headers, t))
                } else {
                    external?.let {
                        Toast.makeText(ctx, "${it.label} isn't installed, playing in Matthews TV", Toast.LENGTH_SHORT).show()
                    }
                    ctx.startActivity(VlcPlayerActivity.intent(ctx, url, headers, t))
                }
            }
        }
    }

    fun openInStremio(ctx: Context, type: String, id: String, videoId: String?) {
        val path = if (videoId != null && videoId != id) "$type/$id/$videoId" else "$type/$id"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/$path"))
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "Stremio isn't installed on this TV", Toast.LENGTH_LONG).show()
        }
    }

    private fun open(ctx: Context, uri: Uri) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_LONG).show()
        }
    }
}
