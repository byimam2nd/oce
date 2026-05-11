package com.Pencurimovie

import com.lagradost.cloudstream3.TvType

/**
 * 📘 MASTER CONFIGURATION: BASE HTML PROVIDER (V10.3 - STABLE PRODUCTION)
 * 
 * Pusat kendali seluruh metadata dan selektor untuk 6 provider.
 * Menggunakan sistem 'Owner Tagging' (ProviderID:::) untuk isolasi konfigurasi.
 */
object PencurimovieConstants {

    // ========================================================================
    // --- [1] BASIC METADATA & IDENTITY ---
    // ========================================================================

    val CONFIG_NAMES = listOf(
        "Anichin:::Anichin",
        "Animasu:::Animasu🐰",
        "Donghuastream:::Donghuastream",
        "LayarKaca21:::LayarKaca",
        "Pencurimovie:::Pencurimovie",
        "Samehadaku:::Samehadaku⛩️",
        "GLOBAL:::Base HTML Provider"
    )

    val CONFIG_MAIN_URLS = listOf(
        "Anichin:::https://anichin.cafe",
        "Animasu:::https://v1.animasu.top",
        "Donghuastream:::https://donghuastream.org",
        "LayarKaca21:::https://lk21.de",
        "Pencurimovie:::https://ww73.pencurimovie.bond",
        "Samehadaku:::https://v1.samehadaku.how",
        "GLOBAL:::https://example.com"
    )

    val CONFIG_SERIES_URLS = listOf(
        "LayarKaca21:::https://series.lk21.de",
        "GLOBAL:::"
    )

    val CONFIG_SEARCH_URLS = listOf(
        "LayarKaca21:::https://gudangvape.com",
        "GLOBAL:::"
    )

    val CONFIG_LANGS = listOf(
        "Donghuastream:::zh",
        "GLOBAL:::id"
    )

    val CONFIG_SUPPORTED_TYPES = listOf(
        "Anichin:::Anime,AnimeMovie,TvSeries",
        "Animasu:::Anime,AnimeMovie,OVA",
        "Donghuastream:::Anime",
        "LayarKaca21:::Movie,TvSeries,AsianDrama",
        "Pencurimovie:::Movie,Anime,Cartoon",
        "Samehadaku:::Anime,AnimeMovie,OVA",
        "GLOBAL:::Anime,AnimeMovie,TvSeries,Movie,AsianDrama"
    )

    // ========================================================================
    // --- [2] ENGINE & NAVIGATION PATTERNS ---
    // ========================================================================

    val CONFIG_SEARCH_PATH_PATTERNS = listOf(
        "Anichin:::{baseUrl}/page/{page}/?s={query}",
        "Animasu:::{baseUrl}/?s={query}",
        "Donghuastream:::{baseUrl}/pagg/{page}/?s={query}",
        "LayarKaca21:::https://gudangvape.com/search.php?s={query}",
        "Pencurimovie:::{baseUrl}/?s={query}",
        "Samehadaku:::{baseUrl}/?s={query}",
        "GLOBAL:::{baseUrl}/page/{page}/?s={query}"
    )

    val CONFIG_MAIN_PAGE_PATH_PATTERNS = listOf(
        "Anichin:::{baseUrl}/{data}{page}",
        "Animasu:::{baseUrl}/pencarian/?{data}&halaman={page}",
        "Donghuastream:::{baseUrl}/{data}{page}",
        "LayarKaca21:::{data}{page}",
        "Pencurimovie:::{baseUrl}/{data}/page/{page}",
        "Samehadaku:::{baseUrl}/{data}{page}",
        "GLOBAL:::{baseUrl}/{data}{page}"
    )

    val CONFIG_MOVIE_PATH_SEGMENTS = listOf(
        "Anichin:::-movie-",
        "Donghuastream:::-movie-",
        "LayarKaca21:::/movie/",
        "Pencurimovie:::/movies/",
        "Samehadaku:::/movie/",
        "GLOBAL:::/movie/"
    )

    val CONFIG_TV_PATH_SEGMENTS = listOf(
        "LayarKaca21:::/series/",
        "Pencurimovie:::/series/",
        "Samehadaku:::/anime/",
        "GLOBAL:::/anime/"
    )

