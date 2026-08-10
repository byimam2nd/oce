# 🧠 Architecture Guide

## File Map

```
BaseProvider/src/main/kotlin/com/baseprovider/
│
├── core/                    ← Provider engine & flow
│   ├── BaseProviderEngine.kt    ← engine lifecycle, page/load link
│   ├── DetailPageScrapper.kt    ← selector-driven page scrape, watchUrl resolution
│   ├── ProviderCloudstream.kt   ← MainAPI adapter (thin bridge)
│   ├── ProviderScrapper.kt      ← HTTP fetch, retry, throttle
│   └── ProviderMapper.kt        ← HTML elements → CloudStream data objects
│
├── config/                  ← Config-driven provider (the "source of truth")
│   ├── ProviderConfig.kt        ← data class: selectors, options, defaults
│   ├── ProviderConfigParser.kt  ← JSON → ProviderConfig
│   ├── ConfigRegistry.kt        ← remote-first config resolution (raw.githubusercontent)
│   └── *.json                   ← per-provider config files (Anichin, Animasu, ...)
│
├── collector/               ← generated link pipeline
│   ├── LinkCollector.kt          ← collect/aggregate stream links
│   └── FallbackPipeline.kt       ← chain fallback across extractors
│
├── cache/                   ← ExpiringCache (per-provider TTL)
├── network/                 ← HttpClient.kt (fetch + retry), CircuitBreaker, SmartThrottle
├── extractor/               ← Video host extractors (JS Packer, API, WebView) + ExtractorRegistry
├── log/                     ← Logging.kt (Telegram), LogLevel, FailureType
└── model/                   ← ProviderModels, ProviderParser (attribute & text utilities)

ProviderAnichin/             ← Thin wrapper module (3-4 files, ~10 lines each)
├── Anichin.kt               ← class Anichin : ProviderCloudstream()
├── AnichinPlugin.kt         ← registerMainAPI()
└── build.gradle.kts
```

## Config-Driven Provider (Owner Tagging replacement)

Semua selector & opsi disimpan sebagai **JSON config per provider** di
`BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json` — bukan lagi konstanta
ber-label "Owner Tagging".

Resolve order (`ConfigRegistry.get(providerId)`):
1. Remote: ambil `https://raw.githubusercontent.com/.../config/<name>.json` (fresh deploy tanpa rebuild)
2. Bundled: muat resource `classpath:/<name>.json` (fallback offline)
3. GLOBAL: `global.json` (fallback terakhir)
4. Jika semua gagal → `ProviderConfig(id=..., mainUrl=default)` + log warning

Contoh `anichin.json`:
```json
{
  "id": "Anichin",
  "mainUrl": "https://anichin.cafe",
  "supportedTypes": ["Anime", "AnimeMovie", "TvSeries"],
  "searchPathPattern": "{baseUrl}/page/{page}/?s={query}",
  "searchItems": "div.listupd article.bs",
  "episodeItems": ".eplister li",
  "watchButtons": ".eplister li > a, .play-button, .watch-now, .btn-watch"
}
```

## Core Data Pipeline

```
User Input (search/load)
    ↓
ProviderCloudstream.kt (MainAPI) → BaseProviderEngine.kt
    ↓
ProviderScrapper.kt (HTTP fetch + retry/throttle)
    ↓
ProviderMapper.kt (HTML → SearchResponse/LoadResponse/Episode)
    ↓
*json config (selectors resolved via ProviderConfig)
    ↓
collector/LinkCollector.kt + extractor/ExtractorRegistry (video extraction)
    ↓
CloudStream Player
```

## Selector Semantics

- Selector fields di config bersifat **optional** — parser menerapkan default
  (`ProviderConfigParser.kt:11-97`). Field kosong = fallback ke default, **bukan error**.
- Sangat dianjurkan mengisi selector yang dibutuhkan jenis konten:
  - `Movie`/`AnimeMovie` → `watchButtons` wajib eksplisit (default `.play-button, .watch-now, .btn-watch`
    tidak cocok untuk semua template).
  - `TvSeries`/`Anime` → `episodeItems` + `episodeHref` wajib eksplisit.
  - Non-JSON search → `searchItems`, `searchTitle`, `searchHref` wajib.

## Logging System

- **FAIL/ERROR/CRITICAL** → Telegram group (topic OCE_logs) via `log/Logging.kt`
- **Dedup key**: `level|tag|method|host` — errors berurutan melebur dengan counter [N]
- **Format**: `[N]❌[FAIL]Provider/method/url/selectors/message`
- **FailureType**: klasifikasi (misal `HTTP`, `PARSE`, `TIMEOUT`) di `log/FailureType.kt`

## Extractor Architecture

Setiap extractor extends `ExtractorApi()`:
- `name` — display name
- `mainUrl` — domain untuk matching
- `requiresReferer` — apakah perlu referer header
- `getUrl()` — ekstraksi video dari URL

Extractors terdaftar di `extractor/ExtractorRegistry.kt` (`ProviderExtractors.list`).
Matching: domain URL dinormalisasi vs `mainUrl` extractor (`getMatchingExtractors`).

Extractors memakai beberapa pendekatan (berurutan):
1. **JS Packer decode** — `findPackedJsInPage()` + `decodePackedJs()` + `extractAllVideoUrls()`
2. **WebViewResolver** — untuk halaman JS-heavy (CloudStream WebView)
3. **Deep scan** — regex-based URL extraction dari raw HTML
4. **AJAX/API** — POST/DECRYPT untuk host tertentu (AWSStream, Dhcplay, dll)