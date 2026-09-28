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
→ `provider` (Menambah Provider) → `selector-checker` (verifikasi) → `build-deploy` (commit & push)

### "Saya mau fix extractor yang broken"
→ `extraction` (Registry Priority dulu) → `logging` (log terbaru) → `build-deploy`

### "Selector tidak match"
→ `selector-checker` (3 rules + 4 phase) → `provider` (edit config) → `build-deploy`

### "Video gagal playback (3002)"
→ `extraction` (MasterLinkGenerator) → `logging`

### "Extractor 404 / konten hilang"
→ `extraction` (CONTENT_REMOVED) — **ini bukan bug OCE**, jangan laporkan ke user

### "Commit & push"
→ `build-deploy` (Commit Rules) — WAJIB cek CI. Docs-only (`OCE-skills/`) tidak trigger CI.

### "Debug error"
→ `development` (Debugging) → `logging` (query log **terbaru**) → `extraction` / `provider`

### "Log lama masih muncul setelah fix"
→ `logging` (Aturan Golden + verifikasi beta adoption) — belum tentu bug aktif

### "Paham arsitektur OCE"
→ `architecture`

### "Tambah config field baru"
→ `provider` (Menambah Config Field) → `development` (Impact Analysis)

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

## Aturan Wajib (berlaku untuk semua skill)

1. **Hanya log terbaru** — log lama bukan bukti bug aktif (`logging`)
2. **Trace semua entry path** sebelum patch guard (`development`)
3. **Cek registry** sebelum menyebut extractor aktif (`extraction`)
4. **Cek HTML live** sebelum menulis selector (`selector-checker`)
5. **No local gradle** — verifikasi hanya via CI (`build-deploy`)
6. **Jangan hidupkan fitur yang sudah di-revert** tanpa konfirmasi user (`shared`)

## Quality Standards

Setiap skill harus:
1. **Repository-aware** — fakta dari repo aktual, bukan asumsi
2. **Actionable** — agent tahu apa yang harus dilakukan
3. **Decision rules** — kapan pakai skill ini vs skill lain
4. **Verification** — cara buktikan task berhasil
5. **Failure recovery** — apa yang dilakukan saat gagal
6. **Cross-referenced** — link ke skill terkait
7. **Tanpa duplikasi** — satu fakta satu tempat; yang perlu detail panjang di-*link*, bukan disalin

## Maintenance

Saat proyek berubah, cek skill mana yang jadi basi:
- Jumlah provider / extractor berubah → `architecture`, `provider`, `shared`
- `FailureType` atau config field berubah → `logging`, `provider`
- Registry / step type berubah → `extraction`
- Workflow CI berubah → `build-deploy`

Setelah update: cek `wc -w */SKILL.md` — target total < 7500 words. Skill yang panjang = agent boros context.
