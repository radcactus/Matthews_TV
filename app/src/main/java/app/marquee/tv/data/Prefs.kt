package app.marquee.tv.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("marquee", Context.MODE_PRIVATE)

    private fun string(key: String) = sp.getString(key, null)?.takeIf { it.isNotBlank() }
    private fun setString(key: String, v: String?) = sp.edit().putString(key, v?.trim()).apply()

    var authKey: String?
        get() = string("authKey")
        set(v) = setString("authKey", v)

    var email: String?
        get() = string("email")
        set(v) = setString("email", v)

    /** User chose to browse without signing in. */
    var skippedLogin: Boolean
        get() = sp.getBoolean("skippedLogin", false)
        set(v) = sp.edit().putBoolean("skippedLogin", v).apply()

    var tmdbKey: String?
        get() = string("tmdbKey")
        set(v) = setString("tmdbKey", v)

    /** Optional Stremio streaming server, e.g. http://192.168.1.20:11470 */
    var serverUrl: String?
        get() = string("serverUrl")?.trimEnd('/')
        set(v) = setString("serverUrl", v)

    var syncProgress: Boolean
        get() = sp.getBoolean("syncProgress", true)
        set(v) = sp.edit().putBoolean("syncProgress", v).apply()

    /** "vlc" (built-in VLC engine, default), "builtin" (ExoPlayer), "ask", or an external app id. */
    var playerChoice: String
        get() = sp.getString("playerChoice", null)
            ?: "vlc"
        set(v) = sp.edit().putString("playerChoice", v).apply()

    // ---- subtitles ----
    /** Language you last picked (e.g. "English"); null = subtitles off. */
    var subtitleLanguage: String?
        get() = string("subLang")
        set(v) = setString("subLang", v)

    /** 0 small, 1 medium, 2 large, 3 extra large. */
    var subtitleSize: Int
        get() = sp.getInt("subSize", 1)
        set(v) = sp.edit().putInt("subSize", v).apply()

    /** 0 white, 1 yellow. */
    var subtitleColor: Int
        get() = sp.getInt("subColor", 0)
        set(v) = sp.edit().putInt("subColor", v).apply()

    var subtitleBackground: Boolean
        get() = sp.getBoolean("subBg", false)
        set(v) = sp.edit().putBoolean("subBg", v).apply()

    /** Test links when the streams panel opens. */
    var checkStreams: Boolean
        get() = sp.getBoolean("checkStreams", true)
        set(v) = sp.edit().putBoolean("checkStreams", v).apply()

    /** Also test debrid links marked as not cached (this starts a download on the debrid account). */
    var probeUncached: Boolean
        get() = sp.getBoolean("probeUncached", false)
        set(v) = sp.edit().putBoolean("probeUncached", v).apply()

    var hiddenCatalogs: Set<String>
        get() = sp.getStringSet("hiddenCatalogs", emptySet()).orEmpty().toSet()
        set(v) = sp.edit().putStringSet("hiddenCatalogs", v).apply()

    /** Last addon collection JSON, so the home screen paints instantly on launch. */
    var addonsJson: String?
        get() = string("addonsJson")
        set(v) = setString("addonsJson", v)

    fun loadProgress(): Map<String, Progress> = runCatching {
        Http.json.decodeFromString(ListSerializer(Progress.serializer()), sp.getString("progress", "[]")!!)
            .associateBy { it.id }
    }.getOrDefault(emptyMap())

    fun saveProgress(map: Map<String, Progress>) {
        // Keep the 300 most recent entries.
        val list = map.values.sortedByDescending { it.updatedAt }.take(300)
        sp.edit().putString("progress", Http.json.encodeToString(ListSerializer(Progress.serializer()), list)).apply()
    }
}
