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

Sistem extractor video OCE: registry priority, config-driven steps, `MasterLinkGenerator`, proteksi 3002, klasifikasi 404, dan aturan no-cache.

## When to Use

- Menambah/memperbaiki extractor
- Debug playback (3002, link kosong, semua link ditolak)
- Menentukan extractor mana yang benar-benar aktif

## When NOT to Use

- Edit selector provider (→ `provider`)
- Verifikasi selector HTML (→ `selector-checker`)
- Query log (→ `logging`)

## Registry Priority — Baca Ini Dulu

`extractor/ExtractorRegistry.kt`, object `ProviderExtractors`. **Keberadaan file JSON tidak menentukan aktivasi.** Tiga kelompok:

| Kelompok | Mekanisme | Konsekuensi |
|----------|-----------|-------------|
| `configDrivenIds` (34) | ID ada di set **dan** JSON ada → `ConfigDrivenExtractor` | JSON load gagal → fallback ke class legacy |
| `pureConfigIds` (8) | ID di list, tanpa stub Kotlin | JSON load gagal → **dilewati, bukan crash** |
| Legacy-only (3) | ID tidak ada di set | Selalu class Kotlin: `Odnoklassniki`, `VideoplayerVip`, `Anonmp4` |

`config/extractors/Odnoklassniki.json` **dormant** — sengaja tidak ada di `configDrivenIds` karena steps-nya tidak bisa mengekspresikan CDN throughput probe, fallback multi-quality `videos`, dan unescape `\"`. Jangan diaktifkan tanpa port fitur itu.

**Debugging rule:** sebelum hypothesesi soal extractor, cek `configDrivenIds` / `pureConfigIds`. Extractor yang kelihat "tidak jalan" mungkin memang masih legacy, atau sebaliknya JSON-nya ada tapi ID-nya tidak terdaftar.

## Config-Driven Extractors

- Lokasi: `BaseProvider/src/main/kotlin/com/baseprovider/config/extractors/<Id>.json`
- Parser: `ExtractorConfigParser.kt` — **16 step type**: `fetch`, `postForm`, `postJson`, `regex`, `jsonPath`, `constructUrl`, `substring`, `resolveUrl`, `packedJs`, `aesGcm`, `rhinoEval`, `xorSig`, `delegate`, `iframe`, `redirect`, `webview`

### Adaptive Pattern (WAJIB)
Gunakan `substring` + `regex` sebagai fallback — ekstraksi dari struktur lama, lalu regex ke full response:

```json
"steps": [
  {"step":"fetch","url":"{url}","store":"response"},
  {"step":"substring","startMarker":"...","endMarker":"...","source":"response","store":"chunk"},
  {"step":"regex","pattern":"...","source":"chunk","filter":".m3u8"},
  {"step":"regex","pattern":"...","source":"response","filter":".m3u8"}
]
```

**Jangan hapus fallback.** Website berubah tanpa peringatan.

### HTTP 404 di step `fetch` → `CONTENT_REMOVED`

`ConfigDrivenExtractor` sekarang menghentikan varian saat `fetch` mengembalikan 404:

```kotlin
if (response.code == 404) {
    state.contentRemoved = true   // sisa step hanya regex di halaman error
    logFail(..., type = FailureType.CONTENT_REMOVED, stage = "EXTRACT")
    return
}
```

Efek: tidak lagi ada misleading "variant failed" untuk konten yang memang dihapus upstream. Regresi yang sama diterapkan di `KrakenfilesExtractor` dan `OdnoklassnikiExtractor`.

## MasterLinkGenerator — Proteksi 3002

Semua link video **HARUS** lewat `MasterLinkGenerator.createSmartLink()`.

```
1. Reject blank + junk URL (analytics/tracking)
2. Detect adaptive (.m3u8 / .mpd)
3. Enrich headers
4. bareHeaders=true → AdaptiveHeaderProbe.resolve()   → reject bila semua kombinasi gagal
5. .m3u8 → M3u8MasterVerifier.verify()               → Clean / Valid / AllMalformed
6. Emit ExtractorLink
```

**3002 = `PARSING_MANIFEST_MALFORMED`** — master m3u8 malformed (variant tanpa URI) → ExoPlayer rekursi. Proteksinya global lewat `M3u8MasterVerifier`; tidak perlu per-extractor.

**Keterbatasan yang diketahui:** `AdaptiveHeaderProbe.Decision` **tidak menyimpan HTTP status**. Probe yang ditolak karena 404 tidak bisa dibedakan dari 403/timeout di log. Jangan klaim "probe salah" tanpa bukti signature log yang cocok.

## CRITICAL RULE: No Cache for Extractors

