---
name: oce-architecture
description: OCE Architecture — module structure, data flow, mental model, dependency graph
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: architecture
---

# OCE Architecture

## Purpose

Membantu agent membangun mental model repository OCE — dari root structure hingga runtime flow. Skill ini harus dibaca SEBELUM melakukan perubahan apapun pada kode OCE.

## When to Use

- Sebelum mengedit file di BaseProvider/
- Sebelum menambah provider atau extractor baru
- Saat debugging cross-module issue
- Saat melakukan impact analysis perubahan

## When NOT to Use

- Untuk tugas simple yang hanya menyentuh 1 file ( langsung ke skill spesifik)
- Untuk editing konfigurasi JSON provider (→ `provider` skill)

## Repository Structure

```
OCE/
├── BaseProvider/                    ← KODE BERSAMA (sumber tunggal)
│   └── src/main/kotlin/com/baseprovider/
│       ├── core/                    ← Orchestration layer
│       ├── config/                  ← ProviderConfig + JSON configs
│       ├── collector/               ← Link collection dari DOM
│       ├── cache/                   ← ExpiringCache (scraper only)
│       ├── network/                 ← HttpClient, CircuitBreaker, SmartThrottle
│       ├── extractor/               ← Video extractors + MasterLinkGenerator
│       ├── log/                     ← Supabase observability
│       ├── model/                   ← Parser, SelectorResolver, SelectorValidator
│       └── settings/                ← OceSettings, SettingsDialog
│
├── ProviderAnichin/                 ← Provider SPESIFIK (thin wrapper)
│   ├── src/main/kotlin/com/Anichin/
│   │   ├── Anichin.kt               ← class Anichin : ProviderCloudstream()
│   │   └── AnichinPlugin.kt         ← register MainAPI + extractors
│   └── build.gradle.kts             ← version, metadata SAJA
├── ProviderAnimasu/                 ← Pattern sama untuk semua provider
├── ProviderAnimexin/
├── ProviderDonghuastream/
├── ProviderDutamovie21/
├── ProviderIndoDrama21/
├── ProviderLayarKaca21/
├── ProviderSamehadaku/
│
├── build.gradle.kts                 ← Root build script
├── settings.gradle.kts              ← Auto-include semua provider module
├── .github/workflows/               ← ci-cd.yml + release.yml
├── scripts/                         ← validate_providers.py, e2e tests
└── supabase/                        ← SQL migrations + dashboard queries
```

## sourceSets — Kunci Arsitektur

`BaseProvider/` dikompilasi LANGSUNG ke setiap provider via sourceSets:

```kotlin
// settings.gradle.kts
include(":BaseProvider")  // always included

// Auto-include: setiap dir dengan build.gradle.kts (kecuali BaseProvider, BaseHtmlProvider)
File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}
```

**Artinya:** perubahan di `BaseProvider/` otomatis berlaku untuk SEMUA provider. Tidak perlu sync script.

## Data Flow

### Flow 1: Main Page (getMainPage)
```
User → CloudStream App → ProviderCloudstream.getMainPage()
  → BaseProviderEngine.getMainPage()
    → ProviderScrapper.getMainPage()
      → fetchDocument(url, config)        // HTTP GET + HTML cache
      → SelectorResolver.select()         // searchItems selector
      → ProviderMapper.mapToSearchResult() // → SearchResponse items
  → HomePageResponse(listOf(HomePageList))
```

### Flow 1b: Cloudflare Challenge Handling (fetchDocument)
```
fetchDocument(url)
  → HttpClient.fetchDocument()
    → for each mirror + UA variant:
      → app.get(url) → NiceResponse
      → if code >= 400:
          → throw HttpStatusException(code, retryAfter, message, body)
      → doc.setBaseUri(res.url)           // POSTER FIX: enable absUrl()
      → return doc
    → catch HttpStatusException:
      → if CLOUDFLARE_HTTP.match(msg) OR CLOUDFLARE_HTTP.match(body):
          → WebViewCloudflareSolver.trySolve()
            → WebView load challenge
            → cf_clearance cookie → HostCookieJar
            → bind to WebView UA (solvedUserAgents[host])
          → if solved → continue@hostLoop (retry with solved UA)
      → else if code == 403:
          → continue (try next UA, NO retryAfter)
      → else if code in 500..599 / 429:
          → SmartThrottle.reportRetryAfter / reportFailure
      → break / continue to next mirror
```

### Flow 2: Search
```
User → CloudStream App → ProviderCloudstream.search(query)
  → BaseProviderEngine.search()
    → ProviderScrapper.search()
      → fetchDocument(searchUrl)          // build URL from searchPathPattern
      → SelectorResolver.select()         // searchItems selector
      → ProviderMapper.mapToSearchResult()
  → List<SearchResponse>
```

### Flow 3: Load (Detail Page)
```
User → CloudStream App → ProviderCloudstream.load(url)
  → BaseProviderEngine.load()
    → DetailPageScrapper.load(url)
      → fetchDocument(url)                // HTTP GET + HTML cache (with setBaseUri)
      → SelectorResolver.selectFirst()    // loadTitle, loadPoster, loadDesc, etc.
      → ProviderMapper.buildLoadResponse() // → LoadResponse with episodes
  → LoadResponse
```

### Poster Resolution (loadPoster / searchPoster)
```
loadPoster selector → SelectorResolver.selectValidated()
  → safeExtractImage(attributes) [ProviderParser.kt]
    → attr(name) → raw URL
    → runCatching { absUrl(name) }        // needs doc.baseUri
    → fallback to raw if absUrl fails
  → SelectorValidator.isValidPoster()
    → requires http(s):// or // prefix
  → fixUrlSmart() → final absolute URL
```

