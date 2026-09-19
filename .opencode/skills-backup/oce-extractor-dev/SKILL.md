---
name: oce-extractor-dev
description: OCE Extractor Development — cara buat dan update extractor video
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: extractor-development
---

# OCE Extractor Development

## Daftar Extractor yang Ada

Registry: `BaseProvider/.../extractor/ExtractorRegistry.kt` (`object ProviderExtractors`).
Daftar di bawah dari `legacyList`. Extractor dengan id di `configDrivenIds`
memakai `ConfigDrivenExtractor` (konfigurasi JSON di
`config/extractors/<Id>.json`) — sisanya class legacy.

| Nama | Domain | Metode |
|---|---|---|
| `AbyssPlayer` | abyssplayer.com | Decrypt API (hydrax) / deep scan |
| `AnichinStream` | anichin.stream | Direct `/hls/{id}.m3u8` |
| `Anonmp4` | anonmp4.art | API extraction |
| `AWSStream` | awstream.net | POST hash → videoSource |
| `BloggerVideo` | www.blogger.com | Video/iframe extraction |
| `ByseSX` | byse.sx | AES decrypt API |
| `Cloudhownetwork` | cloud.hownetwork.xyz | extends Hownetwork |
| `Dailymotion` | dailymotion.com | Regex video URL |
| `Dhcplay` | dhcplay.com | WebViewResolver + packed JS |
| `EmTurbovid` | emturbovid.com | API extraction |
| `Filedon` | filedon.co | Direct extraction |
| `Gdplayer` | gdplayer.to | API kaken token |
| `Hownetwork` | stream.hownetwork.xyz | POST API → m3u8 |
| `Krakenfiles` | krakenfiles.com | API extraction |
| `Lk21PlayerPage` | playeriframe.sbs | Iframe → Hownetwork |
| `LuluStream` | luluvdo.com | POST form → vplayer |
| `MegaPlay` | megaplay.buzz | API data-id → getSources |
| `Minochinos` | minochinos.com | Packed JS + JW Player |
| `Morencius` | morencius.com | API extraction |
| `Movearnpre` | movearnpre.com | Packed JS + JW Player |
| `Odnoklassniki` | ok.ru | Regex embed JSON |
| `PlayCdn` | playcdn.de | API extraction |
| `PlayPutarIn` | play.putar.in | URL parameter forward |
| `PlayStreamplay` | play.streamplay.co.in | Iframe extraction |
| `Rumble` | rumble.com | Script data extraction |
| `ShortIcu` | short.icu | Redirect + deep scan |
| `StreamHG` | hgcloud.to | WebViewResolver + packed JS |
| `StreamRuby` | rubyvidhub.com | POST `/dl` + packed JS fallback |
| `Svanila` | streamruby.net | extends StreamRuby |
| `Svilla` | streamruby.com | extends StreamRuby |
| `VideoNodePage` | videonode.de | Iframe extraction |
| `VideoplayerVip` | videoplayer.vip | API extraction |
| `Vidguardto` | vidguard.to / listeamed.net | Rhino JS + sigDecode |
| `Voe` | voe.sx | Regex m3u8 extraction |
| `Wishfast` | wishfast.to | Packed JS + file pattern |
| `Xtwap` | xtwap.top | JW Player → play.php → m3u8 |

## Cara Tambah Extractor Baru

```kotlin
class NamaExtractor : ExtractorApi() {
    override var name = "Nama"
    override var mainUrl = "https://domain.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Fetch halaman embed
        val response = app.get(url, referer = referer)
        
        // 2. Cari video URL
        val videoUrl = cariVideoUrl(response.text)
        
        // 3. Kirim ke CloudStream player
        MasterLinkGenerator.createSmartLink(
            this.name, videoUrl, referer ?: mainUrl, callback = callback
        )
    }
}
```

## JS Packer Decoder

Untuk situs yang pakai packed JavaScript:

```kotlin
// Gunakan fungsi yang sudah ada:
val decoded = findPackedJsInPage(html)?.let { (p, k, b) -> decodePackedJs(p, k, b) }

// Cari file URL dari hasil decode:
val fileMatch = Regex("""file\s*:\s*"([^"]+)""").find(decoded)
if (fileMatch != null) {
    val fileUrl = fileMatch.groupValues[1]
    MasterLinkGenerator.createSmartLink(name, fileUrl, mainUrl, callback = callback)
}
```

## Daftarkan Extractor

```kotlin
object ProviderExtractors {
    private val legacyList = listOf(
        // ... extractor yang sudah ada ...
        NamaExtractor()  // ← tambah di sini
    )

    // Id yang dimigrasi ke config-driven (config/extractors/<Id>.json)
    private val configDrivenIds = setOf(
        // ... id yang sudah migrasi ...
    )
}
```

## Proteksi Error 3002 (PARSING_MANIFEST_MALFORMED)

**Gejala**: episode gagal diputar dengan error `3002` (PARSING_MANIFEST_MALFORMED),
kadang muncul di buka kedua setelah hasil extractor tersimpan.

**Root cause**: master m3u8 dari server malformed — ada variant tanpa URI
(mis. baris kosong setelah `#EXT-X-STREAM-INF:...,RESOLUTION=1920x1080`).
Media3/ExoPlayer me-resolve baris kosong → master itu sendiri → rekursi →
3002. Server-side tidak bisa diperbaiki (master byte-identical lintas semua
combo header).

**Solusi (sudah terpasang, jangan regresi)** — `M3u8MasterVerifier.verify()`
dipanggil otomatis dari `MasterLinkGenerator.createSmartLink` untuk semua
link `.m3u8`:

