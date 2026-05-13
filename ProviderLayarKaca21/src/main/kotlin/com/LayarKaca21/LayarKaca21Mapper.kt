package com.LayarKaca21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import com.LayarKaca21.LayarKaca21Constants.SEARCH_TITLE
import com.LayarKaca21.LayarKaca21Constants.BLOAT_REGEX
import com.LayarKaca21.LayarKaca21Constants.SEARCH_HREF
import com.LayarKaca21.LayarKaca21Constants.SEARCH_POSTER
import com.LayarKaca21.LayarKaca21Constants.ATTR_IMAGE
import com.LayarKaca21.LayarKaca21Constants.SEARCH_RATING
import com.LayarKaca21.LayarKaca21Constants.SEARCH_EP_TEXT
import com.LayarKaca21.LayarKaca21Constants.VAL_REFERER
import com.LayarKaca21.LayarKaca21Constants.LOAD_TITLE
import com.LayarKaca21.LayarKaca21Constants.LOAD_POSTER
import com.LayarKaca21.LayarKaca21Constants.LOAD_BANNER
import com.LayarKaca21.LayarKaca21Constants.LOAD_DESC
import com.LayarKaca21.LayarKaca21Constants.LOAD_INFO_BOX
import com.LayarKaca21.LayarKaca21Constants.CONFIG_HOOK_YEAR_SELECTOR
import com.LayarKaca21.LayarKaca21Constants.CONFIG_HOOK_YEAR_EXTRACTOR
import com.LayarKaca21.LayarKaca21Constants.LOAD_STATUS
import com.LayarKaca21.LayarKaca21Constants.LOAD_TAGS
import com.LayarKaca21.LayarKaca21Constants.LOAD_RATING
import com.LayarKaca21.LayarKaca21Constants.ATTR_HREF
import com.LayarKaca21.LayarKaca21Constants.LOAD_TRAILER
import com.LayarKaca21.LayarKaca21Constants.EPISODE_HREF
import com.LayarKaca21.LayarKaca21Constants.EPISODE_TITLE
import com.LayarKaca21.LayarKaca21Constants.EPISODE_NUM
import com.LayarKaca21.LayarKaca21Constants.EPISODE_DESC
import com.LayarKaca21.LayarKaca21Constants.EPISODE_TIME
import org.json.JSONObject

/**
 * TRANSFORMATION LAYER - Element -> Model
 */

