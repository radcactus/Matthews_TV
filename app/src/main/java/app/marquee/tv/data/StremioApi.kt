package app.marquee.tv.data

import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StremioException(message: String) : Exception(message)

/** Stremio account API: the same endpoints the official apps use. */
object StremioApi {
    private const val BASE = "https://api.strem.io/api/"

    private suspend fun call(method: String, body: JsonObject): JsonElement {
        val res = Http.postJson(BASE + method, body) as? JsonObject
            ?: throw StremioException("Unexpected response")
        val err = res["error"]
        if (err != null && err !is JsonNull) {
            val msg = (err as? JsonObject)?.str("message") ?: err.toString()
            throw StremioException(msg)
        }
        return res["result"] ?: JsonNull
    }

    /** Returns the auth key. */
    suspend fun login(email: String, password: String): String {
        val r = call("login", buildJsonObject {
            put("type", "Login")
            put("email", email)
            put("password", password)
            put("facebook", false)
        }) as? JsonObject
        return r?.str("authKey") ?: throw StremioException("Login failed")
    }

    suspend fun logout(authKey: String) {
        runCatching { call("logout", buildJsonObject { put("type", "Logout"); put("authKey", authKey) }) }
    }

    /** Installed addons in the user's order: [{transportUrl, manifest, flags}]. */
    suspend fun addonCollection(authKey: String): JsonArray {
        val r = call("addonCollectionGet", buildJsonObject {
            put("type", "AddonCollectionGet")
            put("authKey", authKey)
            put("update", true)
        }) as? JsonObject
        return r?.arr("addons") ?: JsonArray(emptyList())
    }

    suspend fun library(authKey: String): List<JsonObject> {
        val r = call("datastoreGet", buildJsonObject {
            put("authKey", authKey)
            put("collection", "libraryItem")
            put("ids", buildJsonArray { })
            put("all", true)
        })
        return (r as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    suspend fun putLibrary(authKey: String, items: List<JsonObject>) {
        call("datastorePut", buildJsonObject {
            put("authKey", authKey)
            put("collection", "libraryItem")
            put("changes", JsonArray(items))
        })
    }
}

/** Talks to any Stremio addon over its HTTP transport. */
object AddonClient {
    private fun enc(s: String) = Uri.encode(s, ":")

    suspend fun manifest(url: String): Addon? {
        val m = Http.get(url) as? JsonObject ?: return null
        return Addon.parse(url, m)
    }

    suspend fun catalog(
        addon: Addon,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
    ): List<MetaPreview> {
        val extraPath = if (extra.isEmpty()) "" else
            "/" + extra.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val url = "${addon.base}/catalog/$type/${enc(id)}$extraPath.json"
        val res = Http.get(url) as? JsonObject ?: return emptyList()
        return res.arr("metas")?.mapNotNull { el ->
            runCatching { Http.json.decodeFromJsonElement(MetaPreview.serializer(), el) }.getOrNull()
        }.orEmpty()
    }

    suspend fun meta(addon: Addon, type: String, id: String): MetaDetail? {
        val res = Http.get("${addon.base}/meta/$type/${enc(id)}.json") as? JsonObject ?: return null
        val m = res["meta"] as? JsonObject ?: return null
        return runCatching { Http.json.decodeFromJsonElement(MetaDetail.serializer(), m) }.getOrNull()
    }

    suspend fun streams(addon: Addon, type: String, videoId: String): List<Stream> {
        val res = Http.get("${addon.base}/stream/$type/${enc(videoId)}.json") as? JsonObject
            ?: return emptyList()
        return res.arr("streams")?.mapNotNull { el ->
            runCatching { Http.json.decodeFromJsonElement(Stream.serializer(), el) }.getOrNull()
        }.orEmpty()
    }
}
