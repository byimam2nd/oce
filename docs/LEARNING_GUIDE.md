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
│   ├── ConfigRegistry.kt        ← bundled-only config resolution (classpath)
│   └── *.json                   ← per-provider config files (Anichin, Animasu, ...)
│
├── collector/               ← generated link pipeline
│   ├── LinkCollector.kt          ← collect/aggregate stream links
│   └── FallbackPipeline.kt       ← chain fallback across extractors
│
├── cache/                   ← ExpiringCache, AdaptiveDecryptCache
├── network/                 ← HttpClient.kt (fetch + retry), CircuitBreaker, SmartThrottle
├── extractor/               ← Video host extractors (JS Packer, API, WebView) + ExtractorRegistry,
│                              MasterLinkGenerator, M3u8MasterVerifier, AdaptiveHeaderProbe
├── log/                     ← Supabase observability, LogLevel, FailureType
├── model/                   ← ProviderModels, SelectorResolver (selector fallback/fingerprint)
└── settings/                ← OceSettings, SettingsDialog

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
1. Bundled: muat resource `classpath:/<name>.json` (di-cache per proses)
2. GLOBAL: `global.json` (fallback jika provider tidak terdaftar / load gagal)

> ⚠️ `ConfigRegistry` saat ini **bundled-only** — TIDAK ada fetch remote.
> Jangan menulis dokumentasi yang mengklaim remote-first sebelum fitur itu ada.

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

- **FAIL/ERROR/CRITICAL** → Supabase tabel `logs` (batch insert, fire-and-forget)
  via `log/Logging.kt` — **Telegram sudah dihapus** (commit `bf68862`).
- **Observability lifecycle**: `scrape_runs` + `scrape_steps` via
  `log/SupabaseObservability.kt` (fire-and-forget, config env `SUPABASE_*`).
- **Log fields**: `level|tag|stage|method|failure_type|extractor|host|url|selectors|attempt|duration_ms|run_id|traceback`
- **FailureType**: klasifikasi (misal `NETWORK`, `HTTP`, `TIMEOUT`, `URL`, `SELECTOR`) di `log/FailureType.kt`
- **M3u8MasterVerifier**: master m3u8 malformed (variant tanpa URI) dicegah →
  `logFail` `INVALID_URL` stage `VERIFY`; tidak pernah muncul sebagai 3002 di player.

## Extractor Architecture

Setiap extractor extends `ExtractorApi()`:
- `name` — display name
- `mainUrl` — domain untuk matching
- `requiresReferer` — apakah perlu referer header
- `getUrl()` — ekstraksi video dari URL

Extractors terdaftar di `extractor/ExtractorRegistry.kt` (`ProviderExtractors.list`).
Matching: domain URL dinormalisasi vs `mainUrl` extractor (`getMatchingExtractors`).

Extractor config-driven: id di `configDrivenIds` memakai `ConfigDrivenExtractor`
(konfigurasi JSON di `config/extractors/<Id>.json`); sisanya class legacy.

Extractors memakai beberapa pendekatan (berurutan):
1. **JS Packer decode** — `findPackedJsInPage()` + `decodePackedJs()` + `extractAllVideoUrls()`
2. **WebViewResolver** — untuk halaman JS-heavy (CloudStream WebView)
3. **Deep scan** — regex-based URL extraction dari raw HTML
4. **AJAX/API** — POST/DECRYPT untuk host tertentu (AWSStream, Dhcplay, dll)

Semua link deliver via `MasterLinkGenerator.createSmartLink(...)`:
- Guard `url.isBlank()` → `logFail` `INVALID_URL`
- Link m3u8 adaptive → `M3u8MasterVerifier.verify()` (proteksi 3002)
- Link bare → `AdaptiveHeaderProbe.resolve()` (probe header tercepat/valid)
- **ATURAN: extractor TIDAK boleh cache hasil fetch** — selalu fetch ulang.