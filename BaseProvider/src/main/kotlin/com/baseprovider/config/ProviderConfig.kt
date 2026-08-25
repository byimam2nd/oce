package com.baseprovider.config

import com.lagradost.cloudstream3.TvType
import com.lagradost.api.Log

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
    // Regex URL movie alternatif (opsional). Dipakai saat migrasi domain
    // mengubah pola slug film tanpa mengubah struktur tema — kasus
    // Dutamovie21: vikingsgab "/movie/slug-2025/" -> cyber-junkie "/slug-2025/".
    // Isi contoh: "-\\d{4}/?$" (slug berakhiran -YYYY).
    val movieUrlRegex: String = "",
    // True: jika loadLinks tak menemukan kandidat apa pun, dispatch URL
    // episode itu sendiri ke extractor yang cocok dengan hostnya (untuk
    // situs yang menyuntik player via JS - kasus Samehadaku/wibufile).
    val selfExtract: Boolean = false,
    // Regex (dipisah koma) URL yang DITOLAK menjadi item konten — utk
    // memisahkan sub-direktori non-katalog (kasus Dutamovie21: /blog/
    // berisi posting dewasa yg ikut muncul di pencarian & pecah sebagai
    // series palsu).
    val excludeUrlPatterns: String = "",
    // Timeout untuk APIHolder.getTracker dalam milliseconds (Long agar cocok
    // langsung dengan withTimeoutOrNull tanpa konversi).
    val trackerTimeoutMs: Long = 300L,
    val tvPathSegment: String = "/anime/",
    val episodeDataUrlPattern: String = "{url}",

    // ── Features ──
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
    val uaPool: List<String> = emptyList(),
    val refererPlayerMode: String = "current_url",
    val iframeSelectors: String = "iframe",

    // ── Poster Resize (adaptive) ──
    // Template rewrite poster/thumbnail URL ke image proxy on-the-fly.
    // Token {url} = URL asli (URL-encoded). Kosong = mati (URL asli).
    // Contoh: https://images.weserv.nl/?url={url}&w=342&output=avif,webp
    val posterResizeUrl: String = "",
    // Template khusus thumbnail episode — ditampilkan lebih kecil dari poster
    // grid, jadi bisa pakai lebar lebih kecil (~200px). Kosong = fallback ke
    // posterResizeUrl.
    val thumbnailResizeUrl: String = "",

    // ── Name Cleaning ──
    val qualityStripRegex: String = """\d{3,4}p|HD|SD|FHD""",

    // ── Headers ──
    val globalHeaders: Map<String, String> = emptyMap(),
    val googleReferer: Boolean = false,

    // ── Navigation ──
    val mainPageLists: List<Pair<String, String>> = emptyList(),

    // ── Extractor Control ──
    val allowedExtractors: Set<String> = emptySet(),
    // Host yang dilewati total (host mati / link iklan / shortlink rusak).
    // Cocokkan terhadap host URL final (mis. "short.icu").
    val skipHosts: Set<String> = emptySet(),

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
    val switchVideoSelector: String = "",

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
    val qualityStripRegexCompiled: Regex by lazy {
        try {
            Regex(qualityStripRegex, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex("""\d{3,4}p|HD|SD|FHD""", RegexOption.IGNORE_CASE)
        }
    }

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

    companion object {}
}

val BLOAT_REGEX_DEFAULT = Regex(
    """(?i)(\bONA\b|\bOngoing\b|\bCompleted\b|\bSpecial\b|\bTAMAT\b|\bIndo\b|\bFull\b|\bSeason\b""" +
        """|\bEpisode\s*\d*|Subtitle\s*Indonesia|\bDonghua\b|\bSub\b|Nonton|Anime|Movie|TV|Series""" +
        """|Lengkap|HD|Free|\d{3,4}p|Dual\s*Audio|\s*–\s*|\s*\|\s*)""",
    RegexOption.IGNORE_CASE
)
