package com.baseprovider

import com.baseprovider.ProviderConfig
import com.lagradost.cloudstream3.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LinkCollector(private val config: ProviderConfig) {

    suspend fun collectAjaxPlayers(document: Document, currentUrl: String, links: MutableSet<Pair<String, String?>>) {
        if (config.ajaxPlayerUrl.isBlank() || config.selectorJsonData.isBlank()) return
        val el = document.selectFirst(config.selectorJsonData) ?: return
        runCatching {
            val json = JSONObject(el.data())
            val id = json.optString("id")
            if (id.isNotBlank()) {
                logDebug(config.id, "Fetching AJAX players for ID: $id from ${config.ajaxPlayerUrl}")
                val res = app.post(config.ajaxPlayerUrl, data = mapOf("id" to id), headers = config.globalHeaders, referer = currentUrl).document
                res.select("li, a, option").forEach { item ->
                    val label = item.text().trim()
                    val raw = item.selectAttr(config.attrValue) ?: item.attr("href") ?: ""
                    if (raw.isNotBlank()) links.add(raw to label)
                }
            }
        }.onFailure { e -> logDebug(config.id, "AJAX player collection failed: ${e.message}") }
    }

    fun collectLinkOptions(document: Document, links: MutableSet<Pair<String, String?>>) {
        if (config.linkOptions.isBlank()) return
        logDebug(config.id, "LINK_OPTIONS selector: ${config.linkOptions}")
        val matches = document.select(config.linkOptions)
        logDebug(config.id, "LINK_OPTIONS => ${matches.size} match(es)")
        matches.forEach { container ->
            val anchors = container.select("a")
            if (anchors.isNotEmpty()) anchors.forEach { a ->
                val link = a.attr("data-url").ifBlank { a.attr("href") }
                links.add(link to a.text())
            }
            else { val raw = container.selectAttr(config.attrValue) ?: container.attr("href") ?: ""; if (raw.isNotBlank()) links.add(raw to container.text()) }
        }
    }

    fun collectDownloadItems(document: Document, links: MutableSet<Pair<String, String?>>) {
        if (config.downloadItems.isBlank()) return
        val dlMatches = document.select(config.downloadItems)
        logDebug(config.id, "DOWNLOAD_ITEMS selector '${config.downloadItems}' => ${dlMatches.size} match(es)")
        dlMatches.forEach { container ->
            container.select("a").forEach { a -> val href = a.attr("href"); if (href.isNotBlank()) links.add(href to a.text()) }
        }
    }

    fun collectIframes(document: Document, links: MutableSet<Pair<String, String?>>) {
        val iframeTagMatches = if (config.iframeTag.isNotBlank()) document.select(config.iframeTag) else org.jsoup.select.Elements()
        logDebug(config.id, "iframeTag => ${iframeTagMatches.size} iframe(s)")
        iframeTagMatches.forEach { el ->
            config.iframeSources.forEach { attr -> val s = el.attr(attr); if (s.isNotBlank() && s != "about:blank") links.add(s to null) }
        }
    }
}
