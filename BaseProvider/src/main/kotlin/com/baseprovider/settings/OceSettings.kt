package com.baseprovider.settings

import android.content.Context
import android.content.SharedPreferences
import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.plugins.Plugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extension settings (user-controlled, tanpa ubah kode OCE).
 *
 * Penyimpanan per-provider via SharedPreferences `oce_settings_<providerId>`.
 * Nilai tersimpan = override; yang tidak disimpan fallback ke [ProviderConfig].
 */
object OceSettings {

    private const val PREFS_PREFIX = "oce_settings_"

    @Volatile private var appContext: Context? = null

    /** Wajib dipanggil dari [Plugin.load] SEBELUM provider API di-instantiate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun install(plugin: Plugin, providerId: String, config: ProviderConfig) {
        plugin.openSettings = { ctx ->
            SettingsDialog().show(ctx, providerId, config)
        }
    }

    fun prefs(providerId: String): SharedPreferences? {
        val ctx = appContext ?: return null
        return ctx.getSharedPreferences("$PREFS_PREFIX$providerId", Context.MODE_PRIVATE)
    }

    /** null = semua kategori aktif (default). Set kosong = tidak ada kategori. */
    fun enabledCategories(providerId: String): Set<String>? {
        val raw = prefs(providerId)?.getString(KEY_CATEGORIES, null)
        return raw?.split(",")?.filter { it.isNotBlank() }?.toSet()
    }

    fun cacheTtlMinutes(providerId: String, default: Long): Long {
        val p = prefs(providerId) ?: return default
        return p.getLong(KEY_CACHE_TTL, default)
    }

    fun applyOverrides(providerId: String, base: ProviderConfig): ProviderConfig {
        val p = prefs(providerId) ?: return base
        return base.copy(
            cacheTtlMinutes = p.getLong(KEY_CACHE_TTL, base.cacheTtlMinutes)
        )
    }

    fun filterMainPageLists(
        lists: List<Pair<String, String>>,
        enabled: Set<String>?
    ): List<Pair<String, String>> {
        if (enabled == null) return lists
        return lists.filter { it.second in enabled }
    }

    /** Custom categories user (url to name), disimpan per-provider. */
    fun customCategories(providerId: String): List<Pair<String, String>> {
        val raw = prefs(providerId)?.getString(KEY_CUSTOM_CATEGORIES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url")
                val name = obj.optString("name")
                if (url.isBlank() || name.isBlank()) null else url to name
            }
        }.getOrDefault(emptyList())
    }

    fun addCustomCategory(providerId: String, url: String, name: String) {
        val p = prefs(providerId) ?: return
        val current = customCategories(providerId)
        if (current.any { it.first == url || it.second == name }) return
        saveCustomCategories(p, current + (url to name))
    }

    fun removeCustomCategory(providerId: String, url: String) {
        val p = prefs(providerId) ?: return
        saveCustomCategories(p, customCategories(providerId).filterNot { it.first == url })
    }

    /** Ganti seluruh daftar custom categories (dipakai dialog saat Save). */
    fun setCustomCategories(providerId: String, list: List<Pair<String, String>>) {
        val p = prefs(providerId) ?: return
        saveCustomCategories(p, list)
    }

    private fun saveCustomCategories(p: SharedPreferences, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { (url, name) ->
            arr.put(JSONObject().put("url", url).put("name", name))
        }
        p.edit().putString(KEY_CUSTOM_CATEGORIES, arr.toString()).apply()
    }

    internal const val KEY_CATEGORIES = "enabled_categories"
    internal const val KEY_CACHE_TTL = "cache_ttl_minutes"
    internal const val KEY_CUSTOM_CATEGORIES = "custom_categories"
}