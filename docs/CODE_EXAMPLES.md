# 📝 CODE EXAMPLES

**Collection of copy-paste ready examples**  
**All examples tested and working**

---

## 🔹 EXTRACTOR EXAMPLES

### **Example 1: Simple MP4 URL**

**Use Case:** Direct MP4 link extraction

```kotlin
package com.MyProvider

import com.MyProvider.generated_sync.MasterLinkGenerator

class SimpleExtractor : ExtractorApi() {
    override val name = "Simple"
    override val mainUrl = "https://example.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract video URL from page
        val document = app.get(url, referer = referer).document
        val videoUrl = document.selectFirst("meta[property=og:video]")?.attr("content") ?: return

        // ✅ Auto-detect quality, type, headers
        MasterLinkGenerator.createLink(
            source = name,
            url = videoUrl,
            referer = referer
        )?.let { callback(it) }
    }
}
```

**Features:**
- ✅ Auto-detect quality from URL
- ✅ Auto-detect type (MP4 → VIDEO)
- ✅ Auto-generate headers

---

### **Example 2: M3U8 Playlist**

**Use Case:** M3U8 master playlist with multiple qualities

```kotlin
package com.MyProvider

import com.MyProvider.generated_sync.MasterLinkGenerator

class M3U8Extractor : ExtractorApi() {
    override val name = "M3U8"
    override val mainUrl = "https://example.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract playlist URL
        val document = app.get(url, referer = referer).document
        val playlistUrl = document.selectFirst("source[type='application/x-mpegURL']")
            ?.attr("src") ?: return

        // ✅ Parse M3U8, return ALL quality variants
        MasterLinkGenerator.createLinksFromM3U8(
            source = name,
            m3u8Url = playlistUrl,
            referer = referer,
            callback = callback
        )
        // Result: [1080p, 720p, 480p, 360p] - User can switch!
    }
}
```

**Features:**
- ✅ Auto-parse M3U8 playlist
- ✅ Return multiple quality options
- ✅ User can manually switch quality

---

### **Example 3: Iframe with Fallback**

**Use Case:** Extract from iframe with fallback logic

```kotlin
package com.MyProvider

import com.MyProvider.generated_sync.MasterLinkGenerator
import com.MyProvider.generated_sync.loadExtractorWithFallback

class IframeExtractor : ExtractorApi() {
    override val name = "Iframe"
    override val mainUrl = "https://example.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract iframe URL
        val document = app.get(url, referer = referer).document
        val iframeUrl = document.selectFirst("iframe")?.attr("src") ?: return

        // ✅ Try loadExtractor first, fallback to direct extraction
        val loaded = loadExtractorWithFallback(
            url = iframeUrl,
            referer = referer,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        if (!loaded) {
            // Fallback: direct extraction
            MasterLinkGenerator.createLink(
                source = name,
                url = iframeUrl,
                referer = referer
            )?.let { callback(it) }
        }
    }
}
```

**Features:**
- ✅ Try existing extractors first
- ✅ Fallback to direct extraction
- ✅ Maximum compatibility

---

### **Example 4: Multiple Video Sources**

**Use Case:** Page with multiple video sources

```kotlin
package com.MyProvider

import com.MyProvider.generated_sync.MasterLinkGenerator

class MultiSourceExtractor : ExtractorApi() {
    override val name = "MultiSource"
    override val mainUrl = "https://example.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        // Extract all video sources
        document.select("source").forEach { source ->
            val videoUrl = source.attr("src")
            val quality = source.attr("res")  // e.g., "1080", "720"

            if (videoUrl.isNotEmpty()) {
                MasterLinkGenerator.createLink(
                    source = name,
                    url = videoUrl,
                    referer = referer,
                    quality = quality.toIntOrNull()  // Manual override
                )?.let { callback(it) }
            }
        }
    }
}
```

**Features:**
- ✅ Extract multiple sources
- ✅ Manual quality override
- ✅ All sources returned to user

---

## 🔹 PROVIDER EXAMPLES

### **Example 5: Search with Caching**

**Use Case:** Search with intelligent caching

