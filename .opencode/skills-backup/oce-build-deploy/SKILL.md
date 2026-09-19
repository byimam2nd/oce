---
name: oce-build-deploy
description: OCE Build & Deploy — CI/CD pipeline, commit, release
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: build-deploy
---

# OCE Build & Deploy

## Build System

### Gradle
- Android Library + Kotlin
- CloudStream plugin via `com.lagradost.cloudstream3.gradle`
- Build cache enabled, parallel execution

### RULE WAJIB: Build HANYA via Commit & Push + CI
- **DILARANG keras** menjalankan `./gradlew` (atau build gradle apa pun) di lingkungan lokal/terminal.
- Build & verifikasi kode dilakukan **hanya** dengan: `git commit` → `git push origin master` → cek CI (`gh run watch <id> --exit-status`).
- Tidak ada `./gradlew` lokal di mesin pengembangan; mencoba build lokal hanya membuang waktu & men-download cache yang tidak perlu.
- Jika user meminta "build" / "verifikasi" / "cek compile", jalankan commit + push + CI — jangan build lokal.

### sourceSets
BaseProvider dikompilasi langsung ke setiap provider — **tidak ada sync script**.

## CI/CD Pipeline

### Build (`ci-cd.yml`) — trigger: push ke master
```
push → Checkout → JDK 17 → Android SDK
  → ./gradlew make (build .cs3)
  → ./gradlew makePluginsJson
  → cp *.cs3 + plugins.json → branch "builds"
```

### Release (`release.yml`) — trigger: tag v*
```
tag v* → Checkout → JDK 17 → Android SDK
  → ./gradlew make
  → ./gradlew makePluginsJson
  → patch URLs untuk GitHub Release
  → create GitHub Release dengan .cs3 + plugins.json
```

### Branch Structure
- `master` — source code
- `builds` — build artifacts (.cs3, plugins.json) untuk beta
- GitHub Releases — stable artifacts

## Commit Rules

1. **Jangan commit sebelum diperintah user**
2. Commit message jelas dan deskriptif
3. Format: `fix:` / `feat:` / `refactor:` / `chore:`
4. Satu commit satu perubahan logis

## Tag & Release

### Aturan Versi — ikuti BESARNYA EFEK pada proyek

Bump versi ditentukan oleh dampak pengembangan terhadap proyek, BUKAN urutan
rilis. Sebelum menag, nilai dulu: seberapa besar pengaruh perubahan ini ke
perilaku user / struktur proyek?

| Dampak | Contoh | Bump |
|--------|--------|------|
| Kecil (patch) | bug fix, perf, refactor internal, chore, no user-facing change | `vX.Y.(Z+1)` |
| Sedang (minor) | fitur baru user-facing, provider/extractor baru, perilaku berubah | `vX.(Y+1).0` |
| Besar / breaking (major) | arsitektur berubah, API/config break, migrasi besar | `v(X+1).0.0` |

Contoh nyata: perubahan kecil (hapus cache extractor, cancel race scope,
batch insert log) → patch bump, mis. `v3.13.0` → `v3.13.01`. Fitur baru
(mis. provider baru) → minor bump. Breaking (mis. konfigurasi JSON berubah
format) → major bump.

### Alur

```bash
git tag -a v3.13.01 -m "..."   # tag ANNOTATE di HEAD (setelah CI hijau)
git push origin v3.13.01       # trigger release pipeline (public repo)
git push private v3.13.01      # source repo
```

- Tag SELALU di HEAD — satu rilis mencakup semua commit sejak tag sebelumnya
  (tidak ada tag per-commit).
- TUNGGU CI build hijau SEBELUM tag (tag men-trigger release pipeline).
- Tag di-push ke DUA remote (`origin` + `private`); release.yml membuat
  GitHub Release di public repo.
- Format nomor versi ikuti semver (patch boleh `01` dst, sesuai dampak kecil).

## Distribution

- **Stable**: `repo.json` → `releases/latest/download/plugins.json`
- **Beta**: `repo-beta.json` → `raw.githubusercontent.com/.../builds/plugins.json`
