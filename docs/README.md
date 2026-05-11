# 📚 Dokumentasi Proyek OCE

## 📖 Daftar Isi

### Untuk Pemula (WAJIB)
- **[PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)** - Struktur folder dan file proyek (WAJIB DIBACA)

### Untuk Pengembangan
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Referensi cepat operasi umum
- **[CODE_EXAMPLES.md](CODE_EXAMPLES.md)** - Contoh kode siap pakai
- **[FUNCTION_INDEX.md](FUNCTION_INDEX.md)** - Lookup fungsi berdasarkan alfabet
- **[DEVELOPMENT_GUIDELINES.md](DEVELOPMENT_GUIDELINES.md)** - Panduan pengembangan

### Untuk Contributing
- **[CONTRIBUTING.md](../CONTRIBUTING.md)** - Panduan berkontribusi (di root folder)

---

## ⚠️ Dokumentasi Obsolete

Dokumentasi berikut **TIDAK AKTIF** karena struktur proyek sudah berubah:

- ~~PHILOSOPHY_AND_ARCHITECTURE.md~~ - referensi folder yang tidak ada
- ~~ARCHITECTURE.md~~ - referensi folder yang tidak ada
- ~~CONTEXT.md~~ - referensi folder yang tidak ada
- ~~QUICK_START.md~~ - referensi folder yang tidak ada
- ~~COMMON_MISTAKES.md~~ - referensi struktur lama
- ~~REFACTORING_PLAN.md~~ - referensi struktur lama
- ~~REFACTORING_COMPLETE.md~~ - referensi struktur lama
- ~~RESEARCH_MODULE_RATELIMITER.md~~ - tidak relevan
- ~~GITHUB_CLI_WORKFLOW_AUTOMATION.md~~ - workflow tidak ada
- ~~EXTRACTOR_KNOWLEDGE_BASE.md~~ - referensi tidak ada

Dokumentasi di atas dipertahankan untuk referensi history saja.

---

## 🎯 Mulai Dari Sini

**Untuk developer baru:**
1. Baca [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) - Pahami struktur proyek
2. Baca [DEVELOPMENT_GUIDELINES.md](DEVELOPMENT_GUIDELINES.md) - Pahami cara开发
3. Lihat [CODE_EXAMPLES.md](CODE_EXAMPLES.md) - Lihat contoh implementasi

**Untuk contribuytor:**
1. Baca [CONTRIBUTING.md](../CONTRIBUTING.md) - Guidelines kontribusi

---

## 📁 Struktur Dokumen

```
docs/
├── PROJECT_STRUCTURE.md       # Struktur folder proyek (PENTING!)
├── DEVELOPMENT_GUIDELINES.md  # Panduan pengembangan
├── QUICK_REFERENCE.md         # Referensi cepat
├── CODE_EXAMPLES.md           # Contoh kode
├── FUNCTION_INDEX.md          # Index fungsi
├── CONTEXT.md                 # Overview proyek
└── README.md                  # Index ini
```

---

## 🔗 Link Eksternal

- [CloudStream Official Docs](https://recloudstream.github.io/csdocs/)
- [CloudStream Wiki](https://cloudstream.miraheze.org/wiki/)
- [CloudStream GitHub](https://github.com/recloudstream)

---

## 📋 Catatan

- Setiap provider memiliki file `*Utils.kt` sendiri - **tidak ada shared module**
- Tidak ada `master/` folder atau `generated_sync/` - dokumentasi lama sudah obsolete
- Build system menggunakan Gradle dengan CloudStream gradle plugin

---

*Last Updated: 2026-05-06*
*Status: ✅ Accurate & Updated*