    val CONFIG_EPISODE_DATA_URL_PATTERNS = listOf("GLOBAL:::{url}")
    val CONFIG_SEARCH_PAGE_LIMITS = listOf("Anichin:::3", "Animasu:::1", "Donghuastream:::3", "LayarKaca21:::1", "GLOBAL:::2")
    val CONFIG_REVERSE_EPISODES = listOf("LayarKaca21:::false", "Pencurimovie:::false", "GLOBAL:::true")

    // --- JSON Search Properties ---
    val CONFIG_SEARCH_IS_JSON = listOf("LayarKaca21:::true", "GLOBAL:::false")
    val CONFIG_SEARCH_JSON_ROOTS = listOf("LayarKaca21:::data", "GLOBAL:::data")
    val CONFIG_SEARCH_JSON_TITLES = listOf("LayarKaca21:::title", "GLOBAL:::title")
    val CONFIG_SEARCH_JSON_HREFS = listOf("LayarKaca21:::slug", "GLOBAL:::slug")
    val CONFIG_SEARCH_JSON_POSTERS = listOf("LayarKaca21:::poster", "GLOBAL:::poster")
    val CONFIG_SEARCH_JSON_POSTER_PREFIXES = listOf("LayarKaca21:::https://static-jpg.lk21.party/wp-content/uploads/", "GLOBAL:::")
    val CONFIG_SEARCH_JSON_TYPES = listOf("LayarKaca21:::type", "GLOBAL:::type")

    // ========================================================================
    // --- [3] MAIN PAGE CONTENT LISTS ---
    // ========================================================================

    val CONFIG_MAIN_PAGE_LISTS = listOf(
        "Anichin:::seri/?status=&type=&order=popular&page=|Popular Donghua;seri/?status=&type=&order=update&page=|Recently Updated;seri/?sub=&order=latest&page=|Latest Added;seri/?status=ongoing&type=&order=update&page=|Ongoing;seri/?status=completed&type=&order=update&page=|Completed",
        "Animasu:::urutan=update|Baru diupdate;status=&tipe=&urutan=publikasi|Baru ditambahkan;status=&tipe=&urutan=populer|Terpopuler;status=&tipe=&urutan=rating|Rating Tertinggi;status=&tipe=Movie&urutan=update|Movie Terbaru;status=&tipe=Movie&urutan=populer|Movie Terpopuler",
        "Donghuastream:::anime/?status=&type=&order=update&page=|Recently Updated;anime/?status=completed&type=&order=update|Completed;anime/?status=&type=special&sub=&order=update|Special Anime",
        "LayarKaca21:::https://lk21.de/populer/page/|Film Terpopuler;https://lk21.de/rating/page/|Film Berdasarkan IMDb Rating;https://lk21.de/most-commented/page/|Film Dengan Komentar Terbanyak;https://series.lk21.de/latest-series/page/|Series Terbaru;https://series.lk21.de/series/asian/page/|Film Asian Terbaru;https://lk21.de/latest/page/|Film Upload Terbaru",
        "Pencurimovie:::movies|Latest Movies;series|TV Series;most-rating|Most Rating Movies;top-imdb|Top IMDB Movies",
        "Samehadaku:::page/|Episode Terbaru;daftar-anime-2/?title=&status=&type=TV&order=popular&page=|TV Populer;daftar-anime-2/?title=&status=&type=OVA&order=title&page=|OVA;daftar-anime-2/?title=&status=&type=Movie&order=title&page=|Movie",
        "GLOBAL:::trending/page/|Sedang Tren;terbaru/page/|Update Terbaru"
    )

    // ========================================================================
    // --- [4] CSS SELECTORS (SEARCH & LOAD) ---
    // ========================================================================

    val SEARCH_ITEMS = listOf(
        "LayarKaca21:::article, div.content-main article, div#gmr-main-load article",
        "Anichin,Donghuastream:::div.listupd > article",
        "Samehadaku:::div.animposx, div.post-show ul li",
        "Animasu:::div.listupd div.bs",
        "Pencurimovie:::div.ml-item",
        "GLOBAL:::article, .listupd .bsx, .item"
    )

