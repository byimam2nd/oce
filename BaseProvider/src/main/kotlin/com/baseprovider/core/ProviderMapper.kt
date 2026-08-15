package com.baseprovider.core

import com.baseprovider.config.*
import com.baseprovider.log.*
import com.baseprovider.model.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ProviderMapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
) {

    // L4: regex hrefClean dikompilasi sekali per pola unik, tidak per elemen
    // (toSearchResult dipanggil untuk tiap item hasil search).
    private val compiledHrefClean = ConcurrentHashMap<String, Regex?>()

    private fun hrefCleanRegex(): Regex? {
        val pattern = config.hrefCleanRegex
        if (pattern.isBlank()) return null
        return compiledHrefClean.computeIfAbsent(pattern) {
            runCatching { Regex(it) }.getOrNull()
        }
    }

    private fun compiledRegex(pattern: String): Regex? =
        compiledHrefClean.computeIfAbsent(pattern) {
            runCatching { Regex(it) }.getOrNull()
        }

    fun toSearchResult(element: Element, baseUrl: String? =
        null): SearchResponse? {
        return runCatching {
            val base = baseUrl ?: config.mainUrl
            val key = config.id
            val titleEl = if (config.searchTitle.isNotBlank()) {
                SelectorResolver.selectValidated(element, config.searchTitle, "$key:searchTitle", FieldType.TITLE) { it.text()?.trim() }
                    ?: element.parent()?.let { SelectorResolver.selectValidated(it, config.searchTitle, "$key:searchTitle", FieldType.TITLE) { it.text()?.trim() } }
            } else {
                element.selectFirst("h2, h3")
            }
            val rawTitle = titleEl?.text()?.trim() ?: titleEl
                ?.selectAttr(config.attrImage) ?: titleEl
                    ?.attr("title") ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, config
                .bloatRegex).safeDeduplicate()
            val hrefEl = if (config.searchHref.isNotBlank()) {
                SelectorResolver.selectFirst(element, config.searchHref, "$key:searchHref")
                    ?: element.selectFirst("a")
                    ?: element.parent()?.let { SelectorResolver.selectFirst(it, config.searchHref, "$key:searchHref") }
            } else {
                element.selectFirst("a")
                    ?: element.parent()?.selectFirst("a")
            }
            var href = fixUrlSmart(hrefEl?.attr("href"), base)
            val cleanRx = hrefCleanRegex()
            if (cleanRx != null && config.hrefCleanReplace.isNotBlank()) {
                href = try {
                    href.replace(cleanRx, config.hrefCleanReplace)
                } catch (_: Exception) { href }
            }
            val poster = if (config.searchPoster.isNotBlank()) {
                SelectorResolver.selectValidated(element, config.searchPoster, "$key:searchPoster", FieldType.POSTER) { it.safeExtractImage(config.attrImage) }
                    ?.safeExtractImage(config.attrImage)
            } else {
                element.selectFirst("img")?.safeExtractImage(config
                    .attrImage)
            }
            val rating = if (config.searchRating.isNotBlank()) SelectorResolver
                .selectFirst(element, config.searchRating, "$key:searchRating")
                ?.text() else null
            val eps = if (config.searchEpText.isNotBlank()) SelectorResolver
                .selectFirst(element, config.searchEpText, "$key:searchEpText")
                ?.text()?.safeExtractEpNum() else null
            val hasTvPath = config.tvPathSegment.isNotBlank() && href
                .contains(config.tvPathSegment)
            // Heuristic adaptive: URL tv-like tanpa butuh tvPathSegment config
            // (mis. /tv/, /series/, /anime/) dikenali sebagai series walaupun
            // config belum di-update struktur situsnya.
            val urlLooksTv = listOf("/tv/", "/series/", "/anime/", "/drama/",
                "/episode/", "/eps/").any { href.contains(it, true) }
            val isMovie = !hasTvPath && !urlLooksTv && (
                (config.moviePathSegment.isNotBlank() && href
                    .contains(config.moviePathSegment))
                    || href.contains("movie", true)
            )
            val type = if (isMovie) TvType.Movie else if (config
                .supportedTypes.contains(TvType.Anime)) TvType
                    .Anime else TvType.TvSeries
            api.newAnimeSearchResponse(title, href, type) {
                this.posterUrl = PosterResizer.resize(poster, config.posterResizeUrl)
                this.posterHeaders = config.globalHeaders.toMutableMap()
                    .apply { put("Referer", config.mainUrl) }
                this.score = Score.from10(rating)
                addDubStatus(dubExist = element.text().contains(config
                    .dubKeyword, true), subExist = true, subEpisodes = eps)
            }
        }.getOrElse { e ->
            logDebug(config.id, "Mapping Item Failure: ${e.message}")
            null
        }
    }

    fun extractMetadata(document: Document,
        currentUrl: String): MetadataPackage {
        val key = config.id
        val rawTitle = if (config.loadTitle.isNotBlank()) SelectorResolver
            .textValidated(document, config.loadTitle, "$key:loadTitle", FieldType.TITLE)
            ?: "Unknown Title" else "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, config.bloatRegex)
            .safeDeduplicate()
        val poster = if (config.loadPoster.isNotBlank()) SelectorResolver
            .selectValidated(document, config.loadPoster, "$key:loadPoster", FieldType.POSTER) { it.safeExtractImage(config.attrImage) }
            ?.safeExtractImage(config.attrImage) ?: "" else ""

        if (title == "Unknown Title" || poster.isBlank()) {
            val missing = mutableListOf<String>()
            if (title == "Unknown Title") missing.add("Title")
            if (poster.isBlank()) missing.add("Poster")
            logFail(
                config.id,
                "Metadata Integrity Failure: Missing ${missing.joinToString(" & ")}",
                url = currentUrl,
                method = "extractMetadata",
                type = FailureType.METADATA_FAILURE,
                selectors = "loadTitle, loadPoster, loadDesc, loadInfoBox, loadTags, loadRating, loadStatus"
            )
        }

        val banner = if (config.loadBanner.isNotBlank()) SelectorResolver
            .selectFirst(document, config.loadBanner, "$key:loadBanner")
            ?.safeExtractImage(config.attrImage) else null
        val description = if (config.loadDesc.isNotBlank()) SelectorResolver
            .text(document, config.loadDesc, "$key:loadDesc") ?: "" else ""
        val infoText = if (config.loadInfoBox.isNotBlank()) SelectorResolver
            .text(document, config.loadInfoBox, "$key:loadInfoBox") ?: "" else ""
        val year = infoText.safeExtractYear() ?: run {
            if (config.yearSelector.isNotBlank() && config
                .yearExtractorRegex.isNotBlank()) {
                val yearEl = SelectorResolver.selectFirst(document,
                    config.yearSelector, "$key:yearSelector")
                try { compiledRegex(config.yearExtractorRegex)
                    ?.find(yearEl?.text() ?: "")?.groupValues?.get(1)
                        ?.toIntOrNull() } catch (_: Exception) { null }
            } else null
        }
        val statusText = if (config.loadStatus.isNotBlank())
            SelectorResolver.text(document, config.loadStatus,
                "$key:loadStatus") else null
        return MetadataPackage(
            title = title, poster = poster, banner = banner, description =
                description,
            year = year, statusText = statusText,
            tags = if (config.loadTags.isNotBlank()) SelectorResolver.select(
                document, config.loadTags, "$key:loadTags").map { it.text() } else emptyList(),
            rating = if (config.loadRating.isNotBlank()) SelectorResolver
                .text(document, config.loadRating, "$key:loadRating") else null,
            status = if (statusText?.contains(config.ongoingKeyword,
                true) == true) ShowStatus.Ongoing else ShowStatus.Completed,
            imdbId = if (config.imdbExternal.isNotBlank()) {
                SelectorResolver.selectFirst(document, config.imdbExternal,
                    "$key:imdbExternal")
                    ?.selectAttr(config.attrHref)
                    ?.split("/")
                    ?.filter { it.startsWith("tt") }
                    ?.firstOrNull()
            } else null,
            tmdbId = if (config.tmdbExternal.isNotBlank()) {
                SelectorResolver.selectFirst(document, config.tmdbExternal,
                    "$key:tmdbExternal")
                    ?.selectAttr(config.attrHref)
                    ?.split("/")
                    ?.lastOrNull()
                    ?.toIntOrNull()
            } else null,
            trailer = if (config.loadTrailer.isNotBlank()) {
                SelectorResolver.selectFirst(document, config.loadTrailer,
                    "$key:loadTrailer")?.let {
                    if (it.tagName() == "iframe") it
                        .safeExtractImage(config.attrImage)
                    else it.selectAttr(config.attrHref)
                }
            } else null
        )
    }

    fun extractEpisodes(
        document: Document,
        currentUrl: String,
        seasonDataScript: Element?,
        epItems: org.jsoup.select.Elements,
        poster: String,
    ): List<Episode> {
        var episodes = mutableListOf<Episode>()
        if (seasonDataScript != null) {
            runCatching {
                val root = JSONObject(seasonDataScript.data())
                root.keys().forEach { k ->
                    val arr = root.getJSONArray(k)
                    for (i in 0 until arr.length()) {
                        val ep = arr.getJSONObject(i)
                        val slug = ep.optString("slug")
                        if (slug.isNotBlank()) {
                            episodes.add(
                                api.newEpisode(fixUrlSmart(slug,
                                    currentUrl)) {
                                    this.season = ep.optInt("s")
                                    this.episode = ep.optInt("episode_no")
                                    this.name = "${config.episodeKeyword} ${ep.optInt("episode_no")}"
                                }
                            )
                        }
                    }
                }
            }.onFailure { e ->
                logDebug(config.id, "Season data JSON parse failed: ${e.message}")
            }
        }
        if (episodes.isEmpty()) {
            val seenEpNums = mutableSetOf<Int>()
            val key = config.id
            episodes.addAll(
                epItems.mapNotNull { ep ->
                    runCatching {
                        val anchor = (
                            if (config.episodeHref.isNotBlank()) SelectorResolver
                                .selectFirst(ep, config.episodeHref, "$key:episodeHref")
                            else null
                        ) ?: ep.selectFirst("a")
                            ?: if (ep.tagName() == "a") ep
                            else null ?: return@runCatching null
                        val href = config.episodeDataUrlPattern
                            .replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl))
                        if (href.isBlank()) return@runCatching null
                        val titleEl = (
                            if (config.episodeTitle.isNotBlank()) SelectorResolver
                                .selectFirst(ep, config.episodeTitle, "$key:episodeTitle")
                            else null
                        ) ?: ep.selectFirst("a")
                            ?: if (ep.tagName() == "a") ep
                            else null
                        val epNum = (
                                if (config.episodeNum.isNotBlank()) SelectorResolver
                                    .selectFirst(ep, config.episodeNum, "$key:episodeNum")
                                    ?.text()?.safeExtractEpNum()
                                else null
                            ) ?: titleEl?.text()?.safeExtractEpNum()
                                ?: ep.text().safeExtractEpNum()
                        if (epNum != null && !seenEpNums.add(epNum)) {
                            return@runCatching null
                        }
                        val rawName = titleEl?.text()?.trim() ?: ""
                        val isJustNumber = rawName
                            .matches(JUST_NUMBER_REGEX)
                        api.newEpisode(href) {
                            if (!isJustNumber && rawName.isNotBlank()) this
                                .name = rawName
                            this.episode = epNum
                            this.description = if (config.episodeDesc
                                .isNotBlank()) {
                                SelectorResolver.text(ep, config
                                    .episodeDesc, "$key:episodeDesc")
                            } else null
                            this.runTime = if (config.episodeTime
                                .isNotBlank()) {
                                SelectorResolver.text(ep, config
                                    .episodeTime, "$key:episodeTime")
                                    ?.filter { it.isDigit() }
                                        ?.toIntOrNull()
                            } else null
                            this.posterUrl = PosterResizer.resize(
                                ep.selectFirst("img")?.safeExtractImage(config
                                    .attrImage) ?: poster,
                                config.thumbnailResizeUrl.ifBlank { config.posterResizeUrl }
                            )
                        }
                    }.onFailure { e ->
                        logDebug(config.id, "Episode mapping failed: ${e.message}")
                    }.getOrNull()
                }
            )
        }
        return if (config.reverseEpisodes && seasonDataScript ==
            null) episodes.reversed() else episodes
    }
}
