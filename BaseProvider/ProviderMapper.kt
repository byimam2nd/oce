package com.baseprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import com.baseprovider.ProviderHTMLConstants.SEARCH_TITLE
import com.baseprovider.ProviderHTMLConstants.BLOAT_REGEX
import com.baseprovider.ProviderHTMLConstants.SEARCH_HREF
import com.baseprovider.ProviderHTMLConstants.SEARCH_POSTER
import com.baseprovider.ProviderHTMLConstants.ATTR_IMAGE
import com.baseprovider.ProviderHTMLConstants.SEARCH_RATING
import com.baseprovider.ProviderHTMLConstants.SEARCH_EP_TEXT
import com.baseprovider.ProviderHTMLConstants.VAL_REFERER
import com.baseprovider.ProviderHTMLConstants.LOAD_TITLE
import com.baseprovider.ProviderHTMLConstants.LOAD_POSTER
import com.baseprovider.ProviderHTMLConstants.LOAD_BANNER
import com.baseprovider.ProviderHTMLConstants.LOAD_DESC
import com.baseprovider.ProviderHTMLConstants.LOAD_INFO_BOX
import com.baseprovider.ProviderHTMLConstants.CONFIG_HOOK_YEAR_SELECTOR
import com.baseprovider.ProviderHTMLConstants.CONFIG_HOOK_YEAR_EXTRACTOR
import com.baseprovider.ProviderHTMLConstants.LOAD_STATUS
import com.baseprovider.ProviderHTMLConstants.LOAD_TAGS
import com.baseprovider.ProviderHTMLConstants.LOAD_RATING
import com.baseprovider.ProviderHTMLConstants.ATTR_HREF
import com.baseprovider.ProviderHTMLConstants.LOAD_TRAILER
import com.baseprovider.ProviderHTMLConstants.EPISODE_HREF
import com.baseprovider.ProviderHTMLConstants.EPISODE_TITLE
import com.baseprovider.ProviderHTMLConstants.EPISODE_NUM
import com.baseprovider.ProviderHTMLConstants.EPISODE_DESC
import com.baseprovider.ProviderHTMLConstants.EPISODE_TIME
import org.json.JSONObject

/**
 * TRANSFORMATION LAYER - Element -> Model
 */