    val SEARCH_TITLE = listOf(
        "LayarKaca21:::h3, h2, a[title]", 
        "Samehadaku:::h2.entry-title a, .title", 
        "Anichin,Donghuastream:::div.bsx > a, a", 
        "Animasu:::div.tt", 
        "Pencurimovie:::a[oldtitle], a[title]", 
        "GLOBAL:::h3, h2, .title"
    )
    
    val SEARCH_HREF = listOf("LayarKaca21:::a", "Anichin,Donghuastream:::div.bsx > a, a", "GLOBAL:::a")
    
    val SEARCH_POSTER = listOf(
        "Samehadaku:::div.animposx img, .content-thumb img",
        "Pencurimovie:::a img[data-original], a img[data-src]", 
        "Donghuastream:::div.bsx a img", 
        "Animasu:::div.limit img, img[data-src], .thumb img", 
        "LayarKaca21:::div.poster img, img[data-src], img[src]", 
        "Anichin:::div.bsx img", 
        "GLOBAL:::img"
    )
    
    val SEARCH_RATING = listOf("Samehadaku:::.rtng, .score", "LayarKaca21:::span.rating", "GLOBAL:::.rating, .score")
    val SEARCH_EP_TEXT = listOf("Samehadaku:::.eps span, .epx", "LayarKaca21:::span.episode strong", "Anichin:::div.bsx span.epx", "Animasu:::span.epx", "GLOBAL:::.ep, .episode")

    val LOAD_TITLE = listOf("LayarKaca21:::div.movie-info h1, h1.entry-title", "Animasu:::h1[itemprop=headline], div.infox h1", "Pencurimovie:::div.mvic-desc h3", "Anichin,Donghuastream,Samehadaku:::h1.entry-title, h1.title", "GLOBAL:::h1")
    val LOAD_POSTER = listOf("LayarKaca21:::div.poster img, img[data-src]", "Pencurimovie:::div.mvic-thumb img", "Donghuastream:::div.thumb > img, img.ts-post-image", "Anichin,Animasu,Samehadaku:::div.thumb img", "Animasu:::div.bigcontent img", "GLOBAL:::.thumb img, .poster img")
    val LOAD_BANNER = listOf("GLOBAL:::.banner img, .backdrop img")
    
    val LOAD_DESC = listOf(
        "Pencurimovie:::div.desc p.f-desc", 
        "Samehadaku:::div.description p, .entry-content", 
        "Anichin:::div.description, .entry-content, .desc", 
        "Animasu:::div.sinopsis, .desc", 
        "LayarKaca21:::div.meta-info, div.description", 
        "GLOBAL:::.description, .plot, .entry-content"
    )
    
    val LOAD_INFO_BOX = listOf("Animasu:::div.infox div.spe", "Pencurimovie:::div.mvic-info", "Samehadaku:::div.spe", "Anichin,Donghuastream:::.spe", "GLOBAL:::.info")
    val LOAD_TAGS = listOf("Pencurimovie:::div.mvic-info p:contains(Genre) a", "Animasu:::span:contains(Genre:) a", "LayarKaca21:::div.tag-list span", "Samehadaku:::div.genre-info a", "GLOBAL:::.genre a")
    val LOAD_RATING = listOf("LayarKaca21:::div.info-tag strong", "GLOBAL:::.rating, .score")
    val LOAD_STATUS = listOf("Samehadaku:::div.spe span:contains(Status)", "Animasu:::span:contains(Status:) font", "GLOBAL:::.status")
    val LOAD_QUALITY = listOf("GLOBAL:::.quality")
    val LOAD_TRAILER = listOf("LayarKaca21:::ul.action-left > li:nth-child(3) > a", "Samehadaku:::iframe[src*=\"youtube\"]", "GLOBAL:::div.trailer iframe")
    val LOAD_RECOMMEND = listOf("LayarKaca21:::li.slider article", "GLOBAL:::.recommendations article")

