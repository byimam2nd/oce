# Contributing to OCE

Terima kasih tertarik berkontribusi ke OCE! Berikut panduan singkatnya.

## Struktur Proyek

```
BaseProvider/src/main/kotlin/com/baseprovider/
├── core/           ← ProviderCloudstream (MainAPI), ProviderScrapper, DetailPageScrapper
├── config/         ← ProviderConfig + per-provider JSON (selector & opsi)
├── collector/      ← LinkCollector, FallbackPipeline
├── cache/          ← ExpiringCache, AdaptiveDecryptCache
├── network/        ← HttpClient wrapper, CircuitBreaker, SmartThrottle
├── extractor/      ← Video host extractors + ExtractorRegistry, MasterLinkGenerator
├── log/            ← Supabase observability, FailureType
├── model/          ← ProviderModels, SelectorResolver (selector fallback/fingerprint)
└── settings/       ← OceSettings, SettingsDialog

ProviderNama/                   ← Thin provider module (extends ProviderCloudstream)
├── build.gradle.kts
├── src/main/kotlin/com/Nama/
│   ├── Nama.kt                 ← class Nama : ProviderCloudstream()
│   └── NamaPlugin.kt           ← registerMainAPI + registerExtractorAPI
```

## Cara Kerja

Semua logika scraping ada di `BaseProvider/`. Provider module hanya **thin wrapper** — 2 file, ~5 baris kode. Selector dikonfigurasi via **JSON config per provider** di `BaseProvider/.../config/<name>.json`.

```json
{
  "id": "Anichin",
  "mainUrl": "https://anichin.cafe",
  "searchItems": "div.listupd article.bs",
  "searchTitle": "h2",
  "loadTitle": "h1.entry-title, h1.title"
}
```

Multi-variant selector (fallback antar selector) didukung dengan koma, diproses
oleh `SelectorResolver` (relokasi/fingerprint saat struktur situs berubah).
Konvensi `"ProviderID:::css-selector"` (Owner Tagging) sudah dihapus dari kode.

## Aturan

1. **Jangan edit file di `ProviderNama/`** secara langsung — cukup edit `config/<name>.json` atau file lain di `BaseProvider/`
2. **Patch minimal** — jangan refactor kode yang tidak terkait
3. **Test selector** — jalankan `curl` + Python script (lihat skill `selector-checker`)
4. **Jangan build gradle lokal** — build & verifikasi via commit → push → CI
5. **Jangan update versi** — versi di-set otomatis saat tag release

## Pull Request

1. Fork repo
2. Buat branch: `fix/xxx` atau `feat/xxx`
3. Commit dengan pesan deskriptif
4. Push → buat PR

## Menambah Provider Baru

1. Buat folder `ProviderNama/` dengan `build.gradle.kts`, `Nama.kt`, `NamaPlugin.kt`, `AndroidManifest.xml`
2. Buat `config/<nama>.json` di `BaseProvider/.../config/` (selector + opsi)
3. Registrasi id → filename di `ConfigRegistry.kt` (map `providers`)
4. Build: commit → push → CI

## Menambah Extractor Baru

1. Buat class di `extractor/` yang extends `ExtractorApi()`
2. Override: `name`, `mainUrl`, `requiresReferer`, `getUrl()`
3. Daftarkan di `ProviderExtractors.legacyList` (`extractor/ExtractorRegistry.kt`)
4. Opsional migrasi config-driven: tambah id ke `configDrivenIds` + buat `config/extractors/<Id>.json`
5. Semua link deliver via `MasterLinkGenerator.createSmartLink(...)` — proteksi 3002 & blank URL otomatis