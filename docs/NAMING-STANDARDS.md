# Naming Standards for CloudStream Providers

## 📋 Overview

This document defines the naming conventions used across all provider implementations to ensure consistency, readability, and maintainability.

---

## 1. Cache Variables

### Standard Pattern
```kotlin
private val searchCache = CacheManager<List<SearchResponse>>(defaultTtl = 5 * 60 * 1000L)
private val mainPageCache = CacheManager<HomePageResponse>(defaultTtl = 3 * 60 * 1000L)
private val loadCache = CacheManager<LoadResponse>(defaultTtl = 10 * 60 * 1000L)
```

### Rules
- **Naming**: `searchCache`, `mainPageCache`, `loadCache` (camelCase)
- **TTL Values**:
  - Search: 5 minutes (`5 * 60 * 1000L`)
  - Main Page: 3 minutes (`3 * 60 * 1000L`)
  - Load: 10 minutes (`10 * 60 * 1000L`)
- **Visibility**: Always `private`
- **Type**: Always `CacheManager<T>`

### ❌ Avoid
```kotlin
private val cache = ConcurrentHashMap<String, CacheEntry>()  // Too generic
private val search_cache = ...  // snake_case not used in Kotlin
private val searchCache = CacheManager<List<SearchResponse>>()  // Missing TTL
```

---

## 2. Timeout Variables

### Standard Pattern
```kotlin
private val requestTimeout = 10_000L  // 10 seconds
```

### Rules
- **Naming**: `requestTimeout`
- **Value**: `10_000L` (10 seconds, underscore for readability)
- **Type**: `Long`
- **Visibility**: `private`

### ❌ Avoid
```kotlin
private val timeout = 5000L  // Too generic, wrong value
private val REQUEST_TIMEOUT = 10000L  // UPPER_SNAKE_CASE for val
private val requestTimeout = 10000L  // Missing underscore for readability
```

---

## 3. Link Normalization Functions

### Standard Pattern
```kotlin
private fun normalizeLink(url: String): String {
    // Implementation specific to each provider
    return if (url.startsWith("/")) "$mainUrl$url" else url
}
```

### Rules
- **Naming**: `normalizeLink`
- **Input**: `url: String`
- **Output**: `String`
- **Visibility**: `private`
- **Suspend**: Only if network call is needed

### ❌ Avoid
```kotlin
private fun getProperLink(uri: String): String { ... }  // Inconsistent name
private fun getProperAnimeLink(uri: String): String { ... }  // Too specific
private fun normalizeUrl(url: String): String { ... }  // Different name
```

---

## 4. Element Extension Functions

### Standard Pattern
```kotlin
private fun Element.toSearchResult(): SearchResponse {
    val title = this.select("h3").text().trim()
    val href = fixUrl(this.select("a").attr("href"))
    val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))
    
    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = poster
    }
}
```

### Rules
- **Naming**: `toSearchResult()`
- **Visibility**: `private`
- **Suspend**: Never (should not make network calls)
- **Return**: Non-nullable `SearchResponse` (or specific type like `AnimeSearchResponse`)

### ❌ Avoid
```kotlin
fun Element.toSearchResult(): SearchResponse { ... }  // Public visibility
suspend fun Element.toSearchResult(): SearchResponse { ... }  // Unnecessary suspend
private fun Element.toSearchResult(): SearchResponse? { ... }  // Nullable return
```

---

## 5. Quality Extraction Functions

### Standard Pattern
```kotlin
private fun extractQuality(element: Element): SearchQuality {
    val qualityText = element.select("div.quality-badge").text().uppercase()
    
    return when {
        qualityText.contains("CAM") -> SearchQuality.Cam
        qualityText.contains("WEB") -> SearchQuality.WebRip
        qualityText.contains("BLURAY") -> SearchQuality.BlueRay
        else -> SearchQuality.HD
    }
}
```

### Rules
- **Naming**: `extractQuality`
- **Input**: `element: Element`
- **Output**: `SearchQuality` enum
- **Visibility**: `private`

### ❌ Avoid
```kotlin
fun getSearchQuality(parent: Element): SearchQuality { ... }  // Different name
private fun getIndexQuality(str: String?): Int { ... }  // Different input/output
private fun detectQuality(element: Element): String { ... }  // Wrong return type
```

---

## 6. Main URL Variable

### Standard Pattern
```kotlin
override var mainUrl = "https://example.com"
```

### Rules
- **Naming**: `mainUrl` (override from MainAPI)
- **Spacing**: Single space around `=`
- **Quotes**: Double quotes for URLs

### ❌ Avoid
```kotlin
override var mainUrl              = "https://example.com"  // Extra spacing
override var mainUrl="https://example.com"  // Missing spaces
override val mainUrl = "https://example.com"  // Should be var
```

---

## 7. Section Headers

### Standard Pattern
```kotlin
// ========================================
// CACHE INSTANCES
// ========================================

// ========================================
// MAIN PAGE CATEGORIES
// ========================================

// ========================================
// GET MAIN PAGE
// ========================================

// ========================================
// SEARCH
// ========================================

// ========================================
// LOAD DETAIL PAGE
// ========================================

// ========================================
// LOAD LINKS (VIDEO SOURCES)
// ========================================
```

### Rules
- Use `//` comments with `=` separators
- ALL CAPS for section names
- Consistent separator length (40 characters)

---

## 8. Provider Header

### Standard Pattern
```kotlin
// ========================================
// PROVIDER NAME PROVIDER
// ========================================
// Site: https://example.com
// Type: Movie/TV Series/Anime
// Language: Indonesian (id)
// Standard: cloudstream-ekstension
// ========================================
```

### Rules
- Include site URL, content type, language
- Use consistent format across all providers

---

## 9. Function Documentation

### Standard Pattern
```kotlin
// ========================================
// GET MAIN PAGE
// ========================================
// Fetches category listings with pagination
// Results are cached to avoid redundant requests
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
```

### Rules
- Section header before each major function
- Brief description of what the function does
- Mention caching behavior if applicable

---

## 10. Variable Naming Conventions

### General Rules
- **camelCase** for all variables and functions
- **Descriptive names** - avoid single letter variables except in lambdas
- **Boolean variables** - prefix with `is`, `has`, `can`, `should`
- **Collections** - plural names (`episodes`, `results`, `urls`)

### Examples
```kotlin
val cacheKey = "${request.data}${page}"  // ✅ Descriptive
val isMovie = document.select("div.movie").isNotEmpty()  // ✅ Boolean prefix
val episodes = document.select("div.episode").map { ... }  // ✅ Plural for collections
val x = document.select("div")  // ❌ Single letter
val data = fetch()  // ❌ Too generic
```

---

## Summary Table

| Category | Standard Name | Visibility | Notes |
|----------|--------------|------------|-------|
| Search Cache | `searchCache` | private | TTL: 5 minutes |
| Main Page Cache | `mainPageCache` | private | TTL: 3 minutes |
| Load Cache | `loadCache` | private | TTL: 10 minutes |
| Timeout | `requestTimeout` | private | Value: `10_000L` |
| Link Normalizer | `normalizeLink()` | private | Input: String, Output: String |
| Search Result Parser | `toSearchResult()` | private | Extension on Element |
| Quality Extractor | `extractQuality()` | private | Input: Element, Output: SearchQuality |
| Main URL | `mainUrl` | override var | Single space around `=` |