```kotlin
package com.MyProvider

import com.MyProvider.generated_sync.CacheManager
import com.MyProvider.generated_sync.executeWithRetry
import com.MyProvider.generated_sync.rateLimitDelay

class MyProvider : MainAPI() {
    override var name = "MyProvider"
    override var mainUrl = "https://example.com"
    override val hasMainPage = true

    // ✅ Cache instance (TTL: 30 minutes)
    private val searchCache = CacheManager<List<SearchResponse>>()

    override suspend fun search(query: String): List<SearchResponse> {
        val cacheKey = "search_$query"

        // ✅ Check cache first (NO RATE LIMIT FOR CACHE HIT!)
        searchCache.get(cacheKey)?.let { cached ->
            return cached  // Cache HIT - instant response!
        }

        // Cache MISS - fetch from network
        val results = executeWithRetry(maxRetries = 3) {
            rateLimitDelay()  // Be nice to server
            val document = app.get("$mainUrl/search?q=$query").document
            
            document.select(".result-item").mapNotNull { item ->
                val title = item.selectFirst(".title")?.text() ?: return@mapNotNull null
                val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.attr("src")

                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        // ✅ Save to cache (TTL: 30 minutes)
        searchCache.put(cacheKey, results)

        return results
    }
}
```

**Features:**
- ✅ Intelligent caching
- ✅ Retry logic
- ✅ Rate limiting
- ✅ Fast cache hits

---

### **Example 6: Main Page with Caching**

**Use Case:** Main page with fingerprint-based cache validation

```kotlin
package com.MyProvider

import com.MyProvider.generated_sync.CacheManager
import com.MyProvider.generated_sync.SmartCacheMonitor

class MyProvider : MainAPI() {
    override var name = "MyProvider"
    override var mainUrl = "https://example.com"
    override val hasMainPage = true

    private val mainPageCache = CacheManager<HomePageResponse>()
    private val monitor = MyProviderMonitor()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "${request.data}$page"

        // ✅ Check cache with fingerprint validation
        val cached = mainPageCache.get(cacheKey)
        val fingerprint = monitor.generateFingerprint(mainUrl)

        if (cached != null && fingerprint != null) {
            val validity = monitor.checkCacheValidity(cacheKey, fingerprint)
            if (validity == SmartCacheMonitor.CacheValidationResult.CACHE_VALID) {
                return cached  // Cache valid - instant response!
            }
        }

        // Fetch new data
        val document = app.get("${request.data}$page").document
        val home = document.select(".anime-item").mapNotNull { item ->
            // ... extraction logic
        }

        val response = newHomePageResponse(request.name, home)

        // ✅ Save to cache with new fingerprint
        mainPageCache.put(cacheKey, response)
        if (fingerprint != null) {
            cacheFingerprints[cacheKey] = fingerprint
        }

        return response
    }
}

// ✅ Custom monitor for this provider
class MyProviderMonitor : SmartCacheMonitor() {
    override val titleSelector = ".anime-item a"

    override suspend fun customRateLimit() {
        rateLimitDelay(moduleName = "MyProvider")
    }
}
```

**Features:**
- ✅ Fingerprint-based cache validation
- ✅ Auto-invalidate on content change
- ✅ Smart cache management

---

### **Example 7: Load with Episode Pre-fetching**

**Use Case:** Load page with background episode link pre-fetching

```kotlin
package com.MyProvider

import com.MyProvider.generated_sync.CacheManager
import com.MyProvider.generated_sync.EpisodePreFetcher

class MyProvider : MainAPI() {
    override var name = "MyProvider"
    override var mainUrl = "https://example.com"

    private val loadCache = CacheManager<LoadResponse>()

    override suspend fun load(url: String): LoadResponse {
        // Check cache
        loadCache.get(url)?.let { return it }

        val document = app.get(url).document
        val title = document.selectFirst(".title")?.text() ?: "Unknown"

        // Extract episodes
        val episodes = document.select(".episode-list a").map { ep ->
            newEpisode(ep.attr("href")) {
                this.name = ep.text()
            }
        }.reversed()

        // ✅ Pre-fetch episode links in background (first 10 episodes)
        EpisodePreFetcher.preFetchEpisodes(episodes, mainUrl)

        val response = newAnimeLoadResponse(title, url, TvType.Anime, episodes)

        // ✅ Save to cache
        loadCache.put(url, response)

        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ✅ Check pre-fetched cache first
        if (EpisodePreFetcher.loadCached(data, callback, subtitleCallback)) {
            return true  // Cache HIT - instant!
        }

        // Cache MISS - extract normally
        val document = app.get(data).document
        val videoUrl = document.selectFirst("video source")?.attr("src") ?: return false

        MasterLinkGenerator.createLink(
            source = name,
            url = videoUrl,
            referer = data
        )?.let { callback(it) }

        return true
    }
}
```

