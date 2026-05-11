# 📚 FUNCTION INDEX

**Alphabetical index of all public functions**  
**Quick lookup for common operations**

---

## A

### **autoOptimizeImage()**
- **File:** `MasterImageOptimization.kt`
- **Purpose:** Optimize image URL based on context (poster, backdrop, thumbnail)
- **Usage:**
```kotlin
val optimized = autoOptimizeImage(posterUrl, "poster")
// Result: URL with optimal width parameter
```

---

## C

### **cleanDisplayText()**
- **File:** `MasterTextCleaning.kt`
- **Purpose:** Clean text for display (remove year, resolution, etc.)
- **Usage:**
```kotlin
val cleanTitle = cleanDisplayText("Movie Title (2024) - 1920x1080")
// Result: "Movie Title"
```

### **compressImageForMobile()**
- **File:** `MasterImageOptimization.kt`
- **Purpose:** Compress image for mobile (85% quality)
- **Usage:**
```kotlin
val compressed = compressImageForMobile(imageUrl, quality = 85)
```

### **createExtractorLink()**
- **File:** `MasterAutoUsed.kt`
- **Purpose:** Create ExtractorLink with auto-detection
- **Usage:**
```kotlin
val link = createExtractorLink(
    source = "Extractor",
    url = videoUrl,
    referer = referer
)
```

### **createLinksFromM3U8()**
- **File:** `MasterExtractors.kt` (P1)
- **Purpose:** Parse M3U8 playlist, return all quality variants
- **Usage:**
```kotlin
MasterLinkGenerator.createLinksFromM3U8(
    source = "Extractor",
    m3u8Url = playlistUrl,
    referer = referer,
    callback = callback
)
// Result: [1080p, 720p, 480p, 360p]
```

---

## D

### **deduplicate()**
- **File:** `MasterRequestDeduplicator.kt`
- **Purpose:** Prevent duplicate concurrent requests
- **Usage:**
```kotlin
val result = AutoRequestDeduplicator.deduplicate("key") {
    fetchData()  // Only runs once even if called multiple times
}
```

### **detectQualityFromUrl()**
- **File:** `MasterExtractors.kt` (P1)
- **Purpose:** Auto-detect quality from URL pattern
- **Patterns:**
  - `.../video_1080p.m3u8` → 1080
  - `.../FHD/stream.m3u8` → 1080
  - `.../720p/video.m3u8` → 720
  - `.../HD/stream.m3u8` → 720
  - `.../SD/video.m3u8` → 480
  - No match → 480 (default)
- **Usage:**
```kotlin
val quality = detectQualityFromUrl("https://example.com/video_1080p.m3u8")
// Result: 1080
```

---

## E

### **executeWithRetry()**
- **File:** `MasterUtils.kt`
- **Purpose:** Execute with automatic retry on failure
- **Usage:**
```kotlin
val result = executeWithRetry(maxRetries = 3) {
    app.get(url).document
}
```

### **extractAllM3u8Urls()**
- **File:** `MasterRegexHelpers.kt`
- **Purpose:** Extract all M3U8 URLs from text
- **Usage:**
```kotlin
val urls = extractAllM3u8Urls(script, baseUrl)
```

### **extractCleanTitle()**
- **File:** `MasterTextCleaning.kt`
- **Purpose:** Extract clean title from raw string
- **Usage:**
```kotlin
val cleanTitle = extractCleanTitle("Movie Title 2024 1080p END")
// Result: "Movie Title"
```

### **extractEpisodeNumber()**
- **File:** `MasterRegexHelpers.kt`
- **Purpose:** Extract episode number from text
- **Usage:**
```kotlin
val epNum = extractEpisodeNumber("Episode 123 END")
// Result: 123
```

### **extractResolution()**
- **File:** `MasterRegexHelpers.kt`
- **Purpose:** Extract resolution from text
- **Usage:**
```kotlin
val (width, height) = extractResolution("1920x1080")
// Result: 1920 to 1080
```

### **extractSeasonNumber()**
- **File:** `MasterRegexHelpers.kt`
- **Purpose:** Extract season number from text
- **Usage:**
```kotlin
val seasonNum = extractSeasonNumber("Season 2 Episode 5")
// Result: 2
```

### **extractYear()**
- **File:** `MasterRegexHelpers.kt`
- **Purpose:** Extract year from text
- **Usage:**
```kotlin
val year = extractYear("Movie Title (2024)")
// Result: 2024
```

---

## G

### **getDefaultHttpHeaders()**
- **File:** `MasterHttpWrappers.kt`
- **Purpose:** Get default headers for requests
- **Usage:**
```kotlin
val headers = getDefaultHttpHeaders(domain = "example.com")
```

