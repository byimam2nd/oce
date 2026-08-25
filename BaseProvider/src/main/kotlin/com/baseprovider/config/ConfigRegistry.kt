package com.baseprovider.config

import com.lagradost.api.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ConfigRegistry {
    private const val TAG = "ConfigRegistry"

    private val providers = mapOf(
        "Anichin" to "anichin",
        "Animasu" to "animasu",
        "Donghuastream" to "donghuastream",
        "Dutamovie21" to "dutamovie21",
        "IndoDrama21" to "indodrama21",
        "LayarKaca21" to "layarkaca21",
        "Samehadaku" to "samehadaku",
        "AnimexinDev" to "animexindev",
    )

    private val globalConfig: ProviderConfig by lazy { loadBundled("global") ?: ProviderConfig(id = "GLOBAL") }

    private val bundledCache = ConcurrentHashMap<String, ProviderConfig>()

    fun get(id: String): ProviderConfig {
        val fileName = providers[id]
        if (fileName == null) {
            Log.w(TAG, "Unknown provider: $id, using GLOBAL fallback")
            return globalConfig
        }

        val bundled = loadBundled(fileName)
        if (bundled != null) return bundled

        Log.w(TAG, "No config found for $id, using GLOBAL fallback")
        return globalConfig
    }

    private fun loadBundled(fileName: String): ProviderConfig? {
        bundledCache[fileName]?.let { return it }
        return try {
            val stream = this::class.java.classLoader?.getResourceAsStream("$fileName.json")
                ?: return null
            val jsonStr = stream.bufferedReader().readText()
            val json = JSONObject(jsonStr)
            val id = json.optString("id", fileName)
            Log.d(TAG, "Loaded bundled config: $fileName.json")
            fromJson(id, json).also { bundledCache[fileName] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled load failed for $fileName.json: ${e.message}")
            null
        }
    }
}
