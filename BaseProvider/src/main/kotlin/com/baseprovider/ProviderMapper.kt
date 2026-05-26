package com.baseprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.baseprovider.config.ProviderConfig
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.json.JSONObject

class ProviderMapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
) {

    fun toSearchResult(element: Element, baseUrl: String? = null): SearchResponse? {
        return runCatching {
            val base = baseUrl ?: config.mainUrl
            val titleEl = element.selectFirst(config.searchTitle) ?: element.parent()?.selectFirst(config.searchTitle) ?: element.selectFirst("h2, h3")
            val rawTitle = titleEl?.text()?.trim() ?: titleEl?.selectAttr(config.attrImage) ?: titleEl?.attr("title") ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, config.bloatRegex).safeDeduplicate()
            val hrefEl = element.selectFirst(config.searchHref) ?: element.selectFirst("a") ?: element.parent()?.selectFirst("a")
            var href = fixUrlSmart(hrefEl?.attr("href"), base)
            if (config.hrefCleanRegex.isNotBlank() && config.hrefCleanReplace.isNotBlank()) { href = href.replace(Regex(config.hrefCleanRegex), config.hrefCleanReplace) }
            val poster = element.selectFirst(config.searchPoster)?.safeExtractImage(config.attrImage)
            val rating = element.selectFirst(config.searchRating)?.text()
            val eps = element.selectFirst(config.searchEpText)?.text()?.safeExtractEpNum()
            val hasTvPath = config.tvPathSegment.isNotBlank() && href.contains(config.tvPathSegment)
            val isMovie = !hasTvPath && ((config.moviePathSegment.isNotBlank() && href.contains(config.moviePathSegment)) || href.contains("movie", true))
            val type = if (isMovie) TvType.Movie else if (config.supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
            api.newAnimeSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.posterHeaders = config.globalHeaders.toMutableMap().apply { put("Referer", config.mainUrl) }
                this.score = Score.from10(rating)
                addDubStatus(dubExist = element.text().contains(config.dubKeyword, true), subExist = true, subEpisodes = eps)
            }
        }.getOrElse { e ->
            logDebug(config.id, "Mapping Item Failure: ${e.message}")
            null
        }
    }

    fun extractMetadata(document: Document, currentUrl: String): MetadataPackage {
        val rawTitle = document.selectFirst(config.loadTitle)?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, config.bloatRegex).safeDeduplicate()
        val poster = document.selectFirst(config.loadPoster)?.safeExtractImage(config.attrImage) ?: ""

        if (title == "Unknown Title" || poster.isBlank()) {
            val missing = mutableListOf<String>()
            if (title == "Unknown Title") missing.add("Title")
            if (poster.isBlank()) missing.add("Poster")
            logFail(config.id, "Metadata Integrity Failure: Missing ${missing.joinToString(" & ")}", url = currentUrl, method = "extractMetadata", type = FailureType.METADATA_FAILURE, selectors = "loadTitle, loadPoster, loadDesc, loadInfoBox, loadTags, loadRating, loadStatus")
        }

        val banner = document.selectFirst(config.loadBanner)?.safeExtractImage(config.attrImage)
        val description = document.selectFirst(config.loadDesc)?.text()?.trim() ?: ""
        val infoText = document.selectFirst(config.loadInfoBox)?.text() ?: ""
        val year = infoText.safeExtractYear() ?: run {
            if (config.yearSelector.isNotBlank() && config.yearExtractorRegex.isNotBlank()) {
                Regex(config.yearExtractorRegex).find(document.select(config.yearSelector).text())?.groupValues?.get(1)?.toIntOrNull()
            } else null
        }
        val statusText = document.selectFirst(config.loadStatus)?.text()
        return MetadataPackage(
            title = title, poster = poster, banner = banner, description = description,
            year = year, statusText = statusText,
            tags = if (config.loadTags.isNotBlank()) document.select(config.loadTags).map { it.text() } else emptyList(),
            rating = document.selectFirst(config.loadRating)?.text(),
            status = if (statusText?.contains(config.ongoingKeyword, true) == true) ShowStatus.Ongoing else ShowStatus.Completed,
            imdbId = document.selectFirst(config.imdbExternal)?.selectAttr(config.attrHref)?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull(),
            tmdbId = document.selectFirst(config.tmdbExternal)?.selectAttr(config.attrHref)?.split("/")?.lastOrNull()?.toIntOrNull(),
            trailer = document.selectFirst(config.loadTrailer)?.let { if (it.tagName() == "iframe") it.safeExtractImage(config.attrImage) else it.selectAttr(config.attrHref) }
        )
    }

    fun extractEpisodes(document: Document, currentUrl: String, seasonDataScript: Element?, epItems: org.jsoup.select.Elements, poster: String): List<Episode> {
        var episodes = mutableListOf<Episode>()
        if (seasonDataScript != null) { runCatching { val root = JSONObject(seasonDataScript.data()); root.keys().forEach { k -> val arr = root.getJSONArray(k)
            for (i in 0 until arr.length()) { val ep = arr.getJSONObject(i); episodes.add(api.newEpisode(fixUrlSmart(ep.getString("slug"), currentUrl)) { this.season = ep.optInt("s"); this.episode = ep.optInt("episode_no"); this.name = "${config.episodeKeyword} ${ep.optInt("episode_no")}" }) } } } }
        if (episodes.isEmpty()) { episodes.addAll(epItems.mapNotNull { ep -> runCatching { val anchor = ep.selectFirst(config.episodeHref) ?: ep.selectFirst("a") ?: if (ep.tagName() == "a") ep else null ?: return@runCatching null
            val href = config.episodeDataUrlPattern.replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl)); val titleEl = ep.selectFirst(config.episodeTitle) ?: ep.selectFirst("a") ?: if (ep.tagName() == "a") ep else null
            val epNum = titleEl?.text()?.safeExtractEpNum() ?: ep.selectFirst(config.episodeNum)?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum(); val rawName = titleEl?.text()?.trim() ?: ""
            val isJustNumber = rawName.matches(Regex("""^\d+(\.\d+)?$""")); api.newEpisode(href) { if (!isJustNumber && rawName.isNotBlank()) this.name = rawName; this.episode = epNum; this.description = ep.selectFirst(config.episodeDesc)?.text()?.trim()
                this.runTime = ep.selectFirst(config.episodeTime)?.text()?.filter { it.isDigit() }?.toIntOrNull(); this.posterUrl = ep.selectFirst("img")?.safeExtractImage(config.attrImage) ?: poster } }.getOrNull() }) }
        return if (config.reverseEpisodes && seasonDataScript == null) episodes.reversed() else episodes
    }
}