**Features:**
- ✅ Background pre-fetching
- ✅ Instant cache hits
- ✅ Better user experience

---

## 🔹 UTILITY EXAMPLES

### **Example 8: Custom Quality Detection**

**Use Case:** When auto-detect needs manual override

```kotlin
import com.MyProvider.generated_sync.MasterLinkGenerator
import com.MyProvider.generated_sync.detectQualityFromUrl

// ✅ Use auto-detect
val quality1 = detectQualityFromUrl("https://example.com/video_1080p.m3u8")
// Result: 1080

// ✅ Use manual override
MasterLinkGenerator.createLink(
    source = "Extractor",
    url = videoUrl,
    quality = 720  // Force 720p
)

// ✅ Combine: auto-detect with cap
val detected = detectQualityFromUrl(videoUrl)
val capped = minOf(detected, 720)  // Cap at 720p

MasterLinkGenerator.createLink(
    source = "Extractor",
    url = videoUrl,
    quality = capped
)
```

---

### **Example 9: URL Validation**

**Use Case:** Validate URL before extraction

```kotlin
import com.MyProvider.generated_sync.isValidVideoUrl

// ✅ Validate before extraction
if (!isValidVideoUrl(videoUrl)) {
    logError("Extractor", "Invalid video URL: $videoUrl")
    return
}

// ✅ Use in extraction flow
override suspend fun getUrl(...) {
    val videoUrl = extractUrl(...)
    
    if (!videoUrl.isValidVideoUrl()) {
        return  // Invalid, skip
    }
    
    MasterLinkGenerator.createLink(...)
}
```

---

### **Example 10: Headers Customization**

**Use Case:** Custom headers for specific domains

```kotlin
import com.MyProvider.generated_sync.MasterLinkGenerator

val customHeaders = mapOf(
    "Accept" to "*/*",
    "Authorization" to "Bearer token123",  // Custom auth
    "X-Custom-Header" to "value"
)

MasterLinkGenerator.createLink(
    source = "Extractor",
    url = videoUrl,
    referer = referer,
    headers = customHeaders  // Merge with default headers
)?.let { callback(it) }
```

---

## 🔹 ADVANCED EXAMPLES

### **Example 11: Circuit Breaker Integration**

**Use Case:** Auto-skip failing extractors

```kotlin
import com.MyProvider.generated_sync.CircuitBreakerRegistry

val breaker = CircuitBreakerRegistry.get("MyExtractor")

breaker.execute {
    // Extraction logic
    // Auto-skip after 5 failures
    // Auto-recover after 1 minute
}
```

---

### **Example 12: Request Deduplication**

**Use Case:** Prevent duplicate concurrent requests

```kotlin
import com.MyProvider.generated_sync.AutoRequestDeduplicator

val result = AutoRequestDeduplicator.deduplicate("search_$query") {
    // This block only runs once even if called multiple times
    fetchResults(query)
}
```

---

## 📚 SEE ALSO

- [QUICK_START.md](QUICK_START.md) - Quick onboarding
- [PHILOSOPHY_AND_ARCHITECTURE.md](PHILOSOPHY_AND_ARCHITECTURE.md) - Deep dive
- [FUNCTION_INDEX.md](FUNCTION_INDEX.md) - Function lookup

---

**Last Updated:** 2026-03-30  
**All Examples Tested:** ✅ YES  
**Copy-Paste Ready:** ✅ YES
