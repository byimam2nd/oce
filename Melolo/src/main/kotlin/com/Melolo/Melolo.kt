package com.Melolo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class Melolo : MainAPI() {
    override var mainUrl = "https://api.tmthreader.com"
    override var name = "Melolo😶"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val aid = "645713"
    private val catalogBase = "https://melolo-api-azure.vercel.app/api/melolo"

    override val mainPage = mainPageOf(
        "latest" to "Terbaru",
        "trending" to "Trending",
        "q:ceo" to "CEO",
        "q:romansa" to "Romansa",
        "q:sistem" to "Sistem",
        "q:keluarga" to "Keluarga",
        "q:mafia" to "Mafia",
        "q:aksi" to "Aksi",
        "q:balas dendam" to "Balas Dendam",
        "q:pernikahan" to "Pernikahan",
        "q:drama periode" to "Drama Periode",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSearchCategory = request.data.startsWith("q:", true)
        if (page > 1 && !isSearchCategory) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        
        val (books, hasNext) = if (isSearchCategory) fetchSearchPage(request.data.removePrefix("q:").trim(), limit = 20, offset = (page.coerceAtLeast(1) - 1) * 20) 
                            else (if (request.data == "trending") fetchTrending() else fetchLatest()) to false
        
        val items = books.mapNotNull { b -> 
            newTvSeriesSearchResponse(b.book_name ?: return@mapNotNull null, "$mainUrl/series/${b.book_id ?: return@mapNotNull null}", TvType.TvSeries) { 
                this.posterUrl = b.thumb_url 
            } 
        }
        
        val result = newHomePageResponse(HomePageList(request.name, items), hasNext)
        return result
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = fetchSearch(query, 20, 0).mapNotNull { b -> 
            newTvSeriesSearchResponse(b.book_name ?: return@mapNotNull null, "$mainUrl/series/${b.book_id ?: return@mapNotNull null}", TvType.TvSeries) { 
                this.posterUrl = b.thumb_url 
            } 
        }
        return results
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val items = fetchSearchPage(query, limit = 20, offset = (page.coerceAtLeast(1) - 1) * 20).first.mapNotNull { b -> 
            newTvSeriesSearchResponse(b.book_name ?: return@mapNotNull null, "$mainUrl/series/${b.book_id ?: return@mapNotNull null}", TvType.TvSeries) { 
                this.posterUrl = b.thumb_url 
            } 
        }
        return items.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/").substringBefore("?").trim()
        val detail = fetchDetail(bookId)
        val episodes = detail.video_list.filter { it.disable_play != true }.mapNotNull { ep -> 
            newEpisode(EpisodeData(bookId, detail.series_id_str ?: bookId, ep.vid ?: return@mapNotNull null, ep.vid_index ?: return@mapNotNull null, detail.video_platform ?: 3).toJson()) { 
                this.name = "Episode ${ep.vid_index}"
                this.posterUrl = ep.cover
                this.episode = ep.vid_index 
            } 
        }.sortedBy { it.episode ?: Int.MAX_VALUE }
        
        return newTvSeriesLoadResponse(detail.series_title ?: "Melolo", "$mainUrl/series/$bookId", TvType.TvSeries, episodes) { 
            this.plot = detail.series_intro
            this.posterUrl = detail.series_cover 
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val body = """{"video_id":"${ep.vid}","biz_param":{"video_id_type":0,"device_level":1,"video_platform":${ep.videoPlatform}},"NovelCommonParam":{"app_language":"id","sys_language":"id","user_language":"id","ui_language":"id","language":"id","region":"ID","current_region":"ID","app_region":"ID","sys_region":"ID","carrier_region":"ID","carrier_region_v2":"ID","fake_priority_region":"ID","time_zone":"Asia/Jakarta","mcc_mnc":"51011"}}""".trimIndent()
        
        val responseText = executeWithRetry {
            rateLimitDelay(moduleName = "Melolo")
            app.post("$mainUrl/novel/player/video_model/v1/?aid=$aid", requestBody = body.toRequestBody("application/json".toMediaType()), headers = mapOf("Content-Type" to "application/json", "X-Xs-From-Web" to "false", "User-Agent" to "okhttp/4.9.3", "Referer" to "$mainUrl/")).text
        }
        
        val resp = tryParseJson<PlayerVideoModelResponse>(responseText)
        listOfNotNull(resp?.data?.main_url, resp?.data?.backup_url).distinct().forEach { url -> 
            callback(newExtractorLink(name, "Melolo", url, ExtractorLinkType.VIDEO) { 
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
                this.headers = mapOf("User-Agent" to "okhttp/4.9.3") 
            }) 
        }
        return resp?.data?.main_url != null || resp?.data?.backup_url != null
    }

    private suspend fun fetchLatest(): List<CatalogBook> {
        return try {
            val res = executeWithRetry { app.get("$catalogBase/latest", timeout = 30L) }
            tryParseJson<CatalogLatestResponse>(res.text)?.books.orEmpty().filter { it.language.equals("id", true) }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchTrending(): List<CatalogBook> {
        return try {
            val res = executeWithRetry { app.get("$catalogBase/trending", timeout = 30L) }
            tryParseJson<CatalogTrendingResponse>(res.text)?.books.orEmpty().filter { it.language.equals("id", true) }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchSearch(query: String, limit: Int, offset: Int): List<CatalogBook> {
        return try {
            val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&offset=$offset"
            val res = executeWithRetry { app.get(url, timeout = 30L) }
            tryParseJson<CatalogSearchResponse>(res.text)?.data?.search_data?.flatMap { it.books }.orEmpty().filter { it.language.equals("id", true) }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchSearchPage(query: String, limit: Int, offset: Int): Pair<List<CatalogBook>, Boolean> {
        return try {
            val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&offset=$offset"
            val res = executeWithRetry { app.get(url, timeout = 30L) }
            val resp = tryParseJson<CatalogSearchResponse>(res.text)
            (resp?.data?.search_data?.flatMap { it.books }.orEmpty().filter { it.language.equals("id", true) }) to (resp?.data?.has_more == true)
        } catch (_: Exception) { emptyList<CatalogBook>() to false }
    }

    private suspend fun fetchDetail(bookId: String): CatalogVideoData {
        val res = executeWithRetry { app.get("$catalogBase/detail/$bookId", timeout = 30L) }
        return tryParseJson<CatalogDetailResponse>(res.text)?.data?.video_data ?: throw ErrorLoadingException("Empty detail data")
    }

    data class CatalogLatestResponse(@JsonProperty("books") val books: List<CatalogBook> = emptyList())
    data class CatalogTrendingResponse(@JsonProperty("books") val books: List<CatalogBook> = emptyList())
    data class CatalogSearchResponse(@JsonProperty("data") val data: CatalogSearchData? = null)
    data class CatalogSearchData(@JsonProperty("has_more") val has_more: Boolean? = null, @JsonProperty("search_data") val search_data: List<CatalogSearchBlock> = emptyList())
    data class CatalogSearchBlock(@JsonProperty("books") val books: List<CatalogBook> = emptyList())
    data class CatalogBook(@JsonProperty("book_id") val book_id: String? = null, @JsonProperty("book_name") val book_name: String? = null, @JsonProperty("thumb_url") val thumb_url: String? = null, @JsonProperty("language") val language: String? = null)
    data class CatalogDetailResponse(@JsonProperty("data") val data: CatalogDetailData? = null)
    data class CatalogDetailData(@JsonProperty("video_data") val video_data: CatalogVideoData? = null)
    data class CatalogVideoData(@JsonProperty("series_id_str") val series_id_str: String? = null, @JsonProperty("series_title") val series_title: String? = null, @JsonProperty("series_intro") val series_intro: String? = null, @JsonProperty("series_cover") val series_cover: String? = null, @JsonProperty("video_list") val video_list: List<CatalogEpisode> = emptyList(), @JsonProperty("video_platform") val video_platform: Int? = null)
    data class CatalogEpisode(@JsonProperty("vid") val vid: String? = null, @JsonProperty("vid_index") val vid_index: Int? = null, @JsonProperty("cover") val cover: String? = null, @JsonProperty("disable_play") val disable_play: Boolean? = null)
    data class PlayerVideoModelResponse(@JsonProperty("data") val data: PlayerVideoModelData? = null)
    data class PlayerVideoModelData(@JsonProperty("main_url") val main_url: String? = null, @JsonProperty("backup_url") val backup_url: String? = null)
    data class EpisodeData(@JsonProperty("bookId") val bookId: String, @JsonProperty("seriesId") val seriesId: String, @JsonProperty("vid") val vid: String, @JsonProperty("episode") val episode: Int, @JsonProperty("videoPlatform") val videoPlatform: Int = 3)
}