- `M3u8MasterVerifier.verify()` → selalu fetch ulang, tanpa `ExpiringCache`
- `AdaptiveHeaderProbe.resolve()` → selalu probe ulang; `inFlight` single-flight hanya mencegah probe ganda bersamaan
- `ExpiringCache` **hanya** untuk HTML scraper (`ProviderScrapper`, `DetailPageScrapper`, `HttpClient`)
- **DILARANG** `ExpiringCache` di extractor code

## skipHosts — Invariant Semua Path

`config.skipHosts` (JSON provider) dicek di dua tempat; keduanya wajib:

| Lokasi | Kode |
|--------|------|
| `FallbackPipeline` loop utama | `FallbackPipeline.kt:58` |
| `FallbackPipeline` iframe recursive | `FallbackPipeline.kt:252` (`tryManualIframeFetch`) |

Path kedua terlewat sampai `eb51e94` — iframe samehadaku menunjuk host yang sudah di-skip (acefile/gofile) tapi path ini tidak lewat loop utama. **When adding a new extraction path, add the skipHosts check too.**

## Cloudflare Challenge (HTTP 403 + challenge page)

Situs seperti Anichin mengirim **403 + HTML challenge**, bukan redirect.

1. **Capture body** saat throw:
   ```kotlin
   if (r.code >= 400) throw HttpStatusException(r.code, retryAfter, "HTTP ${r.code} on $url", r.text ?: "")
   ```
2. **Check message DAN body** di handler:
   ```kotlin
   CLOUDFLARE_HTTP.containsMatchIn(msg) || CLOUDFLARE_HTTP.containsMatchIn(body) -> { /* solver */ }
   ```
3. **Regex hanya indikator CF asli** — JANGAN `\b403\b`:
   ```kotlin
   Regex("Just a moment|__cf_chl|cf-chl-|challenge-platform|cf-ray|cloudflare", IGNORE_CASE)
   ```
   → Plain 403 (geo-block, IP ban) tidak memicu solver, langsung rotasi UA.
4. **403 handler tidak set `retryAfter`** — mencegah timeout saat rotasi UA.
5. **WebViewCloudflareSolver**: wajib di `Dispatchers.Main`; polling 500ms event-driven; budget 45s untuk Turnstile; ambil `cf_clearance` → `HostCookieJar`, bind ke UA WebView.

## Menambah Extractor Baru

**Pure config (preferred, tanpa Kotlin):**
1. Buat `config/extractors/<Id>.json`
2. Tambah ID ke `pureConfigIds`
3. Wajib: `substring` + `regex` fallback
4. Verifikasi dengan curl (lihat Testing)

**Legacy Kotlin:** class `ExtractorApi()` → implement `getUrl()` → tambah ke `legacyList` → akhiri dengan `MasterLinkGenerator.createSmartLink()`.

## Testing

```python
# Cloudflare bypass check
from curl_cffi import requests
r = requests.get(url, impersonate='chrome')
print(r.status_code)
```

```bash
curl -sL -A "Mozilla/5.0 ..." "$EMBED_URL" | grep -oP 'file\s*:\s*"([^"]+)"'
curl -sI "$M3U8_URL" | head -5
```

## Failure Modes

| Problem | Cause | Fix |
|---------|-------|-----|
| 3002 saat playback | Master m3u8 malformed | `M3u8MasterVerifier` (otomatis) |
| Link list kosong | Extractor tidak dapat URL | Cek steps, `configDrivenIds` |
| Semua link ditolak | Probe gagal semua kombinasi | Cek kebutuhan header situs |
| "Variant failed" padahal 404 | Konten dihapus upstream | Sudah ditangani `CONTENT_REMOVED` |
| Extractor tak terpakai | ID tak ada di `configDrivenIds` | Cek registry priority di atas |
| Link ke host ter-skip | Path bypass `skipHosts` | Cek kedua lokasi skip check |
| Stale link | Cache di extractor | Violasi no-cache rule |
| Poster tak tampil | URL relatif tanpa baseUri | `doc.setBaseUri(res.url)` di `HttpClient` |
| Main page kosong | CF challenge di listing | Capture body, cek indikator CF |

## Verification

- [ ] ID terdaftar di set yang benar (`configDrivenIds` / `pureConfigIds`)
- [ ] Config JSON parseable (`ExtractorConfigParserTest`)
- [ ] Steps punya fallback `substring` + `regex`
- [ ] 404 tidak menghasilkan misleading variant-failure
- [ ] `MasterLinkGenerator` dipanggil
- [ ] Tidak ada `ExpiringCache` di extractor code

## Related Skills

- `architecture` — registry & data flow
- `provider` — `skipHosts` config, selector
- `logging` — `EXTRACTOR_FAILURE`, `CONTENT_REMOVED`, `INVALID_URL`
- `selector-checker` — Phase 3 (link options)
