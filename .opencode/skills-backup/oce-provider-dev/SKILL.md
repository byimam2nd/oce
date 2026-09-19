---
name: oce-provider-dev
description: OCE Provider Development — cara nambah, edit, dan maintain provider (config-driven)
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: provider-development
---

# OCE Provider Development

## Struktur Provider

Setiap provider HTML hanya punya 3 file:

```
ProviderNama/
├── build.gradle.kts              ← version, metadata
├── src/main/AndroidManifest.xml  ← minimal
└── src/main/kotlin/com/Nama/
    ├── Nama.kt                   ← class Nama : ProviderCloudstream()
    └── NamaPlugin.kt             ← register MainAPI + extractors
```

### Contoh `Nama.kt`
```kotlin
package com.Nama
import com.baseprovider.ProviderCloudstream
class Nama : ProviderCloudstream()
```

### Contoh `NamaPlugin.kt`
```kotlin
package com.Nama
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.baseprovider.extractor.ProviderExtractors

@CloudstreamPlugin
class NamaPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Nama())
        ProviderExtractors.list.forEach { registerExtractorAPI(it) }
    }
}
```

### Contoh `build.gradle.kts`
```kotlin
version = 1
cloudstream {
    description = "..."
    language = "id"
    authors = listOf("...")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "..."
    isCrossPlatform = false
}
```

## Edit Selector — Config JSON (bukan Owner Tagging)

Semua selector per provider disimpan sebagai **JSON config** di
`BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json`:

```json
{
  "id": "Anichin",
  "name": "Anichin",
  "mainUrl": "https://anichin.cafe",
  "searchItems": "div.listupd article.bs",
  "searchTitle": "h2",
  "searchHref": "a",
  "searchPoster": "div.bsx img, .ts-post-image, .wp-post-image",
  "loadTitle": "h1.entry-title, h1.title, .entry-title h1",
  "episodeItems": ".eplister li",
  "linkOptions": "option[data-index], option[value]"
}
```

- Multi-variant selector didukung dengan koma (`"h1.entry-title, h1.title"`) —
  fallback antar selector pada provider yang sama, diproses `SelectorResolver`.
- Tidak ada lagi format `"ProviderID:::css-selector"` — konvensi itu sudah
  dihapus dari kode.
- `global.json` berisi default bersama; `ConfigRegistry` memuat bundled config
  dan fallback ke GLOBAL jika provider tidak ditemukan.

## Nambah Provider Baru

1. Buat folder `ProviderNama/` dengan struktur di atas
2. Buat `config/<nama>.json` di `BaseProvider/.../config/` (isi selector + opsi)
3. Registrasi id → filename di `ConfigRegistry.kt` (map `providers`)
4. build → test

## Catatan Penting

- Semua provider kini berbasis HTML + config-driven. Provider standalone
  (Dramabox, Idlix, Melolo) sudah dihapus — jangan menambahkan kembali.
- `ConfigRegistry` bundled-only saat ini; jangan klaim remote-first.
- JANGAN tambah sourceSets manual untuk provider baru — `settings.gradle.kts`
  auto-include module apa pun yang punya `build.gradle.kts` (kecuali BaseProvider).