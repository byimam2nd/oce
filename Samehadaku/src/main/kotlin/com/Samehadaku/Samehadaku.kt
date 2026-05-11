package com.Samehadaku

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {
    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku⛩️"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "daftar-anime-2/?title=&status=&type=TV&order=popular&page=" to "TV Populer",
        "daftar-anime-2/?title=&status=&type=OVA&order=title&page=" to "OVA",
        "daftar-anime-2/?title=&status=&type=Movie&order=title&page=" to "Movie"
    )

    private fun String.removeBloat(): String =
        replace(Regex("(Nonton|Anime|Subtitle\\s*Indonesia)", RegexOption.IGNORE_CASE), "").trim()

    private fun String.fixQuality(): Int = when (uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun getStatus(statusText: String?): ShowStatus {
        if (statusText == null) return ShowStatus.Completed
        return if (statusText.contains("Ongoing", ignoreCase = true)) ShowStatus.Ongoing else ShowStatus.Completed
    }

    private fun getType(url: String): TvType {
        return when {
            url.contains("/ova/", true) -> TvType.OVA
            url.contains("/movie/", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = (selectFirst("a")?.attr("title").orEmpty().ifBlank {
            selectFirst("div.title, h2.entry-title a, div.lftinfo h2")?.text().orEmpty()
        }).ifEmpty { selectFirst("h2")?.text().orEmpty() }
        if (title.isEmpty()) return null

        val href = fixUrl(a.attr("href"))
        val poster = fixUrlNull(selectFirst("img")?.attr("src") ?: selectFirst("img[data-src]")?.attr("data-src"))
        val type = getType(href)

        return newAnimeSearchResponse(title.trim().removeBloat(), href, type) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.startsWith("http")) "${request.data}$page" else "$mainUrl/${request.data}$page"
        val httpResult = executeWithRetry {
            rateLimitDelay(moduleName = "Samehadaku")
            app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT)
        }
        
        val homeList = if (request.name == "Episode Terbaru") {
            httpResult.document.select("div.post-show ul li").mapNotNull { li ->
                runCatching {
                    val anchor = li.selectFirst("a") ?: return@mapNotNull null
                    val cleanTitle = (anchor.attr("title").ifBlank { anchor.text() })
                        .replace(Regex("(Episode|Ep)\\s*\\d+", RegexOption.IGNORE_CASE), "").removeBloat().trim()
                    newAnimeSearchResponse(cleanTitle, fixUrl(anchor.attr("href")), TvType.Anime) {
                        this.posterUrl = fixUrlNull(li.selectFirst("img")?.attr("src"))
                        addSub(extractEpisodeNumber(li.text()))
                    }
                }.getOrElse { null }
            }
        } else {
            httpResult.document.select("div.animposx").mapNotNull { runCatching { it.toSearchResult() }.getOrElse { null } }
        }
        
        val response = newHomePageResponse(HomePageList(request.name, homeList, request.name == "Episode Terbaru"), homeList.isNotEmpty())
        return response
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Samehadaku")
            app.get("$mainUrl/?s=$query", timeout = AutoUsedConstants.DEFAULT_TIMEOUT).document
        }
        val results = document.select("div.animposx").mapNotNull { runCatching { it.toSearchResult() }.getOrElse { null } }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val loadResult = executeWithRetry {
            rateLimitDelay(moduleName = "Samehadaku")
            app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).document
        }
        
        val animeTitle = loadResult.selectFirst("h1.entry-title")?.text()?.removeBloat()
            ?: loadResult.selectFirst("h1.title")?.text()?.removeBloat() ?: throw Exception("Title not found")

        val posterUrlValue = loadResult.selectFirst("div.thumb img")?.attr("src") ?: loadResult.selectFirst("meta[property=og:image]")?.attr("content")
        val description = loadResult.select("div.desc p").text().ifEmpty { loadResult.select("div.description p").text() }
        val tags = loadResult.select("div.genre-info a").map { it.text() }
        val year = loadResult.selectFirst("div.spe span:contains(Rilis)")?.ownText()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val status = getStatus(loadResult.selectFirst("div.spe span:contains(Status)")?.ownText())
        val type = getType(url)
        val trailerUrl = loadResult.selectFirst("iframe[src*=\"youtube\"]")?.attr("src")

        val episodes = loadResult.select("div.lstepsiode ul li").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            newEpisode(fixUrl(a.attr("href"))) { episode = extractEpisodeNumber(a.text()) }
        }.reversed()

        val tracker = runCatching { APIHolder.getTracker(listOf(animeTitle), TrackerType.getTypes(type), year, true) }.getOrNull()
        
        val loadResponse = newAnimeLoadResponse(animeTitle, url, type) {
            posterUrl = tracker?.image ?: posterUrlValue
            backgroundPosterUrl = tracker?.cover
            plot = description
            this.tags = tags
            this.year = year
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
            addTrailer(trailerUrl)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
        return loadResponse
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val downloadDoc = executeWithRetry {
            rateLimitDelay(moduleName = "Samehadaku")
            app.get(data, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).document
        }
        
        downloadDoc.select("div#downloadb li").amap { li ->
            val qualityText = li.selectFirst("strong")?.text() ?: "Unknown"
            li.select("a").amap { anchor ->
                try {
                    loadFixedExtractor(fixUrl(anchor.attr("href")), qualityText, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractorWithFallback(url = url, referer = mainUrl, subtitleCallback = subtitleCallback) { link ->
            runBlocking {
                MasterLinkGenerator.createLink(
                    source = link.name,
                    url = link.url,
                    referer = link.referer,
                    quality = quality.fixQuality(),
                    headers = link.headers
                )?.let { callback.invoke(it) }
            }
        }
    }

    private fun extractEpisodeNumber(text: String?): Int? {
        if (text == null) return null
        val numberMatch = Regex("""(\d+(?:\.\d+)?)""").find(text)
        return numberMatch?.groupValues?.get(1)?.split(".")?.firstOrNull()?.toIntOrNull()
    }
}
