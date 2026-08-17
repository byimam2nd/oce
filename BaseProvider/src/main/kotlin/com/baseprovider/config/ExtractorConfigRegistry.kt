package com.baseprovider.config

import com.lagradost.api.Log
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object ExtractorConfigRegistry {
    private const val TAG = "ExtractorConfigRegistry"
    private const val REMOTE_BASE = "https://raw.githubusercontent.com/byimam2nd/oce/master/BaseProvider/src/main/kotlin/com/baseprovider/config/extractors"
    private const val REMOTE_TTL_MS = 10 * 60 * 1000L

    private class CachedConfig(val config: ExtractorConfig, val fetchedAt: Long)

    private val remoteCache = ConcurrentHashMap<String, CachedConfig>()
    private val bundledCache = ConcurrentHashMap<String, ExtractorConfig>()

    fun get(id: String): ExtractorConfig? {
        val cached = remoteCache[id]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < REMOTE_TTL_MS) {
            return cached.config
        }

        val remote = fetchRemote(id)
        if (remote != null) {
            remoteCache[id] = CachedConfig(remote, System.currentTimeMillis())
            return remote
        }
        remoteCache.remove(id)

        val bundled = loadBundled(id)
        if (bundled != null) return bundled

        Log.w(TAG, "No config found for extractor: $id")
        return null
    }

    fun getOrDefault(id: String, fallback: ExtractorConfig): ExtractorConfig =
        get(id) ?: fallback

    private fun fetchRemote(id: String): ExtractorConfig? {
        return try {
            val url = URL("$REMOTE_BASE/$id.json")
            val connection = url.openConnection().apply {
                connectTimeout = 10000
                readTimeout = 10000
            }
            val jsonStr = connection.getInputStream().bufferedReader().readText()
            val json = JSONObject(jsonStr)
            val configId = json.optString("id", id)
            Log.d(TAG, "Fetched remote extractor config: $id.json")
            fromExtractorJson(configId, json)
        } catch (e: Exception) {
            Log.w(TAG, "Remote fetch failed for extractor $id.json: ${e.message}")
            null
        }
    }

    private fun loadBundled(id: String): ExtractorConfig? {
        bundledCache[id]?.let { return it }
        return try {
            val stream = this::class.java.classLoader?.getResourceAsStream("extractors/$id.json")
                ?: return null
            val jsonStr = stream.bufferedReader().readText()
            val json = JSONObject(jsonStr)
            val configId = json.optString("id", id)
            Log.d(TAG, "Loaded bundled extractor config: $id.json")
            fromExtractorJson(configId, json).also { bundledCache[id] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled extractor load failed for $id.json: ${e.message}")
            null
        }
    }
}