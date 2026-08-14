package com.baseprovider.core

import com.baseprovider.cache.PrefetchCache
import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

class BaseProviderEngine(
    api: MainAPI,
    private val config: ProviderConfig
) {
    private val mapper = ProviderMapper(api = api, config = config)
    private val scrapper = ProviderScrapper(
        api = api,
        config = config,
        mapper = mapper
    )
    private val detailScrapper = DetailPageScrapper(
        api = api,
        config = config,
        mapper = mapper
    )
    private val prefetchCache = PrefetchCache(
        ttlMs = config.prefetchTtlMinutes * 60_000L
    )
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchSemaphore = Semaphore(3)
    // User-play hanya menunggu prefetch in-flight sampai batas ini; sisanya
    // lanjut race first-video sendiri supaya tombol play tidak nge-spin lama.
    private val USER_AWAIT_PREFETCH_MS = 4000L

    suspend fun getMainPage(page: Int,
        request: MainPageRequest): HomePageResponse {
        val home = scrapper.getMainPage(page, request)
        if (config.prefetchEnabled) {
            prefetchHomeItems(home)
        }
        return home
    }

    suspend fun search(query: String): List<SearchResponse> {
        val results = scrapper.search(query)
        if (config.prefetchEnabled) {
            prefetchSearchItems(results)
        }
        return results
    }

    suspend fun load(url: String): LoadResponse {
        if (!config.prefetchEnabled) return detailScrapper.load(url)
        val response = prefetchCache.getOrLoad(url) {
            detailScrapper.load(url)
        }
        prefetchEpisodeLinks(response)
        return response
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!config.prefetchEnabled) {
            return scrapper.loadLinks(data, isCasting, subtitleCallback, callback)
        }
        // 1) Cache hit → instan, tanpa network.
        prefetchCache.getLinks(data)?.let { cached ->
            cached.subtitles.forEach(subtitleCallback)
            cached.links.forEach(callback)
            return true
        }
        // 2) Ada prefetch in-flight untuk data ini: tunggu SEBENTAR (race
        //    link-pertama biasanya cepat). Jika timeout, jangan menunggu
        //    prefetch penuh — lanjut jalur user sendiri.
        if (prefetchCache.isLinksLoading(data)) {
            val res = prefetchCache.awaitLinks(data, USER_AWAIT_PREFETCH_MS)
            if (res != null) {
                val (ok, cached) = res
                if (ok) {
                    cached.subtitles.forEach(subtitleCallback)
                    cached.links.forEach(callback)
                    return true
                }
            }
        }
        // 3) User path: race first-video (waitForAll=false) supaya player cepat.
        //    Sekaligus warm full-cache di background untuk play berikutnya.
        val userOk = scrapper.loadLinks(data, isCasting, subtitleCallback, callback)
        ensureLinksCached(data)
        return userOk
    }

    /**
     * Warm full-cache links untuk [data] di background (waitForAll=true) —
     * in-flight guard di PrefetchCache mencegah double-fetch jika prefetch
     * episode juga berjalan.
     */
    private fun ensureLinksCached(data: String) {
        if (prefetchCache.isLinksFresh(data) || prefetchCache.isLinksLoading(data)) return
        prefetchScope.launch {
            prefetchSemaphore.withPermit {
                runCatching {
                    val (_, cached) = prefetchCache.getOrLoadLinks(data) {
                        val subtitles = mutableListOf<SubtitleFile>()
                        val links = mutableListOf<ExtractorLink>()
                        val success = scrapper.loadLinks(
                            data,
                            false,
                            { subtitles.add(it) },
                            { links.add(it) },
                            waitForAll = true
                        )
                        success to PrefetchCache.CachedLinks(subtitles, links)
                    }
                    warmPlayerConnections(cached.links)
                }
            }
        }
    }

    /**
     * Saat home list tampil, warm episode page (LoadResponse) untuk item yang
     * terlihat. Update check berbasis TTL: item yang cache-nya masih fresh
     * di-skip (terus pakai cache), yang expired di-fetch ulang di background.
     */
    private fun prefetchHomeItems(home: HomePageResponse) {
        prefetchItemUrls(home.items.flatMap { it.list }
            .mapNotNull { it.url.takeIf { u -> u.isNotBlank() } })
    }

    /**
     * Saat hasil search tampil, warm episode page untuk hasil teratas dengan
     * pola yang sama seperti home list → klik hasil search jadi instan.
     */
    private fun prefetchSearchItems(results: List<SearchResponse>) {
        prefetchItemUrls(results.mapNotNull { it.url.takeIf { u -> u.isNotBlank() } })
    }

    private fun prefetchItemUrls(urls: List<String>) {
        urls.distinct()
            .take(config.prefetchHomeLimit)
            .forEach { url ->
                if (prefetchCache.isLoadFresh(url)) return@forEach
                prefetchScope.launch {
                    prefetchSemaphore.withPermit {
                        runCatching {
                            prefetchCache.getOrLoad(url) { detailScrapper.load(url) }
                        }
                    }
                }
            }
    }

    /**
     * Saat episode page dibuka, extract link video player untuk episode yang
     * muncul (di background, non-blocking). Ketika user play, loadLinks() sudah
     * menemukan hasil di cache → instan.
     */
    private fun prefetchEpisodeLinks(response: LoadResponse) {
        val episodes = episodesOf(response).take(config.prefetchEpisodeLimit)
        episodes.forEach { ep ->
            val data = ep.data?.takeIf { it.isNotBlank() } ?: return@forEach
            ensureLinksCached(data)
        }
    }

    /**
     * Warm koneksi ke host player yang sudah diketahui dari hasil ekstraksi
     * links — TCP+TLS (via app.get) disiapkan di background sehingga saat
     * user play, handshake ke host player sudah selesai.
     */
    private fun warmPlayerConnections(links: List<ExtractorLink>) {
        val hosts = links.mapNotNull { runCatching { URI(it.url).host }
            .getOrNull() }.filter { it.isNotBlank() }.distinct().take(3)
        hosts.forEach { host ->
            prefetchScope.launch {
                prefetchSemaphore.withPermit {
                    runCatching {
                        app.get("https://$host/", timeout = 3000L)
                    }
                }
            }
        }
    }

    private fun episodesOf(response: LoadResponse): List<Episode> = when (response) {
        is TvSeriesLoadResponse -> response.episodes
        is AnimeLoadResponse -> response.episodes.values.flatten()
        else -> emptyList()
    }
}