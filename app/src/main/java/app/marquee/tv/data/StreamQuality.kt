package app.marquee.tv.data

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import java.util.Locale

/**
 * What a stream is, read from the text addons attach to it
 * (name, title, description and filename).
 */
data class Quality(
    val height: Int,          // 2160, 1440, 1080, 720, 480, or 0 if unknown
    val codec: Codec,
    val source: Source,
    val hdr: String?,         // "DV", "HDR10+", "HDR", or null
) {
    /** Short tag for the stream row, e.g. "2160p · HEVC · HDR · WEB-DL". */
    val label: String
        get() = listOfNotNull(
            heightLabel(height),
            codec.label,
            hdr,
            source.label,
        ).joinToString(" · ")

    companion object {
        fun heightLabel(h: Int) = when {
            h >= 2160 -> "2160p"
            h > 0 -> "${h}p"
            else -> null
        }
    }
}

/**
 * Video codecs, best compression first. At the same resolution a newer codec
 * gives the same picture at a smaller size, so it streams with less buffering:
 * AV1 is about 30% smaller than HEVC, HEVC about 40–50% smaller than H.264.
 */
enum class Codec(val label: String?, val mime: String?, val rank: Int) {
    AV1("AV1", "video/av01", 0),
    HEVC("HEVC", "video/hevc", 1),
    VP9("VP9", "video/x-vnd.on2.vp9", 2),
    AVC("H.264", "video/avc", 3),
    UNKNOWN(null, null, 4),
    LEGACY("XviD", null, 5),     // XviD, DivX, MPEG-2: old and inefficient
}

/** Where the file came from. Camera recordings sort below everything else. */
enum class Source(val label: String?, val rank: Int) {
    REMUX("Remux", 0),
    BLURAY("BluRay", 1),
    WEBDL("WEB-DL", 2),
    WEBRIP("WEBRip", 3),
    HDTV("HDTV", 4),
    UNKNOWN(null, 3),
    CAM("CAM", 9),
}

object StreamQuality {
    private val cache = HashMap<String, Quality>()

    private val r2160 = Regex("""(2160p|\b4k\b|\buhd\b)""")
    private val r1440 = Regex("""1440p|\b2k\b""")
    private val r1080 = Regex("""1080[pi]|\bfhd\b|\bfull ?hd\b""")
    private val r720 = Regex("""720p|\bhd ?ready\b""")
    private val r576 = Regex("""576p|540p""")
    private val r480 = Regex("""480p|360p|\bsd\b|\bdvd(rip)?\b""")

    private val av1 = Regex("""\bav1\b|\bav01\b""")
    private val hevc = Regex("""\bhevc\b|\bh[ .]?265\b|\bx265\b""")
    private val vp9 = Regex("""\bvp9\b""")
    private val avc = Regex("""\bavc\b|\bh[ .]?264\b|\bx264\b""")
    private val legacy = Regex("""\bxvid\b|\bdivx\b|\bmpeg-?2\b""")

    private val cam = Regex("""\b(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine|hdtc|scr|screener)\b""")
    private val remux = Regex("""\bremux\b""")
    private val bluray = Regex("""\bblu-?ray\b|\bbdrip\b|\bbrrip\b|\bbd\b""")
    private val webdl = Regex("""\bweb-?dl\b|\bweb\b""")
    private val webrip = Regex("""\bweb-?rip\b""")
    private val hdtv = Regex("""\bhdtv\b|\bpdtv\b""")

    private val dv = Regex("""\bdv\b|dolby ?vision|\bdovi\b""")
    private val hdr10p = Regex("""hdr10\+|hdr10plus""")
    private val hdr = Regex("""\bhdr(10)?\b""")

    fun parse(s: Stream): Quality {
        val text = listOfNotNull(s.name, s.title, s.description, s.behaviorHints?.filename)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
        synchronized(cache) { cache[text]?.let { return it } }
        val q = Quality(
            height = when {
                r2160.containsMatchIn(text) -> 2160
                r1440.containsMatchIn(text) -> 1440
                r1080.containsMatchIn(text) -> 1080
                r720.containsMatchIn(text) -> 720
                r576.containsMatchIn(text) -> 576
                r480.containsMatchIn(text) -> 480
                else -> 0
            },
            codec = when {
                av1.containsMatchIn(text) -> Codec.AV1
                hevc.containsMatchIn(text) -> Codec.HEVC
                vp9.containsMatchIn(text) -> Codec.VP9
                avc.containsMatchIn(text) -> Codec.AVC
                legacy.containsMatchIn(text) -> Codec.LEGACY
                else -> Codec.UNKNOWN
            },
            source = when {
                cam.containsMatchIn(text) -> Source.CAM
                remux.containsMatchIn(text) -> Source.REMUX
                bluray.containsMatchIn(text) -> Source.BLURAY
                webrip.containsMatchIn(text) -> Source.WEBRIP
                webdl.containsMatchIn(text) -> Source.WEBDL
                hdtv.containsMatchIn(text) -> Source.HDTV
                else -> Source.UNKNOWN
            },
            hdr = when {
                dv.containsMatchIn(text) -> "DV"
                hdr10p.containsMatchIn(text) -> "HDR10+"
                hdr.containsMatchIn(text) -> "HDR"
                else -> null
            },
        )
        synchronized(cache) { cache[text] = q }
        return q
    }
}

/**
 * Which video codecs this TV can decode in hardware, so a stream it can't
 * play smoothly doesn't end up at the top of the list.
 */
object DeviceCaps {
    private var hardwareMimes: Set<String>? = null

    fun init(context: Context) {
        runCatching {
            hardwareMimes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && isHardware(it) }
                .flatMap { it.supportedTypes.map { t -> t.lowercase(Locale.ROOT) } }
                .toSet()
        }
    }

    private fun isHardware(info: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val n = info.name.lowercase(Locale.ROOT)
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }

    /**
     * Whether the TV has a hardware decoder for this codec. Unknown codecs and
     * a failed lookup count as yes, so nothing is hidden by mistake.
     */
    fun canDecode(c: Codec): Boolean {
        val mimes = hardwareMimes ?: return true
        val mime = c.mime ?: return true
        if (c == Codec.AVC) return true   // every TV decodes H.264
        return mime in mimes
    }
}
