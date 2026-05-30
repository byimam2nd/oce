package com.baseprovider

import com.lagradost.api.Log
import org.json.JSONObject
import java.net.URL

object ConfigRegistry {
    private const val TAG = "ConfigRegistry"
    private const val REMOTE_BASE = "https://raw.githubusercontent.com/byimam2nd/oce/master/BaseProvider/src/main/kotlin/com/baseprovider/config"

    private val providers = mapOf(
        "Anichin" to "anichin",
        "Animasu" to "animasu",
        "Donghuastream" to "donghuastream",
        "Dutamovie21" to "dutamovie21",
        "IndoDrama21" to "indodrama21",
        "LayarKaca21" to "layarkaca21",
        "Pencurimovie" to "pencurimovie",
        "Samehadaku" to "samehadaku",
    )

    private val globalConfig: ProviderConfig by lazy { loadBundled("global") ?: ProviderConfig(id = "GLOBAL") }

    fun get(id: String): ProviderConfig {
        val fileName = providers[id]
        if (fileName == null) {
            Log.w(TAG, "Unknown provider: $id, using GLOBAL fallback")
            return globalConfig
        }

        val remote = fetchRemote(fileName)
        if (remote != null) return remote

        val bundled = loadBundled(fileName)
        if (bundled != null) return bundled

        Log.w(TAG, "No config found for $id, using GLOBAL fallback")
        return globalConfig
    }

    private fun fetchRemote(fileName: String): ProviderConfig? {
        return try {
            val url = URL("$REMOTE_BASE/$fileName.json")
            val connection = url.openConnection().apply {
                connectTimeout = 10000
                readTimeout = 10000
            }
            val jsonStr = connection.getInputStream().bufferedReader().readText()
            val json = JSONObject(jsonStr)
            val id = json.optString("id", fileName)
            Log.d(TAG, "Fetched remote config: $fileName.json")
            ProviderConfig.fromJson(id, json)
        } catch (e: Exception) {
            Log.w(TAG, "Remote fetch failed for $fileName.json: ${e.message}")
            null
        }
    }

    private fun loadBundled(fileName: String): ProviderConfig? {
        return try {
            val stream = this::class.java.classLoader?.getResourceAsStream("$fileName.json")
                ?: return null
            val jsonStr = stream.bufferedReader().readText()
            val json = JSONObject(jsonStr)
            val id = json.optString("id", fileName)
            Log.d(TAG, "Loaded bundled config: $fileName.json")
            ProviderConfig.fromJson(id, json)
        } catch (e: Exception) {
            Log.w(TAG, "Bundled load failed for $fileName.json: ${e.message}")
            null
        }
    }
}
