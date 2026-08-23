package com.baseprovider.core

import com.baseprovider.cache.ExpiringCache
import com.baseprovider.collector.*
import com.baseprovider.config.*
import com.baseprovider.log.*
import com.baseprovider.model.*
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProviderScrapper(
    private val api: MainAPI,
    private val config: ProviderConfig,
    private val mapper: ProviderMapper
) {
    // Semaphore untuk ekstraksi paralel link (banyak sumber diproses
    // bersamaan, tiap link dibatasi PER_LINK_TIMEOUT_MS oleh FallbackPipeline).
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
                val elements = SelectorResolver.select(document, config.searchItems,
                    "${config.id}:searchItems")
                // Mapping paralel via async/await: tiap item diproses independen,
                // hasil digabung HANYA setelah SEMUA selesai (awaitAll) → list
                // home selalu lengkap, tidak pernah setengah-setengah.
                coroutineScope {
                    elements.map { el ->
                        async(Dispatchers.IO) {
                            runCatching { mapper.toSearchResult(el, url) }.getOrNull()
                        }
                    }.awaitAll().filterNotNull().distinctBy { it.url }
                }
            } else emptyList()
            newHomePageResponse(list = HomePageList(name = request.name,
                list = home, isHorizontalImages = isHorizontal), hasNext =
                    home.isNotEmpty())
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
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

    suspend fun search(query: String, page: Int = 1): List<SearchResponse> {
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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
            val url = config.searchPathPattern.replace("{baseUrl}",
                baseUrl).replace("{page}", page.toString()).replace("{query}", encodedQuery)
            val document = fetchDocument(url, config, refer, htmlCache =
                htmlCache)
            if (config.searchItems.isNotBlank()) {
                val elements = SelectorResolver.select(document, config.searchItems,
                    "${config.id}:searchItems")
                logDebug(config.id, "search[$query] page=$page -> ${elements.size} item mentah")
                coroutineScope {
                    elements.map { el ->
                        async(Dispatchers.IO) {
                            runCatching { mapper
                                .toSearchResult(el, url) }.getOrNull()
                        }
                    }.awaitAll().filterNotNull().distinctBy { it.url }
                }
            } else emptyList()
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
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

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val runId = SupabaseObservability.beginRun(
            sourceCode = config.id,
            sourceName = config.name,
            sourceMainUrl = config.mainUrl,
            context = "EPISODE",
            triggeredBy = "user_play",
            startUrl = data
        )
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            // Defense-in-depth: URL listing/kategori tidak pernah punya player.
            // Blokir di sini juga menangkap item basi dari cache/riwayat yang
            // lolos sebelum guard mapper ada (kasus /drama/, /action/).
            if (mapper.isListingUrl(data)) {
                logFail(
                    config.id,
                    "loadLinks rejected listing/category URL: $data",
                    url = data,
                    method = "loadLinks",
                    type = FailureType.INVALID_URL,
                    stage = "COLLECT",
                    runId = runId
                )
                return@runCatching false
            }
            val document = fetchDocument(data, config,
                referer = config.mainUrl, skipCache = false,
                htmlCache = htmlCache)
            val currentUrl = data
            val allPossibleLinks = java.util.Collections
                .synchronizedSet(mutableSetOf<Pair<String, String?>>())
            val videoCount = AtomicInteger(0)
            val wrappedCallback: (ExtractorLink) -> Unit =
                { link -> videoCount.incrementAndGet(); callback(link) }

            // M1: collectAjaxPlayers (network POST) dijalankan PARALEL dengan
            // kolektor DOM (cepat) — bukan serial. Hasil AJAX digabung saat siap.
            coroutineScope {
                val ajaxCollect = async(Dispatchers.IO) {
                    linkCollector.collectAjaxPlayers(document, currentUrl,
                        allPossibleLinks)
                }
                linkCollector.collectLinkOptions(document, allPossibleLinks)
                linkCollector.collectDownloadItems(document, allPossibleLinks)
                linkCollector.collectSwitchVideoButtons(document, currentUrl,
                    allPossibleLinks)
                linkCollector.collectIframes(document, allPossibleLinks)
                ajaxCollect.await()
            }

            logSuccess(config.id,
                "loadLinks candidates=${allPossibleLinks.size} " +
                    "(linkOptions/iframes/downloads/switch/ajax)")
            if (allPossibleLinks.isEmpty()) {
                logFail(
                    config.id,
                    "No media links or iframes found",
                    url = data,
                    method = "loadLinks",
                    type = FailureType.SELECTOR_FAILURE,
                    selectors = config.linkOptions.ifBlank { "none" },
                    stage = "COLLECT",
                    runId = runId
                )
                return@runCatching false
            }

            SupabaseObservability.logCollectStep(runId, allPossibleLinks.size,
                durationMs = System.currentTimeMillis() - startedAt)

            val pendingLinks = allPossibleLinks
                .filter { it.first.isNotBlank() && !it.first.startsWith("#") }
                .sortedByDescending { priorityOf(it.first) }

            // Collect-all: tunggu SEMUA ekstraktor selesai, lalu semua sumber
            // dikumpulkan sekaligus ke player. ExoPlayer hanya mengambil sumber
            // sekali — link yang datang setelah loadLinks return tidak muncul,
            // jadi daftar sumber harus lengkap sebelum video diputar.
            // Tiap link dibatasi PER_LINK_TIMEOUT_MS (20s) oleh FallbackPipeline,
            // sehingga wait-all tidak menggantung tanpa batas.
            // B4: scope dibuat per-run dan job run sebelumnya di-cancel saat
            // loadLinks baru dimulai (ekstraksi basi tidak membuang resource).
            // Deferreds run lama ikut diselesaikan agar call loadLinks yang
            // ditinggalkan user tidak menggantung di await.
            activeRace.getAndSet(null)?.let { prev ->
                prev.supervisor.cancel()
                prev.allDone.complete(Unit)
            }
            val raceSupervisor = SupervisorJob()
            val runScope = CoroutineScope(raceSupervisor + Dispatchers.IO)
            val allDone = CompletableDeferred<Unit>()
            val jobs = pendingLinks.map { (raw, label) ->
                runScope.launch {
                    runCatching {
                        linkSemaphore.withPermit { fallbackPipeline
                            .processLink(raw, label, currentUrl,
                                subtitleCallback, wrappedCallback, runId) }
                    }
                }
            }
            runScope.launch {
                jobs.forEach { it.join() }
                allDone.complete(Unit)
                fallbackPipeline.logLinkResults(videoCount.get(),
                    pendingLinks.size, data)
            }
            activeRace.set(ActiveRace(raceSupervisor, allDone))

            // Tunggu SEMUA job selesai; hasil = ada video yang ditemukan.
            allDone.await()
            videoCount.get() > 0
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) {
                SupabaseObservability.endRun(
                    runId,
                    status = "failed",
                    durationMs = System.currentTimeMillis() - startedAt
                )
                throw e
            }
            val ft = if (e.message?.contains("cancel", true) ==
                true) FailureType.CANCELLED else FailureType
                    .NETWORK_FAILURE
            logCritical(config.id, "LoadLinks Critical Failure on data: $data", e, url = data, method = "loadLinks", type = ft, runId = runId); false
        }
        SupabaseObservability.endRun(
            runId,
            status = if (result) "success" else "failed",
            durationMs = System.currentTimeMillis() - startedAt
        )
        return result
    }

    /**
     * Prioritas ekstraksi: urutkan link agar yang paling mungkin menghasilkan
     * video diekstrak lebih dulu → link pertama cepat sampai ke player.
     */
    private fun priorityOf(raw: String): Int {
        val u = raw.lowercase()
        return when {
            u.contains(".m3u8") || u.contains(".mpd") || u.contains("master") ||
                u.contains("playlist") || u.contains(".ts") -> 100
            u.contains(".mp4") || u.contains(".webm") || u.contains(".mkv") ||
                u.contains(".mov") -> 90
            u.contains("anichin.stream") || u.contains("abyssplayer") ||
                u.contains("gdriveplayer") || u.contains("sibnet") ||
                u.contains("dailymotion") -> 80
            u.contains("youtube") || u.contains("ok.ru") ||
                u.contains("rumble") || u.contains("vimeo") -> 70
            u.contains("short.") -> 20
            else -> 50
        }
    }

    /**
     * State race ekstraksi per-run (B4). Run sebelumnya di-cancel saat
     * loadLinks baru dimulai; [allDone] diselesaikan agar call yang
     * ditinggalkan tidak menggantung. Hanya SATU race aktif per instance
     * pada satu waktu.
     */
    private class ActiveRace(
        val supervisor: Job,
        val allDone: CompletableDeferred<Unit>
    )

    private val activeRace = AtomicReference<ActiveRace?>(null)
}