class ProviderMapper(
    private val api: MainAPI,
    private val providerId: String,
    private val mainUrl: String,
    private val moviePathSegment: String,
    private val supportedTypes: Set<TvType>,
    private val dubKeyword: String,
    private val globalHeaders: Map<String, String>,
    private val ongoingKeyword: String,
    private val episodeKeyword: String,
    private val reverseEpisodes: Boolean,
    private val episodeDataUrlPattern: String
) {

    fun toSearchResult(element: Element, baseUrl: String? = null): SearchResponse? {
        return runCatching {
            val base = baseUrl ?: mainUrl
            val titleEl = element.selectFirstSafe(providerId, SEARCH_TITLE, "SEARCH_TITLE") ?: element.parent()?.selectFirstSafe(providerId, SEARCH_TITLE, "SEARCH_TITLE") ?: element.selectFirst("h2, h3")
            val rawTitle = titleEl?.text()?.trim() ?: titleEl?.attrSafe(providerId, ATTR_IMAGE, "ATTR_IMAGE") ?: titleEl?.attr("title") ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX).safeDeduplicate()
            val hrefEl = element.selectFirstSafe(providerId, SEARCH_HREF, "SEARCH_HREF") ?: element.selectFirst("a") ?: element.parent()?.selectFirst("a")
            var href = fixUrlSmart(hrefEl?.attr("href"), base)
            val cleanRegex = resolveConfig(providerId, ProviderHTMLConstants.CONFIG_HREF_CLEAN_REGEXPS, "")
            val cleanReplace = resolveConfig(providerId, ProviderHTMLConstants.CONFIG_HREF_CLEAN_REPLACES, "")
            if (cleanRegex.isNotBlank() && cleanReplace.isNotBlank()) { href = href.replace(Regex(cleanRegex), cleanReplace) }
            val poster = element.selectFirstSafe(providerId, SEARCH_POSTER, "SEARCH_POSTER")?.safeExtractImage(ATTR_IMAGE); val rating = element.selectFirstSafe(providerId, SEARCH_RATING, "SEARCH_RATING")?.text(); val eps = element.selectFirstSafe(providerId, SEARCH_EP_TEXT, "SEARCH_EP_TEXT")?.text()?.safeExtractEpNum()
            val isMovie = (moviePathSegment.isNotBlank() && href.contains(moviePathSegment)) || href.contains("movie", true)
            val type = if (isMovie) TvType.Movie else if (supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
            api.newAnimeSearchResponse(title, href, type) { 
                this.posterUrl = poster
                this.posterHeaders = globalHeaders.toMutableMap().apply { put(VAL_REFERER, mainUrl) }
                this.score = Score.from10(rating)
                addDubStatus(dubExist = element.text().contains(dubKeyword, true), subExist = true, subEpisodes = eps) 
            }
        }.getOrElse { e -> 
            logDebug(providerId, "Mapping Item Failure: ${e.message}")
            null 
        }
    }

    fun extractMetadata(document: Document, currentUrl: String): MetadataPackage {
        val rawTitle = document.selectFirstSafe(providerId, LOAD_TITLE, "LOAD_TITLE")?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX).safeDeduplicate()
        val poster = document.selectFirstSafe(providerId, LOAD_POSTER, "LOAD_POSTER")?.safeExtractImage(ATTR_IMAGE) ?: ""
        
        // --- METADATA INTEGRITY CHECK ---
        if (title == "Unknown Title" || poster.isBlank()) {
            val missing = mutableListOf<String>()
            if (title == "Unknown Title") missing.add("Title")
            if (poster.isBlank()) missing.add("Poster")
            logFail(providerId, "Metadata Integrity Failure: Missing ${missing.joinToString(" & ")}", url = currentUrl)
        }

        val banner = document.selectFirstSafe(providerId, LOAD_BANNER, "LOAD_BANNER")?.safeExtractImage(ATTR_IMAGE)
        val description = document.selectFirstSafe(providerId, LOAD_DESC, "LOAD_DESC")?.text()?.trim() ?: ""
        val infoText = document.selectFirstSafe(providerId, LOAD_INFO_BOX, "LOAD_INFO_BOX")?.text() ?: ""
        val year = infoText.safeExtractYear() ?: run {
            val selector = resolveConfig(providerId, CONFIG_HOOK_YEAR_SELECTOR, "")
            val regexStr = resolveConfig(providerId, CONFIG_HOOK_YEAR_EXTRACTOR, "")
            if (selector.isNotBlank() && regexStr.isNotBlank()) { Regex(regexStr).find(document.select(selector).text())?.groupValues?.get(1)?.toIntOrNull() } else null
        }
        val statusText = document.selectFirstSafe(providerId, LOAD_STATUS, "LOAD_STATUS")?.text()
        return MetadataPackage(
            title = title, poster = poster, banner = banner, description = description, 
            year = year, statusText = statusText,
            tags = document.selectSafe(providerId, LOAD_TAGS, "LOAD_TAGS").map { it.text() },
            rating = document.selectFirstSafe(providerId, LOAD_RATING, "LOAD_RATING")?.text(),
            status = if (statusText?.contains(ongoingKeyword, true) == true) ShowStatus.Ongoing else ShowStatus.Completed,
            imdbId = document.selectFirstSafe(providerId, ProviderHTMLConstants.SELECTOR_IMDB_EXTERNAL, "SELECTOR_IMDB_EXTERNAL")?.attrSafe(providerId, ATTR_HREF, "ATTR_HREF")?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull(),
            tmdbId = document.selectFirstSafe(providerId, ProviderHTMLConstants.SELECTOR_TMDB_EXTERNAL, "SELECTOR_TMDB_EXTERNAL")?.attrSafe(providerId, ATTR_HREF, "ATTR_HREF")?.split("/")?.lastOrNull()?.toIntOrNull(),
            trailer = document.selectFirstSafe(providerId, LOAD_TRAILER, "LOAD_TRAILER")?.let { if (it.tagName() == "iframe") it.safeExtractImage(ATTR_IMAGE) else it.attrSafe(providerId, ATTR_HREF, "ATTR_HREF") }
        )
    }

    fun extractEpisodes(document: Document, currentUrl: String, seasonDataScript: Element?, epItems: org.jsoup.select.Elements, poster: String): List<Episode> {
        var episodes = mutableListOf<Episode>()
        if (seasonDataScript != null) { runCatching { val root = JSONObject(seasonDataScript.data()); root.keys().forEach { k -> val arr = root.getJSONArray(k)
                    for (i in 0 until arr.length()) { val ep = arr.getJSONObject(i); episodes.add(api.newEpisode(fixUrlSmart(ep.getString("slug"), currentUrl)) { this.season = ep.optInt("s"); this.episode = ep.optInt("episode_no"); this.name = "${episodeKeyword} ${ep.optInt("episode_no")}" }) } } } }
        if (episodes.isEmpty()) { episodes.addAll(epItems.mapNotNull { ep -> runCatching { val anchor = ep.selectFirstSafe(providerId, EPISODE_HREF, "EPISODE_HREF") ?: ep.selectFirst("a") ?: return@runCatching null
                val href = episodeDataUrlPattern.replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl)); val titleEl = ep.selectFirstSafe(providerId, EPISODE_TITLE, "EPISODE_TITLE") ?: ep.selectFirst("a")
                val epNum = titleEl?.text()?.safeExtractEpNum() ?: ep.selectFirstSafe(providerId, EPISODE_NUM, "EPISODE_NUM")?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum(); val rawName = titleEl?.text()?.trim() ?: ""
                val isJustNumber = rawName.matches(Regex("""^\d+(\.\d+)?$""")); api.newEpisode(href) { if (!isJustNumber && rawName.isNotBlank()) this.name = rawName; this.episode = epNum; this.description = ep.selectFirstSafe(providerId, EPISODE_DESC, "EPISODE_DESC")?.text()?.trim()
                    this.runTime = ep.selectFirstSafe(providerId, EPISODE_TIME, "EPISODE_TIME")?.text()?.filter { it.isDigit() }?.toIntOrNull(); this.posterUrl = ep.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: poster } }.getOrNull() }) }
        return if (reverseEpisodes && seasonDataScript == null) episodes.reversed() else episodes
    }
}
