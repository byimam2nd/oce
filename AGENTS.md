# AGENTS.md — OCE Development Context

Project CloudStream extension OCE. Bacaan wajib sebelum mengerjakan task.

## Aturan Keras

1. **DILARANG build gradle lokal** (`./gradlew` dsb.). Build & verifikasi HANYA
   via `commit → push → cek CI` (`gh run watch <id> --exit-status`). CI trigger
   di repo `byimam2nd/oce-source`; pakai `gh --repo byimam2nd/oce-source`.
2. **Jangan edit file di `ProviderNama/`** — akan ditimpa saat build. Edit di `BaseProvider/`.
3. **Jangan cache hasil fetch extractor** — `M3u8MasterVerifier` &
   `AdaptiveHeaderProbe` selalu fetch ulang (lihat skill `oce-extractor-dev`).
4. **Jangan commit sebelum diperintah user.**
5. `ExpiringCache` HANYA untuk HTML cache scraper — DILARANG untuk extractor.

## Remote

- `origin` = `byimam2nd/oce` (public, release)
- `private` = `byimam2nd/oce-source` (source, CI trigger)
- Push ke master: `git push origin master && git push private master`
- Tag release: `git tag -a vX.Y.Z` → push ke kedua remote → workflow `release.yml`
  membuat GitHub Release di public repo.

## Arsitektur Singkat

- `BaseProvider/src/main/kotlin/com/baseprovider/` — sumber tunggal kode bersama:
  `core/` (MainAPI+scraper), `config/` (per-provider JSON), `collector/`,
  `cache/`, `network/`, `extractor/` (ExtractorRegistry, MasterLinkGenerator,
  M3u8MasterVerifier), `log/` (Supabase observability), `model/`, `settings/`.
- Provider module = thin wrapper (3 file): `Nama.kt` + `NamaPlugin.kt` +
  `build.gradle.kts`. Selector di `config/<name>.json` (bukan Owner Tagging —
  konvensi `"ProviderID:::css-selector"` sudah dihapus).
- Semua link video deliver via `MasterLinkGenerator.createSmartLink(...)` —
  proteksi blank URL + 3002 + header probe otomatis.
- Observability: Supabase (`logs`, `scrape_runs`, `scrape_steps`). Telegram
  sudah dihapus. Cara cek log ada di skill `oce-logging`.

## Skill Relevan

- `oce-architecture` — struktur & sourceSets
- `oce-provider-dev` — config-driven provider
- `oce-extractor-dev` — extractor, 3002, no-cache rule
- `oce-build-deploy` — CI/CD, tag & release
- `oce-logging` — Supabase observability
- `selector-checker` — verifikasi selector 4 phase

## Verifikasi Sebelum Selesai

- Syntax & import valid, tidak ada dead code/duplicate logic
- Edge cases: null/empty/boundary/timeout/failure path
- Satu perubahan logis per commit, pesan jelas (`fix:`/`feat:`/`refactor:`/`chore:`)
- CI hijau sebelum dianggap selesai