package com.baseprovider.settings

import android.content.Context
import android.content.SharedPreferences
import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.plugins.Plugin

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

    fun prefetchEnabled(providerId: String, default: Boolean): Boolean {
        val p = prefs(providerId) ?: return default
        return p.getBoolean(KEY_PREFETCH, default)
    }

    fun cacheTtlMinutes(providerId: String, default: Long): Long {
        val p = prefs(providerId) ?: return default
        return p.getLong(KEY_CACHE_TTL, default)
    }

    fun searchPageLimit(providerId: String, default: Int): Int {
        val p = prefs(providerId) ?: return default
        return p.getInt(KEY_SEARCH_LIMIT, default)
    }

    fun applyOverrides(providerId: String, base: ProviderConfig): ProviderConfig {
        val p = prefs(providerId) ?: return base
        return base.copy(
            prefetchEnabled = p.getBoolean(KEY_PREFETCH, base.prefetchEnabled),
            cacheTtlMinutes = p.getLong(KEY_CACHE_TTL, base.cacheTtlMinutes),
            searchPageLimit = p.getInt(KEY_SEARCH_LIMIT, base.searchPageLimit)
        )
    }

    fun filterMainPageLists(
        lists: List<Pair<String, String>>,
        enabled: Set<String>?
    ): List<Pair<String, String>> {
        if (enabled == null) return lists
        return lists.filter { it.second in enabled }
    }

    internal const val KEY_CATEGORIES = "enabled_categories"
    internal const val KEY_PREFETCH = "prefetch_enabled"
    internal const val KEY_CACHE_TTL = "cache_ttl_minutes"
    internal const val KEY_SEARCH_LIMIT = "search_page_limit"
}