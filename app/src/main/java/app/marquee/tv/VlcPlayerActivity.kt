package app.marquee.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import app.marquee.tv.data.Progress
import app.marquee.tv.data.Repo
import app.marquee.tv.data.StreamChecker
import app.marquee.tv.ui.Accent
import app.marquee.tv.ui.ActionButton
import app.marquee.tv.ui.MarqueeTheme
import app.marquee.tv.ui.Panel
import app.marquee.tv.ui.TextDim
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The built-in player, powered by VLC's engine (libVLC) inside Matthews TV.
 * Plays nearly any format (MKV, HEVC, AV1, DTS, TrueHD, embedded subtitles)
 * without leaving the app.
 */
class VlcPlayerActivity : ComponentActivity() {

    private lateinit var libVlc: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private var attached = false
    private var saver: Job? = null
    private var saved = false
    private var everPlayed = false
    private var handedOff = false

    private lateinit var url: String
    private lateinit var target: PlayTarget
    private var headers: Map<String, String> = emptyMap()

    // UI state
    private var position by mutableLongStateOf(0L)
    private var length by mutableLongStateOf(0L)
    private var playing by mutableStateOf(false)
    private var buffering by mutableFloatStateOf(100f)
    private var controlsVisible by mutableStateOf(true)
    private var picker by mutableStateOf<String?>(null)    // "audio" | "subs"
    private var interaction by mutableIntStateOf(0)        // bumps keep the controls up

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        target = runCatching {
            app.marquee.tv.data.Http.json.decodeFromString(PlayTarget.serializer(), intent.getStringExtra(EXTRA_TARGET)!!)
        }.getOrNull() ?: return finish()
        val keys = intent.getStringArrayExtra(EXTRA_HEADER_KEYS).orEmpty()
        val values = intent.getStringArrayExtra(EXTRA_HEADER_VALUES).orEmpty()
        headers = keys.zip(values).toMap()

