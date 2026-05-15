# Contributing to OCE

Terima kasih tertarik berkontribusi ke OCE! Berikut panduan singkatnya.

## Struktur Proyek

```
BaseProvider/src/main/kotlin/com/baseprovider/
├── BaseProviderHelpers.kt     ← Logging, config resolution
├── ProviderCloudstream.kt      ← MainAPI adapter
├── ProviderHTMLConstants.kt    ← Selector & config (Owner Tagging)
├── ProviderScrapper.kt         ← HTTP + search + load + loadLinks
├── ProviderMapper.kt           ← HTML → CloudStream objects
├── ProviderExtractors.kt       ← Video host extractors
├── ProviderParser.kt           ← Utility functions
│
ProviderNama/                   ← Thin provider module (extends ProviderCloudstream)
├── build.gradle.kts
├── src/main/kotlin/com/Nama/
│   ├── Nama.kt                 ← class Nama : ProviderCloudstream()
│   └── NamaPlugin.kt           ← registerMainAPI + registerExtractorAPI
```

## Cara Kerja

Semua logika scraping ada di `BaseProvider/`. Provider module hanya **thin wrapper** — 2 file, ~5 baris kode. Selector dikonfigurasi via **Owner Tagging** di `ProviderHTMLConstants.kt`.

Format Owner Tagging: `"ProviderID:::css-selector"`
Contoh: `"Anichin:::div.bsx h2, .tt, a[title]"`

Selector di-resolve oleh `selectFirstSafe()` — ambil match pertama dari provider-specific → multi-provider → GLOBAL.

## Aturan

1. **Jangan edit file di `ProviderNama/`** secara langsung — cukup edit `ProviderHTMLConstants.kt` + `ProviderExtractors.kt` di `BaseProvider/`
2. **Patch minimal** — jangan refactor kode yang tidak terkait
3. **Test selector** — jalankan `curl` + Python script untuk verifikasi selector match
4. **Jangan update versi** — versi di-set otomatis saat release

## Pull Request

1. Fork repo
2. Buat branch: `fix/xxx` atau `feat/xxx`
3. Commit dengan pesan deskriptif
4. Push → buat PR

## Menambah Provider Baru

1. Tambah selector di `ProviderHTMLConstants.kt` (Owner Tagging)
2. Buat folder `ProviderNama/` dengan `build.gradle.kts`, `Nama.kt`, `NamaPlugin.kt`, `AndroidManifest.xml`
3. Jika butuh extractor baru, tambah di `ProviderExtractors.kt`
4. Build: `./gradlew make`

## Menambah Extractor Baru

1. Buat class di `ProviderExtractors.kt` yang extends `ExtractorApi()`
2. Override: `name`, `mainUrl`, `requiresReferer`, `getUrl()`
3. Daftarkan di `ProviderExtractors.list`