### Flow 4: Load Links (Video Extraction)
```
User → CloudStream App → ProviderCloudstream.loadLinks(data)
  → BaseProviderEngine.loadLinks()
    → ProviderScrapper.loadLinks()
      → LinkCollector.collectLinkOptions()  // option[value], a[data-url], iframes
      → LinkCollector.collectDownloadItems()
      → FallbackPipeline.processLink()     // per-link:
        → ExtractorRegistry.getMatchingExtractors(url)
        → ConfigDrivenExtractor.getUrl() or LegacyExtractor.getUrl()
          → MasterLinkGenerator.createSmartLink()
            → AdaptiveHeaderProbe.resolve()  // if bareHeaders=true
            → M3u8MasterVerifier.verify()    // if .m3u8
            → callback(ExtractorLink)
```

## Module Boundary

### BaseProvider/ (shared)
| Subdir | Responsibility | Key Classes |
|--------|---------------|-------------|
| `core/` | Orchestration | `ProviderCloudstream`, `BaseProviderEngine`, `ProviderScrapper`, `DetailPageScrapper`, `ProviderMapper` |
| `config/` | Configuration | `ProviderConfig` (90+ fields), `ConfigRegistry` (bundled-only), `ExtractorConfig`, `ExtractorConfigRegistry` |
| `collector/` | Link collection | `LinkCollector` (DOM → link candidates), `FallbackPipeline` (per-link extract) |
| `extractor/` | Video extraction | `ExtractorRegistry`, `ConfigDrivenExtractor`, `MasterLinkGenerator`, `M3u8MasterVerifier`, `AdaptiveHeaderProbe` |
| `model/` | Data models + parsing | `ProviderParser` (safeExtractImage, fixUrlSmart), `SelectorResolver`, `SelectorValidator` |
| `cache/` | HTML caching | `ExpiringCache` (scraper only, DILARANG untuk extractor) |
| `network/` | HTTP | `HttpClient` wrapper, `CircuitBreaker`, `SmartThrottle`, `DomainHelpers` |
| `log/` | Observability | `ProviderLog`, `SupabaseObservability`, `FailureType` enum |
| `settings/` | User settings | `OceSettings`, `SettingsDialog` |

### Provider*/ (per-provider wrapper)
Setiap provider hanya punya 3-4 file:
- `Nama.kt` — `class Nama : ProviderCloudstream()` (kosong, 1 baris)
- `NamaPlugin.kt` — `registerMainAPI(Nama())` + `ProviderExtractors.list.forEach { registerExtractorAPI(it) }`
- `build.gradle.kts` — version + cloudstream metadata
- `AndroidManifest.xml` — minimal

**DILARANG edit file di Provider*/ — akan ditimpa build. Selalu edit di BaseProvider/.**

## Config System

### ProviderConfig (90+ fields)
Key field categories:
- **Identity:** `id`, `name`, `mainUrl`, `lang`
- **Selectors:** `searchItems`, `searchTitle`, `searchHref`, `searchPoster`, `loadTitle`, `loadPoster`, `loadDesc`, `episodeItems`, `linkOptions`, dll
- **URL patterns:** `searchPathPattern`, `mainPagePathPattern`, `episodeDataUrlPattern`
- **Attributes:** `attrImage` (data-original, src, dll), `attrValue` (value, data-url, dll)
- **Behavior:** `reverseEpisodes`, `isJsonSearch`, `mainPageCacheBuster`, `selfExtract`
- **Quality:** `qualityStripRegex`, `bloatRegex`
- **Extractor control:** `allowedExtractors`, `skipHosts`, `iframeSelectors`

### ConfigRegistry
```
ConfigRegistry.get(providerId)
  → lookup providers[providerId] → fileName
  → loadBundled(fileName) → classLoader.getResourceAsStream("$fileName.json")
  → cache in ConcurrentHashMap
  → fallback: globalConfig (from "global.json")
```

**Bundled-only.** TIDAK ada remote fetch. Config harus ada di classpath.

### Selector Resolution
Multi-variant selector: `"h1.entry-title, h1.title, .entry-title h1"` (comma-separated).
`SelectorResolver` tries each variant, returns first match. Fallback otomatis jika selector berubah di situs.

## Key Invariants

1. **BaseProvider = single source of truth** — semua shared logic di sini
2. **Provider wrapper = thin** — hanya register class
3. **Config-driven** — selector di JSON, bukan di kode
4. **No local gradle build** — CI only
5. **ExpiringCache = scraper only** — DILARANG untuk extractor
6. **All extractors lewat MasterLinkGenerator** — proteksi 3002 global
7. **Telegram = deleted** — logging hanya ke Supabase

## Verification

Sebelum claim memahami arsitektur:
- [ ] Bisa sebutkan 7 subdir di BaseProvider/
- [ ] Bisa trace flow dari getMainPage() hingga HTTP request
- [ ] Bisa sebutkan semua fields di ProviderConfig yang relevant untuk 1 provider
- [ ] Bisa jelaskan perbedaan BaseProvider/ vs Provider*/
- [ ] Bisa explain bagaimana sourceSets bekerja

## Related Skills

- `provider` — cara edit/tambah provider
- `extraction` — cara kerja extractor system
- `logging` — observability & FailureType
- `build-deploy` — CI/CD pipeline
- `selector-checker` — verifikasi selector
