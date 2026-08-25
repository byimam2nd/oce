package com.baseprovider.core

import com.baseprovider.cache.ExpiringCache
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document

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
    private val htmlCache = ExpiringCache<Document>(config.cacheTtlMinutes * 60 * 1000L)

    suspend fun load(url: String): LoadResponse {
        val visited = mutableSetOf(normalizeUrl(url))
        return loadRecursive(url, 0, visited)
    }

    private fun normalizeUrl(url: String): String =
        url.substringBefore("#").trimEnd('/')

    private suspend fun loadRecursive(
        url: String,
        depth: Int,
        visited: MutableSet<String>
    ): LoadResponse {
        val document = fetchDocument(url, config, referer = config.mainUrl,
            htmlCache = htmlCache)
        val currentUrl = url
        val key = config.id
        val __t0 = System.currentTimeMillis()
        // Marker TV dihitung SEKALI di atas — dipakai fallback gate sekaligus
        // keputusan isMovie di bawah (tanpa mengubah semantik keduanya).
        val hasTvPath = config.tvPathSegment.isNotBlank() && currentUrl
            .contains(config.tvPathSegment)
        val urlLooksTv = listOf("/tv/", "/series/", "/anime/", "/drama/",
            "/episode/", "/eps/").any { currentUrl.contains(it, true) }
        // M2: cek kelengkapan page-1 DENGAN selector murah (tanpa panggil
        // extractMetadata yang ber-log METADATA_FAILURE — page-1 yang sengaja
        // stub untuk di-follow tidak boleh memunculkan failure palsu). Hanya
        // follow (fetch halaman kedua) jika metadata/episodes kritis kurang.
        val titlePresent = config.loadTitle.isNotBlank() && SelectorResolver
            .textValidated(document, config.loadTitle, "$key:loadTitle",
                FieldType.TITLE) != null
        val posterPresent = config.loadPoster.isNotBlank() && SelectorResolver
            .selectValidated(document, config.loadPoster, "$key:loadPoster",
                FieldType.POSTER) { it.safeExtractImage(config.attrImage) } != null
        val epItems = if (config.episodeItems.isNotBlank()) SelectorResolver
            .select(document, config.episodeItems, "${config.id}:episodeItems")
            else org.jsoup.select.Elements()

        // Player tab ada di halaman? (dipakai detector sebagai salah satu sinyal)
        val hasOnPagePlayer = config.linkOptions.isNotBlank() && runCatching {
            config.linkOptions.split(',').any {
                document.selectFirst(it.trim()) != null
            }
        }.getOrDefault(false)

        // Fallback episode links: deteksi berbasis pola URL (bukan selector)
        val fallbackEpisodeLinks = if (epItems.isEmpty() &&
            config.supportedTypes.any { it != TvType.Movie }) {
            SelectorResolver.detectEpisodeLinks(document, currentUrl)
        } else org.jsoup.select.Elements()

        // Season container dicek SEBELUM detector (dibutuhkan sebagai sinyal)
        val seasonDataScript = if (config.seasonContainer
            .isNotBlank()) SelectorResolver.selectFirst(document,
                config.seasonContainer, "${config.id}:seasonContainer") else null

        // ── MovieSeriesDetector: semua logika movie/series terpusat di sini ──
        val detection = MovieSeriesDetector.detect(
            url = currentUrl,
            config = config,
            epItems = epItems,
            hasOnPagePlayer = hasOnPagePlayer,
            seasonDataScript = seasonDataScript,
            fallbackEpisodeLinks = fallbackEpisodeLinks,
            looksLikeMovieUrlFn = mapper::looksLikeMovieUrl
        )
        val effectiveEpItems = detection.effectiveEpItems

        if (depth < 2 && config.followLinkSelector.isNotBlank()) {
            val needsFollow = !titlePresent || !posterPresent
            val epHints = effectiveEpItems.isNotEmpty()
            val missingData = needsFollow || !epHints
            val nextAnchor = if (missingData) SelectorResolver.selectFirst(
                document, config.followLinkSelector,
                "${key}:followLinkSelector") else null
            val nextHref = nextAnchor?.attr("href")
            if (!nextHref.isNullOrBlank() && !nextHref.startsWith("javascript:", true)) {
                val nextUrl = fixUrlSmart(nextHref, currentUrl)
                val normalizedNext = normalizeUrl(nextUrl)
                if (normalizedNext != normalizeUrl(currentUrl) &&
                    normalizedNext != normalizeUrl(url) &&
                    visited.add(normalizedNext)
                ) {
                    return loadRecursive(nextUrl, depth + 1, visited)
                }
            }
        }

        val metadata = mapper.extractMetadata(document, currentUrl)

        // ── Tipe dari MovieSeriesDetector (terpusat, ter-log dengan reason) ──
        val isMovie = detection.isMovie
        logSuccess(key,
            "Detection: type=${if (isMovie) "MOVIE" else "SERIES"} " +
                "reason=${detection.reason} rawEps=${detection.rawEpCount} " +
                "validEps=${detection.validEpCount} " +
                "(${System.currentTimeMillis() - __t0} ms)")

        val type = if (isMovie) TvType.Movie else if (config.supportedTypes
            .contains(TvType.Anime)) TvType.Anime else TvType.TvSeries

        val pageData = coroutineScope {
            val recs = async {
                if (config.loadRecommend.isNotBlank()) {
                    SelectorResolver.select(document, config.loadRecommend,
                        "${config.id}:loadRecommend")
                        .mapNotNull { mapper.toSearchResult(it,
                            currentUrl) }
                } else emptyList()
            }
            val acts = async {
                if (config.actorItems.isBlank() || config.actorName
                    .isBlank()) emptyList()
                else SelectorResolver.select(document, config.actorItems,
                    "${config.id}:actorItems").mapNotNull {
                    val n = SelectorResolver.selectFirst(it, config
                        .actorName, "${config.id}:actorName")?.text()
                        ?.trim() ?: ""
                    val p = it.selectFirst("img")?.safeExtractImage(config
                        .attrImage) ?: ""
                    if (n.isNotBlank() && n.length < 100) Actor(n,
                        p) else null
                }
            }
            val eps = async {
                if (!isMovie) mapper.extractEpisodes(document,
                    currentUrl, seasonDataScript, effectiveEpItems,
                    metadata.poster) else emptyList()
            }
            val trk = async {
                // Non-blocking: ambil hasil tracker HANYA jika sudah siap dalam
                // 300ms (mis. sudah ada di trackerCache APIHolder). Jika query
                // AniList/MAL/TMDb masih berjalan, langsung lanjut tanpa tracker
                // — metadata halaman (poster/plot/episode dari HTML) tetap tampil
                // tanpa menunggu. Tidak ada yang memblokir render.
                withTimeoutOrNull(config.trackerTimeoutMs) {
                    try {
                        APIHolder.getTracker(listOf(metadata.title),
                            TrackerType.getTypes(type), metadata.year,
                            true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
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
            "Loaded page: ${metadata.title} (${if (isMovie) "Movie" else "Series"}, tags=${metadata.tags?.size ?: 0}) dalam ${System.currentTimeMillis() - __t0} ms",
            url = currentUrl, method = "load",
            selectors = "loadTitle, loadPoster, loadDesc, loadInfoBox",
            durationMs = System.currentTimeMillis() - __t0
        )

        if (isMovie) {
            val watchUrl = if (config.watchButtons.isNotBlank()) {
                fixUrlSmart(SelectorResolver.selectFirst(document,
                    config.watchButtons, "${config.id}:watchButtons")
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
