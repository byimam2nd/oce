---
name: oce-dev
description: OCE Development Guide — panduan utama pengembangan plugin CloudStream OCE
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: development-guide
---

# OCE Development Guide

Panduan utama untuk pengembangan plugin CloudStream OCE.

## Aturan Dasar

1. **Semua kode di `BaseProvider/`** — jangan edit langsung di folder provider
2. **Tidak ada sync script** — sourceSets handle otomatis
3. **Owner Tagging** — format `"ProviderID:::css-selector"` untuk konfigurasi per-provider
4. **Commit harus jelas** — jangan commit sebelum diperintah user

## Skill Navigasi

| Skill | Deskripsi |
|---|---|
| [`oce-architecture`](../oce-architecture/SKILL.md) | BaseProvider, sourceSets, Owner Tagging |
| [`oce-provider-dev`](../oce-provider-dev/SKILL.md) | Cara nambah/edit provider |
| [`selector-checker`](../selector-checker/SKILL.md) | ✅ Verifikasi selector 4 phase |
| [`oce-extractor-dev`](../oce-extractor-dev/SKILL.md) | Extractor, JS Packer decoder |
| [`oce-logging`](../oce-logging/SKILL.md) | Telegram log, FailureType |
| [`oce-build-deploy`](../oce-build-deploy/SKILL.md) | CI/CD, commit, release |

## Workflow Pengembangan

```
1. Pahami masalah → cek Supabase observability (logs / scrape_steps)
2. Identifikasi root cause
3. Edit file di BaseProvider/
4. Test dengan 4-phase selector checker
5. Tampilkan hasil ke user
6. Tunggu perintah commit/push
```