### **getOptimalWidthForType()**
- **File:** `MasterImageOptimization.kt`
- **Purpose:** Get optimal width for image type
- **Usage:**
```kotlin
val width = getOptimalWidthForType("poster")
// Result: 300
```

### **getOptimizedHttpClient()**
- **File:** `MasterHttpWrappers.kt`
- **Purpose:** Get optimized HTTP client
- **Usage:**
```kotlin
val client = getOptimizedHttpClient()
```

### **getSessionUserAgent()**
- **File:** `MasterHttpWrappers.kt`
- **Purpose:** Get session-based User-Agent
- **Usage:**
```kotlin
val userAgent = getSessionUserAgent("example.com")
```

---

## I

### **isValidVideoUrl()**
- **File:** `MasterExtractors.kt` (P1)
- **Purpose:** Validate URL as video stream
- **Validation:**
  - Must start with `http://` or `https://`
  - Must NOT end with `.html` or `.php`
  - Must have video extension OR HLS indicator
- **Usage:**
```kotlin
if (isValidVideoUrl(videoUrl)) {
    // Valid video URL
} else {
    // Invalid, skip
}
```

---

## N

### **normalizeTitle()**
- **File:** `MasterTextCleaning.kt`
- **Purpose:** Normalize title (remove special chars, trim)
- **Usage:**
```kotlin
val normalized = normalizeTitle("Movie Title: Special Edition!")
// Result: "Movie Title Special Edition"
```

---

## O

### **optimizedHttpGet()**
- **File:** `MasterHttpWrappers.kt`
- **Purpose:** Optimized HTTP GET with auto-optimizations
- **Features:**
  - HTTP/2 support
  - DNS cache
  - Connection pooling
  - Session-based User-Agent
- **Usage:**
```kotlin
val html = optimizedHttpGet(url, timeout = 10000L)
```

---

## R

### **removeBloat()**
- **File:** `MasterTextCleaning.kt`
- **Purpose:** Remove bloat words from titles
- **Usage:**
```kotlin
val clean = removeBloat("Movie Title Nonton Streaming Sub Indo")
// Result: "Movie Title"
```

### **removeNonDigits()**
- **File:** `MasterRegexHelpers.kt`
- **Purpose:** Remove non-digits from text
- **Usage:**
```kotlin
val digits = removeNonDigits("Episode 123 END")
// Result: "123"
```

---

## S

### **selectBestM3U8Quality()**
- **File:** `MasterAutoUsed.kt`
- **Purpose:** Auto-select best quality from M3U8 playlist
- **Usage:**
```kotlin
val bestUrl = selectBestM3U8Quality(
    m3u8Url = playlistUrl,
    referer = referer,
    maxQuality = 720  // Cap at 720p
)
```

---

## T

### **testStreamAccessibility()**
- **File:** `MasterAutoUsed.kt`
- **Purpose:** Test stream accessibility with HEAD request
- **Usage:**
```kotlin
val isAccessible = testStreamAccessibility(url, referer)
// Result: true/false
```

---

## QUICK REFERENCE

### **Most Common Functions:**

| Function | Use Case | File |
|----------|----------|------|
| `createExtractorLink()` | Single URL extraction | `MasterAutoUsed.kt` |
| `createLinksFromM3U8()` | M3U8 playlist parsing | `MasterExtractors.kt` |
| `detectQualityFromUrl()` | Auto-detect quality | `MasterExtractors.kt` |
| `executeWithRetry()` | Network requests | `MasterUtils.kt` |
| `isValidVideoUrl()` | URL validation | `MasterExtractors.kt` |

### **By Category:**

**ExtractorLink Creation:**
- `createExtractorLink()` - Single URL
- `createLinksFromM3U8()` - M3U8 playlist

**Quality Detection:**
- `detectQualityFromUrl()` - Auto-detect
- `selectBestM3U8Quality()` - Auto-select

**Text Cleaning:**
- `cleanDisplayText()` - Remove metadata
- `extractCleanTitle()` - Full cleaning
- `normalizeTitle()` - Normalize
- `removeBloat()` - Remove filler words

**HTTP Requests:**
- `optimizedHttpGet()` - Optimized GET
- `getOptimizedHttpClient()` - Get client
- `getDefaultHttpHeaders()` - Get headers

**Caching:**
- `CacheManager.get()` - Get from cache
- `CacheManager.put()` - Save to cache

---

## SEE ALSO

- [QUICK_START.md](QUICK_START.md) - Quick onboarding
- [CODE_EXAMPLES.md](CODE_EXAMPLES.md) - Copy-paste examples
- [PHILOSOPHY_AND_ARCHITECTURE.md](PHILOSOPHY_AND_ARCHITECTURE.md) - Deep dive

---

**Last Updated:** 2026-03-30  
**Total Functions Indexed:** 25+