        libVlc = LibVLC(
            this,
            arrayListOf(
                "--http-reconnect",
                "--audio-time-stretch",
                "--avcodec-skiploopfilter=1",   // smoother playback of heavy H.264 in software
            ),
        )
        mp = MediaPlayer(libVlc)
        videoLayout = VLCVideoLayout(this)

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=2000")
            if (target.startMs > 0) addOption(":start-time=${target.startMs / 1000.0}")
            headers.forEach { (k, v) ->
                when (k.lowercase()) {
                    "user-agent" -> addOption(":http-user-agent=$v")
                    "referer", "referrer" -> addOption(":http-referrer=$v")
                }
            }
        }
        mp.setMedia(media)
        media.release()

        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; everPlayed = true }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> position = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.Buffering -> buffering = e.buffering
                MediaPlayer.Event.EndReached -> {
                    save(finishedOverride = true)
                    finish()
                }
                MediaPlayer.Event.EncounteredError -> onPlaybackError()
            }
        }

        setContent {
            MarqueeTheme {
                PlayerScreen()
            }
        }

        if (target.startMs > 0) {
            Toast.makeText(this, "Resuming from ${app.marquee.tv.ui.formatTime(target.startMs)}", Toast.LENGTH_SHORT).show()
        }

        saver = lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                save(sync = false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!attached) {
            mp.attachViews(videoLayout, null, true, false)
            attached = true
            mp.play()
        }
    }

    override fun onStop() {
        super.onStop()
        save()
        saver?.cancel()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mp.isInitialized) {
            mp.setEventListener(null)
            runCatching { mp.stop() }
            if (attached) runCatching { mp.detachViews() }
            mp.release()
            libVlc.release()
        }
    }

    private fun onPlaybackError() {
        if (handedOff) return
        handedOff = true
        // If VLC couldn't even start, give the other built-in engine one try.
        if (!everPlayed && !intent.getBooleanExtra(EXTRA_IS_FALLBACK, false)) {
            Toast.makeText(this, "Trying the other built-in player…", Toast.LENGTH_SHORT).show()
            startActivity(PlayerActivity.intent(this, url, headers, target, isFallback = true))
        } else {
            StreamChecker.markBroken(url, "Failed to play")
            Toast.makeText(this, "Can't play this stream. Try another one.", Toast.LENGTH_LONG).show()
        }
        saved = true    // nothing worth saving
        finish()
    }

    private fun save(sync: Boolean = true, finishedOverride: Boolean = false) {
        if (handedOff || !::mp.isInitialized) return
        val pos = runCatching { mp.time }.getOrDefault(position)
        val dur = runCatching { mp.length }.getOrDefault(length)
        if (dur <= 0 && !finishedOverride) return
        if (pos < 5_000 && !finishedOverride) return
        val finished = finishedOverride || pos.toFloat() / dur >= 0.92f
        if (finished && saved) return
        if (finished) saved = true
        Repo.saveProgress(
            Progress(
                id = target.id, type = target.type, videoId = target.videoId, name = target.name,
                poster = target.poster, position = pos, duration = dur.coerceAtLeast(0), label = target.label,
            ),
            finished = finished,
            nextVideoId = target.nextVideoId,
            nextLabel = target.nextLabel,
            sync = sync || finished,
        )
    }

    // ---- remote control ---------------------------------------------------

    private fun togglePlay() {
        if (mp.isPlaying) mp.pause() else mp.play()
    }

    private fun seekBy(ms: Long) {
        val len = mp.length
        val t = (mp.time + ms).coerceIn(0L, if (len > 0) len - 1_000 else Long.MAX_VALUE)
        mp.setTime(t)
        position = t
    }

    private fun showControls() {
        controlsVisible = true
        interaction++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlay(); showControls(); return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); showControls(); return true }
            }
            if (!controlsVisible && picker == null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); showControls(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(30_000); showControls(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePlay(); showControls(); return true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { showControls(); return true }
                }
            } else {
                interaction++
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- UI -----------------------------------------------------------------

    @Composable
    private fun PlayerScreen() {
        val playFocus = remember { FocusRequester() }

        BackHandler(enabled = picker != null || controlsVisible) {
            if (picker != null) picker = null else controlsVisible = false
        }

        // Hide the controls after 5 seconds without a key press while playing.
        LaunchedEffect(interaction, controlsVisible, playing, picker) {
            if (controlsVisible && playing && picker == null) {
                delay(5_000)
                controlsVisible = false
            }
        }
        LaunchedEffect(controlsVisible, picker) {
            if (controlsVisible && picker == null) {
                delay(50)
                runCatching { playFocus.requestFocus() }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { videoLayout }, modifier = Modifier.fillMaxSize())

            if (buffering < 100f && everPlayed || !everPlayed) {
                Text(
                    if (!everPlayed) "Loading…" else "Buffering ${buffering.toInt()}%",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            if (controlsVisible && picker == null) {
                Controls(playFocus)
            }

            picker?.let { kind -> TrackPicker(kind) }
        }
    }

    @Composable
    private fun Controls(playFocus: FocusRequester) {
        Column(Modifier.fillMaxSize()) {
            // Title at the top
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 40.dp, vertical = 24.dp),
            ) {
                Column {
                    Text(target.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    target.label?.let { Text(it, color = TextDim, fontSize = 14.sp) }
                }
            }
            Spacer(Modifier.weight(1f))
            // Progress and buttons at the bottom
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 28.dp),
            ) {
                val frac = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).background(Accent, RoundedCornerShape(2.dp)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(clock(position), fontSize = 13.sp, color = TextDim)
                    Spacer(Modifier.weight(1f))
                    Text(if (length > 0) "-${clock(length - position)}" else "", fontSize = 13.sp, color = TextDim)
                }
                Row(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionButton(
                        if (playing) "Pause" else "Play",
                        primary = true,
                        onClick = { togglePlay(); interaction++ },
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    ActionButton("−10s", onClick = { seekBy(-10_000); interaction++ })
                    ActionButton("+30s", onClick = { seekBy(30_000); interaction++ })
                    Spacer(Modifier.width(12.dp))
                    ActionButton("Audio", onClick = { picker = "audio" })
                    ActionButton("Subtitles", onClick = { picker = "subs" })
                }
                Text(
                    "Tip: with the controls hidden, left/right skips 10s back or 30s ahead, OK pauses.",
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    @Composable
    private fun TrackPicker(kind: String) {
        val tracks = remember(kind) {
            val list = if (kind == "audio") mp.audioTracks else mp.spuTracks
            list?.map { it.id to it.name }.orEmpty()
                .let { all -> if (kind == "subs") all else all.filter { it.first != -1 } }   // keep "Disable" for subs
        }
        val current = if (kind == "audio") mp.audioTrack else mp.spuTrack
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }

        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(420.dp)
                    .fillMaxSize()
                    .background(Panel)
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (kind == "audio") "Audio" else "Subtitles", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (tracks.isEmpty()) {
                    Text(
                        if (kind == "audio") "No other audio tracks in this file." else "This file has no subtitles.",
                        color = TextDim,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = { it.first }) { (id, name) ->
                        val label = (if (id == current) "✓  " else "    ") + name.ifBlank { "Track $id" }
                        ActionButton(
                            label,
                            primary = id == current,
                            onClick = {
                                if (kind == "audio") mp.setAudioTrack(id) else mp.setSpuTrack(id)
                                picker = null
                                showControls()
                            },
                            modifier = if (tracks.firstOrNull()?.first == id) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
            }
        }
    }

    private fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_HEADER_KEYS = "hk"
        private const val EXTRA_HEADER_VALUES = "hv"
        private const val EXTRA_IS_FALLBACK = "fallback"

        fun intent(ctx: Context, url: String, headers: Map<String, String>, t: PlayTarget, isFallback: Boolean = false): Intent =
            Intent(ctx, VlcPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TARGET, app.marquee.tv.data.Http.json.encodeToString(PlayTarget.serializer(), t))
                .putExtra(EXTRA_HEADER_KEYS, headers.keys.toTypedArray())
                .putExtra(EXTRA_HEADER_VALUES, headers.values.toTypedArray())
                .putExtra(EXTRA_IS_FALLBACK, isFallback)
    }
}
