package com.Donghuastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
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
import com.Donghuastream.DonghuastreamConstants.DEFAULT_TIMEOUT
import com.Donghuastream.DonghuastreamConstants.SEARCH_ITEMS
import com.Donghuastream.DonghuastreamConstants.BLOAT_REGEX
import com.Donghuastream.DonghuastreamConstants.FOLLOW_LINK_SELECTOR
import com.Donghuastream.DonghuastreamConstants.LOAD_RECOMMEND
import com.Donghuastream.DonghuastreamConstants.ACTOR_ITEMS
import com.Donghuastream.DonghuastreamConstants.ACTOR_NAME
import com.Donghuastream.DonghuastreamConstants.ATTR_IMAGE
import com.Donghuastream.DonghuastreamConstants.EPISODE_ITEMS
import com.Donghuastream.DonghuastreamConstants.LINK_OPTIONS
import com.Donghuastream.DonghuastreamConstants.DOWNLOAD_ITEMS
import com.Donghuastream.DonghuastreamConstants.CONFIG_HOOK_REFERER_PLAYER
import com.Donghuastream.DonghuastreamConstants.CONFIG_HOOK_IFRAME_SELECTORS

/**
 * SCRAPING ORCHESTRATOR LAYER
 */

class DonghuastreamScrapper(
    private val api: MainAPI,
    private val providerId: String,
    private val mainUrl: String,
    private val seriesUrl: String,
    private val searchUrl: String,
    private val searchPathPattern: String,
    private val mainPagePathPattern: String,
    private val useDocumentLarge: Boolean,
    private val globalHeaders: Map<String, String>,
    private val isJsonSearch: Boolean,
    private val searchJsonRoot: String,
    private val searchJsonTitle: String,
    private val searchJsonHref: String,
    private val searchJsonPoster: String,
    private val searchJsonPosterPrefix: String,
    private val searchJsonType: String,
    private val searchPageLimit: Int,
    private val seriesKeyword: String,
    private val moviePathSegment: String,
    private val supportedTypes: Set<TvType>,
    private val episodeDataUrlPattern: String,
    private val mapper: DonghuastreamMapper,
    private val name: String
) {

    suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = if (request.name.contains(seriesKeyword, true) && seriesUrl.isNotBlank()) seriesUrl else mainUrl
        val url = if (request.data.startsWith("http")) { 
            val d = request.data.replace("{page}", page.toString())
            val pagePattern = Regex("""(/page/|page=)$page(\b|/|$)""")
            if (!pagePattern.containsMatchIn(d)) { 
                if (d.endsWith("/page/")) "${d}$page" 
                else { val conn = if (d.contains("?")) "&" else "?"; "${d}${conn}page=$page" } 
            } else d
        } else { 
            mainPagePathPattern.replace("{baseUrl}", baseUrl).replace("{data}", request.data).replace("{page}", page.toString()) 
        }

        return runCatching {
            val document = getHtmlParsed(url)
            val isHorizontal = resolveConfig(providerId, DonghuastreamConstants.CONFIG_HOOK_IS_HORIZONTAL, "false").toBoolean() && request.name.contains("Episode Terbaru", true)
            val home = document.selectSafe(providerId, SEARCH_ITEMS, "SEARCH_ITEMS").mapNotNull { runCatching { mapper.toSearchResult(it, url) }.getOrNull() }
            newHomePageResponse(list = HomePageList(name = request.name, list = home, isHorizontalImages = isHorizontal), hasNext = home.isNotEmpty())
        }.getOrElse { e -> 
            logFail(providerId, "MainPage Fetch Failure on ${request.name}: ${e.message}", url = url, method = "getMainPage")
            newHomePageResponse(request.name, emptyList(), false) 
        }
    }

    suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val baseUrl = if (searchUrl.isNotBlank()) searchUrl else mainUrl; val refer = app.get(mainUrl).url
        if (isJsonSearch) { 
            val url = searchPathPattern.replace("{baseUrl}", baseUrl).replace("{query}", encodedQuery).replace("{page}", "1")
            return runCatching {
                val response = app.get(url, referer = refer, headers = globalHeaders).text; val root = JSONObject(response)
                val items = if (searchJsonRoot.isBlank()) root.getJSONArray("results") else root.getJSONArray(searchJsonRoot)
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until items.length()) { val item = items.getJSONObject(i)
                    val title = item.optString(searchJsonTitle).safeCleanBloat(item.optString(searchJsonTitle), BLOAT_REGEX)
                    val slug = item.optString(searchJsonHref); var pUrl = item.optString(searchJsonPoster)
                    if (!pUrl.startsWith("http") && searchJsonPosterPrefix.isNotBlank()) pUrl = searchJsonPosterPrefix + pUrl
                    val isTv = item.optString(searchJsonType).contains("series", true) || item.optString(searchJsonType).contains("tv", true)
                    var finalUrl = if (isTv) "$seriesUrl/$slug" else "$mainUrl/$slug"
                    results.add(api.newAnimeSearchResponse(title, finalUrl, if (isTv) TvType.TvSeries else TvType.Movie) { this.posterUrl = pUrl; this.posterHeaders = globalHeaders.toMutableMap().apply { put(DonghuastreamConstants.VAL_REFERER, mainUrl) } })
                }
                results
            }.getOrElse { e -> 
                logFail(providerId, "JSON Search Execution Failed for '$query': ${e.message}", url = url, method = "search")
                emptyList() 
            }
        }
        return coroutineScope { (1..searchPageLimit).map { page -> async { runCatching { 
                        val url = searchPathPattern.replace("{baseUrl}", baseUrl).replace("{page}", page.toString()).replace("{query}", encodedQuery)
                        val document = getHtmlParsed(url, refer)
                        document.selectSafe(providerId, SEARCH_ITEMS, "SEARCH_ITEMS").mapNotNull { runCatching { mapper.toSearchResult(it, url) }.getOrNull() } }.getOrElse { e -> logDebug(providerId, "Search Page $page Error: ${e.message}"); emptyList() } } }.awaitAll().flatten().distinctBy { it.url } }
    }

    suspend fun load(url: String): LoadResponse { return loadRecursive(url, 0) }

    private suspend fun loadRecursive(url: String, depth: Int): LoadResponse {
        val document = getHtmlParsed(url)
        val currentUrl = url
        if (depth < 2) { 
            val follow = resolveConfigList(providerId, FOLLOW_LINK_SELECTOR)
            if (follow.isNotEmpty()) { val nextAnchor = document.selectFirstSafe(providerId, follow, "FOLLOW_LINK_SELECTOR"); val nextHref = nextAnchor?.attr("href")
                if (!nextHref.isNullOrBlank()) { val nextUrl = fixUrlSmart(nextHref, currentUrl); if (nextUrl != currentUrl && nextUrl != url) return loadRecursive(nextUrl, depth + 1) } } }

        val metadata = mapper.extractMetadata(document, currentUrl)
        
        val (recommendations, actors) = coroutineScope {
            val recs = async { document.selectSafe(providerId, LOAD_RECOMMEND, "LOAD_RECOMMEND").mapNotNull { mapper.toSearchResult(it, currentUrl) } }
            val acts = async { document.selectSafe(providerId, ACTOR_ITEMS, "ACTOR_ITEMS").mapNotNull { 
                val n = it.selectFirstSafe(providerId, ACTOR_NAME, "ACTOR_NAME")?.text()?.trim() ?: ""
                val p = it.selectFirst("img")?.safeExtractImage(ATTR_IMAGE) ?: ""
                if (n.isNotBlank() && n.length < 100) Actor(n, p) else null 
            } }
            recs.await() to acts.await()
        }
        
        val epItems = document.selectSafe(providerId, EPISODE_ITEMS, "EPISODE_ITEMS")
        val seasonDataScript = document.selectFirstSafe(providerId, DonghuastreamConstants.SELECTOR_SEASON_CONTAINER, "SELECTOR_SEASON_CONTAINER")
        val isMovie = (seasonDataScript == null) && ((moviePathSegment.isNotBlank() && currentUrl.contains(moviePathSegment)) || epItems.isEmpty())
        val type = if (isMovie) TvType.Movie else if (supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
        val tracker = runCatching { APIHolder.getTracker(listOf(metadata.title), TrackerType.getTypes(type), metadata.year, true) }.getOrElse { e -> logDebug(providerId, "Tracker Fetch Warning: ${e.message}"); null }

        if (isMovie) {
            val watchUrl = fixUrlSmart(document.selectFirstSafe(providerId, DonghuastreamConstants.SELECTOR_WATCH_BUTTONS, "SELECTOR_WATCH_BUTTONS")?.attr("href"), currentUrl).ifBlank { currentUrl }
            return api.newMovieLoadResponse(metadata.title, url, type, episodeDataUrlPattern.replace("{url}", watchUrl)) { 
                this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner
                this.posterHeaders = globalHeaders.toMutableMap().apply { put(DonghuastreamConstants.VAL_REFERER, mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }; this.year = metadata.year; this.score = Score.from10(metadata.rating)
                this.recommendations = recommendations; this.comingSoon = metadata.statusText?.let { st -> resolveConfig(providerId, DonghuastreamConstants.STR_COMING_SOON, "").split(",").any { st.contains(it, true) } } ?: false
                addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) }
        } else { 
            val episodes = mapper.extractEpisodes(document, currentUrl, seasonDataScript, epItems, metadata.poster)
            return if (type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie) {
                api.newAnimeLoadResponse(metadata.title, url, type) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = globalHeaders.toMutableMap().apply { put(DonghuastreamConstants.VAL_REFERER, mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addEpisodes(DubStatus.Subbed, episodes); addTrailer(metadata.trailer); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()) }
            } else { api.newTvSeriesLoadResponse(metadata.title, url, type, episodes) { this.posterUrl = tracker?.image ?: metadata.poster; this.backgroundPosterUrl = tracker?.cover ?: metadata.banner; this.posterHeaders = globalHeaders.toMutableMap().apply { put(DonghuastreamConstants.VAL_REFERER, mainUrl) }; this.plot = metadata.description; this.tags = metadata.tags.ifEmpty { null }
                    this.year = metadata.year; this.score = Score.from10(metadata.rating); this.recommendations = recommendations; this.showStatus = metadata.status; addTrailer(metadata.trailer); addActors(actors); addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull()); addImdbId(metadata.imdbId); addTMDbId(metadata.tmdbId?.toString()) } }
        }
    }

    suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val document = getHtmlParsed(data, skipCache = true)
            val currentUrl = data
            val attrValueSelectors = resolveConfigList(providerId, DonghuastreamConstants.ATTR_VALUE)
            val allPossibleLinks = mutableSetOf<Pair<String, String?>>()

            // 1. AJAX PLAYER FETCHING (NEW - For LK21 & Similar)
            val ajaxUrl = resolveConfig(providerId, DonghuastreamConstants.CONFIG_AJAX_PLAYER_URLS, "")
            val jsonDataSelector = resolveConfigList(providerId, DonghuastreamConstants.SELECTOR_JSON_DATA)
            if (ajaxUrl.isNotBlank() && jsonDataSelector.isNotEmpty()) {
                jsonDataSelector.asSequence().mapNotNull { document.selectFirst(it) }.firstOrNull()?.let { el ->
                    runCatching {
                        val json = JSONObject(el.data())
                        val id = json.optString("id")
                        if (id.isNotBlank()) {
                            logDebug(providerId, "Fetching AJAX players for ID: $id from $ajaxUrl")
                            val res = app.post(ajaxUrl, data = mapOf("id" to id), headers = globalHeaders, referer = currentUrl).document
                            res.select("li, a, option").forEach { item ->
                                val label = item.text().trim()
                                val raw = item.attrSafe(providerId, attrValueSelectors, "ATTR_VALUE") ?: item.attr("href") ?: ""
                                if (raw.isNotBlank()) allPossibleLinks.add(raw to label)
                            }
                        }
                    }
                }
            }

            // 2. AGGRESSIVE GATHERING V12
            resolveConfigList(providerId, LINK_OPTIONS).forEach { selector ->
                document.select(selector).forEach { container ->
                    val anchors = container.select("a")
                    if (anchors.isNotEmpty()) anchors.forEach { a -> 
                        val link = a.attr("data-url").ifBlank { a.attr("href") }
                        allPossibleLinks.add(link to a.text()) 
                    }
                    else { val raw = container.attrSafe(providerId, attrValueSelectors, "ATTR_VALUE") ?: container.attr("href") ?: ""; if (raw.isNotBlank()) allPossibleLinks.add(raw to container.text()) }
                }
            }

            resolveConfigList(providerId, DOWNLOAD_ITEMS).forEach { selector ->
                document.select(selector).forEach { container ->
                    container.select("a").forEach { a -> val href = a.attr("href"); if (href.isNotBlank()) allPossibleLinks.add(href to a.text()) }
                }
            }

            document.selectSafe(providerId, DonghuastreamConstants.SELECTOR_IFRAME_TAG, "SELECTOR_IFRAME_TAG").forEach { el ->
                resolveConfigList(providerId, DonghuastreamConstants.ATTR_IFRAME_SOURCES).forEach { attr -> val s = el.attr(attr); if (s.isNotBlank()) allPossibleLinks.add(s to null) }
            }

            if (allPossibleLinks.isEmpty()) {
                logFail(providerId, "No media links or iframes found for: $data", url = data, method = "loadLinks")
            }

            coroutineScope {
                allPossibleLinks.filter { it.first.isNotBlank() }.map { (raw, label) -> async { runCatching {
                    val decodedRaw = if (!raw.startsWith("http") && !raw.startsWith("//") && !raw.startsWith("/") && raw.safeIsBase64()) {
                        val lk21 = decryptLk21PlayerUrl(raw)
                        if (lk21 != null) lk21
                        else {
                            val dec = raw.safeDecode()
                            if (dec.contains("iframe")) Jsoup.parse(dec).selectFirst("iframe")?.attr("src") ?: raw
                            else if (dec.startsWith("http") || dec.startsWith("//") || dec.startsWith("/")) dec else raw
                        }
                    } else raw

                    val fixedUrl = fixUrlSmart(decodedRaw, currentUrl).safeHttpsify().unpackPacked()
                    if (fixedUrl.isBlank()) return@runCatching

                    logDebug(providerId, "Processing link: $fixedUrl (label: $label)")

                    val okDirect = runCatching { loadExtractorWithFallbackCustom(fixedUrl, currentUrl, subtitleCallback, headers = globalHeaders, callback = callback, providerTag = providerId) }.getOrDefault(false)
                    if (!okDirect) {
                        val refererMode = resolveConfig(providerId, CONFIG_HOOK_REFERER_PLAYER, DonghuastreamConstants.STR_REFERER_MODE_CURRENT)
                        val refererForPlayer = if (refererMode == DonghuastreamConstants.STR_REFERER_MODE_SERIES) "$seriesUrl/" else currentUrl
                        
                        logDebug(providerId, "Direct extraction failed, trying manual iframe fetch for: $fixedUrl (Referer: $refererForPlayer)")
                        
                        val playerDoc = app.get(fixedUrl, referer = refererForPlayer, headers = globalHeaders).document
                        val iframeSelectors = resolveConfigList(providerId, CONFIG_HOOK_IFRAME_SELECTORS)
                        val iframeAttributes = resolveConfigList(providerId, DonghuastreamConstants.ATTR_IFRAME_SOURCES)
                        
                        val iframeEl = iframeSelectors.asSequence().mapNotNull { playerDoc.selectFirst(it) }.firstOrNull()
                        if (iframeEl == null) {
                            logDebug(providerId, "No iframe found on player page: $fixedUrl")
                            return@runCatching
                        }
                        
                        val iframeSrc = iframeAttributes.asSequence().mapNotNull { iframeEl.attr(it).ifBlank { null } }.firstOrNull() ?: return@runCatching
                        
                        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
                        val refererForExtractor = getBaseUrl(fixedUrl)
                        
                        logDebug(providerId, "Found iframe: $finalIframe, extracting...")
                        
                        val okRecursive = runCatching { loadExtractorWithFallbackCustom(finalIframe, refererForExtractor, subtitleCallback, headers = globalHeaders, callback = callback, providerTag = providerId) }.getOrDefault(false)
                        if (!okRecursive && (finalIframe.contains(".mp4") || finalIframe.contains(".m3u8") || finalIframe.contains(".mkv") || finalIframe.contains(".mpd"))) {
                            MasterLinkGenerator.createSmartLink(label ?: name, finalIframe, refererForExtractor, headers = globalHeaders, callback = callback)
                        }
                    }
                }.getOrElse { e -> logDebug(providerId, "Link Processor Error on $raw: ${e.message}") } } }.awaitAll()
            }
            true
        }.getOrElse { e -> logCritical(providerId, "LoadLinks Critical Failure on data: $data", e, url = data, method = "loadLinks"); false }
    }

    private suspend fun getHtmlParsed(url: String, referer: String? = null, skipCache: Boolean = false): Document {
        if (!skipCache) { globalHtmlCache.get(url)?.let { return it } }
        return executeWithRetry { 
            rateLimitDelay(url)
            val res = app.get(url, timeout = DEFAULT_TIMEOUT, headers = globalHeaders, referer = referer)
            val doc = if (useDocumentLarge) res.documentLarge else res.document
            if (!skipCache) { globalHtmlCache.put(url, doc) }
            doc
        }
    }
}
