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

    suspend fun load(url: String): LoadResponse = loadRecursive(url, 0)

    private suspend fun loadRecursive(url: String,
        depth: Int): LoadResponse {
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

        // ── Validasi konten: apakah elemen episode berasal dari series ini? ──
        // Cek: slug dari currentUrl harus muncul di href mayoritas epItems.
        // Jika YA -> daftar episode sungguhan (berapa pun jumlahnya).
        // Jika TIDAK -> kontaminasi relocate/fingerprint -> abaikan.
        // Kasus THOG: slug "tales-of-herding-gods" muncul di 97 href → SERIES ✓
        // Kasus DM21 Sacrifice: slug "sacrifice-2026" TIDAK ada di href
        //   "/tv/ludwig-season-2-2026/" → kontaminasi → MOVIE ✓
        val currentSlug = runCatching {
            java.net.URI(currentUrl).path?.trim('/')?.substringBefore('/') ?: ""
        }.getOrDefault("")

        fun hasRealEpisodes(items: org.jsoup.select.Elements): Boolean {
            if (items.isEmpty()) return false
            if (currentSlug.isBlank()) return items.isNotEmpty()
            var match = 0
            for (ep in items) {
                val a = ep.selectFirst("a[href]") ?: ep.takeIf { it.tagName() == "a" }
                val href = a?.attr("href") ?: ""
                if (href.contains(currentSlug, ignoreCase = true)) match++
            }
            return match >= (items.size + 1) / 2 // ≥50% cocok = sungguhan
        }

        // Adaptive fallback detectEpisodeLinks — hanya jika epItems kosong
        // ATAU elemen yang ada bukan episode dari series ini (kontaminasi).
        val episodeLinks = if ((epItems.isEmpty() || !hasRealEpisodes(epItems)) &&
            config.supportedTypes.any { it != TvType.Movie }) {
            if (epItems.isNotEmpty()) {
                logSuccess(key,
                    "EpisodeFilter: ${epItems.size} elemen BUKAN episode dari " +
                        "series ini (slug='$currentSlug') — abaikan")
            }
            SelectorResolver.detectEpisodeLinks(document, currentUrl)
        } else {
            org.jsoup.select.Elements()
        }
        val effectiveEpItems = if (epItems.isNotEmpty() &&
            hasRealEpisodes(epItems)) epItems else episodeLinks
        if (depth < 2 && config.followLinkSelector.isNotBlank()) {
            val needsFollow = !titlePresent || !posterPresent
            val epHints = effectiveEpItems.isNotEmpty()
            // Follow jika metadata kritis kurang ATAU episode tidak ada di
            // page-1 — follow selector biasanya menunjuk halaman yang
            // melengkapi data. Tanpa cek episode, halaman dengan title OK
            // tapi episode kosong akan kehilangan daftar episode.
            val missingData = needsFollow || !epHints
            val nextAnchor = if (missingData) SelectorResolver.selectFirst(
                document, config.followLinkSelector,
                "${key}:followLinkSelector") else null
            val nextHref = nextAnchor?.attr("href")
            if (!nextHref.isNullOrBlank() && !nextHref.startsWith("javascript:", true)) {
                val nextUrl = fixUrlSmart(nextHref, currentUrl)
                if (nextUrl != currentUrl && nextUrl !=
                    url) return loadRecursive(nextUrl, depth + 1)
            }
        }

        val metadata = mapper.extractMetadata(document, currentUrl)

        val seasonDataScript = if (config.seasonContainer
            .isNotBlank()) SelectorResolver.selectFirst(document,
                config.seasonContainer, "${config.id}:seasonContainer") else null
        // Heuristic isMovie tidak hanya mengandung epItems.isEmpty(): URL yang
        // jelas tv-like (/tv/, /series/, /anime/, /episode/) menandakan series
        // walaupun selector episode gagal match. Movie -> moviePathSegment atau
        // tidak ada indikator series sama sekali.
        val isMovie = (seasonDataScript == null) && !hasTvPath && !urlLooksTv && (
            (config.moviePathSegment.isNotBlank() && currentUrl
                .contains(config.moviePathSegment))
                || effectiveEpItems.isEmpty()
        )
        logDebug(key,
            "load[$currentUrl] isMovie=$isMovie " +
                "tvPath=$hasTvPath urlLooksTv=$urlLooksTv epEff=${effectiveEpItems.size}")
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
                withTimeoutOrNull(300L) {
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
