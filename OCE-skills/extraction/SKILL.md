---
name: oce-extraction
description: OCE Extraction — extractor system, config-driven, MasterLinkGenerator, 3002 protection, no-cache rule
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: extraction
---

# OCE Extraction

## Purpose

Memahami dan mengembangkan sistem extractor video OCE — config-driven extractors, MasterLinkGenerator, M3u8MasterVerifier, AdaptiveHeaderProbe, dan aturan no-cache.

## When to Use

- Menambah extractor baru
- Memperbaiki extractor yang broken
- Debugging video playback issues (3002, empty links)
- Memahami flow extraction

## When NOT to Use

- Untuk edit provider config/selector (→ `provider`)
- Untuk cek log (→ `logging`)
- Untuk verifikasi selector (→ `selector-checker`)

## Extractor Architecture

### Registry: `extractor/ExtractorRegistry.kt`

```kotlin
object ProviderExtractors {
    private val legacyList = listOf(
        AbyssPlayer(), AnichinStream(), Anonmp4(), AWSStream(), ...
    )
    private val configDrivenIds = setOf(
        // IDs yang sudah migrasi ke config JSON
    )
}
```

### Flow: `buildList()`
```
Loop legacyList (Kotlin class)
├── ID ada di configDrivenIds?
│   ├── YA → ExtractorConfigRegistry.get(id)
│   │   ├── config found → ConfigDrivenExtractor(config)
│   │   └── config null → fallback Kotlin class
│   └── TIDAK → pakai Kotlin class langsung
```

### 30+ Registered Extractors

| Nama | Domain | Metode |
|------|--------|--------|
| AbyssPlayer | abyssplayer.com | Decrypt API |
| AnichinStream | anichin.stream | Direct HLS |
| Dailymotion | dailymotion.com | Regex |
| Dhcplay | dhcplay.com | WebViewResolver + packed JS |
| Gdplayer | gdplayer.to | API kaken token |
| Odnoklassniki | ok.ru | Regex embed JSON |
| StreamRuby | rubyvidhub.com | POST /dl + packed JS |
| Voe | voe.sx | Regex m3u8 |
| ... | ... | ... |

## Config-Driven Extractors

### Config Location
`BaseProvider/src/main/kotlin/com/baseprovider/config/extractors/<Id>.json`

### Config Structure
```json
{
  "id": "Rumble",
  "name": "Rumble",
  "mainUrl": "https://rumble.com",
  "requiresReferer": true,
  "outputFilter": "master",
  "variants": [{ "id": "default", "name": "Default" }],
  "steps": [
    {"step": "fetch", "url": "{url}", "store": "response"},
    {"step": "substring", "startMarker": "...", "endMarker": "...", "source": "response", "store": "chunk"},
    {"step": "regex", "pattern": "...", "source": "chunk", "filter": ".m3u8"}
  ]
}
```

### 16 Step Types
`Fetch`, `PostForm`, `PostJson`, `Regex`, `JsonPath`, `ConstructUrl`, `Substring`, `ResolveUrl`, `PackedJs`, `AesGcm`, `RhinoEval`, `XorSig`, `Delegate`, `Iframe`, `Redirect`, `Webview`

### Adaptive Pattern (WAJIB untuk config-driven)

Gunakan kombinasi `substring` + `regex` sebagai fallback:
```json
"steps": [
    {"step": "fetch", "url": "{url}", "store": "response"},
    {"step": "substring", "startMarker": "...", "endMarker": "...", "source": "response", "store": "chunk"},
    {"step": "regex", "pattern": "...", "source": "chunk", "filter": ".m3u8"},
    {"step": "regex", "pattern": "...", "source": "response", "filter": ".m3u8"}
]
```
Steps 1-3: extract dari struktur lama (jika ada)
Step 4: fallback regex ke full HTML (jika struktur berubah)

**JANGAN HAPUS fallback.** Website berubah kapan saja — fallback memastikan extractor tetap jalan.

## MasterLinkGenerator — Proteksi 3002

Semua link video HARUS lewat `MasterLinkGenerator.createSmartLink()`.

