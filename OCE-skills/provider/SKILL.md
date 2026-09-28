---
name: oce-provider
description: OCE Provider — config-driven providers, adding/editing providers, selector config
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: provider-development
---

# OCE Provider

## Purpose

Menambah, edit, dan memelihara provider OCE — struktur module, config JSON, dan workflow mengubah selector behavior.

## When to Use

- Menambah provider baru
- Mengganti CSS selector di config
- Menambah config field baru
- Debug issue spesifik provider

## When NOT to Use

- Extractor (→ `extraction`)
- Verifikasi selector via curl (→ `selector-checker`)
- Commit & build (→ `build-deploy`)

## Provider Structure

Setiap provider hanya 3-4 file:

```
ProviderNama/
├── build.gradle.kts              ← version + cloudstream metadata SAJA
├── src/main/AndroidManifest.xml  ← minimal
└── src/main/kotlin/com/Nama/
    ├── Nama.kt                   ← class Nama : ProviderCloudstream()
    └── NamaPlugin.kt             ← registerMainAPI + registerExtractorAPI
```

```kotlin
// Nama.kt
package com.Nama
import com.baseprovider.core.ProviderCloudstream
class Nama : ProviderCloudstream()

// NamaPlugin.kt
@CloudstreamPlugin
class NamaPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Nama())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
```

**Jangan tambah sourceSets manual** — `settings.gradle.kts` auto-include.

## Config System

- Lokasi: `BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json`
- Load: `ConfigRegistry.get(providerId)` → `classLoader.getResourceAsStream` → cache. **Bundled-only**, tidak ada remote fetch. Fallback `global.json`.
- Terdaftar: Anichin, Animasu, Animexin, Donghuastream, Dutamovie21, IndoDrama21, LayarKaca21, Samehadaku.

Field `ProviderConfig` berjumlah **95**. Daftar lengkap ada di `ProviderConfig.kt` — baca file itu bila butuh field yang tidak ada di bawah.

### Field yang paling sering diubah

| Field | Default | Fungsi |
|-------|---------|--------|
| `mainUrl` | — | Base URL (wajib) |
| `searchItems` / `searchTitle` / `searchHref` / `searchPoster` | — | Main page & search |
| `loadTitle` / `loadPoster` / `loadDesc` | — | Detail page |
| `episodeItems` / `episodeHref` | — | Daftar episode |
| `linkOptions` / `downloadItems` | — | Opsi server & unduhan |
| `attrImage` | `data-original, data-src, data-lazy-src, src, content` | Prioritas atribut gambar |
| `attrValue` | `value, data-index, data-id, data-url, data-link` | Prioritas atribut nilai |
| `reverseEpisodes` | `true` | Balik urutan episode |
| `qualityStripRegex` | `\d{3,4}p\|HD\|SD\|FHD` | Buang kualitas dari judul |
| `skipHosts` | `emptySet()` | Host yang tidak boleh diekstrak |
| `allowedExtractors` | — | Batasi extractor per provider |

### Multi-variant selector
Satu string comma-separated, dicoba berurutan, first match:
```json
"loadTitle": "h1.entry-title, h1.title, .entry-title h1"
```

## Menambah Provider Baru

1. `mkdir -p ProviderNama/src/main/kotlin/com/Nama`
2. Buat 4 file (lihat struktur di atas)
3. Buat `config/nama.json` — **copy config provider yang paling mirip**, jangan dari nol
4. Tambah `"Nama" to "nama"` di `ConfigRegistry.kt`
5. Verifikasi selector dengan `selector-checker`
6. Push → CI → tes di CloudStream (main page, search, detail, episode, playback)

## Menambah Config Field

1. Field + default di `ProviderConfig.kt`
2. Parsing di `ProviderConfigParser.kt`
3. Test di `ProviderConfigParserTest.kt` (dan test "all bundled configs parse")
4. Pakai di engine code
5. Set di JSON provider bila perlu

## Menambah Selector

1. Pastikan selector benar-benar ada di HTML — verifikasi dengan `selector-checker` **sebelum** edit
2. Edit `config/<name>.json`, pakai multi-variant untuk dua layout
3. **Verifikasi dua halaman** bila selector episode: series page (daftar episode) dan episode page (opsi server). Keduanya punya DOM berbeda.
4. Push → CI

### Kalau item tidak ada di HTML
Selector bukan penyebabnya. Contoh nyata: Anichin series page hanya berisi episode 1–159, sementara episode 160 hanya ada di episode page — link-nya memang tidak ada di HTML series page. Tidak ada selector yang bisa menemukannya; itu masalah sisi situs. Jangan ajouter selector berulang.

## Verification

- [ ] Module punya 3-4 file
- [ ] `ConfigRegistry` punya entry
- [ ] JSON parseable (`scripts/validate_providers.py` via CI)
- [ ] Selector match di HTML live (`selector-checker`)
- [ ] Series page **dan** episode page diverifikasi
- [ ] Main page, search, detail, episode, playback berfungsi

## Related Skills

- `selector-checker` — verifikasi selector live
- `extraction` — `skipHosts`, extractor per provider
- `architecture` — config system, sourceSets
- `logging` — `SELECTOR_FAILURE` investigation
- `build-deploy` — CI, validate_providers.py