    val EPISODE_ITEMS = listOf("Animasu:::ul#daftarepisode > li", "Samehadaku:::div.lstepsiode ul li", "Pencurimovie:::div.tvseason div.les-content a", "Anichin,Donghuastream:::.eplister li", "GLOBAL:::.ep-list li")
    val EPISODE_HREF = listOf("Samehadaku:::a", "LayarKaca21:::a", "Pencurimovie:::a", "Anichin,Donghuastream:::.eplister li > a", "GLOBAL:::a")
    val EPISODE_TITLE = listOf("Samehadaku:::a", "Anichin,Donghuastream:::.epl-title", "Animasu:::a", "GLOBAL:::.title")
    val EPISODE_NUM = listOf("Anichin,Donghuastream:::.epl-num", "GLOBAL:::.ep-num")
    val EPISODE_DESC = listOf("GLOBAL:::.ep-desc")
    val EPISODE_TIME = listOf("GLOBAL:::.ep-duration")

    val LINK_OPTIONS = listOf(
        "Animasu:::.mobius > .mirror > option", 
        "Anichin,Donghuastream:::option[data-index], option[value]", 
        "LayarKaca21:::ul#player-list > li", 
        "GLOBAL:::select.mirror option"
    )
    val DOWNLOAD_ITEMS = listOf("Samehadaku:::div#downloadb li", "GLOBAL:::.dl-list a")
    val ACTOR_ITEMS = listOf("GLOBAL:::.cast-item")
    val ACTOR_NAME = listOf("GLOBAL:::.name")

    // ========================================================================
    // --- [5] SPECIAL CONSTANTS & STRINGS (UI & LOGIC) ---
    // ========================================================================

    val FOLLOW_LINK_SELECTOR = listOf("LayarKaca21:::a#openNow, div.links a", "GLOBAL:::")
    val CONFIG_HREF_CLEAN_REGEXPS = listOf("Animasu:::^https?://[^/]+/(?:nonton-anime-|anime-|)([a-zA-Z0-9-]+)(?:-episode-.*|-movie.*|)/?$", "GLOBAL:::")
    val CONFIG_HREF_CLEAN_REPLACES = listOf("Animasu:::https://v1.animasu.top/anime/$1", "GLOBAL:::")
    
    val CONFIG_GLOBAL_HEADERS = listOf("GLOBAL:::User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36|Accept-Language=id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
    
    // --- [HOOKS FOR HARDCODED LOGIC] ---
    val CONFIG_HOOK_IS_HORIZONTAL = listOf("Samehadaku:::true", "GLOBAL:::false")
    val CONFIG_HOOK_YEAR_EXTRACTOR = listOf("LayarKaca21:::\\\\d, (\\\\d+)", "GLOBAL:::")
    val CONFIG_HOOK_YEAR_SELECTOR = listOf("LayarKaca21:::div.movie-info h1", "GLOBAL:::")
    val CONFIG_HOOK_REFERER_PLAYER = listOf("LayarKaca21:::series_url", "GLOBAL:::current_url")
    val CONFIG_HOOK_IFRAME_SELECTORS = listOf("LayarKaca21:::div.embed-container iframe, iframe", "GLOBAL:::iframe")
    
    const val VAL_REFERER = "Referer"
    const val VAL_USER_AGENT = "User-Agent"
    const val DEFAULT_TIMEOUT = 15000L

    // UI Keywords & Semantic Mapping
    val STR_DUB = listOf("GLOBAL:::dub")
    val STR_ONGOING = listOf("GLOBAL:::Ongoing")
    val STR_EPISODE = listOf("GLOBAL:::Episode")
    val STR_SERIES = listOf("GLOBAL:::Series")

    // --- Attributes ---
    val ATTR_TITLE = listOf("GLOBAL:::title")
    val ATTR_IMAGE = listOf("GLOBAL:::data-original", "GLOBAL:::data-src", "GLOBAL:::data-lazy-src", "GLOBAL:::src")
    val ATTR_HREF = listOf("GLOBAL:::href")
    val ATTR_VALUE = listOf("GLOBAL:::value", "GLOBAL:::data-index", "GLOBAL:::data-id", "GLOBAL:::data-url", "GLOBAL:::data-link")
    val ATTR_CONTENT = listOf("GLOBAL:::content")

    val BLOAT_REGEX = Regex("(Nonton|Anime|Subtitle\\s*Indonesia|Movie|TV|Series|Lengkap|HD|Free|\\d{3,4}p|Dual\\s*Audio|TAMAT)", RegexOption.IGNORE_CASE)
}
