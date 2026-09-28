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

Mental model repository OCE — struktur module, alur data runtime, dan batas dependensi. Baca SEBELUM perubahan kode apa pun.

## When to Use

- Sebelum mengedit `BaseProvider/`
- Menambah provider atau extractor
- Debugging cross-module
- Impact analysis

## When NOT to Use

- Task 1 file tanpa perubahan struktur (→ skill spesifik)
- Edit selector/config JSON (→ `provider`)
- Detail Cloudflare & extractor (→ `extraction`)
- Config field reference (→ `provider`)

## Repository Structure

```
OCE/
├── BaseProvider/                    ← KODE BERSAMA (sumber tunggal)
│   └── src/main/kotlin/com/baseprovider/
│       ├── core/                    ← Orchestration (Engine, Scrapper, Mapper)
│       ├── config/                  ← ProviderConfig, ConfigRegistry, *.json, extractors/*.json
│       ├── collector/               ← LinkCollector, FallbackPipeline
│       ├── cache/                   ← ExpiringCache (scraper only)
│       ├── network/                 ← HttpClient, CircuitBreaker, SmartThrottle
│       ├── extractor/               ← ExtractorRegistry, ConfigDrivenExtractor, MasterLinkGenerator
│       ├── log/                     ← Logging, FailureType, SupabaseObservability
│       ├── model/                   ← ProviderParser, SelectorResolver, SelectorValidator
│       └── settings/                ← OceSettings, SettingsDialog
│
├── ProviderAnichin/                 ← Thin wrapper (pattern identik semua provider)
│   ├── build.gradle.kts             ← version + metadata SAJA
│   └── src/main/kotlin/com/Anichin/ ← Anichin.kt + AnichinPlugin.kt
├── ProviderAnimasu/  ProviderAnimexin/  ProviderDonghuastream/
├── ProviderDutamovie21/  ProviderIndoDrama21/  ProviderLayarKaca21/  ProviderSamehadaku/
│
├── build.gradle.kts  settings.gradle.kts
├── .github/workflows/               ← ci-cd.yml, release.yml, health-check.yml, provider-test.yml
├── scripts/                         ← validate_providers.py, health_check.py
├── supabase/                        ← migrations SQL
└── OCE-skills/                      ← knowledge layer untuk AI agent
```

## sourceSets — Kunci Arsitektur

`BaseProvider/` dikompilasi LANGSUNG ke setiap provider; `settings.gradle.kts` auto-include setiap dir yang punya `build.gradle.kts` (kecuali yang di-`disabled`).

**Konsekuensi:** perubahan di `BaseProvider/` otomatis berlaku untuk SEMUA provider. Tidak perlu sync script. Tidak perlu tambah sourceSets manual.

## Data Flow

### Flow 1: Main Page
```
ProviderCloudstream.getMainPage()
  → BaseProviderEngine → ProviderScrapper
    → fetchDocument(url, config)      // HTTP + HTML cache (ExpiringCache)
    → SelectorResolver.select(searchItems)   // multi-variant, first match
    → ProviderMapper.mapToSearchResult()
```

### Flow 2: Search
Sama seperti Flow 1, URL dibangun dari `searchPathPattern`.

### Flow 3: Load (detail)
```
DetailPageScrapper.load(url)
  → fetchDocument(url)               // setBaseUri(res.url) wajib untuk URL relatif
  → SelectorResolver.selectFirst(loadTitle/loadPoster/loadDesc/...)
  → ProviderMapper.buildLoadResponse()
```

### Flow 4: Load Links (extraction)
```
ProviderScrapper.loadLinks()
  → LinkCollector.collectLinkOptions()     // option[value], a[data-url], iframe
  → FallbackPipeline.processLink(url)      // per-link, dalam runCatching
    → isUnusableCandidate() → skipHosts check
    → ExtractorRegistry.getMatchingExtractors(domain)
    → ConfigDrivenExtractor.getUrl() | legacy Extractor.getUrl()
      → MasterLinkGenerator.createSmartLink()
        → AdaptiveHeaderProbe.resolve()    // bila bareHeaders=true
        → M3u8MasterVerifier.verify()      // bila .m3u8
      → callback(ExtractorLink)
```

Detail tiap langkah: `extraction`.

### Registry Reality (angka aktual)

`buildList()` di `ExtractorRegistry.kt` menghasilkan **45 extractor aktif**:

