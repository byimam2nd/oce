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

class BaseProviderEngine(
    api: MainAPI,
    config: ProviderConfig
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

    suspend fun getMainPage(page: Int,
        request: MainPageRequest): HomePageResponse {
        val home = scrapper.getMainPage(page, request)
        if (config.prefetchEnabled) {
            prefetchHomeItems(home)
        }
        return home
    }

    suspend fun search(query: String): List<SearchResponse> =
        scrapper.search(query)

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
        val (ok, cached) = prefetchCache.getOrLoadLinks(data) {
            val subtitles = mutableListOf<SubtitleFile>()
            val links = mutableListOf<ExtractorLink>()
            val success = scrapper.loadLinks(
                data,
                isCasting,
                { subtitles.add(it) },
                { links.add(it) }
            )
            success to PrefetchCache.CachedLinks(subtitles, links)
        }
        if (ok) {
            cached.subtitles.forEach(subtitleCallback)
            cached.links.forEach(callback)
        }
        return ok
    }

    /**
     * Saat home list tampil, warm episode page (LoadResponse) untuk item yang
     * terlihat. Update check berbasis TTL: item yang cache-nya masih fresh
     * di-skip (terus pakai cache), yang expired di-fetch ulang di background.
     */
    private fun prefetchHomeItems(home: HomePageResponse) {
        val items = home.list.flatMap { it.list }
            .mapNotNull { it.url.takeIf { u -> u.isNotBlank() } }
            .distinct()
            .take(config.prefetchHomeLimit)
        items.forEach { url ->
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
            if (prefetchCache.isLinksFresh(data)) return@forEach
            prefetchScope.launch {
                prefetchSemaphore.withPermit {
                    runCatching {
                        prefetchCache.getOrLoadLinks(data) {
                            val subtitles = mutableListOf<SubtitleFile>()
                            val links = mutableListOf<ExtractorLink>()
                            val success = scrapper.loadLinks(
                                data,
                                false,
                                { subtitles.add(it) },
                                { links.add(it) }
                            )
                            success to PrefetchCache.CachedLinks(subtitles, links)
                        }
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