| Verdict | Perilaku |
|---|---|
| `Clean` | Master bersih / bukan master / fetch gagal → deliver as-is (ABR tetap jalan) |
| `Valid(variants)` | Deliver tiap variant valid sebagai link M3U8 terpisah (chunklist langsung) |
| `AllMalformed` | Jangan deliver apa pun → `logFail` `INVALID_URL` stage `VERIFY` |

Karena semua extractor wajib lewat `createSmartLink`, proteksi ini GLOBAL —
tidak perlu per-extractor.

## ATURAN: Jangan Cache Hasil Fetch Extractor

**User requirement (verified via Supabase)**: hasil fetch extractor TIDAK
boleh di-cache. Selalu fetch ulang dari website setiap extractor dijalankan.
Cache lama menyebabkan verdict/fetch basi dipakai ulang → 3002 berulang.

- `M3u8MasterVerifier.verify()` → selalu fetch + verifikasi ulang, TANPA
  `ExpiringCache`.
- `AdaptiveHeaderProbe.resolve()` → selalu probe ulang per host, TANPA cache
  per-host. Single-flight `inFlight` boleh tetap (hanya mencegah probe ganda
  bersamaan, bukan menyimpan hasil).
- `ExpiringCache.kt` HANYA untuk HTML cache scraper (`core/ProviderScrapper`,
  `core/DetailPageScrapper`, `network/HttpClient`) — DILARANG untuk extractor.
- Side note: app `RepoLinkGenerator` (di CloudStream app, bukan plugin)
  masih men-cache hasil extractor ±20 menit (static HashMap, flag `saturated`
  → extractor tidak dijalankan ulang). Ini di luar kendali plugin.

## Catatan

- `loadExtractorWithFallbackCustom` = local → global → deep scan
- Extractor dipanggil otomatis jika domain cocok (`ProviderExtractors.getMatchingExtractors`)
- Tambah ke `ProviderExtractors.legacyList` di `extractor/ExtractorRegistry.kt`
- Migrasi config-driven: tambah id ke `configDrivenIds` + buat
  `config/extractors/<Id>.json` (non-breaking; jika config gagal load → fallback class legacy)

## ATURAN: Semua Extractor Config-Driven Harus Adaptive

**User requirement**: semua extractor config-driven harus adaptive terhadap
perubahan struktur halaman website. Website dapat mengubah struktur HTML kapan
saja — extractor harus tetap jalan.

### Pola Adaptive

Gunakan kombinasi `substring` + `regex` sebagai fallback:

```json
{
  "steps": [
    {"step": "fetch", "url": "{url}", "store": "response"},
    {"step": "substring", "startMarker": "...", "endMarker": "...", "source": "response", "store": "chunk"},
    {"step": "regex", "pattern": "...", "source": "chunk", "filter": ".m3u8"},
    {"step": "regex", "pattern": "...", "source": "response", "filter": ".m3u8"}
  ]
}
```

- Step 1-3: ekstrak dari struktur lama (jika ada)
- Step 4: fallback regex ke full HTML (jika struktur berubah)
- `substring` step: `startMarker`/`endMarker` → `store` variable
- Jika struktur lama tidak ada, `substring` gagal silently → regex ke `response` tetap jalan

### Contoh: Rumble.json

```json
{
  "steps": [
    {"step": "fetch", "url": "{url}", "referer": "{mainUrl}/", "store": "response"},
    {"step": "substring", "startMarker": "{\"mp4", "endMarker": "\"evt\":{", "source": "response", "store": "mp4Chunk"},
    {"step": "regex", "pattern": "\\\"url\\\":\\\"(.*?)\\\"", "group": 1, "source": "mp4Chunk", "filter": ".m3u8"},
    {"step": "regex", "pattern": "\\\"url\\\":\\\"(.*?)\\\"", "group": 1, "source": "response", "filter": ".m3u8"}
  ]
}
```

Old structure (script `{"mp4...evt":{`): steps 1-3 handle.
New structure (regex langsung): step 4 handle.

## Testing: curl_cffi untuk Bypass Cloudflare

Cloudflare bypass tidak bisa ditest dengan Python `requests` (selalu 403).
Gunakan `curl_cffi` untuk test:

```python
from curl_cffi import requests

# Impersonate Chrome untuk bypass CF
r = requests.get(url, impersonate='chrome')
print(r.status_code)  # 200 jika CF bypass berhasil
print('mp4' in r.text)  # True jika halaman embed video
```

Install: `pip install curl_cffi`

Catatan: Playwright tidak support ARM64 Linux (Termux). curl_cffi adalah
alternatif terbaik untuk testing CF bypass di environment ini.

## ConfigDrivenExtractor Mechanism

### Flow

```
ExtractorRegistry.buildList()
├─ Loop legacyList (Kotlin class)
│  ├─ ID ada di configDrivenIds?
│  │   ├─ YA → ExtractorConfigRegistry.get(id)
│  │   │   ├─ config ada → ConfigDrivenExtractor(config)
│  │   │   └─ config null → fallback Kotlin class
│  │   └─ TIDAK → pakai Kotlin class langsung
```

### Deliver Mechanism

`ConfigDrivenExtractor.deliver()` memanggil `MasterLinkGenerator.createSmartLink`
dengan `bareHeaders = true`. Ini memicu `AdaptiveHeaderProbe` yang test beberapa
combo header. Jika semua combo return non-2xx/3xx, link ditolak.

Untuk situs CF: pastikan m3u8 URL accessible dengan `app.get()` CloudStream.
Jika CF bypass gagal di m3u8 URL (beda dengan embed page), itu CloudStream
library issue — tambah log untuk debugging.