class LayarKaca21Mapper(
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
            val titleEl = element.selectFirstSafe(providerId, SEARCH_TITLE) ?: element.parent()?.selectFirstSafe(providerId, SEARCH_TITLE) ?: element.selectFirst("h2, h3")
            val rawTitle = titleEl?.text()?.trim() ?: titleEl?.attrSafe(providerId, ATTR_IMAGE) ?: titleEl?.attr("title") ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX).safeDeduplicate()
            val hrefEl = element.selectFirstSafe(providerId, SEARCH_HREF) ?: element.selectFirst("a") ?: element.parent()?.selectFirst("a")
            var href = fixUrlSmart(hrefEl?.attr("href"), base)
            val cleanRegex = resolveConfig(providerId, LayarKaca21Constants.CONFIG_HREF_CLEAN_REGEXPS, "")
            val cleanReplace = resolveConfig(providerId, LayarKaca21Constants.CONFIG_HREF_CLEAN_REPLACES, "")
            if (cleanRegex.isNotBlank() && cleanReplace.isNotBlank()) { href = href.replace(Regex(cleanRegex), cleanReplace) }
            val poster = element.selectFirstSafe(providerId, SEARCH_POSTER)?.safeExtractImage(ATTR_IMAGE); val rating = element.selectFirstSafe(providerId, SEARCH_RATING)?.text(); val eps = element.selectFirstSafe(providerId, SEARCH_EP_TEXT)?.text()?.safeExtractEpNum()
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
        val rawTitle = document.selectFirstSafe(providerId, LOAD_TITLE)?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, BLOAT_REGEX).safeDeduplicate()
        val poster = document.selectFirstSafe(providerId, LOAD_POSTER)?.safeExtractImage(ATTR_IMAGE) ?: ""
        val banner = document.selectFirstSafe(providerId, LOAD_BANNER)?.safeExtractImage(ATTR_IMAGE)
        val description = document.selectFirstSafe(providerId, LOAD_DESC)?.text()?.trim() ?: ""
        val infoText = document.selectFirstSafe(providerId, LOAD_INFO_BOX)?.text() ?: ""
        val year = infoText.safeExtractYear() ?: run {
            val selector = resolveConfig(providerId, CONFIG_HOOK_YEAR_SELECTOR, "")
            val regexStr = resolveConfig(providerId, CONFIG_HOOK_YEAR_EXTRACTOR, "")
            if (selector.isNotBlank() && regexStr.isNotBlank()) { Regex(regexStr).find(document.select(selector).text())?.groupValues?.get(1)?.toIntOrNull() } else null
        }
        val statusText = document.selectFirstSafe(providerId, LOAD_STATUS)?.text()
        return MetadataPackage(
            title = title, poster = poster, banner = banner, description = description, 
            year = year, statusText = statusText,
            tags = document.selectSafe(providerId, LOAD_TAGS).map { it.text() },
            rating = document.selectFirstSafe(providerId, LOAD_RATING)?.text(),
            status = if (statusText?.contains(ongoingKeyword, true) == true) ShowStatus.Ongoing else ShowStatus.Completed,
            imdbId = document.selectFirstSafe(providerId, LayarKaca21Constants.SELECTOR_IMDB_EXTERNAL)?.attrSafe(providerId, ATTR_HREF)?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull(),
            tmdbId = document.selectFirstSafe(providerId, LayarKaca21Constants.SELECTOR_TMDB_EXTERNAL)?.attrSafe(providerId, ATTR_HREF)?.split("/")?.lastOrNull()?.toIntOrNull(),
            trailer = document.selectFirstSafe(providerId, LOAD_TRAILER)?.let { if (it.tagName() == "iframe") it.safeExtractImage(ATTR_IMAGE) else it.attrSafe(providerId, ATTR_HREF) }
        )
    }

    fun extractEpisodes(document: Document, currentUrl: String, seasonDataScript: Element?, epItems: org.jsoup.select.Elements, poster: String): List<Episode> {
        var episodes = mutableListOf<Episode>()
        if (seasonDataScript != null) { runCatching { val root = JSONObject(seasonDataScript.data()); root.keys().forEach { k -> val arr = root.getJSONArray(k)
                    for (i in 0 until arr.length()) { val ep = arr.getJSONObject(i); episodes.add(api.newEpisode(fixUrlSmart(ep.getString("slug"), currentUrl)) { this.season = ep.optInt("s"); this.episode = ep.optInt("episode_no"); this.name = "${episodeKeyword} ${ep.optInt("episode_no")}" }) } } } }
        if (episodes.isEmpty()) { episodes.addAll(epItems.mapNotNull { ep -> runCatching { val anchor = ep.selectFirstSafe(providerId, EPISODE_HREF) ?: ep.selectFirst("a") ?: return@runCatching null
                val href = episodeDataUrlPattern.replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl)); val titleEl = ep.selectFirstSafe(providerId, EPISODE_TITLE) ?: ep.selectFirst("a")
                val epNum = titleEl?.text()?.safeExtractEpNum() ?: ep.selectFirstSafe(providerId, EPISODE_NUM)?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum(); val rawName = titleEl?.text()?.trim() ?: ""
                val isJustNumber = rawName.matches(Regex("""^\d+(\.\d+)?$""")); api.newEpisode(href) { if (!isJustNumber && rawName.isNotBlank()) this.name = rawName; this.episode = epNum; this.description = ep.selectFirstSafe(providerId, EPISODE_DESC)?.text()?.trim()
                    this.runTime = ep.selectFirstSafe(providerId, EPISODE_TIME)?.text()?.filter { it.isDigit() }?.toIntOrNull(); this.posterUrl = ep.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: poster } }.getOrNull() }) }
        return if (reverseEpisodes && seasonDataScript == null) episodes.reversed() else episodes
    }
}
