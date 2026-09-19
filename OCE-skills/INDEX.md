# OCE Skills INDEX

Map seluruh OCE Skills. Gunakan untuk menemukan skill yang tepat.

## Skills

| # | Skill | Purpose | When to Use | File |
|---|-------|---------|-------------|------|
| 1 | `oce-shared` | Shared rules: git, change safety, anti-hallucination, verification | SEBELUM semua skill lain | `shared/SKILL.md` |
| 2 | `oce-architecture` | Module structure, data flow, mental model repository | Sebelum perubahan signifikan | `architecture/SKILL.md` |
| 3 | `oce-build-deploy` | CI/CD pipeline, commit rules, tag & release | Commit, push, build, release | `build-deploy/SKILL.md` |
| 4 | `oce-development` | Dev workflow, debugging, testing, impact analysis | Development task, debugging | `development/SKILL.md` |
| 5 | `oce-extraction` | Extractor system, config-driven, MasterLinkGenerator, 3002 | Tambah/edit extractor | `extraction/SKILL.md` |
| 6 | `oce-logging` | Supabase observability, FailureType, log querying | Debug via production logs | `logging/SKILL.md` |
| 7 | `oce-provider` | Config-driven providers, adding/editing providers, ProviderConfig | Tambah/edit provider | `provider/SKILL.md` |
| 8 | `oce-selector-checker` | 4-phase selector verification via curl | Verifikasi selector | `selector-checker/SKILL.md` |

## Navigation by Task

### "Saya mau tambah provider baru"
→ `provider` ( Adding New Provider) → `selector-checker` (verifikasi) → `build-deploy` (commit & push)

### "Saya mau fix extractor yang broken"
→ `extraction` (Config-Driven Extractors) → `logging` (cek error logs) → `build-deploy`

### "Selector tidak match"
→ `selector-checker` (4-phase test) → `provider` (edit config) → `build-deploy`

### "Video gagal playback (3002)"
→ `extraction` (3002 Protection) → `logging` (cek logs)

### "Saya mau commit & push"
→ `build-deploy` (Commit Rules) — WAJIB cek CI

### "Saya mau debug error"
→ `development` (Debugging) → `logging` (query logs) → `extraction` atau `provider`

### "Saya mau pahami arsitektur OCE"
→ `architecture` (Repository Structure, Data Flow)

### "Saya mau tambah config field baru"
→ `provider` (Adding Config Field) → `development` (Impact Analysis)

## Skill Relationships

```
shared (SEBELUM semua)
  ↓
architecture (mental model)
  ↓
provider ←→ extraction ←→ selector-checker
  ↓               ↓              ↓
development ←→ logging
  ↓
build-deploy (SETELAH semua)
```

## Quality Standards

Setiap skill harus:
1. **Repository-aware** — fakta dari repo aktual, bukan asumsi
2. **Actionable** — agent tahu apa yang harus dilakukan
3. **Decision rules** — kapan pakai skill ini vs skill lain
4. **Verification** — cara buktikan task berhasil
5. **Failure recovery** — apa yang dilakukan saat gagal
6. **Cross-referenced** — link ke skill terkait