| Sumber | Jumlah | Note |
|--------|--------|------|
| `legacyList` | 37 class Kotlin | 34 di-override config-driven, 3 tetap legacy |
| `configDrivenIds` | 34 | ID dengan JSON di `config/extractors/` |
| `pureConfigIds` | 8 | Tanpa stub Kotlin; JSON satu-satunya sumber |
| Legacy-only | 3 | `Odnoklassniki`, `VideoplayerVip`, `Anonmp4` |
| JSON total | 43 | 34 + 8 + `Odnoklassniki.json` (dormant) |

**PENTING:** ID yang punya JSON **tidak selalu** memakai config. Hanya ID di `configDrivenIds` yang di-override. Selalu cek set tersebut sebelum menyimpulkan extractor mana yang aktif → `extraction`.

## Module Boundary

### BaseProvider/ (shared)
| Subdir | Responsibility | Key Classes |
|--------|---------------|-------------|
| `core/` | Orchestration | `ProviderCloudstream`, `BaseProviderEngine`, `ProviderScrapper`, `DetailPageScrapper`, `ProviderMapper` |
| `config/` | Configuration | `ProviderConfig` (95 field), `ConfigRegistry`, `ExtractorConfig`, `ExtractorConfigRegistry` |
| `collector/` | Link collection | `LinkCollector`, `FallbackPipeline` |
| `extractor/` | Video extraction | `ProviderExtractors`, `ConfigDrivenExtractor`, `MasterLinkGenerator`, `M3u8MasterVerifier`, `AdaptiveHeaderProbe` |
| `model/` | Data + parsing | `ProviderParser`, `SelectorResolver`, `SelectorValidator` |
| `cache/` | HTML caching | `ExpiringCache` (scraper only) |
| `network/` | HTTP | `HttpClient`, `CircuitBreaker`, `SmartThrottle`, `NetworkUtils` |
| `log/` | Observability | `Logging`, `FailureType`, `SupabaseObservability` |
| `settings/` | User settings | `OceSettings`, `SettingsDialog` |

### Provider*/ (per-provider)
3-4 file saja: `Nama.kt` (`class Nama : ProviderCloudstream()`), `NamaPlugin.kt` (register MainAPI + extractors), `build.gradle.kts`, `AndroidManifest.xml`.

**DILARANG edit `Provider*/` — ditimpa build. Selalu edit di `BaseProvider/`.**

## Config System

- `ConfigRegistry.get(providerId)` → map ID→filename → `classLoader.getResourceAsStream("$fileName.json")` → cache `ConcurrentHashMap` → fallback `global.json`.
- **Bundled-only.** Tidak ada remote fetch. Config harus ada di classpath.
- 8 provider terdaftar: Anichin, Animasu, Animexin, Donghuastream, Dutamovie21, IndoDrama21, LayarKaca21, Samehadaku.
- 95 field `ProviderConfig`; daftar field yang sering diubah: `provider` skill.
- Multi-variant selector = satu string comma-separated (`"h1.entry-title, h1.title"`), dicoba berurutan, first match.

## Key Invariants

1. **`BaseProvider/` = single source of truth** — semua shared logic di sini
2. **Provider wrapper = thin** — hanya register class
3. **Config-driven** — selector & steps di JSON, bukan di kode
4. **No local gradle build** — CI only (`build-deploy`)
5. **`ExpiringCache` = scraper only** — DILARANG di extractor
6. **Semua link lewat `MasterLinkGenerator`** — proteksi 3002 global
7. **`skipHosts` di SEMUA path** — loop utama *dan* `tryManualIframeFetch` (regresi `eb51e94`)
8. **Log noise = bug** — pesan konstan/high-frequency pakai `logDebug`, bukan `logSuccess` (`buildList` pernah ~45% volume Supabase)
9. **Telegram sudah dihapus** — observability hanya Supabase

## Verification

- [ ] Bisa sebutkan 9 subdir `BaseProvider/` dan batasnya dengan `Provider*/`
- [ ] Bisa trace `getMainPage()` sampai HTTP request
- [ ] Bisa jelaskan bagaimana sourceSetsSku bekerja tanpa sync script
- [ ] Bisa bilang extractor aktif berasal dari registry mana (bukan menebak dari keberadaan JSON)

## Related Skills

- `provider` — field `ProviderConfig`, edit selector
- `extraction` — registry priority, MasterLinkGenerator, Cloudflare, 404
- `development` — workflow & debugging
- `logging` — Supabase observability
- `build-deploy` — CI/CD
- `selector-checker` — verifikasi selector
