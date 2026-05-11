package com.Idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.security.MessageDigest

// Data classes for API
data class IdlixApiResponse(
    val data: List<IdlixApiItem> = emptyList()
)

data class IdlixApiItem(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val posterPath: String? = null,
    val releaseDate: String? = null,
    val voteAverage: String? = null,
    val quality: String? = null,
    val contentType: String? = null
)

data class IdlixDetailResponse(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Any? = null,
    val quality: String? = null,
    val trailerUrl: String? = null,
    val genres: List<IdlixGenre>? = null,
    val cast: List<IdlixCast>? = null,
    val seasons: List<IdlixSeason>? = null,
    val firstSeason: IdlixSeason? = null
)

data class IdlixGenre(
    val id: String? = null,
    val name: String? = null
)

data class IdlixCast(
    val id: String? = null,
    val name: String? = null,
    val profilePath: String? = null
)

data class IdlixSeason(
    val id: String? = null,
    val seasonNumber: Int? = null,
    val name: String? = null,
    val episodes: List<IdlixEpisode>? = null
)

data class IdlixEpisode(
    val id: String? = null,
    val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Any? = null
)

data class IdlixSearchResponse(
    val results: List<IdlixSearchResult> = emptyList()
)

data class IdlixSearchResult(
    val id: String = "",
    val contentType: String = "",
    val title: String = "",
    val posterPath: String = "",
    val slug: String = "",
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double = 0.0,
    val quality: String? = null
)

data class IdlixChallengeResponse(
    val challenge: String = "",
    val signature: String = "",
    val difficulty: Int = 0
)

data class IdlixSolveResponse(
    val embedUrl: String? = null
)

data class IdlixLoadData(
    val id: String,
    val type: String
)

data class IdlixSeasonWrapper(
    val season: IdlixSeason? = null
)

class Idlix : MainAPI() {
    override var mainUrl = "https://z1.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "TV Series Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Amazon Prime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res =
            app.get(url, timeout = 10000L).parsedSafe<IdlixApiResponse>()
                ?: return newHomePageResponse(request.name, emptyList())
        val home = res.data.mapNotNull { item ->
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            if (item.contentType == "movie") {
                newMovieSearchResponse(item.title ?: return@mapNotNull null, "$mainUrl/api/movies/${item.slug}", TvType.Movie) {
                    this.posterUrl =
                        poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality =
                        getQualityFromString(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                newTvSeriesSearchResponse(item.title ?: return@mapNotNull null, "$mainUrl/api/series/${item.slug}", TvType.TvSeries) {
                    this.posterUrl =
                        poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score =
                        Score.from10(item.voteAverage)
                    this.quality = getQualityFromString(item.quality)
                }
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res =
            app.get("$mainUrl/api/search?q=$query&page=1&limit=20").parsedSafe<IdlixSearchResponse>()
                ?: return emptyList()
        return res.results.mapNotNull { item ->
            val poster = "https://image.tmdb.org/t/p/w342${item.posterPath}"
            val link = if (item.contentType ==
                "movie"
            ) {
                "$mainUrl/api/movies/${item.slug}"
            } else {
                "$mainUrl/api/series/${item.slug}"
            }
            if (item.contentType == "movie") {
                newMovieSearchResponse(item.title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year =
                        item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality =
                        getQualityFromString(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                newTvSeriesSearchResponse(item.title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year =
                        item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data =
            app.get(url, timeout = 10000L).parsedSafe<IdlixDetailResponse>()
                ?: throw ErrorLoadingException("Invalid JSON response")
        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val actors =
            data.cast?.mapNotNull {
                it.name?.let { name ->
                    Actor(name, it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" })
                }
            }
                ?: emptyList()

        if (data.seasons != null) {
            val episodes = mutableListOf<Episode>()
            data.firstSeason?.episodes?.forEach { ep ->
                episodes.add(
                    newEpisode(IdlixLoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) {
                        this.name =
                            ep.name
                        this.season = data.firstSeason.seasonNumber
                        this.episode = ep.episodeNumber
                        this.description =
                            ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        this.posterUrl =
                            ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                    }
                )
            }
            data.seasons.forEach { season ->
                val sn = season.seasonNumber ?: return@forEach
                if (sn == data.firstSeason?.seasonNumber) return@forEach
                app
                    .get("$mainUrl/api/series/${data.slug}/season/$sn")
                    .parsedSafe<IdlixSeasonWrapper>()
                    ?.season
                    ?.episodes
                    ?.forEach { ep ->
                        episodes.add(
                            newEpisode(IdlixLoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) {
                                this.name =
                                    ep.name
                                this.season = sn
                                this.episode = ep.episodeNumber
                                this.description = ep.overview
                                this.runTime =
                                    ep.runtime
                                this.score = Score.from10(ep.voteAverage?.toString())
                                this.posterUrl =
                                    ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                            }
                        )
                    }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl =
                    backdrop
                this.year = (data.releaseDate ?: data.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                this.plot =
                    data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        } else {
            return newMovieLoadResponse(
                title, url, TvType.Movie,
                IdlixLoadData(id = data.id ?: "", type = "movie")
                    .toJson()
            ) {
                this.posterUrl =
                    poster
                this.backgroundPosterUrl = backdrop
                this.year =
                    (data.releaseDate ?: data.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                this.plot =
                    data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parsed = parseJson<IdlixLoadData>(data)
        val aclr = app.get("$mainUrl/pagead/ad_frame.js?_=${System.currentTimeMillis()}").text.let {
            Regex("""__aclr\s*=\s*"([a-f0-9]+)""")
                .find(it)
                ?.groupValues
                ?.getOrNull(1)
        }
        val headers = mapOf(
            "accept" to "*/*", "content-type" to "application/json", "origin" to mainUrl,
            "referer" to mainUrl, "user-agent" to USER_AGENT
        )
        val challenge =
            app
                .post(
                    "$mainUrl/api/watch/challenge",
                    data = mapOf(
                        "contentType" to parsed.type, "contentId" to parsed.id,
                        "clearance" to (aclr ?: "")
                    ),
                    headers = headers
                ).parsedSafe<IdlixChallengeResponse>()
                ?: return false
        val solve =
            app
                .post(
                    "$mainUrl/api/watch/solve",
                    data = mapOf(
                        "challenge" to challenge.challenge,
                        "signature" to challenge.signature,
                        "nonce" to solvePow(challenge.challenge, challenge.difficulty).toString()
                    ),
                    headers = headers
                ).parsedSafe<IdlixSolveResponse>()
                ?: return false
        val embedUrl = solve.embedUrl ?: return false
        val finalUrl = app
            .get("$mainUrl$embedUrl")
            .document
            .selectFirst("iframe")
            ?.attr("src") ?: return false
        return loadExtractorWithFallback(finalUrl, mainUrl, subtitleCallback, callback)
    }

    private fun solvePow(challenge: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty)
        var nonce = 0
        while (true) {
            if (sha256(challenge + nonce).startsWith(target)) return nonce
            nonce++
        }
    }

    private fun sha256(input: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
