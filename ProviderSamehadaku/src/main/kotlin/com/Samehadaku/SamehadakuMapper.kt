package com.Samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.Samehadaku.SamehadakuConstants.SEARCH_TITLE
import com.Samehadaku.SamehadakuConstants.BLOAT_REGEX
import com.Samehadaku.SamehadakuConstants.SEARCH_HREF
import com.Samehadaku.SamehadakuConstants.SEARCH_POSTER
import com.Samehadaku.SamehadakuConstants.ATTR_IMAGE
import com.Samehadaku.SamehadakuConstants.SEARCH_RATING
import com.Samehadaku.SamehadakuConstants.SEARCH_EP_TEXT
import com.Samehadaku.SamehadakuConstants.VAL_REFERER
import com.Samehadaku.SamehadakuConstants.LOAD_TITLE
import com.Samehadaku.SamehadakuConstants.LOAD_POSTER
import com.Samehadaku.SamehadakuConstants.LOAD_BANNER
import com.Samehadaku.SamehadakuConstants.LOAD_DESC
import com.Samehadaku.SamehadakuConstants.LOAD_INFO_BOX
import com.Samehadaku.SamehadakuConstants.CONFIG_HOOK_YEAR_SELECTOR
import com.Samehadaku.SamehadakuConstants.CONFIG_HOOK_YEAR_EXTRACTOR
import com.Samehadaku.SamehadakuConstants.LOAD_STATUS
import com.Samehadaku.SamehadakuConstants.LOAD_TAGS
import com.Samehadaku.SamehadakuConstants.LOAD_RATING
import com.Samehadaku.SamehadakuConstants.ATTR_HREF
import com.Samehadaku.SamehadakuConstants.LOAD_TRAILER
import com.Samehadaku.SamehadakuConstants.EPISODE_HREF
import com.Samehadaku.SamehadakuConstants.EPISODE_TITLE
import com.Samehadaku.SamehadakuConstants.EPISODE_NUM
import com.Samehadaku.SamehadakuConstants.EPISODE_DESC
import com.Samehadaku.SamehadakuConstants.EPISODE_TIME
import org.json.JSONObject

/**
 * TRANSFORMATION LAYER - Element -> Model
 */

