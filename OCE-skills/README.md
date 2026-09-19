# OCE Skills

Knowledge layer untuk AI Agent yang bekerja pada repository OCE (CloudStream plugin system).

## Apa ini

OCE Skills adalah kumpulan instruksi engineering yang membantu AI Agent memahami, mengembangkan, memperbaiki, menguji, dan me-manage repository OCE secara akurat.

Bukan tutorial coding umum — setiap skill berisi pengetahuan spesifik OCE yang diambil dari repository aktual.

## Struktur

```
OCE-skills/
├── README.md              ← file ini
├── INDEX.md               ← peta semua skills
├── shared/                ← rules yang berlaku untuk semua skill
├── architecture/          ← module structure, data flow
├── build-deploy/          ← CI/CD, commit, tag, release
├── development/           ← workflow, debugging, testing
├── extraction/            ← extractor system, config-driven
├── logging/               ← Supabase, FailureType
├── provider/              ← config-driven providers
└── selector-checker/      ← 4-phase selector verification
```

## Bagaimana Agent Menemukan Skills

OpenCode mendukung recursive skill discovery. Skills di `OCE-skills/<name>/SKILL.md` akan otomatis ter-deteksi jika path ter-register.

### Konfigurasi (`.opencode/opencode.json`)
```json
{
  "skills": {
    "paths": ["OCE-skills"]
  }
}
```

### Frontmatter (wajib di setiap SKILL.md)
```yaml
---
name: oce-skill-name
description: Deskripsi singkat
license: MIT
metadata:
  project: oce
  type: skill-type
---
```

## Bagaimana Skills Digunakan

1. Agent membaca task dari user
2. Agent cek INDEX.md untuk menemukan skill yang relevan
3. Agent load skill via `skill` tool
4. Agent ikuti workflow di skill
5. Agent verifikasi hasil

## Hubungan Antar Skills

```
shared (rules dasar — baca dulu)
  ↓
architecture (mental model repo)
  ↓
provider ←→ extraction ←→ selector-checker
  ↓               ↓              ↓
development ←→ logging
  ↓
build-deploy (final: commit & push)
```

## Aturan Umum

1. **Repository adalah source of truth** — jangan asumsi, cek kode
2. **Shared rules berlaku untuk semua** — baca `shared/SKILL.md` dulu
3. **Minimal change** — perubahan sekecil mungkin
4. **Verification wajib** — jangan claim selesai tanpa bukti
5. **CI only** — no local gradle build

## Menambah Skill Baru

1. Buat folder baru di `OCE-skills/<nama>/`
2. Buat `SKILL.md` dengan frontmatter lengkap
3. Ikuti format standar (lihat skill yang ada)
4. Update `INDEX.md`
5. Pastikan skill repository-aware (bukan generik)

## Memperbaharui Skills

1. Baca skill yang ada
2. Cek apakah informasi masih sesuai repository
3. Update jika ada perubahan
4. Tandai uncertainty jika belum bisa verifikasi

## Standar Kualitas

| Kriteria | Requirement |
|----------|-------------|
| Repository-aware | Fakta dari repo OCE, bukan asumsi |
| Actionable | Agent tahu apa yang harus dilakukan |
| Decision rules | Kapan pakai vs tidak pakai |
| Verification | Cara buktikan berhasil |
| Failure recovery | Apa yang dilakukan saat gagal |
| Cross-reference | Link ke skill terkait |
| Maintainable | Dapat diupdate saat repo berubah |
