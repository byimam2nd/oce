package com.baseprovider.settings

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.baseprovider.config.ProviderConfig
import com.baseprovider.model.SelectorResolver
import com.baseprovider.network.fetchDocument
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SettingsDialog {

    private class Controls(
        val layout: LinearLayout,
        val categoryChecks: Map<String, CheckBox>,
        val prefetchCheck: CheckBox,
        val ttlSpinner: Spinner,
        val customName: EditText,
        val customUrl: EditText,
        val customContainer: LinearLayout,
        val customChecks: LinkedHashMap<String, CheckBox>,
        val statusView: TextView
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

        layout.addView(sectionLabel(context, "Custom Categories"))
        layout.addView(caption(context, "Kategori tambahan dari URL mana pun di situs provider."))
        val customName = EditText(context).apply {
            hint = "Nama kategori (mis. Anime Action)"
            singleLine = true
        }
        layout.addView(customName)
        val customUrl = EditText(context).apply {
            hint = "URL kategori (mis. https://site/genre/action/)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            singleLine = true
        }
        layout.addView(customUrl)

        val addButton = Button(context).apply { text = "Add Category" }
        layout.addView(addButton)

        val customContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(customContainer)

        val customChecks = LinkedHashMap<String, CheckBox>()
        OceSettings.customCategories(providerId).forEach { (url, name) ->
            val cb = CheckBox(context).apply {
                text = "$name\n$url"
                tag = url
            }
            customChecks[url] = cb
            customContainer.addView(cb)
        }

        val removeButton = Button(context).apply { text = "Remove selected" }
        layout.addView(removeButton)

        val statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFFB00020.toInt())
            setPadding(0, 8, 0, 0)
        }
        layout.addView(statusView)

        addButton.setOnClickListener {
            validateAndAdd(context, providerId, config, customName, customUrl, customContainer, customChecks, statusView)
        }
        removeButton.setOnClickListener {
            val toRemove = customChecks.filterValues { it.isChecked }.keys
            if (toRemove.isEmpty()) {
                statusView.text = "Tidak ada kategori yang dipilih."
                return@setOnClickListener
            }
            toRemove.forEach { url ->
                OceSettings.removeCustomCategory(providerId, url)
                customChecks.remove(url)?.let { customContainer.removeView(it) }
            }
            statusView.text = "Kategori dihapus."
            reloadHome()
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

        return Controls(layout, catChecks, prefetchCheck, ttlSpinner, customName, customUrl, customContainer, customChecks, statusView)
    }

    private fun validateAndAdd(
        context: Context,
        providerId: String,
        config: ProviderConfig,
        nameInput: EditText,
        urlInput: EditText,
        customContainer: LinearLayout,
        customChecks: LinkedHashMap<String, CheckBox>,
        statusView: TextView
    ) {
        val name = nameInput.text.toString().trim()
        val url = urlInput.text.toString().trim()
        if (name.isBlank() || url.isBlank()) {
            statusView.text = "Nama dan URL harus diisi."
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            statusView.text = "URL harus diawali http(s)://"
            return
        }
        if (customChecks.keys.any { it == url }) {
            statusView.text = "URL sudah ditambahkan."
            return
        }
        statusView.text = "Memvalidasi URL..."
        GlobalScope.launch(Dispatchers.Main) {
            val ok = withContext(Dispatchers.IO) {
                isValidCategoryUrl(context, config, url)
            }
            if (ok) {
                OceSettings.addCustomCategory(providerId, url, name)
                val cb = CheckBox(context).apply {
                    text = "$name\n$url"
                    tag = url
                }
                customChecks[url] = cb
                customContainer.addView(cb)
                nameInput.text.clear()
                urlInput.text.clear()
                statusView.text = "Kategori ditambahkan."
                reloadHome()
            } else {
                statusView.text = "Gagal validasi: halaman tidak ditemukan atau tidak berisi item."
            }
        }
    }

    private suspend fun isValidCategoryUrl(
        context: Context,
        config: ProviderConfig,
        url: String
    ): Boolean = runCatching {
        val document = fetchDocument(url, config)
        val items = SelectorResolver.select(
            document,
            config.searchItems,
            "${config.id}:customCategory"
        )
        items.isNotEmpty()
    }.getOrDefault(false)

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

    private fun caption(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setPadding(0, 0, 0, 8)
            setTextColor(0xFF666666.toInt())
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