### Flow
```
createSmartLink(source, url, referer, ...)
1. Reject blank URLs + junk URLs (analytics, tracking)
2. Detect adaptive (.m3u8 / .mpd)
3. Enrich headers
4. IF bareHeaders=true → AdaptiveHeaderProbe.resolve()
   → Test bare/referer/origin/browser-like combos
   → Pick fastest valid (2xx/3xx)
   → If all fail → REJECT link
5. IF adaptive m3u8 → M3u8MasterVerifier.verify()
   → Classify: Clean / Valid(variants) / AllMalformed
   → Clean → deliver as-is
   → Valid → emit each variant separately
   → AllMalformed → REJECT (log: INVALID_URL stage VERIFY)
6. Emit ExtractorLink
```

### Error 3002 (PARSING_MANIFEST_MALFORMED)

**Root cause:** master m3u8 malformed — variant tanpa URI → ExoPlayer rekursi → 3002.

**Proteksi:** `M3u8MasterVerifier.verify()` dipanggil otomatis. Verdict:
| Verdict | Action |
|---------|--------|
| `Clean` | Deliver as-is |
| `Valid(variants)` | Emit each variant separately |
| `AllMalformed` | Reject, log INVALID_URL |

Proteksi ini GLOBAL — tidak perlu per-extractor.

## CRITICAL RULE: No Cache for Extractors

Hasil fetch extractor **DILARANG** di-cache.

- `M3u8MasterVerifier.verify()` → selalu fetch + verifikasi ulang, TANPA ExpiringCache
- `AdaptiveHeaderProbe.resolve()` → selalu probe ulang, single-flight `inFlight` boleh (hanya mencegah probe ganda bersamaan)
- `ExpiringCache.kt` HANYA untuk HTML cache scraper (`ProviderScrapper`, `DetailPageScrapper`, `HttpClient`)
- **DILARANG** menggunakan ExpiringCache di extractor code

## Adding New Extractor

### Option A: Legacy Kotlin Class
1. Buat class extend `ExtractorApi()`
2. Implement `getUrl()` — fetch, parse, callback
3. Tambah ke `ProviderExtractors.legacyList`
4. Panggil `MasterLinkGenerator.createSmartLink()` di akhir

### Option B: Config-Driven (Preferred)
1. Buat `config/extractors/<Id>.json`
2. Define variants + steps
3. Tambah ID ke `configDrivenIds`
4. Test: pastikan steps mengextract video URL dengan benar

### Registration
```kotlin
// Di ExtractorRegistry.kt
private val legacyList = listOf(
    ...
    NamaExtractor()  // ← tambah di sini
)
private val configDrivenIds = setOf(
    ...
    // atau tambah ID untuk config-driven
)
```

## Testing Extractors

### curl_cffi (untuk bypass Cloudflare)
```python
from curl_cffi import requests
r = requests.get(url, impersonate='chrome')
print(r.status_code)  # 200 jika CF bypass berhasil
```

### Manual Check
```bash
# Fetch embed page
curl -sL -A "Mozilla/5.0 ..." "$EMBED_URL" | grep -oP 'file\s*:\s*"([^"]+)"'

# Check if m3u8 is accessible
curl -sI "$M3U8_URL" | head -5
```

### Cloudflare Challenge Handling (HTTP 403 + Challenge Page)

Situs seperti Anichin mengirim **HTTP 403 + HTML challenge page** (bukan redirect). Plugin harus:

1. **Capture response body** di `HttpStatusException`:
   ```kotlin
   if (r.code >= 400) {
       val body = r.text ?: ""
       throw HttpStatusException(r.code, retryAfter, "HTTP ${r.code} on $url", body)
   }
   ```

2. **Check BOTH message AND body** di exception handler:
   ```kotlin
   when {
       CLOUDFLARE_HTTP.containsMatchIn(msg) || CLOUDFLARE_HTTP.containsMatchIn(body) -> {
           // Cloudflare challenge detected → call WebViewCloudflareSolver
       }
   }
   ```

