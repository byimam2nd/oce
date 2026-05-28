package com.baseprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.baseprovider.config.ProviderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

private const val VAL_REFERER = "Referer"
private const val VAL_USER_AGENT = "User-Agent"
private const val DEFAULT_TIMEOUT = 15000L

class ProviderScrapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
    private val mapper: ProviderMapper
) {

    suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = if (request.name.contains(config.seriesKeyword, true) && !config.seriesUrl.isNullOrBlank()) config.seriesUrl!! else config.mainUrl
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
            val document = getHtmlParsed(url)
            val isHorizontal = config.isHorizontal
            val home = if (config.searchItems.isNotBlank()) document.select(config.searchItems).mapNotNull { runCatching { mapper.toSearchResult(it, url) }.getOrNull() } else emptyList()
            newHomePageResponse(list = HomePageList(name = request.name, list = home, isHorizontalImages = isHorizontal), hasNext = home.isNotEmpty())
        }.getOrElse { e ->
            logFail(config.id, "MainPage Fetch Failure on ${request.name}: ${e.message}", url = url, method = "getMainPage", type = FailureType.NETWORK_FAILURE, selectors = "searchItems")
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val baseUrl = if (!config.searchUrl.isNullOrBlank()) config.searchUrl!! else config.mainUrl
        val refer = config.mainUrl
        if (config.isJsonSearch) {
            val url = config.searchPathPattern.replace("{baseUrl}", baseUrl).replace("{query}", encodedQuery).replace("{page}", "1")
            return runCatching {
                val response = app.get(url, referer = refer, headers = config.globalHeaders).text; val root = JSONObject(response)
                val items = if (config.searchJsonRoot.isBlank()) root.getJSONArray("results") else root.getJSONArray(config.searchJsonRoot)
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until items.length()) { val item = items.getJSONObject(i)
                    val title = item.optString(config.searchJsonTitle).safeCleanBloat(item.optString(config.searchJsonTitle), config.bloatRegex)
                    val slug = item.optString(config.searchJsonHref); var pUrl = item.optString(config.searchJsonPoster)
                    if (!pUrl.startsWith("http") && config.searchJsonPosterPrefix.isNotBlank()) pUrl = config.searchJsonPosterPrefix + pUrl
                    val isTv = item.optString(config.searchJsonType).contains("series", true) || item.optString(config.searchJsonType).contains("tv", true)
                    var finalUrl = if (isTv) "${config.seriesUrl ?: baseUrl}/$slug" else "${config.mainUrl}/$slug"
                    results.add(api.newAnimeSearchResponse(title, finalUrl, if (isTv) TvType.TvSeries else TvType.Movie) { this.posterUrl = pUrl; this.posterHeaders = config.globalHeaders.toMutableMap().apply { put(VAL_REFERER, config.mainUrl) } })
                }
                results
            }.getOrElse { e ->
                logFail(config.id, "JSON Search Execution Failed for '$query': ${e.message}", url = url, method = "search", type = FailureType.NETWORK_FAILURE, selectors = "searchItems")
                emptyList()
            }
        }
        val enoughResults = AtomicBoolean(false)
        return coroutineScope { (1..config.searchPageLimit).map { page -> async {
            if (enoughResults.get()) return@async emptyList<SearchResponse>()
            runCatching {
                val url = config.searchPathPattern.replace("{baseUrl}", baseUrl).replace("{page}", page.toString()).replace("{query}", encodedQuery)
                val document = getHtmlParsed(url, refer)
                val pageResults = if (config.searchItems.isNotBlank()) document.select(config.searchItems).mapNotNull { runCatching { mapper.toSearchResult(it, url) }.getOrNull() } else emptyList()
                if (pageResults.size >= MIN_SEARCH_RESULTS) enoughResults.set(true)
                pageResults
            }.getOrElse { e -> logDebug(config.id, "Search Page $page Error: ${e.message}"); emptyList() }
        } }.awaitAll().flatten().distinctBy { it.url } }
    }

    suspend fun load(url: String): LoadResponse { return loadRecursive(url, 0) }

    private suspend fun loadRecursive(url: String, depth: Int): LoadResponse {
        val document = getHtmlParsed(url)
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
                this.posterHeaders = config.globalHeaders.toMutableMap().apply { put(VAL_REFERER, config.mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }; this.year = metadata.year; this.score = Score.from10(metadata.rating)
                this.recommendations = recommendations; this.comingSoon = metadata.statusText?.let { st -> config.comingSoonKeywords.split(",").any { st.contains(it, true) } } ?: false
                addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) }
        } else {
            val episodes = mapper.extractEpisodes(document, currentUrl, seasonDataScript, epItems, metadata.poster)
            return if (type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie) {
                api.newAnimeLoadResponse(metadata.title, url, type) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = config.globalHeaders.toMutableMap().apply { put(VAL_REFERER, config.mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addEpisodes(DubStatus.Subbed, episodes); addTrailer(metadata.trailer); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()) }
            } else { api.newTvSeriesLoadResponse(metadata.title, url, type, episodes) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = config.globalHeaders.toMutableMap().apply { put(VAL_REFERER, config.mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) } }
        }
    }

    suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val document = getHtmlParsed(data, skipCache = true)
            val currentUrl = data
            val allPossibleLinks = mutableSetOf<Pair<String, String?>>()
            val videoCount = AtomicInteger(0)
            val wrappedCallback: (ExtractorLink) -> Unit = { link -> videoCount.incrementAndGet(); callback(link) }

            collectAjaxPlayers(document, currentUrl, allPossibleLinks)
            collectLinkOptions(document, allPossibleLinks)
            collectDownloadItems(document, allPossibleLinks)
            collectIframes(document, allPossibleLinks)

            if (allPossibleLinks.isEmpty()) {
                logFail(config.id, "No media links or iframes found", url = data, method = "loadLinks", type = FailureType.SELECTOR_FAILURE, selectors = config.linkOptions.ifBlank { "none" })
            }

            coroutineScope {
                allPossibleLinks.filter { it.first.isNotBlank() && !it.first.startsWith("#") }.map { (raw, label) -> async {
                    linkSemaphore.withPermit { processLink(raw, label, currentUrl, subtitleCallback, wrappedCallback) }
                } }.awaitAll()
            }

            logLinkResults(videoCount.get(), allPossibleLinks.size, data)
            true
        }.getOrElse { e ->
            val ft = if (e.message?.contains("cancel", true) == true) FailureType.CANCELLED else FailureType.NETWORK_FAILURE
            logCritical(config.id, "LoadLinks Critical Failure on data: $data", e, url = data, method = "loadLinks", type = ft); false
        }
    }

    private suspend fun collectAjaxPlayers(document: Document, currentUrl: String, links: MutableSet<Pair<String, String?>>) {
        if (config.ajaxPlayerUrl.isBlank() || config.selectorJsonData.isBlank()) return
        val el = document.selectFirst(config.selectorJsonData) ?: return
        runCatching {
            val json = JSONObject(el.data())
            val id = json.optString("id")
            if (id.isNotBlank()) {
                logDebug(config.id, "Fetching AJAX players for ID: $id from ${config.ajaxPlayerUrl}")
                val res = app.post(config.ajaxPlayerUrl, data = mapOf("id" to id), headers = config.globalHeaders, referer = currentUrl).document
                res.select("li, a, option").forEach { item ->
                    val label = item.text().trim()
                    val raw = item.selectAttr(config.attrValue) ?: item.attr("href") ?: ""
                    if (raw.isNotBlank()) links.add(raw to label)
                }
            }
        }.onFailure { e -> logDebug(config.id, "AJAX player collection failed: ${e.message}") }
    }

    private fun collectLinkOptions(document: Document, links: MutableSet<Pair<String, String?>>) {
        if (config.linkOptions.isBlank()) return
        logDebug(config.id, "LINK_OPTIONS selector: ${config.linkOptions}")
        val matches = document.select(config.linkOptions)
        logDebug(config.id, "LINK_OPTIONS => ${matches.size} match(es)")
        matches.forEach { container ->
            val anchors = container.select("a")
            if (anchors.isNotEmpty()) anchors.forEach { a ->
                val link = a.attr("data-url").ifBlank { a.attr("href") }
                links.add(link to a.text())
            }
            else { val raw = container.selectAttr(config.attrValue) ?: container.attr("href") ?: ""; if (raw.isNotBlank()) links.add(raw to container.text()) }
        }
    }

    private fun collectDownloadItems(document: Document, links: MutableSet<Pair<String, String?>>) {
        if (config.downloadItems.isBlank()) return
        val dlMatches = document.select(config.downloadItems)
        logDebug(config.id, "DOWNLOAD_ITEMS selector '${config.downloadItems}' => ${dlMatches.size} match(es)")
        dlMatches.forEach { container ->
            container.select("a").forEach { a -> val href = a.attr("href"); if (href.isNotBlank()) links.add(href to a.text()) }
        }
    }

    private fun collectIframes(document: Document, links: MutableSet<Pair<String, String?>>) {
        val iframeTagMatches = if (config.iframeTag.isNotBlank()) document.select(config.iframeTag) else org.jsoup.select.Elements()
        logDebug(config.id, "iframeTag => ${iframeTagMatches.size} iframe(s)")
        iframeTagMatches.forEach { el ->
            config.iframeSources.forEach { attr -> val s = el.attr(attr); if (s.isNotBlank() && s != "about:blank") links.add(s to null) }
        }
    }

    private suspend fun processLink(raw: String, label: String?, currentUrl: String, subtitleCallback: (SubtitleFile) -> Unit, wrappedCallback: (ExtractorLink) -> Unit) {
        runCatching {
            val decodedRaw = decodeRawLink(raw)
            val fixedUrl = fixUrlSmart(decodedRaw, currentUrl).safeHttpsify().substringBefore("#")
            if (fixedUrl.isBlank()) return@runCatching

            logDebug(config.id, "Processing link: $fixedUrl (label: $label)")

            val okDirect = runCatching { loadExtractorWithFallbackCustom(fixedUrl, currentUrl, subtitleCallback, headers = config.globalHeaders, callback = wrappedCallback, providerTag = config.id) }.getOrDefault(false)
            if (!okDirect) {
                if (ProviderExtractors.hasMatchingExtractor(fixedUrl)) {
                    logDebug(config.id, "Skipping manual iframe fetch: extractor already tried for $fixedUrl")
                    return@runCatching
                }
                tryManualIframeFetch(fixedUrl, label, currentUrl, subtitleCallback, wrappedCallback)
            }
        }.getOrElse { e -> logDebug(config.id, "Link Processor Error on $raw: ${e.message}") }
    }

    private suspend fun decodeRawLink(raw: String): String {
        if (raw.startsWith("http") || raw.startsWith("//") || raw.startsWith("/") || !raw.safeIsBase64()) return raw
        val lk21 = decryptLk21PlayerUrl(raw)
        if (lk21 != null) return lk21
        val dec = raw.safeDecode()
        if (dec.contains("iframe")) return Jsoup.parse(dec).selectFirst("iframe")?.attr("src") ?: ""
        if (dec.startsWith("http") || dec.startsWith("//") || dec.startsWith("/")) return dec
        return ""
    }

    private suspend fun tryManualIframeFetch(fixedUrl: String, label: String?, currentUrl: String, subtitleCallback: (SubtitleFile) -> Unit, wrappedCallback: (ExtractorLink) -> Unit) {
        val refererForPlayer = if (config.refererPlayerMode == "series_url") "${config.seriesUrl ?: config.mainUrl}/" else currentUrl
        logDebug(config.id, "Direct extraction failed, trying manual iframe fetch for: $fixedUrl (Referer: $refererForPlayer)")

        val playerDoc = app.get(fixedUrl, referer = refererForPlayer, headers = config.globalHeaders).document
        val iframeSelectors = config.iframeSelectors
        val iframeAttributes = config.iframeSources

        logDebug(config.id, "Manual iframe: selectors=$iframeSelectors, attrs=$iframeAttributes")

        val iframeEl = if (iframeSelectors.isNotBlank()) playerDoc.selectFirst(iframeSelectors) else null
        if (iframeEl == null) {
            logFail(config.id, "No iframe found", url = currentUrl, method = "loadLinks", type = FailureType.INVALID_IFRAME, selectors = iframeSelectors)
            return
        }

        val iframeSrc = iframeAttributes.firstNotNullOfOrNull { iframeEl.attr(it).takeIf { v -> v.isNotBlank() && v != "about:blank" } }
        if (iframeSrc == null) {
            logFail(config.id, "Iframe has no src", url = currentUrl, method = "loadLinks", type = FailureType.INVALID_IFRAME, selectors = iframeAttributes.joinToString(", "))
            return
        }

        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
        val refererForExtractor = getBaseUrl(fixedUrl)

        logDebug(config.id, "Found iframe: $finalIframe, extracting...")

        val okRecursive = runCatching { loadExtractorWithFallbackCustom(finalIframe, refererForExtractor, subtitleCallback, headers = config.globalHeaders, callback = wrappedCallback, providerTag = config.id) }.getOrDefault(false)
        if (!okRecursive && finalIframe.isDirectMediaUrl()) {
            MasterLinkGenerator.createSmartLink(label ?: config.name, finalIframe, refererForExtractor, headers = config.globalHeaders, callback = wrappedCallback)
        }
    }

    private fun logLinkResults(extracted: Int, totalLinks: Int, data: String) {
        if (extracted > 0) {
            logSuccess(config.id, "$extracted/$totalLinks video(s) extracted", url = data, method = "loadLinks", selectors = config.linkOptions)
        } else if (totalLinks > 0) {
            logFail(config.id, "0/$totalLinks links produced video", url = data, method = "loadLinks", type = FailureType.EXTRACTOR_FAILURE, selectors = config.linkOptions)
        }
    }

    private suspend fun getHtmlParsed(url: String, referer: String? = null, skipCache: Boolean = false): Document {
        if (!skipCache) { globalHtmlCache.get(url)?.let { return it } }
        return executeWithRetry {
            rateLimitDelay(url)
            val res = app.get(url, timeout = DEFAULT_TIMEOUT, headers = config.globalHeaders, referer = referer)
            val doc = if (config.useDocumentLarge) res.documentLarge else res.document
            if (!skipCache) { globalHtmlCache.put(url, doc) }
            doc
        }
    }
}

fun Element.selectAttr(attrNames: List<String>): String? {
    for (name in attrNames) {
        val v = attr(name)
        if (v.isNotBlank() && v != "about:blank") return v
    }
    return null
}
