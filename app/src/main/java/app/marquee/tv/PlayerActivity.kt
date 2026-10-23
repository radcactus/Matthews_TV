package app.marquee.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.marquee.tv.data.Http
import app.marquee.tv.data.Progress
import app.marquee.tv.data.Repo
import app.marquee.tv.data.StreamChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private var saver: Job? = null
    private var saved = false
    private var savePosition = true

    private fun target(positionMs: Long) = PlayTarget(
        id = id, type = type, videoId = videoId, name = name, poster = poster,
        label = label, nextVideoId = nextVideoId, nextLabel = nextLabel,
        startMs = maxOf(positionMs, intent.getLongExtra(EXTRA_START, 0L)),
    )

    private val id by lazy { intent.getStringExtra(EXTRA_ID).orEmpty() }
    private val type by lazy { intent.getStringExtra(EXTRA_TYPE) ?: "movie" }
    private val videoId by lazy { intent.getStringExtra(EXTRA_VIDEO).orEmpty() }
    private val name by lazy { intent.getStringExtra(EXTRA_NAME).orEmpty() }
    private val poster by lazy { intent.getStringExtra(EXTRA_POSTER) }
    private val label by lazy { intent.getStringExtra(EXTRA_LABEL) }
    private val nextVideoId by lazy { intent.getStringExtra(EXTRA_NEXT) }
    private val nextLabel by lazy { intent.getStringExtra(EXTRA_NEXT_LABEL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val keys = intent.getStringArrayExtra(EXTRA_HEADER_KEYS).orEmpty()
        val values = intent.getStringArrayExtra(EXTRA_HEADER_VALUES).orEmpty()
        val headers = keys.zip(values).toMap()

        val dataSource = OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(headers)
        // If the first hardware decoder fails, try the next one before giving up.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        view = PlayerView(this).apply {
            player = p
            useController = true
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 4000
            keepScreenOn = true
        }
        setContentView(view)

        val title = listOfNotNull(name.ifBlank { null }, label).joinToString(" · ")
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        p.setMediaItem(item)
        val start = intent.getLongExtra(EXTRA_START, 0L)
        if (start > 0) p.seekTo(start)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(java.util.Locale.getDefault().language)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    save(finishedOverride = true)
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Flag it so the streams panel shows it as broken next time.
                // Decoder or format problems are often fine in VLC; network errors are not.
                val formatProblem = error.errorCode in 4000..4999
                val ext = if (formatProblem) {
                    savePosition = false
                    Playback.fallbackToExternal(this@PlayerActivity, url, headers, target(p.currentPosition))
                } else null
                if (ext != null) {
                    Toast.makeText(this@PlayerActivity, "Built-in player can't decode this, opening ${ext.label}", Toast.LENGTH_LONG).show()
                } else {
                    StreamChecker.markBroken(url, "Failed to play (${error.errorCodeName})")
                    Toast.makeText(
                        this@PlayerActivity,
                        "Can't play this stream (${error.errorCodeName}). Try another one.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                finish()
            }
        })
        p.prepare()
        p.playWhenReady = true
        player = p

        if (start > 0) {
            Toast.makeText(this, "Resuming from ${app.marquee.tv.ui.formatTime(start)}", Toast.LENGTH_SHORT).show()
        }

        // Save locally every 15 seconds so a crash or power-off doesn't lose your place.
        saver = lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                save(sync = false)
            }
        }
    }

    /** D-pad seeks when the controls are hidden; otherwise the controls get the keys. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekBack(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekForward(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (p.isPlaying) p.pause() else p.play()
                    view.showController()
                    return true
                }
            }
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun save(sync: Boolean = true, finishedOverride: Boolean = false) {
        if (!savePosition) return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (pos < 5_000 && !finishedOverride) return
        val finished = finishedOverride || pos.toFloat() / dur >= 0.92f
        if (finished && saved) return
        if (finished) saved = true
        Repo.saveProgress(
            Progress(
                id = id, type = type, videoId = videoId, name = name, poster = poster,
                position = pos, duration = dur, label = label,
            ),
            finished = finished,
            nextVideoId = nextVideoId,
            nextLabel = nextLabel,
            sync = sync || finished,
        )
    }

    override fun onStop() {
        super.onStop()
        save()
        saver?.cancel()
        player?.release()
        player = null
        if (!isFinishing) finish()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_HEADER_KEYS = "hk"
        private const val EXTRA_HEADER_VALUES = "hv"
        private const val EXTRA_START = "start"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_NEXT = "next"
        private const val EXTRA_NEXT_LABEL = "nextLabel"

        fun intent(ctx: Context, url: String, headers: Map<String, String>, t: PlayTarget): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_HEADER_KEYS, headers.keys.toTypedArray())
                .putExtra(EXTRA_HEADER_VALUES, headers.values.toTypedArray())
                .putExtra(EXTRA_START, t.startMs)
                .putExtra(EXTRA_ID, t.id)
                .putExtra(EXTRA_TYPE, t.type)
                .putExtra(EXTRA_VIDEO, t.videoId)
                .putExtra(EXTRA_NAME, t.name)
                .putExtra(EXTRA_POSTER, t.poster)
                .putExtra(EXTRA_LABEL, t.label)
                .putExtra(EXTRA_NEXT, t.nextVideoId)
                .putExtra(EXTRA_NEXT_LABEL, t.nextLabel)
    }
}
