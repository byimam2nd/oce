package com.baseprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.baseprovider.ProviderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

private const val MIN_SEARCH_RESULTS = 20

class ProviderScrapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
    private val mapper: ProviderMapper
) {
    private val linkCollector = LinkCollector(config)
    private val fallbackPipeline = FallbackPipeline(config)

    suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = if (request.name.contains(config.seriesKeyword, true)) config.seriesUrl?.takeIf { it.isNotBlank() } ?: config.mainUrl else config.mainUrl
        val url = if (request.data.startsWith("http")) {
            val d = request.data.replace("{page}", page.toString())
            val pagePattern = Regex("""(/page/|page=)$page(\b|/|$)""")
            if (!pagePattern.containsMatchIn(d)) {
                if (d.endsWith("/page/")) "${d}$page"
                else { val conn = if (d.contains("?")) "&" else "?"; "${d}${conn}page=$page" }
            } else d
        } else {
            config.mainPagePathPattern.replace("{baseUrl}", baseUrl).replace("{data}", request.data).replace("{page}", page.toString())
        }

        return runCatching {
            val document = fetchDocument(url, config)
            val isHorizontal = config.isHorizontal
            val home = if (config.searchItems.isNotBlank()) document.select(config.searchItems).mapNotNull { runCatching { mapper.toSearchResult(it, url) }.getOrNull() }.distinctBy { it.url } else emptyList()
            newHomePageResponse(list = HomePageList(name = request.name, list = home, isHorizontalImages = isHorizontal), hasNext = home.isNotEmpty())
        }.getOrElse { e ->
            logFail(config.id, "MainPage Fetch Failure on ${request.name}: ${e.message}", url = url, method = "getMainPage", type = FailureType.NETWORK_FAILURE, selectors = "searchItems")
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val baseUrl = config.searchUrl?.takeIf { it.isNotBlank() } ?: config.mainUrl
        val refer = config.mainUrl
        if (config.isJsonSearch) {
            val url = config.searchPathPattern.replace("{baseUrl}", baseUrl).replace("{query}", encodedQuery).replace("{page}", "1")
            return runCatching {
                val response = app.get(url, referer = refer, headers = config.globalHeaders).text; val root = JSONObject(response)
                val items = root.getJSONArray(config.searchJsonRoot.ifBlank { "data" })
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until items.length()) { val item = items.getJSONObject(i)
                    val title = item.optString(config.searchJsonTitle).safeCleanBloat(item.optString(config.searchJsonTitle), config.bloatRegex)
                    val slug = item.optString(config.searchJsonHref); var pUrl = item.optString(config.searchJsonPoster)
                    if (!pUrl.startsWith("http") && config.searchJsonPosterPrefix.isNotBlank()) pUrl = config.searchJsonPosterPrefix + pUrl
                    val isTv = item.optString(config.searchJsonType).contains("series", true) || item.optString(config.searchJsonType).contains("tv", true)
                    var finalUrl = if (isTv) "${config.seriesUrl ?: baseUrl}/$slug" else "${config.mainUrl}/$slug"
                    results.add(api.newAnimeSearchResponse(title, finalUrl, if (isTv) TvType.TvSeries else TvType.Movie) { this.posterUrl = pUrl; this.posterHeaders = config.globalHeaders.toMutableMap().apply { put("Referer", config.mainUrl) } })
                }
                results
            }.getOrElse { e ->
                logFail(config.id, "JSON Search Execution Failed for '$query': ${e.message}", url = url, method = "search", type = FailureType.NETWORK_FAILURE, selectors = "searchItems")
                emptyList()
            }
        }
        return runCatching {
            val results = mutableListOf<SearchResponse>()
            for (page in 1..config.searchPageLimit) {
                if (results.size >= MIN_SEARCH_RESULTS) break
                val url = config.searchPathPattern.replace("{baseUrl}", baseUrl).replace("{page}", page.toString()).replace("{query}", encodedQuery)
                val document = fetchDocument(url, config, refer)
                val pageResults = if (config.searchItems.isNotBlank()) document.select(config.searchItems).mapNotNull { runCatching { mapper.toSearchResult(it, url) }.getOrNull() } else emptyList()
                results.addAll(pageResults)
            }
            results.distinctBy { it.url }
        }.getOrElse { e ->
            logFail(config.id, "Search Execution Failed for '$query': ${e.message}", url = baseUrl, method = "search", type = FailureType.NETWORK_FAILURE, selectors = "searchItems")
            emptyList()
        }
    }

    suspend fun load(url: String): LoadResponse { return loadRecursive(url, 0) }

    private suspend fun loadRecursive(url: String, depth: Int): LoadResponse {
        val document = fetchDocument(url, config)
        val currentUrl = url
        if (depth < 2 && config.followLinkSelector.isNotBlank()) {
            val nextAnchor = document.selectFirst(config.followLinkSelector)
            val nextHref = nextAnchor?.attr("href")
            if (!nextHref.isNullOrBlank() && !nextHref.startsWith("javascript:", true)) { val nextUrl = fixUrlSmart(nextHref, currentUrl); if (nextUrl != currentUrl && nextUrl != url) return loadRecursive(nextUrl, depth + 1) }
        }

        val metadata = mapper.extractMetadata(document, currentUrl)

        val (recommendations, actors) = coroutineScope {
            val recs = async { if (config.loadRecommend.isNotBlank()) document.select(config.loadRecommend).mapNotNull { mapper.toSearchResult(it, currentUrl) } else emptyList() }
            val acts = async {
                if (config.actorItems.isBlank() || config.actorName.isBlank()) emptyList()
                else document.select(config.actorItems).mapNotNull {
                    val n = it.selectFirst(config.actorName)?.text()?.trim() ?: ""
                    val p = it.selectFirst("img")?.safeExtractImage(config.attrImage) ?: ""
                    if (n.isNotBlank() && n.length < 100) Actor(n, p) else null
                }
            }
            recs.await() to acts.await()
        }

        val epItems = if (config.episodeItems.isNotBlank()) document.select(config.episodeItems) else org.jsoup.select.Elements()
        val seasonDataScript = if (config.seasonContainer.isNotBlank()) document.selectFirst(config.seasonContainer) else null
        val hasTvPath = config.tvPathSegment.isNotBlank() && currentUrl.contains(config.tvPathSegment)
        val isMovie = (seasonDataScript == null) && !hasTvPath && ((config.moviePathSegment.isNotBlank() && currentUrl.contains(config.moviePathSegment)) || epItems.isEmpty())
        val type = if (isMovie) TvType.Movie else if (config.supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
        val tracker = runCatching { APIHolder.getTracker(listOf(metadata.title), TrackerType.getTypes(type), metadata.year, true) }.getOrElse { e -> logDebug(config.id, "Tracker Fetch Warning: ${e.message}"); null }

        logSuccess(config.id, "Loaded page: ${metadata.title} (${if (isMovie) "Movie" else "Series"}, tags=${metadata.tags?.size ?: 0})", url = currentUrl, method = "load", selectors = "loadTitle, loadPoster, loadDesc, loadInfoBox")

        if (isMovie) {
            val watchUrl = if (config.watchButtons.isNotBlank()) fixUrlSmart(document.selectFirst(config.watchButtons)?.attr("href"), currentUrl).ifBlank { currentUrl } else currentUrl
            return api.newMovieLoadResponse(metadata.title, url, type, config.episodeDataUrlPattern.replace("{url}", watchUrl)) {
                this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner
                this.posterHeaders = config.globalHeaders.toMutableMap().apply { put("Referer", config.mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }; this.year = metadata.year; this.score = Score.from10(metadata.rating)
                this.recommendations = recommendations; this.comingSoon = metadata.statusText?.let { st -> config.comingSoonKeywords.split(",").any { st.contains(it, true) } } ?: false
                addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) }
        } else {
            val episodes = mapper.extractEpisodes(document, currentUrl, seasonDataScript, epItems, metadata.poster)
            return if (type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie) {
                api.newAnimeLoadResponse(metadata.title, url, type) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = config.globalHeaders.toMutableMap().apply { put("Referer", config.mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addEpisodes(DubStatus.Subbed, episodes); addTrailer(metadata.trailer); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()) }
            } else { api.newTvSeriesLoadResponse(metadata.title, url, type, episodes) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = config.globalHeaders.toMutableMap().apply { put("Referer", config.mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) } }
        }
    }

    suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val document = fetchDocument(data, config, skipCache = true)
            val currentUrl = data
            val allPossibleLinks = mutableSetOf<Pair<String, String?>>()
            val videoCount = AtomicInteger(0)
            val wrappedCallback: (ExtractorLink) -> Unit = { link -> videoCount.incrementAndGet(); callback(link) }

            linkCollector.collectAjaxPlayers(document, currentUrl, allPossibleLinks)
            linkCollector.collectLinkOptions(document, allPossibleLinks)
            linkCollector.collectDownloadItems(document, allPossibleLinks)
            linkCollector.collectIframes(document, allPossibleLinks)

            if (allPossibleLinks.isEmpty()) {
                logFail(config.id, "No media links or iframes found", url = data, method = "loadLinks", type = FailureType.SELECTOR_FAILURE, selectors = config.linkOptions.ifBlank { "none" })
            }

            coroutineScope {
                allPossibleLinks.filter { it.first.isNotBlank() && !it.first.startsWith("#") }.map { (raw, label) -> async {
                    linkSemaphore.withPermit { fallbackPipeline.processLink(raw, label, currentUrl, subtitleCallback, wrappedCallback) }
                } }.awaitAll()
            }

            fallbackPipeline.logLinkResults(videoCount.get(), allPossibleLinks.size, data)
            true
        }.getOrElse { e ->
            val ft = if (e.message?.contains("cancel", true) == true) FailureType.CANCELLED else FailureType.NETWORK_FAILURE
            logCritical(config.id, "LoadLinks Critical Failure on data: $data", e, url = data, method = "loadLinks", type = ft); false
        }
    }
}
