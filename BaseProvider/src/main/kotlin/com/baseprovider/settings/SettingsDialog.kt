package com.baseprovider.settings

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent

internal class SettingsDialog {

    private class Controls(
        val layout: LinearLayout,
        val categoryChecks: Map<String, CheckBox>,
        val prefetchCheck: CheckBox,
        val ttlSpinner: Spinner
    )

    fun show(context: Context, providerId: String, config: ProviderConfig) {
        val prefs = OceSettings.prefs(providerId) ?: return
        val controls = buildControls(context, providerId, prefs, config)
        val scroll = ScrollView(context).apply { addView(controls.layout) }

        AlertDialog.Builder(context)
            .setTitle("${config.name} Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ -> save(providerId, controls) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildControls(
        context: Context,
        providerId: String,
        prefs: SharedPreferences,
        config: ProviderConfig
    ): Controls {
        val categories = config.mainPageLists.map { it.second }.distinct()
        val current = OceSettings.enabledCategories(providerId) ?: categories.toSet()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        layout.addView(sectionLabel(context, "Home Categories"))
        val catChecks = LinkedHashMap<String, CheckBox>()
        categories.forEach { name ->
            val cb = CheckBox(context).apply {
                text = name
                isChecked = name in current
            }
            catChecks[name] = cb
            layout.addView(cb)
        }

        layout.addView(sectionLabel(context, "Prefetch"))
        val prefetchCheck = CheckBox(context).apply {
            text = "Enable prefetch"
            isChecked = prefs.getBoolean(KEY_PREFETCH, config.prefetchEnabled)
        }
        layout.addView(prefetchCheck)

        val ttlSpinner = spinnerFor(
            context,
            TTL_OPTIONS.map { "$it min" },
            TTL_OPTIONS.indexOf(prefs.getLong(KEY_CACHE_TTL, config.cacheTtlMinutes))
        )
        layout.addView(labelRow(context, "Cache TTL (minutes)", ttlSpinner))

        return Controls(layout, catChecks, prefetchCheck, ttlSpinner)
    }

    private fun save(providerId: String, controls: Controls) {
        val prefs = OceSettings.prefs(providerId) ?: return
        val enabled = controls.categoryChecks.filterValues { it.isChecked }.keys
        prefs.edit()
            .putString(KEY_CATEGORIES, enabled.joinToString(","))
            .putBoolean(KEY_PREFETCH, controls.prefetchCheck.isChecked)
            .putLong(KEY_CACHE_TTL, TTL_OPTIONS[controls.ttlSpinner.selectedItemPosition])
            .apply()
        reloadHome()
    }

    private fun sectionLabel(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 16f
            setPadding(0, 24, 0, 8)
            setTextColor(0xFF448AFF.toInt())
        }

    private fun labelRow(
        context: Context,
        label: String,
        spinner: Spinner
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(labelView)
        row.addView(spinner)
        return row
    }

    private fun spinnerFor(
        context: Context,
        options: List<String>,
        currentIndex: Int
    ): Spinner {
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            options
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        return Spinner(context).apply {
            this.adapter = adapter
            if (currentIndex >= 0) setSelection(currentIndex)
        }
    }

    private fun reloadHome() {
        runCatching { afterPluginsLoadedEvent.invoke(true) }
            .onFailure { com.lagradost.api.Log.w(TAG, "reload failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "OceSettings"
        const val KEY_CATEGORIES = "enabled_categories"
        const val KEY_PREFETCH = "prefetch_enabled"
        const val KEY_CACHE_TTL = "cache_ttl_minutes"
        val TTL_OPTIONS = longArrayOf(5L, 15L, 30L, 60L, 120L)
    }
}