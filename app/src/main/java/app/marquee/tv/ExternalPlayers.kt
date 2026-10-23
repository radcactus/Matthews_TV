package app.marquee.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.marquee.tv.data.Http
import app.marquee.tv.data.Progress
import app.marquee.tv.data.Repo

/** A video player app Marquee can hand streams to. */
enum class ExternalPlayer(val id: String, val label: String, val packages: List<String>) {
    VLC("vlc_app", "VLC app", listOf("org.videolan.vlc")),
    MX("mx", "MX Player", listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.beta")),
    JUST("just", "Just Player", listOf("com.brouken.player"));

    /** The installed package for this player, or null. */
    fun installedPackage(ctx: Context): String? = packages.firstOrNull { pkg ->
        try {
            ctx.packageManager.getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
        fun installed(ctx: Context) = entries.filter { it.installedPackage(ctx) != null }
    }
}

/** Player choices shown in Settings and the "Play with" picker. */
object PlayerChoice {
    /** VLC's engine inside Matthews TV (the default). */
    const val VLC = "vlc"
    /** Android's ExoPlayer engine inside Matthews TV. */
    const val BUILTIN = "builtin"
    const val ASK = "ask"
    fun label(id: String) = when (id) {
        VLC -> "VLC (built in)"
        BUILTIN -> "ExoPlayer (built in)"
        ASK -> "Ask every time"
        else -> ExternalPlayer.byId(id)?.label ?: id
    }
}

/**
 * Invisible activity that opens an external player and waits for it to close.
 * VLC, MX Player and Just Player all report where you stopped, so resume
 * points and Continue Watching keep working like with the built-in player.
 */
class ExternalPlayerActivity : Activity() {

    private lateinit var target: PlayTarget
    private lateinit var player: ExternalPlayer
    private var url: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        player = ExternalPlayer.byId(intent.getStringExtra(EXTRA_PLAYER)) ?: return finish()
        target = runCatching {
            Http.json.decodeFromString(PlayTarget.serializer(), intent.getStringExtra(EXTRA_TARGET)!!)
        }.getOrNull() ?: return finish()
        if (savedInstanceState != null) return   // already launched; waiting for the result

        val pkg = player.installedPackage(this)
        if (pkg == null) {
            Toast.makeText(this, "${player.label} isn't installed on this TV", Toast.LENGTH_LONG).show()
            return finish()
        }
        val keys = intent.getStringArrayExtra(EXTRA_HEADER_KEYS).orEmpty()
        val values = intent.getStringArrayExtra(EXTRA_HEADER_VALUES).orEmpty()
        val title = listOfNotNull(target.name, target.label).joinToString(" · ")

        val launch = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            putExtra("title", title)
            when (player) {
                ExternalPlayer.VLC -> {
                    // This activity is the one that returns the stop position.
                    component = ComponentName(pkg, "org.videolan.vlc.gui.video.VideoPlayerActivity")
                    putExtra("position", target.startMs)
                    putExtra("from_start", target.startMs <= 0)
                }
                ExternalPlayer.MX, ExternalPlayer.JUST -> {
                    // Just Player implements MX Player's API.
                    setPackage(pkg)
                    putExtra("position", target.startMs.toInt())
                    putExtra("return_result", true)
                    putExtra("secure_uri", true)
                    if (keys.isNotEmpty()) {
                        putExtra("headers", keys.zip(values).flatMap { listOf(it.first, it.second) }.toTypedArray())
                    }
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(launch, REQUEST)
        } catch (_: ActivityNotFoundException) {
            // Older VLC builds name the activity differently; fall back to the package.
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(launch.setComponent(null).setPackage(pkg), REQUEST)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Couldn't open ${player.label}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Deprecated("Activity result API is overkill for this one call")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        if (data != null) saveFrom(data)
        finish()
    }

    private fun saveFrom(data: Intent) {
        val position: Long
        val duration: Long
        val completed: Boolean
        when (player) {
            ExternalPlayer.VLC -> {
                position = data.getLongExtra("extra_position", -1L)
                duration = data.getLongExtra("extra_duration", -1L)
                completed = false
            }
            else -> {
                position = data.getIntExtra("position", -1).toLong()
                duration = data.getIntExtra("duration", -1).toLong()
                completed = data.getStringExtra("end_by") == "playback_completion"
            }
        }
        if (duration <= 0 && !completed) return
        val finished = completed || (duration > 0 && position.toFloat() / duration >= 0.92f)
        if (!finished && position < 5_000) return
        Repo.saveProgress(
            Progress(
                id = target.id, type = target.type, videoId = target.videoId, name = target.name,
                poster = target.poster, position = position.coerceAtLeast(0), duration = duration.coerceAtLeast(0),
                label = target.label,
            ),
            finished = finished,
            nextVideoId = target.nextVideoId,
            nextLabel = target.nextLabel,
            sync = true,
        )
    }

    companion object {
        private const val REQUEST = 41
        private const val EXTRA_URL = "url"
        private const val EXTRA_PLAYER = "player"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_HEADER_KEYS = "hk"
        private const val EXTRA_HEADER_VALUES = "hv"

        fun intent(ctx: Context, player: ExternalPlayer, url: String, headers: Map<String, String>, t: PlayTarget): Intent =
            Intent(ctx, ExternalPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_PLAYER, player.id)
                .putExtra(EXTRA_TARGET, Http.json.encodeToString(PlayTarget.serializer(), t))
                .putExtra(EXTRA_HEADER_KEYS, headers.keys.toTypedArray())
                .putExtra(EXTRA_HEADER_VALUES, headers.values.toTypedArray())
    }
}
