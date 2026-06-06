package com.baseprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.baseprovider.ProviderConfig
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

class ProviderMapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
) {

    fun toSearchResult(element: Element, baseUrl: String? = null): SearchResponse? {
        return runCatching {
            val base = baseUrl ?: config.mainUrl
            val titleEl = if (config.searchTitle.isNotBlank()) element.selectFirst(config.searchTitle) ?: element.parent()?.selectFirst(config.searchTitle) else element.selectFirst("h2, h3")
            val rawTitle = titleEl?.attr("title")?.takeIf { it.isNotBlank() } ?: titleEl?.text()?.trim()?.takeIf { it.isNotBlank() } ?: titleEl?.selectAttr(config.attrImage) ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, config.bloatRegex).safeDeduplicate()
            val hrefEl = if (config.searchHref.isNotBlank()) element.selectFirst(config.searchHref) ?: element.selectFirst("a") ?: element.parent()?.selectFirst("a") else element.selectFirst("a") ?: element.parent()?.selectFirst("a")
            var href = fixUrlSmart(hrefEl?.attr("href"), base)
            if (config.hrefCleanRegex.isNotBlank() && config.hrefCleanReplace.isNotBlank()) { href = try { href.replace(Regex(config.hrefCleanRegex), config.hrefCleanReplace) } catch (_: Exception) { href } }
            val poster = if (config.searchPoster.isNotBlank()) element.selectFirst(config.searchPoster)?.safeExtractImage(config.attrImage) else element.selectFirst("img")?.safeExtractImage(config.attrImage)
            val rating = if (config.searchRating.isNotBlank()) element.selectFirst(config.searchRating)?.text() else null
            val eps = if (config.searchEpText.isNotBlank()) element.selectFirst(config.searchEpText)?.text()?.safeExtractEpNum() else null
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
        val rawTitle = if (config.loadTitle.isNotBlank()) document.selectFirst(config.loadTitle)?.text() ?: "Unknown Title" else "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, config.bloatRegex).safeDeduplicate()
        val poster = if (config.loadPoster.isNotBlank()) document.selectFirst(config.loadPoster)?.safeExtractImage(config.attrImage) ?: "" else ""

        if (title == "Unknown Title" || poster.isBlank()) {
            val missing = mutableListOf<String>()
            if (title == "Unknown Title") missing.add("Title")
            if (poster.isBlank()) missing.add("Poster")
            logFail(config.id, "Metadata Integrity Failure: Missing ${missing.joinToString(" & ")}", url = currentUrl, method = "extractMetadata", type = FailureType.METADATA_FAILURE, selectors = "loadTitle, loadPoster, loadDesc, loadInfoBox, loadTags, loadRating, loadStatus")
        }

        val banner = if (config.loadBanner.isNotBlank()) document.selectFirst(config.loadBanner)?.safeExtractImage(config.attrImage) else null
        val description = if (config.loadDesc.isNotBlank()) document.selectFirst(config.loadDesc)?.text()?.trim() ?: "" else ""
        val infoText = if (config.loadInfoBox.isNotBlank()) document.selectFirst(config.loadInfoBox)?.text() ?: "" else ""
        val year = infoText.safeExtractYear() ?: run {
            if (config.yearSelector.isNotBlank() && config.yearExtractorRegex.isNotBlank()) {
                val yearEl = document.selectFirst(config.yearSelector)
                try { Regex(config.yearExtractorRegex).find(yearEl?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull() } catch (_: Exception) { null }
            } else null
        }
        val statusText = if (config.loadStatus.isNotBlank()) document.selectFirst(config.loadStatus)?.text() else null
        return MetadataPackage(
            title = title, poster = poster, banner = banner, description = description,
            year = year, statusText = statusText,
            tags = if (config.loadTags.isNotBlank()) document.select(config.loadTags).map { it.text() } else emptyList(),
            rating = if (config.loadRating.isNotBlank()) document.selectFirst(config.loadRating)?.text() else null,
            status = if (statusText?.contains(config.ongoingKeyword, true) == true) ShowStatus.Ongoing else ShowStatus.Completed,
            imdbId = if (config.imdbExternal.isNotBlank()) document.selectFirst(config.imdbExternal)?.selectAttr(config.attrHref)?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull() else null,
            tmdbId = if (config.tmdbExternal.isNotBlank()) document.selectFirst(config.tmdbExternal)?.selectAttr(config.attrHref)?.split("/")?.lastOrNull()?.toIntOrNull() else null,
            trailer = if (config.loadTrailer.isNotBlank()) document.selectFirst(config.loadTrailer)?.let { if (it.tagName() == "iframe") it.safeExtractImage(config.attrImage) else it.selectAttr(config.attrHref) } else null
        )
    }

    fun extractEpisodes(document: Document, currentUrl: String, seasonDataScript: Element?, epItems: org.jsoup.select.Elements, poster: String): List<Episode> {
        var episodes = mutableListOf<Episode>()
        if (seasonDataScript != null) { runCatching { val root = JSONObject(seasonDataScript.data()); root.keys().forEach { k -> val arr = root.getJSONArray(k)
            for (i in 0 until arr.length()) { val ep = arr.getJSONObject(i); val slug = ep.optString("slug"); if (slug.isNotBlank()) episodes.add(api.newEpisode(fixUrlSmart(slug, currentUrl)) { this.season = ep.optInt("s"); this.episode = ep.optInt("episode_no"); this.name = "${config.episodeKeyword} ${ep.optInt("episode_no")}" }) } } }.onFailure { e -> logDebug(config.id, "Season data JSON parse failed: ${e.message}") } }
        if (episodes.isEmpty()) { episodes.addAll(epItems.mapNotNull { ep -> runCatching { val anchor = (if (config.episodeHref.isNotBlank()) ep.selectFirst(config.episodeHref) else null) ?: ep.selectFirst("a") ?: if (ep.tagName() == "a") ep else null ?: return@runCatching null
            val href = config.episodeDataUrlPattern.replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl)); if (href.isBlank()) return@runCatching null; val titleEl = (if (config.episodeTitle.isNotBlank()) ep.selectFirst(config.episodeTitle) else null) ?: ep.selectFirst("a") ?: if (ep.tagName() == "a") ep else null
            val epNum = titleEl?.text()?.safeExtractEpNum() ?: (if (config.episodeNum.isNotBlank()) ep.selectFirst(config.episodeNum)?.text()?.safeExtractEpNum() else null) ?: ep.text().safeExtractEpNum(); val rawName = titleEl?.text()?.trim() ?: ""
    val isJustNumber = rawName.matches(JUST_NUMBER_REGEX); api.newEpisode(href) { if (!isJustNumber && rawName.isNotBlank()) this.name = rawName; this.episode = epNum; this.description = if (config.episodeDesc.isNotBlank()) ep.selectFirst(config.episodeDesc)?.text()?.trim() else null
        this.runTime = if (config.episodeTime.isNotBlank()) ep.selectFirst(config.episodeTime)?.text()?.filter { it.isDigit() }?.toIntOrNull() else null; this.posterUrl = ep.selectFirst("img")?.safeExtractImage(config.attrImage) ?: poster } }.onFailure { e -> logDebug(config.id, "Episode mapping failed: ${e.message}") }.getOrNull() }) }
        return if (config.reverseEpisodes && seasonDataScript == null) episodes.reversed() else episodes
    }
}
