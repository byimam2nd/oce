package com.baseprovider.core

import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

    suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        scrapper.getMainPage(page, request)

    suspend fun search(query: String): List<SearchResponse> =
        scrapper.search(query)

    suspend fun load(url: String): LoadResponse =
        detailScrapper.load(url)

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean =
        scrapper.loadLinks(data, isCasting, subtitleCallback, callback)
}
