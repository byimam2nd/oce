package com.baseprovider.config

import com.lagradost.api.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ExtractorConfigRegistry {
    private const val TAG = "ExtractorConfigRegistry"

    private val bundledCache = ConcurrentHashMap<String, ExtractorConfig>()

    fun get(id: String): ExtractorConfig? {
        val bundled = loadBundled(id)
        if (bundled != null) return bundled

        Log.w(TAG, "No config found for extractor: $id")
        return null
    }

    fun getOrDefault(id: String, fallback: ExtractorConfig): ExtractorConfig =
        get(id) ?: fallback

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