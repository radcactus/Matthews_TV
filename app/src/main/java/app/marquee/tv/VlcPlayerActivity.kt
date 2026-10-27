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
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.marquee.tv.data.Progress
import app.marquee.tv.data.Repo
import app.marquee.tv.data.StreamChecker
import app.marquee.tv.data.SubtitleFile
import app.marquee.tv.ui.Accent
import app.marquee.tv.ui.ActionButton
import app.marquee.tv.ui.MarqueeTheme
import app.marquee.tv.ui.Panel
import app.marquee.tv.ui.Pill
import app.marquee.tv.ui.PlayerIcons
import app.marquee.tv.ui.Raised
import app.marquee.tv.ui.TextDim
import app.marquee.tv.ui.TextFaint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

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
    private var seekFocused by mutableStateOf(false)       // focus is on the progress bar

    // Hold-to-scrub: 0 = not scrubbing, otherwise the speed (2x, 4x, …).
    private var scrubSpeed by mutableIntStateOf(0)
    private var scrubDir by mutableIntStateOf(0)
    private var scrubPos by mutableLongStateOf(0L)
    private var scrubJob: Job? = null
    private var resumeAfterScrub = false

    // ---- subtitles (Stremio-style: grouped by language, from the file and from addons)
    /** One choice in the subtitle menu. */
    private data class SubOption(
        val key: String,
        val language: String,
        val label: String,
        val trackId: Int? = null,       // subtitle track inside the file
        val url: String? = null,        // subtitle file from an addon
    )

    private var embeddedSubs by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    private var addonSubs by mutableStateOf<List<Pair<String, SubtitleFile>>>(emptyList())
    private var addonSubsLoaded by mutableStateOf(false)
    private var selectedSub by mutableStateOf<String?>(null)     // SubOption.key, null = off
    private var subDelayMs by mutableLongStateOf(0L)
    private var subSize by mutableIntStateOf(1)
    private var subColor by mutableIntStateOf(0)
    private var subBackground by mutableStateOf(false)
    private val slaveTracks = mutableMapOf<String, Int>()         // addon subtitle url -> VLC track id
    private var pendingSlaveUrl: String? = null
    private var restoreSub: String? = null
    private var autoPicked = false

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

        val prefs = Repo.prefs
        subSize = prefs.subtitleSize
        subColor = prefs.subtitleColor
        subBackground = prefs.subtitleBackground

        videoLayout = VLCVideoLayout(this)
        buildPlayer(target.startMs)

        setContent {
            MarqueeTheme {
                PlayerScreen()
            }
        }

        if (target.startMs > 0) {
            Toast.makeText(this, "Resuming from ${app.marquee.tv.ui.formatTime(target.startMs)}", Toast.LENGTH_SHORT).show()
        }

        // Subtitles from your subtitle addons (OpenSubtitles etc.), like Stremio.
        lifecycleScope.launch {
            addonSubs = runCatching { Repo.subtitles(target.type, target.videoId) }.getOrDefault(emptyList())
                .map { (addon, f) -> addon.name to f }
            addonSubsLoaded = true
            maybeAutoPickSubtitle()
        }

        saver = lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                save(sync = false)
            }
        }
    }

    /** Creates the VLC engine with the current subtitle style and loads the video at [startMs]. */
    private fun buildPlayer(startMs: Long) {
        // VLC sizes subtitles relative to the screen: a smaller number is bigger text.
        val relSize = intArrayOf(20, 16, 13, 10)[subSize.coerceIn(0, 3)]
        val color = if (subColor == 1) 0xFFFF00 else 0xFFFFFF
        libVlc = LibVLC(
            this,
            arrayListOf(
                "--http-reconnect",
                "--audio-time-stretch",
                "--avcodec-skiploopfilter=1",   // smoother playback of heavy H.264 in software
                "--freetype-rel-fontsize=$relSize",
                "--freetype-color=$color",
                "--freetype-outline-thickness=4",
                "--freetype-background-color=0",
                "--freetype-background-opacity=${if (subBackground) 170 else 0}",
            ),
        )
        mp = MediaPlayer(libVlc)

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=2000")
            if (startMs > 0) addOption(":start-time=${startMs / 1000.0}")
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
                MediaPlayer.Event.Playing -> {
                    playing = true
                    everPlayed = true
                    if (subDelayMs != 0L) mp.setSpuDelay(subDelayMs * 1000)
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> position = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.Buffering -> buffering = e.buffering
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> onTracksChanged()
                MediaPlayer.Event.EndReached -> {
                    save(finishedOverride = true)
                    finish()
                }
                MediaPlayer.Event.EncounteredError -> onPlaybackError()
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
        if (::mp.isInitialized) releasePlayer()
    }

    private fun releasePlayer() {
        mp.setEventListener(null)
        runCatching { mp.stop() }
        if (attached) runCatching { mp.detachViews() }
        attached = false
        mp.release()
        libVlc.release()
    }

    /** Subtitle size/colour/background need a fresh VLC engine; restart at the same spot. */
    private fun restartWithNewSubtitleStyle() {
        val pos = runCatching { mp.time }.getOrDefault(position)
        val wasPlaying = mp.isPlaying
        restoreSub = selectedSub
        releasePlayer()
        slaveTracks.clear()
        pendingSlaveUrl = null
        embeddedSubs = emptyList()
        buildPlayer(pos)
        mp.attachViews(videoLayout, null, true, false)
        attached = true
        mp.play()
        if (!wasPlaying) lifecycleScope.launch { delay(800); if (mp.isPlaying) mp.pause() }
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

    // ---- subtitles ------------------------------------------------------------

    private fun onTracksChanged() {
        val spu = runCatching { mp.spuTracks }.getOrNull()?.filter { it.id != -1 }.orEmpty()
        // A subtitle file we just added from an addon shows up as a new track.
        pendingSlaveUrl?.let { u ->
            val known = embeddedSubs.map { it.first }.toSet() + slaveTracks.values
            spu.firstOrNull { it.id !in known }?.let { slaveTracks[u] = it.id; pendingSlaveUrl = null }
        }
        embeddedSubs = spu.filter { it.id !in slaveTracks.values }.map { it.id to it.name.orEmpty() }
        restoreSub?.let { key ->
            subOptions().firstOrNull { it.key == key }?.let { restoreSub = null; selectSub(it, remember = false) }
        }
        maybeAutoPickSubtitle()
    }

    private fun subOptions(): List<SubOption> {
        val embedded = embeddedSubs.map { (id, name) ->
            SubOption("e$id", embeddedLanguage(name), "In the video · ${name.ifBlank { "Track $id" }}", trackId = id)
        }
        val counters = mutableMapOf<String, Int>()
        val fromAddons = addonSubs.map { (addon, f) ->
            val lang = languageName(f.lang)
            val n = (counters[lang] ?: 0) + 1
            counters[lang] = n
            SubOption("a:${f.url}", lang, "$addon · #$n", url = f.url)
        }
        return embedded + fromAddons
    }

    /** Turns on subtitles in the language you used last time, preferring ones inside the file. */
    private fun maybeAutoPickSubtitle() {
        if (autoPicked || restoreSub != null) return
        val lang = Repo.prefs.subtitleLanguage ?: run { autoPicked = true; return }
        val options = subOptions().filter { it.language.equals(lang, ignoreCase = true) }
        val pick = options.firstOrNull { it.trackId != null } ?: options.firstOrNull { it.url != null && addonSubsLoaded }
        if (pick != null) {
            autoPicked = true
            selectSub(pick, remember = false)
        }
    }

    private fun selectSub(o: SubOption?, remember: Boolean = true) {
        if (o == null) {
            mp.setSpuTrack(-1)
            selectedSub = null
            if (remember) Repo.prefs.subtitleLanguage = null
            return
        }
        when {
            o.trackId != null -> mp.setSpuTrack(o.trackId)
            o.url != null -> {
                val id = slaveTracks[o.url]
                if (id != null) {
                    mp.setSpuTrack(id)
                } else {
                    pendingSlaveUrl = o.url
                    mp.addSlave(0 /* IMedia.Slave.Type.Subtitle */, Uri.parse(o.url), true)
                }
            }
        }
        selectedSub = o.key
        if (subDelayMs != 0L) mp.setSpuDelay(subDelayMs * 1000)
        if (remember) Repo.prefs.subtitleLanguage = o.language
    }

    private fun changeDelay(deltaMs: Long) {
        subDelayMs += deltaMs
        mp.setSpuDelay(subDelayMs * 1000)
    }

    private fun languageName(code: String): String {
        val c = code.trim()
        when (c.lowercase()) {
            "pob", "pt-br", "ptbr" -> return "Portuguese (Brazil)"
            "", "und" -> return "Unknown"
        }
        if (c.length in 2..3) {
            val n = Locale(c).getDisplayLanguage(Locale.ENGLISH)
            if (n.isNotBlank() && !n.equals(c, ignoreCase = true)) return n
        }
        return c.replaceFirstChar { it.uppercase() }
    }

    /** VLC names embedded tracks like "Track 2 - [English]"; pull the language out. */
    private fun embeddedLanguage(name: String): String {
        Regex("""\[([^\]]+)]""").findAll(name).map { it.groupValues[1].trim() }.forEach { part ->
            val n = languageName(part)
            if (n.isNotBlank() && n != "Unknown" && part.any { it.isLetter() }) return n
        }
        val words = name.split(Regex("""[^\p{L}]+""")).filter { it.length >= 4 }
        val known = Locale.getAvailableLocales().map { it.getDisplayLanguage(Locale.ENGLISH) }.toSet()
        return words.firstOrNull { w -> known.any { it.equals(w, ignoreCase = true) } }
            ?.replaceFirstChar { it.uppercase() } ?: "Other"
    }

    // ---- remote control ---------------------------------------------------

    private fun togglePlay() {
        if (mp.isPlaying) mp.pause() else mp.play()
    }

    private fun startOver() {
        mp.setTime(0)
        position = 0
        if (!mp.isPlaying) mp.play()
        showControls()
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

    /**
     * Left/right (and the remote's rewind/fast-forward keys): a press skips
     * 10 seconds; holding scrubs at 2x, then 4x, 8x, 16x, 32x, 64x, and
     * playback jumps to that spot when you let go.
     */
    private fun handleSeekKey(event: KeyEvent, dir: Int): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    seekBy(dir * 10_000L)
                    showControls()
                } else if (scrubSpeed == 0) {
                    startScrub(dir)
                }
            }
            KeyEvent.ACTION_UP -> if (scrubSpeed != 0) endScrub()
        }
        return true
    }

    private fun startScrub(dir: Int) {
        scrubDir = dir
        scrubPos = mp.time
        resumeAfterScrub = mp.isPlaying
        if (resumeAfterScrub) mp.pause()
        scrubSpeed = SPEEDS[0]
        showControls()
        scrubJob?.cancel()
        scrubJob = lifecycleScope.launch {
            var level = 0
            var atLevel = 0L
            while (isActive) {
                delay(100)
                atLevel += 100
                if (atLevel >= 1_500 && level < SPEEDS.lastIndex) {
                    level++
                    atLevel = 0
                    scrubSpeed = SPEEDS[level]
                }
                val max = if (length > 0) length - 1_000 else Long.MAX_VALUE
                scrubPos = (scrubPos + dir * scrubSpeed * 100L).coerceIn(0L, max)
                interaction++
            }
        }
    }

    private fun endScrub() {
        scrubJob?.cancel()
        scrubJob = null
        mp.setTime(scrubPos)
        position = scrubPos
        scrubSpeed = 0
        if (resumeAfterScrub) mp.play()
        interaction++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && event.repeatCount == 0) { togglePlay(); showControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return handleSeekKey(event, 1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> return handleSeekKey(event, -1)
        }
        // Left/right skip whenever the controls are hidden or the progress bar
        // has focus. Press down to reach the buttons, where left/right move between them.
        val seekMode = picker == null && (!controlsVisible || seekFocused)
        if (seekMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> return handleSeekKey(event, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return handleSeekKey(event, 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down && event.repeatCount == 0) { togglePlay(); showControls() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> if (!controlsVisible) {
                    if (down) showControls()
                    return true
                }
            }
        }
        if (down) interaction++
        return super.dispatchKeyEvent(event)
    }

    // ---- UI -----------------------------------------------------------------

    @Composable
    private fun PlayerScreen() {
        val playFocus = remember { FocusRequester() }
        val seekFocus = remember { FocusRequester() }

        BackHandler(enabled = picker != null || controlsVisible) {
            if (picker != null) picker = null else controlsVisible = false
        }

        // Hide the controls after 5 seconds without a key press while playing.
        // While paused they stay up as the pause menu.
        LaunchedEffect(interaction, controlsVisible, playing, picker, scrubSpeed) {
            if (controlsVisible && playing && picker == null && scrubSpeed == 0) {
                delay(5_000)
                controlsVisible = false
            }
        }
        LaunchedEffect(controlsVisible, picker) {
            if (controlsVisible && picker == null) {
                delay(50)
                runCatching { seekFocus.requestFocus() }
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

            if (scrubSpeed > 0) {
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color(0x99000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (scrubDir < 0) PlayerIcons.Rewind else PlayerIcons.Forward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                    Text("${scrubSpeed}x", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
                }
            }

            if (controlsVisible && picker == null) {
                Controls(playFocus, seekFocus)
            }

            when (picker) {
                "subs" -> SubtitlePanel()
                "audio" -> AudioPicker()
            }
        }
    }

    @Composable
    private fun Controls(playFocus: FocusRequester, seekFocus: FocusRequester) {
        Column(Modifier.fillMaxSize()) {
            // Title, and for episodes the season/episode number and name.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 40.dp, vertical = 24.dp),
            ) {
                Column {
                    Text(target.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val episodeLine = listOfNotNull(target.label, target.episodeTitle).joinToString("  ·  ")
                    if (episodeLine.isNotBlank()) {
                        Text(episodeLine, color = Color(0xFFDADDE3), fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 22.dp),
            ) {
                val shown = if (scrubSpeed > 0) scrubPos else position
                val frac = if (length > 0) (shown.toFloat() / length).coerceIn(0f, 1f) else 0f
                // The progress bar takes focus first, so left/right always skip;
                // press down for the buttons.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .focusRequester(seekFocus)
                        .onFocusChanged { seekFocused = it.isFocused }
                        .focusable(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val h = if (seekFocused) 8.dp else 4.dp
                    Box(Modifier.fillMaxWidth().height(h).background(Color(0x55FFFFFF), RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth(frac).height(h).background(Accent, RoundedCornerShape(4.dp)))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(clock(shown), fontSize = 13.sp, color = if (scrubSpeed > 0) Color.White else TextDim)
                    Spacer(Modifier.weight(1f))
                    Text(if (length > 0) "-${clock(length - shown)}" else "", fontSize = 13.sp, color = TextDim)
                }

                // Classic transport controls in the usual order, with captions and audio on the right.
                Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        PlayerButton(PlayerIcons.StartOver, "Start over", onClick = { startOver() })
                        PlayerButton(PlayerIcons.Rewind, "Back 10s", onClick = { seekBy(-10_000); interaction++ })
                        PlayerButton(
                            if (playing) PlayerIcons.Pause else PlayerIcons.Play,
                            if (playing) "Pause" else "Play",
                            big = true,
                            onClick = { togglePlay(); interaction++ },
                            modifier = Modifier.focusRequester(playFocus),
                        )
                        PlayerButton(PlayerIcons.Forward, "Forward 10s", onClick = { seekBy(10_000); interaction++ })
                    }
                    Row(
                        Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        PlayerButton(PlayerIcons.Subtitles, "Subtitles", onClick = { picker = "subs" })
                        PlayerButton(PlayerIcons.Audio, "Audio", onClick = { picker = "audio" })
                    }
                }
                Text(
                    "Left/right: skip 10s, hold to scrub faster. OK: pause. Down: buttons.",
                    color = TextFaint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    @Composable
    private fun PlayerButton(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        big: Boolean = false,
    ) {
        var focused by remember { mutableStateOf(false) }
        val size = if (big) 68.dp else 52.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = onClick,
                modifier = modifier
                    .size(size)
                    .onFocusChanged { focused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (big) Accent else Color(0x33FFFFFF),
                    contentColor = if (big) Color.Black else Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = label, modifier = Modifier.size(if (big) 36.dp else 26.dp))
                }
            }
            Text(
                label,
                fontSize = 12.sp,
                color = if (focused) Color.White else TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    /**
     * Stremio-style subtitle menu: languages on the left, the subtitles for
     * that language on the right (inside the video and from your addons),
     * and delay, size, colour and background underneath.
     */
    @Composable
    private fun SubtitlePanel() {
        val options = subOptions()
        val byLang = options.groupBy { it.language }
        val preferred = listOfNotNull(
            options.firstOrNull { it.key == selectedSub }?.language,
            Repo.prefs.subtitleLanguage,
            Locale.getDefault().getDisplayLanguage(Locale.ENGLISH),
            "English",
        ).distinct()
        val languages = byLang.keys.sortedWith(
            compareBy<String>({ l -> preferred.indexOfFirst { it.equals(l, true) }.let { if (it < 0) 99 else it } }, { it }),
        )
        var viewLang by remember { mutableStateOf(options.firstOrNull { it.key == selectedSub }?.language ?: languages.firstOrNull()) }
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(60); runCatching { firstFocus.requestFocus() } }

        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(700.dp)
                    .fillMaxHeight()
                    .background(Panel)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
            ) {
                Text("Subtitles", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (!addonSubsLoaded) {
                    Text("Searching your subtitle addons…", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.weight(1f).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Languages
                    LazyColumn(Modifier.width(220.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item(key = "off") {
                            SubRow(
                                text = "Off",
                                selected = selectedSub == null,
                                onClick = { selectSub(null) },
                                modifier = if (selectedSub == null) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                        items(languages, key = { "lang/$it" }) { lang ->
                            val isCurrent = options.firstOrNull { it.key == selectedSub }?.language == lang
                            SubRow(
                                text = "$lang (${byLang[lang]?.size ?: 0})",
                                selected = lang == viewLang,
                                accent = isCurrent,
                                onClick = { viewLang = lang },
                                onFocus = { viewLang = lang },
                                modifier = if (isCurrent) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                    }
                    // Subtitles in the highlighted language
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val list = byLang[viewLang].orEmpty()
                        if (list.isEmpty()) {
                            item {
                                Text(
                                    if (options.isEmpty() && addonSubsLoaded) "No subtitles found for this video." else "",
                                    color = TextDim,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                        items(list, key = { it.key }) { o ->
                            SubRow(
                                text = (if (o.key == selectedSub) "✓  " else "") + o.label,
                                selected = o.key == selectedSub,
                                accent = o.key == selectedSub,
                                onClick = { selectSub(o) },
                            )
                        }
                    }
                }

                // Delay and style
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Delay", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                    Pill("−0.5s", false, { changeDelay(-500) })
                    Text(
                        "%+.1fs".format(Locale.US, subDelayMs / 1000.0),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(64.dp),
                    )
                    Pill("+0.5s", false, { changeDelay(500) })
                    if (subDelayMs != 0L) Pill("Reset", false, { changeDelay(-subDelayMs) })
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Size", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                    listOf("S", "M", "L", "XL").forEachIndexed { i, label ->
                        Pill(label, subSize == i, {
                            if (subSize != i) {
                                subSize = i
                                Repo.prefs.subtitleSize = i
                                restartWithNewSubtitleStyle()
                            }
                        })
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Style", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                    Pill("White", subColor == 0, { if (subColor != 0) { subColor = 0; Repo.prefs.subtitleColor = 0; restartWithNewSubtitleStyle() } })
                    Pill("Yellow", subColor == 1, { if (subColor != 1) { subColor = 1; Repo.prefs.subtitleColor = 1; restartWithNewSubtitleStyle() } })
                    Spacer(Modifier.width(12.dp))
                    Pill("No background", !subBackground, {
                        if (subBackground) { subBackground = false; Repo.prefs.subtitleBackground = false; restartWithNewSubtitleStyle() }
                    })
                    Pill("Dark background", subBackground, {
                        if (!subBackground) { subBackground = true; Repo.prefs.subtitleBackground = true; restartWithNewSubtitleStyle() }
                    })
                }
            }
        }
    }

    @Composable
    private fun SubRow(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        accent: Boolean = false,
        onFocus: (() -> Unit)? = null,
    ) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Raised else Color.Transparent,
                contentColor = if (accent) Accent else Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            Text(
                text,
                fontSize = 15.sp,
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    @Composable
    private fun AudioPicker() {
        val tracks = remember {
            runCatching { mp.audioTracks }.getOrNull()?.filter { it.id != -1 }?.map { it.id to it.name.orEmpty() }.orEmpty()
        }
        var current by remember { mutableIntStateOf(runCatching { mp.audioTrack }.getOrDefault(-1)) }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }

        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(440.dp)
                    .fillMaxHeight()
                    .background(Panel)
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Audio", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (tracks.isEmpty()) Text("No other audio tracks in this video.", color = TextDim)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tracks, key = { it.first }) { (id, name) ->
                        SubRow(
                            text = (if (id == current) "✓  " else "") + name.ifBlank { "Track $id" },
                            selected = id == current,
                            accent = id == current,
                            onClick = { mp.setAudioTrack(id); current = id },
                            modifier = if (id == current || (current == -1 && tracks.firstOrNull()?.first == id)) {
                                Modifier.focusRequester(first)
                            } else Modifier,
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
        private val SPEEDS = intArrayOf(2, 4, 8, 16, 32, 64)
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
