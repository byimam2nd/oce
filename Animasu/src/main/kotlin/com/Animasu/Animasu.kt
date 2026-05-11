package com.Animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Animasu : MainAPI() {
    override var mainUrl = "https://v1.animasu.top"
    override var name = "Animasu🐰"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "urutan=update" to "Baru diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
        "status=&tipe=Movie&urutan=populer" to "Movie Terpopuler",
    )

    companion object {
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Sedang Tayang", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private fun normalizeLink(uri: String): String = if (uri.contains("/anime/")) {
        uri
    } else {
        var title = uri.substringAfter("$mainUrl/")
        title = when {
            (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
            (title.contains("-movie")) -> title.substringBefore("-movie")
            else -> title
        }
        "$mainUrl/anime/$title"
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = normalizeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this
            .select("div.tt")
            .text()
            .trim()
            .ifEmpty { this.selectFirst("a")?.attr("title").orEmpty() }
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.extractImageAttr() ?: this.selectFirst("img[data-src]")?.attr("data-src")
        )
        val epNum = this
            .selectFirst("span.epx")
            ?.text()
            ?.filter { it.isDigit() }
            ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Animasu")
            app.get("$mainUrl/pencarian/?${request.data}&halaman=$page", timeout = 10000L).document
        }

        val home = document.select("div.listupd div.bs").mapNotNull {
            runCatching { it.toSearchResult() }.getOrElse { null }
        }
        val response = newHomePageResponse(request.name, home)
        return response
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Animasu")
            app.get("$mainUrl/?s=$query", timeout = 10000L).document
        }
        val results = document.select("div.listupd div.bs").mapNotNull {
            runCatching { it.toSearchResult() }.getOrElse { null }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Animasu")
            app.get(url, timeout = 10000L).document
        }

        val title = document
            .selectFirst("div.infox h1")
            ?.text()
            ?.replace("Sub Indo", "")
            ?.trim()
            .orEmpty()
            .ifEmpty {
                document
                    .selectFirst("h1[itemprop=headline]")
                    ?.text()
                    ?.replace("Sub Indo", "")
                    ?.trim()
                    .orEmpty()
            }

        val poster = document.selectFirst("div.bigcontent img")?.extractImageAttr()
            ?: document.selectFirst("div.thumb img")?.extractImageAttr()

        val table = document.selectFirst("div.infox div.spe")
        val type = getType(table?.selectFirst("span:contains(Jenis:)")?.ownText())
        val year = table
            ?.selectFirst("span:contains(Rilis:)")
            ?.ownText()
            ?.substringAfterLast(",")
            ?.trim()
            ?.toIntOrNull()
        val status = table?.selectFirst("span:contains(Status:) font")?.text()
        val trailer = document.selectFirst("div.trailer iframe")?.attr("src")
        val plot = document.select("div.sinopsis p").text().ifEmpty { document.select("div.entry-content p").text() }

        val episodes = document
            .select("ul#daftarepisode > li")
            .mapNotNull {
                val anchor = it.selectFirst("a") ?: return@mapNotNull null
                newEpisode(fixUrl(anchor.attr("href"))) { this.episode = extractEpisodeNumber(anchor.text()) }
            }.reversed()

        val tracker = runCatching { APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true) }
            .getOrNull()

        val loadResponse = newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(status)
            this.plot = plot
            this.tags = table?.select("span:contains(Genre:) a")?.map { it.text() }
            addTrailer(trailer)
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
        val document = executeWithRetry {
            rateLimitDelay(moduleName = "Animasu")
            app.get(data, timeout = 10000L).document
        }

        val playerLinks = document.select(".mobius > .mirror > option").mapNotNull {
            val value = it.attr("value")
            if (value.isNotEmpty()) fixUrl(Jsoup.parse(base64Decode(value)).select("iframe").attr("src")) to it.text() else null
        }

        if (playerLinks.isEmpty()) return false

        playerLinks.amap { (iframe, quality) ->
            try {
                loadFixedExtractor(iframe, quality, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val loaded =
            loadExtractorWithFallback(url = url, referer = referer, subtitleCallback = subtitleCallback) { link ->
                runBlocking {
                    MasterLinkGenerator
                        .createLink(
                            source = link.name,
                            url = link.url,
                            referer = link.referer,
                            quality = if (link.type == ExtractorLinkType.M3U8 || link.name == "Uservideo") {
                                link.quality
                            } else {
                                parseQualityToInt(quality) ?: MasterLinkGenerator.detectQualityFromUrl(link.url)
                            },
                            headers = link.headers
                        )?.let { callback.invoke(it) }
                }
            }
    }

    private fun parseQualityToInt(str: String?): Int = Regex("(\\d{3,4})[pP]")
        .find(str ?: "")
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: Qualities.Unknown.value

    private fun Element.extractImageAttr(): String {
        val attrs = listOf(
            "data-src",
            "src",
            "data-original",
            "data-lazy-src",
            "data-srcset",
            "",
        )
        return attrs
            .asSequence()
            .map { attr(it) }
            .firstOrNull { it.isNotBlank() }
            ?.split(" ")
            ?.firstOrNull() ?: ""
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val numberMatch = Regex("""(\d+(?:\.\d+)?)""").find(text)
        return numberMatch
            ?.groupValues
            ?.get(1)
            ?.split(".")
            ?.firstOrNull()
            ?.toIntOrNull()
    }
}
