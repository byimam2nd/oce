---
name: oce-architecture
description: OCE Architecture — BaseProvider, sourceSets, config-driven providers
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: architecture
---

# OCE Architecture

## Struktur Direktori

```
OCE/
├── BaseProvider/                    ← Kode BERSAMA (sumber tunggal)
│   └── src/main/kotlin/com/baseprovider/
│       ├── core/       ← ProviderCloudstream (MainAPI), ProviderScrapper,
│       │                 DetailPageScrapper, ProviderMapper, BaseProviderEngine, PosterResizer
│       ├── config/     ← ProviderConfig + per-provider JSON (selector & opsi)
│       │                 ConfigRegistry (bundled-only), ExtractorConfig(+Registry)
│       ├── collector/  ← LinkCollector, FallbackPipeline
│       ├── cache/      ← ExpiringCache, AdaptiveDecryptCache
│       ├── network/    ← HttpClient wrapper, CircuitBreaker, SmartThrottle, DomainHelpers
│       ├── extractor/  ← Video host extractors + ExtractorRegistry, MasterLinkGenerator,
│       │                 M3u8MasterVerifier, AdaptiveHeaderProbe
│       ├── log/        ← Supabase observability, LogLevel, FailureType
│       ├── model/      ← ProviderModels, ProviderParser, SelectorResolver, SelectorValidator
│       └── settings/   ← OceSettings, SettingsDialog
│
├── ProviderAnichin/                 ← Provider SPESIFIK (tipis)
│   ├── src/main/kotlin/com/Anichin/
│   │   ├── Anichin.kt               ← class Anichin : ProviderCloudstream()
│   │   └── AnichinPlugin.kt          ← register MainAPI + extractors
│   └── build.gradle.kts              ← version, metadata SAJA (tanpa dependencies blok)
├── ProviderAnimasu/
├── ProviderDonghuastream/
├── ProviderDutamovie21/
├── ProviderIndoDrama21/
├── ProviderLayarKaca21/
├── ProviderSamehadaku/
│
├── build.gradle.kts                  ← Root build script
├── settings.gradle.kts               ← Auto-include semua module (kecuali BaseProvider)
└── supabase/                         ← Migrations SQL + dashboard queries
```

Catatan: semua provider kini berbasis HTML + config-driven. Tidak ada lagi
provider standalone (Dramabox/Idlix/Melolo) — sudah dihapus.

## sourceSets — Kunci Utama

`BaseProvider/` dikompilasi LANGSUNG ke setiap provider via sourceSets:

```kotlin
// Di root build.gradle.kts
sourceSets.getByName("main") {
    java.srcDir("${rootProject.projectDir}/BaseProvider/src/main/kotlin")
}
```

Artinya: setiap provider HTML compile code-nya sendiri + code BaseProvider dalam SATU dex.

## Config-Driven Provider (pengganti Owner Tagging)

Semua selector & opsi disimpan sebagai **JSON config per provider** di
`BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json`
(mis. `anichin.json`, `global.json`) — bukan konstanta ber-label `"ProviderID:::selector"`.

`ConfigRegistry.get(providerId)` resolve dengan urutan:
1. Bundled: muat resource `classpath:/<name>.json` (di-cache per proses)
2. GLOBAL fallback: `global.json` jika config tidak ditemukan

> ⚠️ `ConfigRegistry` saat ini **bundled-only** — TIDAK ada fetch remote.
> Jangan menulis dokumentasi yang mengklaim remote-first sebelum fitur itu ada.

Selector ditulis langsung dalam JSON per provider, dengan dukungan
multi-variant selector (fallback antar selector pada provider yang sama).
`SelectorResolver` menangani relokasi/fingerprint saat selector berubah
situs — bukan Owner Tagging antar-provider.

## Aturan Penting

- ❌ Jangan edit file di `ProviderAnichin/`, `ProviderSamehadaku/` dll — akan ditimpa mental
- ✅ Edit di `BaseProvider/` — otomatis teraplikasi ke semua provider
- ✅ Untuk selector per-provider, edit `<name>.json` di `BaseProvider/.../config/`