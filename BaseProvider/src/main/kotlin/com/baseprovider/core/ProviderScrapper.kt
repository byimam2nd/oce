package com.baseprovider.core

import com.baseprovider.cache.ExpiringCache
import com.baseprovider.collector.*
import com.baseprovider.config.*
import com.baseprovider.log.*
import com.baseprovider.model.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.concurrent.atomic.AtomicInteger

private const val MIN_SEARCH_RESULTS = 20

class ProviderScrapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
    private val mapper: ProviderMapper
) {
    private val linkSemaphore = Semaphore(5)
    private val htmlCache = ExpiringCache<Document>(5 * 60 * 1000L)
    private val linkCollector = LinkCollector(config)
    private val fallbackPipeline = FallbackPipeline(config)

    suspend fun getMainPage(page: Int,
        request: MainPageRequest): HomePageResponse {
        val baseUrl = if (request.name.contains(config.seriesKeyword,
            true)) {
            config.seriesUrl?.takeIf { it.isNotBlank() } ?: config.mainUrl
        } else {
            config.mainUrl
        }
        val url = if (request.data.startsWith("http")) {
            val d = request.data.replace("{page}", page.toString())
            val pagePattern = Regex("""(/page/|page=)$page(\b|/|$)""")
            if (!pagePattern.containsMatchIn(d)) {
                if (d.endsWith("/page/")) "${d}$page"
                else { val conn = if (d.contains("?")) "&" else "?"; "${d}${conn}page=$page" }
            } else d
        } else {
            config.mainPagePathPattern.replace("{baseUrl}", baseUrl)
                .replace("{data}", request.data).replace("{page}", page
                    .toString())
        }

        return runCatching {
            val document = fetchDocument(url, config, htmlCache =
                htmlCache)
            val isHorizontal = config.isHorizontal
            val home = if (config.searchItems.isNotBlank()) {
                document.select(config.searchItems)
                    .mapNotNull { runCatching { mapper.toSearchResult(it,
                        url) }.getOrNull() }
                    .distinctBy { it.url }
            } else emptyList()
            newHomePageResponse(list = HomePageList(name = request.name,
                list = home, isHorizontalImages = isHorizontal), hasNext =
                    home.isNotEmpty())
        }.getOrElse { e ->
            logFail(
                config.id,
                "MainPage Fetch Failure on ${request.name}: ${e.message}",
                url = url,
                method = "getMainPage",
                type = FailureType.NETWORK_FAILURE,
                selectors = "searchItems"
            )
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val baseUrl = config.searchUrl?.takeIf { it
            .isNotBlank() } ?: config.mainUrl
        val refer = config.mainUrl
        if (config.isJsonSearch) {
            val url = config.searchPathPattern.replace("{baseUrl}",
                baseUrl).replace("{query}", encodedQuery).replace("{page}", "1")
            return runCatching {
                val response = app.get(url, referer = refer, headers =
                    config.globalHeaders).text; val root =
                        JSONObject(response)
                val items = root.getJSONArray(config.searchJsonRoot
                    .ifBlank { "data" })
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until items.length()) { val item = items
                    .getJSONObject(i)
                    val title = item.optString(config.searchJsonTitle)
                        .safeCleanBloat(item.optString(config
                            .searchJsonTitle), config.bloatRegex)
                    val slug = item.optString(config
                        .searchJsonHref); var pUrl = item.optString(config
                            .searchJsonPoster)
                    if (!pUrl.startsWith("http") && config
                        .searchJsonPosterPrefix.isNotBlank()) pUrl = config
                            .searchJsonPosterPrefix + pUrl
                    val isTv = item.optString(config.searchJsonType)
                        .contains("series", true)
                        || item.optString(config.searchJsonType).contains("tv", true)
                    var finalUrl = if (isTv) "${config.seriesUrl ?: baseUrl}/$slug" else "${config.mainUrl}/$slug"
                    results.add(
                        api.newAnimeSearchResponse(
                            title,
                            finalUrl,
                            if (isTv) TvType.TvSeries else TvType.Movie
                        ) {
                            this.posterUrl = pUrl
                            this.posterHeaders = config.globalHeaders
                                .toMutableMap()
                                .apply { put("Referer", config.mainUrl) }
                        }
                    )
                }
                results
            }.getOrElse { e ->
                logFail(
                    config.id,
                    "JSON Search Execution Failed for '$query': ${e.message}",
                    url = url,
                    method = "search",
                    type = FailureType.NETWORK_FAILURE,
                    selectors = "searchItems"
                )
                emptyList()
            }
        }
        return runCatching {
            val results = mutableListOf<SearchResponse>()
            for (page in 1..config.searchPageLimit) {
                if (results.size >= MIN_SEARCH_RESULTS) break
                val url = config.searchPathPattern.replace("{baseUrl}",
                    baseUrl).replace("{page}", page.toString()).replace("{query}", encodedQuery)
                val document = fetchDocument(url, config, refer, htmlCache =
                    htmlCache)
                val pageResults = if (config.searchItems.isNotBlank()) {
                    document.select(config.searchItems)
                        .mapNotNull { runCatching { mapper
                            .toSearchResult(it, url) }.getOrNull() }
                } else emptyList()
                results.addAll(pageResults)
            }
            results.distinctBy { it.url }
        }.getOrElse { e ->
            logFail(
                config.id,
                "Search Execution Failed for '$query': ${e.message}",
                url = baseUrl,
                method = "search",
                type = FailureType.NETWORK_FAILURE,
                selectors = "searchItems"
            )
            emptyList()
        }
    }

    suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val document = fetchDocument(data, config,
                referer = config.mainUrl, skipCache = true,
                htmlCache = htmlCache)
            val currentUrl = data
            val allPossibleLinks = mutableSetOf<Pair<String, String?>>()
            val videoCount = AtomicInteger(0)
            val wrappedCallback: (ExtractorLink) -> Unit =
                { link -> videoCount.incrementAndGet(); callback(link) }

            linkCollector.collectAjaxPlayers(document, currentUrl,
                allPossibleLinks)
            linkCollector.collectLinkOptions(document, allPossibleLinks)
            linkCollector.collectDownloadItems(document, allPossibleLinks)
            linkCollector.collectSwitchVideoButtons(document, currentUrl,
                allPossibleLinks)
            linkCollector.collectIframes(document, allPossibleLinks)

            if (allPossibleLinks.isEmpty()) {
                logFail(
                    config.id,
                    "No media links or iframes found",
                    url = data,
                    method = "loadLinks",
                    type = FailureType.SELECTOR_FAILURE,
                    selectors = config.linkOptions.ifBlank { "none" }
                )
            }

            coroutineScope {
                allPossibleLinks.filter { it.first.isNotBlank() && !it
                    .first.startsWith("#") }.map { (raw, label) -> async {
                    linkSemaphore.withPermit { fallbackPipeline
                        .processLink(raw, label, currentUrl,
                            subtitleCallback, wrappedCallback) }
                } }.awaitAll()
            }

            fallbackPipeline.logLinkResults(videoCount.get(),
                allPossibleLinks.size, data)
            true
        }.getOrElse { e ->
            val ft = if (e.message?.contains("cancel", true) ==
                true) FailureType.CANCELLED else FailureType
                    .NETWORK_FAILURE
            logCritical(config.id, "LoadLinks Critical Failure on data: $data", e, url = data, method = "loadLinks", type = ft); false
        }
    }
}
