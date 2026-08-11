package com.baseprovider.collector

import com.baseprovider.config.ProviderConfig
import com.baseprovider.log.*
import com.baseprovider.model.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LinkCollector(private val config: ProviderConfig) {

    private val switchVideoRegex = Regex("""switchVideo\s*\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)

    suspend fun collectAjaxPlayers(document: Document, currentUrl: String,
        links: MutableSet<Pair<String, String?>>) {
        if (config.ajaxPlayerUrl.isBlank() || config.selectorJsonData
            .isBlank()) return
        if (!config.ajaxPlayerUrl.startsWith("http")) return
        val el = document.selectFirst(config.selectorJsonData) ?: return
        val eastPostId = el.attr("data-post")
        if (eastPostId.isNotBlank()) {
            collectEastPlayPlayers(document, currentUrl, eastPostId, links)
        } else {
            collectJsonPlayers(document, currentUrl, el, links)
        }
    }

    private suspend fun collectJsonPlayers(document: Document,
        currentUrl: String,
        el: Element, links: MutableSet<Pair<String, String?>>) {
        runCatching {
            val json = JSONObject(el.data())
            val id = json.optString("id")
            if (id.isNotBlank()) {
                logDebug(config.id, "Fetching AJAX players for ID: $id from ${config.ajaxPlayerUrl}")
                val res = app.post(config.ajaxPlayerUrl, data =
                    mapOf("id" to id), headers = config.globalHeaders,
                        referer = currentUrl).document
                res.select("li, a, option").forEach { item ->
                    val label = item.text().trim()
                    val raw = item.selectAttr(config.attrValue) ?: item
                        .attr("href") ?: ""
                    if (raw.isNotBlank()) links.add(raw to label
                        .ifBlank { null })
                }
            }
        }.onFailure { e -> logDebug(config.id, "AJAX player collection failed: ${e.message}") }
    }

    private suspend fun collectEastPlayPlayers(document: Document,
        currentUrl: String, postId: String,
        links: MutableSet<Pair<String, String?>>) {
        runCatching {
            val options = document.select(config.selectorJsonData)
            logDebug(config.id, "EastPlay AJAX options: ${options.size} for post $postId")
            options.forEach { opt ->
                val nume = opt.attr("data-nume")
                val type = opt.attr("data-type").ifBlank { "schtml" }
                if (nume.isBlank()) return@forEach
                val label = opt.text().trim()
                logDebug(config.id, "Fetching player option nume=$nume type=$type")
                val res = app.post(config.ajaxPlayerUrl,
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to type
                    ), headers = config.globalHeaders,
                        referer = currentUrl).document
                val iframes = res.select("iframe")
                if (iframes.isNotEmpty()) {
                    iframes.forEach { f ->
                        val src = f.attr("data-src").ifBlank { f
                            .attr("src") }
                        if (src.isNotBlank()) links.add(src to label
                            .ifBlank { null })
                    }
                } else {
                    res.select("video source, video").forEach { v ->
                        val src = v.attr("data-src").ifBlank { v
                            .attr("src") }
                        if (src.isNotBlank()) links.add(src to label
                            .ifBlank { null })
                    }
                }
            }
        }.onFailure { e -> logDebug(config.id, "EastPlay AJAX player collection failed: ${e.message}") }
    }

    fun collectLinkOptions(document: Document,
        links: MutableSet<Pair<String, String?>>) {
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
            else {
                val raw = container.selectAttr(config.attrValue)
                    ?: container.attr("href")
                    ?: ""
                if (raw.isNotBlank()) links.add(raw to container.text())
            }
        }
    }

    fun collectDownloadItems(document: Document,
        links: MutableSet<Pair<String, String?>>) {
        if (config.downloadItems.isBlank()) return
        val dlMatches = document.select(config.downloadItems)
        logDebug(config.id, "DOWNLOAD_ITEMS selector '${config.downloadItems}' => ${dlMatches.size} match(es)")
        dlMatches.forEach { container ->
            container.select("a").forEach { a -> val href = a
                .attr("href"); if (href.isNotBlank()) links.add(href to a
                    .text()) }
        }
    }

    fun collectSwitchVideoButtons(document: Document, currentUrl: String,
        links: MutableSet<Pair<String, String?>>) {
        if (config.switchVideoSelector.isBlank()) return
        logDebug(config.id, "SWITCH_VIDEO selector: ${config.switchVideoSelector}")
        val matches = document.select(config.switchVideoSelector)
        logDebug(config.id, "SWITCH_VIDEO => ${matches.size} match(es)")
        matches.forEach { el ->
            val onclick = el.attr("onclick")
            if (onclick.isBlank()) return@forEach
            switchVideoRegex.findAll(onclick).forEach { m ->
                val url = fixUrlSmart(m.groupValues[1].trim(), currentUrl)
                if (url.isNotBlank()) links.add(url to el.text().trim())
            }
        }
    }

    fun collectIframes(document: Document, links: MutableSet<Pair<String,
        String?>>) {
        val iframeTagMatches = if (config.iframeTag.isNotBlank()) document
            .select(config.iframeTag) else org.jsoup.select.Elements()
        logDebug(config.id, "iframeTag => ${iframeTagMatches.size} iframe(s)")
        iframeTagMatches.forEach { el ->
            config.iframeSources.forEach { attr -> val s = el
                .attr(attr); if (s.isNotBlank() && s != "about:blank") links.add(s to null) }
        }
    }
}