class SamehadakuMapper(
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
            val titleEl = element.selectSafe(providerId, SEARCH_TITLE) ?: element.parent()?.selectSafe(providerId, SEARCH_TITLE) ?: element.selectFirst("h2, h3")
            val rawTitle = titleEl?.text()?.trim() ?: titleEl?.attrSafe(providerId, ATTR_IMAGE) ?: titleEl?.attr("title") ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX).safeDeduplicate()
            val hrefEl = element.selectSafe(providerId, SEARCH_HREF) ?: element.selectFirst("a") ?: element.parent()?.selectFirst("a")
            var href = fixUrlSmart(hrefEl?.attr("href"), base)
            val cleanRegex = resolveConfig(providerId, SamehadakuConstants.CONFIG_HREF_CLEAN_REGEXPS, "")
            val cleanReplace = resolveConfig(providerId, SamehadakuConstants.CONFIG_HREF_CLEAN_REPLACES, "")
            if (cleanRegex.isNotBlank() && cleanReplace.isNotBlank()) { href = href.replace(Regex(cleanRegex), cleanReplace) }
            val poster = element.selectSafe(providerId, SEARCH_POSTER)?.safeExtractImage(ATTR_IMAGE); val rating = element.selectSafe(providerId, SEARCH_RATING)?.text(); val eps = element.selectSafe(providerId, SEARCH_EP_TEXT)?.text()?.safeExtractEpNum()
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
        val rawTitle = document.selectSafe(providerId, LOAD_TITLE)?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX).safeDeduplicate()
        val poster = document.selectSafe(providerId, LOAD_POSTER)?.safeExtractImage(ATTR_IMAGE) ?: ""
        val banner = document.selectSafe(providerId, LOAD_BANNER)?.safeExtractImage(ATTR_IMAGE)
        val description = document.selectSafe(providerId, LOAD_DESC)?.text()?.trim() ?: ""
        val infoText = document.selectSafeList(providerId, LOAD_INFO_BOX).text()
        val year = infoText.safeExtractYear() ?: run {
            val selector = resolveConfig(providerId, CONFIG_HOOK_YEAR_SELECTOR, "")
            val regexStr = resolveConfig(providerId, CONFIG_HOOK_YEAR_EXTRACTOR, "")
            if (selector.isNotBlank() && regexStr.isNotBlank()) { Regex(regexStr).find(document.select(selector).text())?.groupValues?.get(1)?.toIntOrNull() } else null
        }
        val statusText = document.selectSafe(providerId, LOAD_STATUS)?.text()
        return MetadataPackage(
            title = title, poster = poster, banner = banner, description = description, 
            year = year, statusText = statusText,
            tags = document.selectSafeList(providerId, LOAD_TAGS).map { it.text() },
            rating = document.selectSafe(providerId, LOAD_RATING)?.text(),
            status = if (statusText?.contains(ongoingKeyword, true) == true) ShowStatus.Ongoing else ShowStatus.Completed,
            imdbId = document.selectSafe(providerId, SamehadakuConstants.SELECTOR_IMDB_EXTERNAL)?.attrSafe(providerId, ATTR_HREF)?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull(),
            tmdbId = document.selectSafe(providerId, SamehadakuConstants.SELECTOR_TMDB_EXTERNAL)?.attrSafe(providerId, ATTR_HREF)?.split("/")?.lastOrNull()?.toIntOrNull(),
            trailer = document.selectSafe(providerId, LOAD_TRAILER)?.let { if (it.tagName() == "iframe") it.safeExtractImage(ATTR_IMAGE) else it.attrSafe(providerId, ATTR_HREF) }
        )
    }

    fun extractEpisodes(document: Document, currentUrl: String, seasonDataScript: Element?, epItems: org.jsoup.select.Elements, poster: String): List<Episode> {
        var episodes = mutableListOf<Episode>()
        if (seasonDataScript != null) { runCatching { val root = JSONObject(seasonDataScript.data()); root.keys().forEach { k -> val arr = root.getJSONArray(k)
                    for (i in 0 until arr.length()) { val ep = arr.getJSONObject(i); episodes.add(api.newEpisode(fixUrlSmart(ep.getString("slug"), currentUrl)) { this.season = ep.optInt("s"); this.episode = ep.optInt("episode_no"); this.name = "${episodeKeyword} ${ep.optInt("episode_no")}" }) } } } }
        if (episodes.isEmpty()) { episodes.addAll(epItems.mapNotNull { ep -> runCatching { val anchor = ep.selectSafe(providerId, EPISODE_HREF) ?: ep.selectFirst("a") ?: return@runCatching null
                val href = episodeDataUrlPattern.replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl)); val titleEl = ep.selectSafe(providerId, EPISODE_TITLE) ?: ep.selectFirst("a")
                val epNum = titleEl?.text()?.safeExtractEpNum() ?: ep.selectSafe(providerId, EPISODE_NUM)?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum(); val rawName = titleEl?.text()?.trim() ?: ""
                val isJustNumber = rawName.matches(Regex("""^\d+(\.\d+)?$""")); api.newEpisode(href) { if (!isJustNumber && rawName.isNotBlank()) this.name = rawName; this.episode = epNum; this.description = ep.selectSafe(providerId, EPISODE_DESC)?.text()?.trim()
                    this.runTime = ep.selectSafe(providerId, EPISODE_TIME)?.text()?.filter { it.isDigit() }?.toIntOrNull(); this.posterUrl = ep.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: poster } }.getOrNull() }) }
        return if (reverseEpisodes && seasonDataScript == null) episodes.reversed() else episodes
    }
}
