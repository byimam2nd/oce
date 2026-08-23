package com.baseprovider.core

import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * Aturan `hasNext` search: hanya provider HTML search dengan `{page}` di
 * pattern yang bisa di-paginate. JSON search selalu one-shot, dan provider
 * tanpa `{page}` akan mengembalikan halaman yang sama → stop agar tidak
 * infinite scroll.
 */
internal fun hasNextSearchPage(
    config: ProviderConfig,
    results: List<SearchResponse>
): Boolean =
    !config.isJsonSearch && config.searchPathPattern.contains("{page}") && results.isNotEmpty()

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

    suspend fun getMainPage(page: Int,
        request: MainPageRequest): HomePageResponse {
        return scrapper.getMainPage(page, request)
    }

    suspend fun search(query: String): List<SearchResponse> {
        return scrapper.search(query)
    }

    /**
     * Paginated search untuk infinite scroll CloudStream. Fetch SATU halaman
     * per panggilan; [page] dimulai dari 1. `hasNext` true selama halaman
     * masih mengembalikan item → SearchViewModel memanggil page berikutnya
     * saat user scroll (search jadi tanpa batas).
     */
    suspend fun search(query: String, page: Int): SearchResponseList {
        val results = scrapper.search(query, page)
        val hasNext = hasNextSearchPage(config, results)
        return newSearchResponseList(results, hasNext = hasNext)
    }

    suspend fun load(url: String): LoadResponse {
        return detailScrapper.load(url)
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        com.lagradost.api.Log.d("BaseProviderEngine",
            "loadLinks(data=$data, casting=$isCasting)")
        return scrapper.loadLinks(data, isCasting, subtitleCallback, callback)
    }
}