package com.baseprovider.core

import com.baseprovider.config.*
import com.baseprovider.log.*
import com.baseprovider.model.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

private data class LoadedPageData(
    val recommendations: List<SearchResponse>,
    val actors: List<Actor>,
    val episodes: List<Episode>,
    val tracker: Tracker?
)

class DetailPageScrapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
    private val mapper: ProviderMapper
) {
    suspend fun load(url: String): LoadResponse = loadRecursive(url, 0)

    private suspend fun loadRecursive(url: String,
        depth: Int): LoadResponse {
        val document = fetchDocument(url, config, referer = config.mainUrl)
        val currentUrl = url
        if (depth < 2 && config.followLinkSelector.isNotBlank()) {
            val nextAnchor = document.selectFirst(config
                .followLinkSelector)
            val nextHref = nextAnchor?.attr("href")
            if (!nextHref.isNullOrBlank() && !nextHref.startsWith("javascript:", true)) {
                val nextUrl = fixUrlSmart(nextHref, currentUrl)
                if (nextUrl != currentUrl && nextUrl !=
                    url) return loadRecursive(nextUrl, depth + 1)
            }
        }

        val metadata = mapper.extractMetadata(document, currentUrl)

        val epItems = if (config.episodeItems.isNotBlank()) document
            .select(config.episodeItems) else org.jsoup.select.Elements()
        val seasonDataScript = if (config.seasonContainer
            .isNotBlank()) document.selectFirst(config
                .seasonContainer) else null
        val hasTvPath = config.tvPathSegment.isNotBlank() && currentUrl
            .contains(config.tvPathSegment)
        val isMovie = (seasonDataScript == null) && !hasTvPath && (
            (config.moviePathSegment.isNotBlank() && currentUrl
                .contains(config.moviePathSegment))
                || epItems.isEmpty()
        )
        val type = if (isMovie) TvType.Movie else if (config.supportedTypes
            .contains(TvType.Anime)) TvType.Anime else TvType.TvSeries

        val pageData = coroutineScope {
            val recs = async {
                if (config.loadRecommend.isNotBlank()) {
                    document.select(config.loadRecommend)
                        .mapNotNull { mapper.toSearchResult(it,
                            currentUrl) }
                } else emptyList()
            }
            val acts = async {
                if (config.actorItems.isBlank() || config.actorName
                    .isBlank()) emptyList()
                else document.select(config.actorItems).mapNotNull {
                    val n = it.selectFirst(config.actorName)?.text()
                        ?.trim() ?: ""
                    val p = it.selectFirst("img")?.safeExtractImage(config
                        .attrImage) ?: ""
                    if (n.isNotBlank() && n.length < 100) Actor(n,
                        p) else null
                }
            }
            val eps = async {
                if (!isMovie) mapper.extractEpisodes(document,
                    currentUrl, seasonDataScript, epItems,
                    metadata.poster) else emptyList()
            }
            val trk = async {
                withTimeout(4000L) {
                    runCatching {
                        APIHolder.getTracker(listOf(metadata.title),
                            TrackerType.getTypes(type), metadata.year,
                            true)
                    }.getOrElse { e ->
                        logDebug(config.id, "Tracker Fetch Warning: ${e.message}")
                        null
                    }
                }
            }
            LoadedPageData(recs.await(), acts.await(), eps.await(), trk.await())
        }
        val recommendations = pageData.recommendations
        val actors = pageData.actors
        val episodes = pageData.episodes
        val tracker = pageData.tracker

        logSuccess(
            config.id,
            "Loaded page: ${metadata.title} (${if (isMovie) "Movie" else "Series"}, tags=${metadata.tags?.size ?: 0})",
            url = currentUrl, method = "load",
            selectors = "loadTitle, loadPoster, loadDesc, loadInfoBox"
        )

        if (isMovie) {
            val watchUrl = if (config.watchButtons.isNotBlank()) {
                fixUrlSmart(document.selectFirst(config.watchButtons)
                    ?.attr("href"), currentUrl)
                    .ifBlank { currentUrl }
            } else currentUrl
            return api.newMovieLoadResponse(metadata.title, url, type,
                config.episodeDataUrlPattern.replace("{url}", watchUrl)) {
                this.posterUrl = tracker?.image ?: metadata.poster
                this.backgroundPosterUrl = tracker?.cover ?: metadata
                    .banner
                this.posterHeaders = config.globalHeaders.toMutableMap()
                    .apply { put("Referer", config.mainUrl) }
                this.plot = metadata.description
                this.tags = metadata.tags.ifEmpty { null }
                this.year = metadata.year
                this.score = Score.from10(metadata.rating)
                this.recommendations = recommendations
                this.comingSoon = metadata.statusText?.let { st ->
                    config.comingSoonKeywords.split(",").any { st
                        .contains(it, true) }
                } ?: false
                addTrailer(metadata.trailer)
                addActors(actors)
                addMalId(tracker?.malId)
                addAniListId(tracker?.aniId?.toIntOrNull())
                addImdbId(metadata.imdbId)
                addTMDbId(metadata.tmdbId?.toString())
            }
        } else {
            return if (type == TvType.Anime || type == TvType.OVA || type ==
                TvType.AnimeMovie) {
                api.newAnimeLoadResponse(metadata.title, url, type) {
                    this.posterUrl = tracker?.image ?: metadata.poster
                    this.backgroundPosterUrl = tracker?.cover ?: metadata
                        .banner
                    this.posterHeaders = config.globalHeaders
                        .toMutableMap().apply { put("Referer", config
                            .mainUrl) }
                    this.plot = metadata.description
                    this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year
                    this.score = Score.from10(metadata.rating)
                    this.recommendations = recommendations
                    this.showStatus = metadata.status
                    addEpisodes(DubStatus.Subbed, episodes)
                    addTrailer(metadata.trailer)
                    addMalId(tracker?.malId)
                    addAniListId(tracker?.aniId?.toIntOrNull())
                }
            } else {
                api.newTvSeriesLoadResponse(metadata.title, url, type,
                    episodes) {
                    this.posterUrl = tracker?.image ?: metadata.poster
                    this.backgroundPosterUrl = tracker?.cover ?: metadata
                        .banner
                    this.posterHeaders = config.globalHeaders
                        .toMutableMap().apply { put("Referer", config
                            .mainUrl) }
                    this.plot = metadata.description
                    this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year
                    this.score = Score.from10(metadata.rating)
                    this.recommendations = recommendations
                    this.showStatus = metadata.status
                    addTrailer(metadata.trailer)
                    addActors(actors)
                    addMalId(tracker?.malId)
                    addAniListId(tracker?.aniId?.toIntOrNull())
                    addImdbId(metadata.imdbId)
                    addTMDbId(metadata.tmdbId?.toString())
                }
            }
        }
    }
}
