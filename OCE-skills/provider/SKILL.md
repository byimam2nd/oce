---
name: oce-provider
description: OCE Provider — config-driven providers, adding/editing providers, ProviderConfig reference
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: provider-development
---

# OCE Provider

## Purpose

Cara menambah, edit, dan memelihara provider OCE. Skill ini menjelaskan config-driven system, struktur provider, dan workflow untuk mengubah selector behavior.

## When to Use

- Menambah provider baru
- Edit selector provider (ganti CSS selector)
- Tambah config field baru
- Debugging provider-specific issue

## When NOT to Use

- Untuk tambah/edit extractor (→ `extraction`)
- Untuk verifikasi selector via curl (→ `selector-checker`)
- Untuk commit & build (→ `build-deploy`)

## Provider Structure

Setiap provider HTML hanya punya 3-4 file:

```
ProviderNama/
├── build.gradle.kts              ← version + metadata SAJA
├── src/main/AndroidManifest.xml  ← minimal
└── src/main/kotlin/com/Nama/
    ├── Nama.kt                   ← class Nama : ProviderCloudstream()
    └── NamaPlugin.kt             ← register MainAPI + extractors
```

### Contoh `Nama.kt`
```kotlin
package com.Nama
import com.baseprovider.core.ProviderCloudstream
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
    description = "Provider description"
    language = "id"
    authors = listOf("AuthorName")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://..."
    isCrossPlatform = false
}
```

**DILARANG tambah sourceSets manual** — `settings.gradle.kts` auto-include.

## Config System

### Config Location
`BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json`

### Config Registry Flow
```kotlin
ConfigRegistry.get(providerId)
  → providers[providerId] → fileName
  → loadBundled(fileName) → classLoader.getResourceAsStream("$fileName.json")
  → cache in ConcurrentHashMap
  → fallback: globalConfig (from "global.json")
```

**Bundled-only.** TIDAK ada remote fetch. Config harus ada di classpath.

### Current Providers
```kotlin
"Anichin" → "anichin"
"Animasu" → "animasu"
"Donghuastream" → "donghuastream"
"Dutamovie21" → "dutamovie21"
"IndoDrama21" → "indodrama21"
"LayarKaca21" → "layarkaca21"
"Samehadaku" → "samehadaku"
"Animexin" → "animexin"
```

## ProviderConfig Reference (90+ Fields)

### Identity Fields
| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `id` | String | (required) | Provider identifier |
| `name` | String | `id` | Display name |
| `mainUrl` | String | `"https://example.com"` | Base URL |
| `seriesUrl` | String? | `null` | Fallback: mainUrl |
| `searchUrl` | String? | `null` | Fallback: mainUrl |
| `lang` | String | `"id"` | Language code |

### Selector Fields (CSS selectors)
| Field | Purpose | Example |
|-------|---------|---------|
| `searchItems` | Container per item | `div.listupd article.bs` |
| `searchTitle` | Title element | `h2` |
| `searchHref` | Link element | `a` |
| `searchPoster` | Poster image | `div.bsx img` |
| `searchRating` | Rating badge | `.rating` |
| `searchEpText` | Episode badge | `.epx` |
| `loadTitle` | Series title | `h1.entry-title` |
| `loadPoster` | Series poster | `div.thumb img` |
| `loadBanner` | Banner image | `.banner img` |
| `loadDesc` | Synopsis | `.entry-content` |
| `loadInfoBox` | Info box | `.spe` |
| `loadTags` | Genre tags | `.genxed a` |
| `loadRating` | Rating | `.rating` |
| `loadStatus` | Status | `Status:</b>` |
| `loadTrailer` | Trailer | `iframe[src*='youtube']` |
| `loadRecommend` | Recommendations | `div.listupd article.bs` |
| `episodeItems` | Episode list | `.eplister li` |
| `episodeHref` | Episode link | `a` |
| `linkOptions` | Server options | `option[value]` |
| `downloadItems` | Download links | `#downloadb` |

**Multi-variant:** comma-separated, e.g. `"h1.entry-title, h1.title, .entry-title h1"` — SelectorResolver tries each.

### URL Pattern Fields
| Field | Default | Purpose |
|-------|---------|---------|
| `searchPathPattern` | `{baseUrl}/page/{page}/?s={query}` | Search URL template |
| `mainPagePathPattern` | `{baseUrl}/{data}{page}` | Main page URL template |
| `episodeDataUrlPattern` | `{url}` | Episode data URL |

### Attribute Fields
| Field | Default | Purpose |
|-------|---------|---------|
| `attrImage` | `["data-original","data-src","data-lazy-src","src","content"]` | Image source priority |
| `attrValue` | `["value","data-index","data-id","data-url","data-link"]` | Value attribute priority |
| `iframeSources` | `["src","data-src","data-link"]` | Iframe src priority |

### Behavior Flags
| Field | Default | Purpose |
|-------|---------|---------|
| `reverseEpisodes` | `true` | Reverse episode order |
| `isJsonSearch` | `false` | JSON API search |
| `mainPageCacheBuster` | `false` | Add cache buster to main page |
| `selfExtract` | `false` | Self-extract video |

### Filter/Clean
| Field | Default | Purpose |
|-------|---------|---------|
| `bloatRegex` | see code | Remove bloat from titles |
| `qualityStripRegex` | `\d{3,4}p\|HD\|SD\|FHD` | Strip quality from names |
| `hrefCleanRegex` | `""` | Clean href URLs |

## Adding New Provider

### Step 1: Create Provider Module
```bash
mkdir -p ProviderNama/src/main/kotlin/com/Nama
```

### Step 2: Create Files
- `ProviderNama/build.gradle.kts`
- `ProviderNama/src/main/AndroidManifest.xml`
- `ProviderNama/src/main/kotlin/com/Nama/Nama.kt`
- `ProviderNama/src/main/kotlin/com/Nama/NamaPlugin.kt`

### Step 3: Create Config
Buat `BaseProvider/.../config/nama.json` dengan selectors.

### Step 4: Register
Tambah entry di `ConfigRegistry.kt`:
```kotlin
"Nama" to "nama"
```

### Step 5: Test
1. Push ke CI → build
2. Install plugin di CloudStream
3. Test: main page, search, detail, episode, playback
4. Verify selectors (→ `selector-checker`)

## Editing Provider Config

### Change Selector
1. Edit `BaseProvider/.../config/<name>.json`
2. Update selector field
3. Push → CI → test

### Add Config Field
1. Add field ke `ProviderConfig.kt` dengan default
2. Add parsing ke `ProviderConfigParser.kt`
3. Add test ke `ProviderConfigParserTest.kt`
4. Use field di engine code
5. Update provider JSON config

## Verification

- [ ] Provider module exists dengan 3-4 file
- [ ] Config JSON parseable (validate_providers.py)
- [ ] ConfigRegistry has provider entry
- [ ] All selectors match live website (→ `selector-checker`)
- [ ] Main page, search, detail, episodes work

## Related Skills

- `selector-checker` — verifikasi selector 4 phase
- `extraction` — how extractors integrate
- `logging` — debug provider failures
- `architecture` — config system, sourceSets
- `build-deploy` — how to build/test
