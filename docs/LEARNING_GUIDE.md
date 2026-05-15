# 🧠 Architecture Guide

## File Map

```
BaseProvider/src/main/kotlin/com/baseprovider/
│
├── ProviderHTMLConstants.kt    ← ALL selectors & configs (Owner Tagging)
│
├── ProviderScrapper.kt         ← HTTP layer: search, load, loadLinks
├── ProviderMapper.kt           ← HTML elements → CloudStream data objects
├── ProviderExtractors.kt       ← Video host extractors (JS Packer, API, WebView)
│
├── ProviderCloudstream.kt      ← MainAPI adapter (thin bridge)
├── BaseProviderHelpers.kt      ← Logging (Telegram), config resolution
├── ProviderParser.kt           ← Utility: attribute extraction, text cleaning
│
├── ProviderHTMLConstants.kt    ← Selectors
│
ProviderNama/                   ← Thin provider (2 files, ~5 lines each)
├── Nama.kt                     ← class Nama : ProviderCloudstream()
├── NamaPlugin.kt               ← registerMainAPI + extractors
├── build.gradle.kts
```

## Owner Tagging System

Semua selector disimpan di `ProviderHTMLConstants.kt` dengan format:

```
"ProviderID:::css-selector"
"MultiProviderID:::css-selector"
"GLOBAL:::css-selector"
```

Resolve order (`selectFirstSafe`):
1. Provider-specific: cari yang match `providerId` di owner list
2. Multi-provider: cari yang match di shared owner list
3. GLOBAL: fallback terakhir
4. Jika semua gagal → return empty, log debug

Contoh:
```kotlin
val SEARCH_TITLE = listOf(
    "Anichin,Donghuastream:::div.bsx h2, .tt, a[title]",
    "Samehadaku:::h2, .entry-title a, .title",
    "Pencurimovie:::a[oldtitle], a[title]",
    "GLOBAL:::h3, h2, .title"
)
```

## Data Pipeline

```
User Input (search/load)
    ↓
ProviderCloudstream.kt (MainAPI)
    ↓
ProviderScrapper.kt (HTTP fetch)
    ↓
ProviderMapper.kt (HTML → SearchResponse/LoadResponse/Episode)
    ↓
ProviderHTMLConstants.kt (selectors via Owner Tagging)
    ↓
ProviderExtractors.kt (video extraction via ExtractorApi)
    ↓
CloudStream Player
```

## Logging System

- **FAIL/ERROR/CRITICAL** → Telegram group (topic OCE_logs)
- **Dedup key**: `level|tag|method|host` — sequential errors merge with [N] counter
- **Format**: `[N]❌[FAIL]Provider/method/url/selectors/message`

## Extractor Architecture

Each extractor extends `ExtractorApi()`:
- `name` — display name
- `mainUrl` — domain untuk matching
- `requiresReferer` — apakah perlu referer header
- `getUrl()` — ekstraksi video dari URL

Extractors use 3 approaches (in order):
1. **JS Packer decode** — `findPackedJsInPage()` + `decodePackedJs()` + `extractAllVideoUrls()`
2. **WebViewResolver** — for JS-heavy pages (CloudStream WebView)
3. **Deep scan** — regex-based URL extraction from raw HTML
