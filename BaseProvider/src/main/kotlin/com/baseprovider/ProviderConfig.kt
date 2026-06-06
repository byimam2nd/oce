package com.baseprovider

import com.lagradost.cloudstream3.TvType
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject

data class ProviderConfig(
    val id: String,

    // ── Identity ──
    val name: String = id,
    val mainUrl: String = "https://example.com",
    val seriesUrl: String? = null,
    val searchUrl: String? = null,
    val lang: String = "id",
    val supportedTypes: Set<TvType> = emptySet(),

    // ── URL Patterns ──
    val searchPathPattern: String = "{baseUrl}/page/{page}/?s={query}",
    val mainPagePathPattern: String = "{baseUrl}/{data}{page}",
    val moviePathSegment: String = "/movie/",
    val tvPathSegment: String = "/anime/",
    val episodeDataUrlPattern: String = "{url}",

    // ── Features ──
    val searchPageLimit: Int = 2,
    val reverseEpisodes: Boolean = true,
    val isJsonSearch: Boolean = false,
    val searchJsonRoot: String = "data",
    val searchJsonTitle: String = "title",
    val searchJsonHref: String = "slug",
    val searchJsonPoster: String = "poster",
    val searchJsonPosterPrefix: String = "",
    val searchJsonType: String = "type",
    val useDocumentLarge: Boolean = false,
    val cacheTtlMinutes: Long = 5L,
    val isHorizontal: Boolean = false,
    val mirrorUrls: List<String> = emptyList(),
    val refererPlayerMode: String = "current_url",
    val iframeSelectors: String = "iframe",

    // ── Name Cleaning ──
    val qualityStripRegex: String = """\d{3,4}p|HD|SD|FHD""",
    val qualityStripRegexCompiled: Regex by lazy { try { Regex(qualityStripRegex, RegexOption.IGNORE_CASE) } catch (_: Exception) { Regex("""\d{3,4}p|HD|SD|FHD""", RegexOption.IGNORE_CASE) } },

    // ── Headers ──
    val globalHeaders: Map<String, String> = emptyMap(),

    // ── Navigation ──
    val mainPageLists: List<Pair<String, String>> = emptyList(),

    // ── Extractor Control ──
    val allowedExtractors: Set<String> = emptySet(),

    // ── UI Keywords ──
    val dubKeyword: String = "dub",
    val ongoingKeyword: String = "Ongoing",
    val episodeKeyword: String = "Episode",
    val seriesKeyword: String = "Series",
    val comingSoonKeywords: String = "Coming Soon",

    // ── Selectors: Search ──
    val searchItems: String = "",
    val searchTitle: String = "",
    val searchHref: String = "",
    val searchPoster: String = "",
    val searchRating: String = "",
    val searchEpText: String = "",

    // ── Selectors: Load ──
    val loadTitle: String = "",
    val loadPoster: String = "",
    val loadBanner: String = "",
    val loadDesc: String = "",
    val loadInfoBox: String = "",
    val loadTags: String = "",
    val loadRating: String = "",
    val loadStatus: String = "",
    val loadTrailer: String = "",
    val loadRecommend: String = "",

    // ── Selectors: Episode ──
    val episodeItems: String = "",
    val episodeHref: String = "",
    val episodeTitle: String = "",
    val episodeNum: String = "",
    val episodeDesc: String = "",
    val episodeTime: String = "",

    // ── Selectors: Link & Misc ──
    val linkOptions: String = "",
    val downloadItems: String = "",
    val actorItems: String = "",
    val actorName: String = "",

    // ── Selectors: Structural ──
    val watchButtons: String = ".play-button, .watch-now, .btn-watch",
    val seasonContainer: String = ".tvseason, #season-data",
    val imdbExternal: String = "a[href*='imdb.com/title/']",
    val tmdbExternal: String = "a[href*='themoviedb.org/']",
    val iframeTag: String = "iframe",
    val followLinkSelector: String = "",

    // ── Selectors: AJAX Player ──
    val ajaxPlayerUrl: String = "",
    val selectorJsonData: String = "",

    // ── Attribute Names ──
    val attrImage: List<String> = listOf("data-original", "data-src", "data-lazy-src", "data-litespeed-src", "src", "content"),
    val attrHref: List<String> = listOf("href"),
    val attrValue: List<String> = listOf("value", "data-index", "data-id", "data-url", "data-link", "data-litespeed-src"),
    val iframeSources: List<String> = listOf("src", "data-src", "data-link", "data-litespeed-src"),

    // ── URL Cleanup ──
    val hrefCleanRegex: String = "",
    val hrefCleanReplace: String = "",

    // ── Hooks ──
    val yearSelector: String = "",
    val yearExtractorRegex: String = "",

    // ── Bloat Regex ──
    val bloatRegex: Regex = BLOAT_REGEX_DEFAULT,
) {
    init { validate() }

    private fun validate() {
        val errors = mutableListOf<String>()

        if (mainUrl.isBlank()) errors += "mainUrl must not be blank"
        if (!mainUrl.startsWith("http")) errors += "mainUrl must start with http"
        if (supportedTypes.isEmpty()) errors += "supportedTypes must not be empty"

        if (refererPlayerMode !in listOf("series_url", "current_url", "main_url"))
            errors += "invalid refererPlayerMode: $refererPlayerMode"

        listOf(
            "bloatRegex" to bloatRegex.pattern,
            "yearExtractorRegex" to yearExtractorRegex,
            "hrefCleanRegex" to hrefCleanRegex,
            "qualityStripRegex" to qualityStripRegex
        ).forEach { (name, pattern) ->
            if (pattern.isNotBlank()) {
                try { Regex(pattern) } catch (e: Exception) {
                    errors += "$name is not a valid regex: ${e.message}"
                }
            }
        }

        if (errors.isNotEmpty()) {
            Log.w("ProviderConfig[$id]", "Validation:\n  ${errors.joinToString("\n  ")}")
        }
    }

    companion object {
        fun fromJson(id: String, json: JSONObject): ProviderConfig {
            return ProviderConfig(
                id = id,
                name = json.optString("name", id),
                mainUrl = json.optString("mainUrl", "https://example.com"),
                seriesUrl = json.optString("seriesUrl", null)?.ifBlank { null },
                searchUrl = json.optString("searchUrl", null)?.ifBlank { null },
                lang = json.optString("lang", "id"),
                supportedTypes = parseTvTypes(json.optJSONArray("supportedTypes")),
                searchPathPattern = json.optString("searchPathPattern", "{baseUrl}/page/{page}/?s={query}"),
                mainPagePathPattern = json.optString("mainPagePathPattern", "{baseUrl}/{data}{page}"),
                moviePathSegment = json.optString("moviePathSegment", "/movie/"),
                tvPathSegment = json.optString("tvPathSegment", "/anime/"),
                episodeDataUrlPattern = json.optString("episodeDataUrlPattern", "{url}"),
                searchPageLimit = json.optInt("searchPageLimit", 2),
                reverseEpisodes = json.optBoolean("reverseEpisodes", true),
                isJsonSearch = json.optBoolean("isJsonSearch", false),
                searchJsonRoot = json.optString("searchJsonRoot", "data"),
                searchJsonTitle = json.optString("searchJsonTitle", "title"),
                searchJsonHref = json.optString("searchJsonHref", "slug"),
                searchJsonPoster = json.optString("searchJsonPoster", "poster"),
                searchJsonPosterPrefix = json.optString("searchJsonPosterPrefix", ""),
                searchJsonType = json.optString("searchJsonType", "type"),
                useDocumentLarge = json.optBoolean("useDocumentLarge", false),
                cacheTtlMinutes = json.optLong("cacheTtlMinutes", 5L),
                isHorizontal = json.optBoolean("isHorizontal", false),
                mirrorUrls = jsonArrayToList(json.optJSONArray("mirrorUrls")),
                refererPlayerMode = json.optString("refererPlayerMode", "current_url"),
                iframeSelectors = json.optString("iframeSelectors", "iframe"),
                qualityStripRegex = json.optString("qualityStripRegex", """\d{3,4}p|HD|SD|FHD"""),
                globalHeaders = jsonObjectToMap(json.optJSONObject("globalHeaders")),
                mainPageLists = parsePairsList(json.optJSONArray("mainPageLists")),
                allowedExtractors = jsonArrayToSet(json.optJSONArray("allowedExtractors")),
                dubKeyword = json.optString("dubKeyword", "dub"),
                ongoingKeyword = json.optString("ongoingKeyword", "Ongoing"),
                episodeKeyword = json.optString("episodeKeyword", "Episode"),
                seriesKeyword = json.optString("seriesKeyword", "Series"),
                comingSoonKeywords = json.optString("comingSoonKeywords", "Coming Soon"),
                searchItems = json.optString("searchItems", ""),
                searchTitle = json.optString("searchTitle", ""),
                searchHref = json.optString("searchHref", ""),
                searchPoster = json.optString("searchPoster", ""),
                searchRating = json.optString("searchRating", ""),
                searchEpText = json.optString("searchEpText", ""),
                loadTitle = json.optString("loadTitle", ""),
                loadPoster = json.optString("loadPoster", ""),
                loadBanner = json.optString("loadBanner", ""),
                loadDesc = json.optString("loadDesc", ""),
                loadInfoBox = json.optString("loadInfoBox", ""),
                loadTags = json.optString("loadTags", ""),
                loadRating = json.optString("loadRating", ""),
                loadStatus = json.optString("loadStatus", ""),
                loadTrailer = json.optString("loadTrailer", ""),
                loadRecommend = json.optString("loadRecommend", ""),
                episodeItems = json.optString("episodeItems", ""),
                episodeHref = json.optString("episodeHref", ""),
                episodeTitle = json.optString("episodeTitle", ""),
                episodeNum = json.optString("episodeNum", ""),
                episodeDesc = json.optString("episodeDesc", ""),
                episodeTime = json.optString("episodeTime", ""),
                linkOptions = json.optString("linkOptions", ""),
                downloadItems = json.optString("downloadItems", ""),
                actorItems = json.optString("actorItems", ""),
                actorName = json.optString("actorName", ""),
                watchButtons = json.optString("watchButtons", ".play-button, .watch-now, .btn-watch"),
                seasonContainer = json.optString("seasonContainer", ".tvseason, #season-data"),
                imdbExternal = json.optString("imdbExternal", "a[href*='imdb.com/title/']"),
                tmdbExternal = json.optString("tmdbExternal", "a[href*='themoviedb.org/']"),
                iframeTag = json.optString("iframeTag", "iframe"),
                followLinkSelector = json.optString("followLinkSelector", ""),
                ajaxPlayerUrl = json.optString("ajaxPlayerUrl", ""),
                selectorJsonData = json.optString("selectorJsonData", ""),
                attrImage = jsonArrayToList(json.optJSONArray("attrImage"), listOf("data-original", "data-src", "data-lazy-src", "data-litespeed-src", "src", "content")),
                attrHref = jsonArrayToList(json.optJSONArray("attrHref"), listOf("href")),
                attrValue = jsonArrayToList(json.optJSONArray("attrValue"), listOf("value", "data-index", "data-id", "data-url", "data-link", "data-litespeed-src")),
                iframeSources = jsonArrayToList(json.optJSONArray("iframeSources"), listOf("src", "data-src", "data-link", "data-litespeed-src")),
                hrefCleanRegex = validateRegex(json.optString("hrefCleanRegex", "")),
                hrefCleanReplace = json.optString("hrefCleanReplace", ""),
                yearSelector = json.optString("yearSelector", ""),
                yearExtractorRegex = validateRegex(json.optString("yearExtractorRegex", "")),
                bloatRegex = try { Regex(json.optString("bloatRegex", BLOAT_REGEX_DEFAULT.pattern)) } catch (_: Exception) { BLOAT_REGEX_DEFAULT },
            )
        }

        private fun parseTvTypes(arr: JSONArray?): Set<TvType> {
            if (arr == null) return emptySet()
            return (0 until arr.length()).mapNotNull { i ->
                when (arr.optString(i, "")) {
                    "Movie" -> TvType.Movie
                    "TvSeries" -> TvType.TvSeries
                    "Anime" -> TvType.Anime
                    "AnimeMovie" -> TvType.AnimeMovie
                    "AsianDrama" -> TvType.AsianDrama
                    "Cartoon" -> TvType.Cartoon
                    "OVA" -> TvType.OVA
                    else -> null
                }
            }.toSet()
        }

        private fun parsePairsList(arr: JSONArray?): List<Pair<String, String>> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                if (pair.length() < 2) return@mapNotNull null
                pair.optString(0, "") to pair.optString(1, "")
            }
        }

        private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val keys = obj.keys()
            val map = mutableMapOf<String, String>()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optString(key, "")
            }
            return map
        }

        private fun jsonArrayToList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { arr.optString(it, "") }
        }

        private fun jsonArrayToList(arr: JSONArray?, default: List<String>): List<String> {
            if (arr == null) return default
            return (0 until arr.length()).map { arr.optString(it, "") }
        }

        private fun jsonArrayToSet(arr: JSONArray?): Set<String> {
            if (arr == null) return emptySet()
            return (0 until arr.length()).map { arr.optString(it, "") }.toSet()
        }

        private fun validateRegex(pattern: String): String {
            if (pattern.isBlank()) return ""
            return try { Regex(pattern); pattern } catch (_: Exception) { "" }
        }
    }
}

private val BLOAT_REGEX_DEFAULT = Regex(
    """(?i)(\bONA\b|\bOngoing\b|\bCompleted\b|\bSpecial\b|\bTAMAT\b|\bIndo\b|\bFull\b|\bSeason\b|\bEpisode\s*\d*|Subtitle\s*Indonesia|Donghua\s*Sub|Nonton|Anime|Movie|TV|Series|Lengkap|HD|Free|\d{3,4}p|Dual\s*Audio|\s*–\s*|\s*\|\s*)""",
    RegexOption.IGNORE_CASE
)
