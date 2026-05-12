# 🤝 Contribution Guide | Panduan Kontribusi

[**🇺🇸 English**](#-contribution-guide) | [**🇮🇩 Bahasa Indonesia**](#-panduan-kontribusi)

---

## 🇺🇸 Contribution Guide

Thank you for your interest in contributing! At **OCE**, we build a high-quality, resilient ecosystem.

### 🏛️ The Law of Centralization
OCE is built on a **"Single Source of Truth"** philosophy.
1.  **Blueprint Rule:** All logic updates MUST be done in `BaseHtmlProvider/`. Changes inside individual provider folders (e.g., `ProviderAnichin/`) will be overwritten by the sync script.
2.  **Site-Agnostic Constants:** Define all CSS selectors and site-specific patterns in `ProviderConstants.kt` using the `ProviderID:::Value` tagging system.

### 📝 Commit Standards
We use **Conventional Commits**:
- `feat`: New feature or provider.
- `fix`: Bug fix or selector update.
- `docs`: Documentation updates.
- `chore`: Maintenance or CI/CD tasks.

---

## 🇮🇩 Panduan Kontribusi

Terima kasih telah meluangkan waktu untuk berkontribusi! 

### 🏛️ Hukum Sentralisasi
Proyek OCE menggunakan arsitektur tersentralisasi.
1.  **Pengembangan Berbasis Blueprint:** Seluruh logika utama berada di `BaseHtmlProvider/`. Anda **DILARANG** memodifikasi folder provider secara langsung.
2.  **Abstraksi Data Melalui Constants:** Semua selektor CSS dan konfigurasi unik situs harus didefinisikan di dalam `ProviderConstants.kt`.

### 📝 Standar Komunikasi (Commits)
Gunakan **Conventional Commits** (seperti `feat:`, `fix:`, `docs:`) untuk menjaga riwayat proyek tetap rapi.

---
<div align="center">
  <b>Thank you for being part of this journey. | Terima kasih telah menjadi bagian dari perjalanan kami.</b>
</div>