3. **CLOUDFLARE_HTTP regex** HANYA match indikator CF asli (TIDAK `\b403\b`):
   ```kotlin
   internal val CLOUDFLARE_HTTP = Regex(
       """Just a moment|__cf_chl|cf-chl-|challenge-platform|cf-ray|cloudflare""",
       RegexOption.IGNORE_CASE
   )
   ```
   → Plain 403 (geo-block, IP ban) tidak trigger solver, langsung rotasi UA.

4. **403 handler** TIDAK set `retryAfter` (menghindari timeout di rotasi UA):
   ```kotlin
   e.code == 403 -> {
       shouldPenalizeHost = true
       // JANGAN SmartThrottle.reportRetryAfter(host, 30)
       continue  // coba UA berikutnya langsung
   }
   ```

5. **WebViewCloudflareSolver** otomatis jalan saat CF terdeteksi:
   - Budget solve dinaikkan ke **20s** (`CF_SOLVE_BUDGET_MS = 20_000L`) untuk memberi waktu JS execution
   - Fail cooldown diturunkan ke **2 menit** (`FAIL_COOLDOWN_MS = 2 * 60_000L`) agar tidak terkunci 30m jika gagal sekali
   - Buka WebView, jalankan JS challenge
   - Ambil `cf_clearance` cookie → simpan ke `HostCookieJar`
   - Bind cookie ke UA WebView (`solvedUserAgents[host]`)
   - Request ulang pakai UA yang sudah solve

## Poster URL Resolution (root-relative → absolute)

Anichin serve poster sebagai `/wp-content/...` (root-relative). `absUrl()` butuh `Document.baseUri`.

**Fix di HttpClient.kt** (setelah parse berhasil):
```kotlin
val doc = if (config.useDocumentLarge) res.documentLarge else res.document
doc.setBaseUri(res.url)  // Set baseUri dari final response URL
```

**Fix di ProviderParser.kt** (`safeExtractImage`):
```kotlin
.mapNotNull { name ->
    val raw = attr(name)
    if (raw.isBlank() || raw == "about:blank") null
    else runCatching { absUrl(name) }.getOrDefault("").ifBlank { raw }
}
```

**SelectorValidator.isValidPoster** butuh URL absolut (`http(s)://` atau `//`). Dengan fix di atas, root-relative resolved → lolos validasi.

**Test regression** di `SelectorResolverTest.kt`:
```kotlin
val docNoBase = Jsoup.parse(html)  // tanpa baseUri
val noBase = imgNoBase.safeExtractImage(listOf("src"))
assertTrue(noBase.isNotBlank())  // fallback raw

val docWithBase = Jsoup.parse(html, "https://anichin.moe/")
val resolved = img.safeExtractImage(listOf("src"))
assertEquals("https://anichin.moe/wp-content/uploads/poster.webp", resolved)
```

## Failure Modes

| Problem | Cause | Fix |
|---------|-------|-----|
| 3002 on playback | Malformed master m3u8 | M3u8MasterVerifier handles automatically |
| Empty link list | Extractor can't find video URL | Check extractor steps, update selectors |
| All links rejected | AdaptiveHeaderProbe fails all combos | Check if site needs special headers |
| CF 403 | Cloudflare challenge | curl_cffi test, check if bypass works |
| Stale links | Cached extractor results | Ensure no-cache rule, rebuild |
| Main page empty | Cloudflare challenge on listing page | Capture response body in HttpStatusException, check body for CF indicators |
| Poster not showing | root-relative poster URLs not resolved | Set doc.setBaseUri(res.url) in HttpClient, use absUrl() in safeExtractImage |

## Verification

- [ ] Extractor registered di `ExtractorRegistry.kt`
- [ ] Config JSON parseable (`ExtractorConfigParserTest`)
- [ ] Video URL extracted correctly (manual check)
- [ ] MasterLinkGenerator called (check callback)
- [ ] No cache in extractor code

## Related Skills

- `architecture` — MasterLinkGenerator flow, data flow
- `provider` — how extractors integrate with providers
- `logging` — FailureType.EXTRACTOR_FAILURE
- `selector-checker` — Phase 3 (episode page, link options)
