<div align="center">

# 🌊 OCE - Open Cloudstream Extensions
**High-Quality Extensions for Your Ultimate Streaming Experience**

[![Build & Deploy](https://github.com/byimam2nd/oce/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/byimam2nd/oce/actions)
[![CloudStream](https://img.shields.io/badge/Platform-CloudStream-blueviolet?style=for-the-badge&logo=android)](https://cloudstream.cf)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](https://www.gnu.org/licenses/gpl-3.0)
[![Maintained](https://img.shields.io/badge/Maintained%3F-yes-green.svg?style=for-the-badge)](https://github.com/byimam2nd/oce/graphs/commit-activity)

<p align="center">
  <a href="#-features">Features</a> •
  <a href="#-installation">Installation</a> •
  <a href="#-available-extensions">Extensions</a> •
  <a href="#-architecture">Architecture</a> •
  <a href="#-support">Support</a>
</p>

---

</div>

## ✨ Kenapa Memilih OCE?

**OCE** (Open Cloudstream Extensions) adalah koleksi ekstensi pihak ketiga untuk aplikasi [CloudStream](https://github.com/recloudstream/cloudstream). Kami berfokus pada penyediaan akses yang stabil, cepat, dan berkualitas ke berbagai sumber konten favorit Anda, mulai dari Anime hingga Film populer.

- **🚀 Optimasi Kecepatan:** Ekstensi dirancang ringan dan efisien.
- **🛡️ Teruji secara Berkala:** Skrip validasi otomatis memastikan selektor selalu *up-to-date*.
- **🇮🇩 Berfokus pada Komunitas:** Prioritas utama pada konten dengan dukungan bahasa Indonesia.
- **💎 Kualitas Premium:** Dukungan multi-kualitas (360p - 1080p) tergantung ketersediaan sumber.

---

## 📥 Panduan Instalasi

Menambahkan repository OCE ke CloudStream sangatlah mudah. Gunakan salah satu metode di bawah ini:

### **Cara Tercepat (Otomatis)**
1. Salin URL berikut:
   ```text
   https://raw.githubusercontent.com/byimam2nd/oce/builds/plugins.json
   ```
   *(Gunakan branch `builds` untuk stabilitas maksimal)*
2. Buka aplikasi **CloudStream**.
3. Masuk ke **Settings** ➡️ **Extensions**.
4. Klik **Add Repository**.
5. Masukkan nama (misal: `OCE`) dan tempelkan URL yang sudah disalin.

### **Cara Manual**
Anda juga bisa mengunduh file `.cs3` langsung dari [GitHub Releases](https://github.com/byimam2nd/oce/releases) dan memasangnya melalui menu **Install .cs3 file** di dalam aplikasi.

---

## 📺 Koleksi Ekstensi Saat Ini

Kami terus memperluas daftar ini. Berikut adalah yang tersedia saat ini:

### 🇯🇵 Anime & Donghua
| Sumber | Wilayah | Status |
| :--- | :---: | :---: |
| **Anichin** | 🇮🇩 ID | ![Active](https://img.shields.io/badge/-Online-success) |
| **Animasu** | 🇮🇩 ID | ![Active](https://img.shields.io/badge/-Online-success) |
| **Samehadaku** | 🇮🇩 ID | ![Active](https://img.shields.io/badge/-Online-success) |
| **Donghuastream** | 🇮🇩 ID | ![Active](https://img.shields.io/badge/-Online-success) |

### 🎬 Movies & TV Series
| Sumber | Wilayah | Status |
| :--- | :---: | :---: |
| **LayarKaca21** | 🇮🇩 ID | ![Active](https://img.shields.io/badge/-Online-success) |
| **Idlix** | 🇮🇩 ID | ![Active](https://img.shields.io/badge/-Online-success) |
| **Pencurimovie** | 🇲🇾 MY/ID | ![Active](https://img.shields.io/badge/-Online-success) |
| **Melolo** | 🌍 Global | ![Active](https://img.shields.io/badge/-Online-success) |
| **Dramabox** | 🌍 Global | ![Active](https://img.shields.io/badge/-Online-success) |

---

## 🏗️ Arsitektur & Otomatisasi

Proyek ini menggunakan sistem **Base HTML Provider Sync** yang canggih untuk memastikan konsistensi kode di seluruh penyedia layanan.

- **`BaseHtmlProvider/`**: Blueprint utama untuk semua provider berbasis HTML. Perubahan di sini akan disinkronkan ke seluruh provider terkait.
- **`scripts/sync_providers.py`**: Mesin sinkronisasi yang secara otomatis menerapkan perubahan dari blueprint ke masing-masing modul provider.
- **`scripts/validate_selectors_pro.py`**: Alat validasi selektor untuk mendeteksi perubahan struktur pada website sumber secara dini.
- **CI/CD Pipeline**: Build otomatis yang menangani kompilasi, pembuatan file `.cs3`, dan pembaruan `plugins.json` pada branch `builds`.

Untuk detail lebih lanjut mengenai struktur dan standar pengembangan, silakan merujuk ke folder [docs/](docs/).

---

## 🤝 Kontribusi & Dukungan

Proyek ini sepenuhnya open-source. Anda dapat membantu kami dengan:
- Memberikan **Star** ⭐ pada repository ini.
- Melaporkan bug melalui **Issues**.
- Mengirimkan **Pull Request** untuk perbaikan atau fitur baru.

Jika Anda merasa proyek ini bermanfaat dan ingin memberikan dukungan lebih:
- ☕ [Buy Me A Coffee](https://buymeacoffee.com/imam2nd)
- 💖 [SociaBuzz (Local Indonesia)](https://sociabuzz.com/imam2nd/tribe)

---

## 🎖️ Credits & Special Thanks

Terima kasih khusus kepada:

- **[CloudStream Team & Contributors](https://github.com/recloudstream)** - Untuk ekosistem luar biasa yang memungkinkan proyek ini ada.
- **[Phisher](https://github.com/Phisher98)** - Untuk inspirasi struktur dan logika penyedia konten.
- **[ExtCloud](https://github.com/recloudstream/cloudstream-extensions)** - Sebagai referensi utama pengembangan ekstensi.

---

## ⚖️ Lisensi & Disclaimer

Proyek ini berlisensi di bawah **GNU GPLv3**.

**OCE (Open Cloudstream Extensions)** adalah proyek independen dan tidak berafiliasi secara resmi dengan tim CloudStream. 

**Penting:** Repository ini tidak menyimpan file video apa pun di server kami. Semua konten disediakan oleh pihak ketiga. Kami tidak bertanggung jawab atas penggunaan ekstensi ini oleh pengguna. Harap gunakan dengan bijak dan hormati hak cipta.

---
<div align="center">
  Dibuat dengan ❤️ oleh <a href="https://github.com/byimam2nd">imam2nd</a>
</div>
