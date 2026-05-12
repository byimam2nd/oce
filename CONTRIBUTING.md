# 🤝 Panduan Kontribusi: Membangun Masa Depan OCE

Terima kasih telah meluangkan waktu untuk berkontribusi! Di **Open Cloudstream Extensions (OCE)**, kami tidak hanya mengumpulkan kode; kami membangun ekosistem yang resilien dan berkualitas tinggi. Kontribusi Anda—sekecil apa pun—sangat berarti bagi ribuan pengguna di komunitas ini.

---

## 🌟 Visi Kontribusi Kami

Kami mengutamakan **kualitas di atas kuantitas**. Setiap kontribusi yang masuk harus selaras dengan prinsip utama kami:
1.  **Stabilitas:** Kode harus tahan terhadap perubahan dinamis pada website sumber.
2.  **Efisiensi:** Performa adalah kunci. Hindari redundansi dan pemuatan data yang berlebihan.
3.  **Kebersihan:** Kode yang bersih adalah kode yang mudah dipelihara oleh siapa pun.

---

## 🏛️ Hukum Sentralisasi (The Law of Centralization)

Proyek OCE dibangun di atas filosofi **"Single Source of Truth"**. Tidak seperti repository ekstensi konvensional, OCE menggunakan arsitektur tersentralisasi di mana logika inti dipisahkan dari data spesifik situs.

### 1. Pengembangan Berbasis Blueprint (The Blueprint Rule)
Seluruh logika ekstraksi, penanganan network, dan pemrosesan data utama berada di dalam direktori `BaseHtmlProvider/`. 
- **Aturan:** Anda **DILARANG** melakukan modifikasi logika langsung pada folder modul provider (misal: `ProviderAnichin/`). Perubahan pada folder tersebut akan terhapus secara otomatis oleh sistem sinkronisasi kami.
- **Tindakan:** Setiap peningkatan fitur atau perbaikan bug pada mesin ekstraksi harus dilakukan di dalam **Base Blueprint**.

### 2. Abstraksi Data Melalui Constants
Mesin kami dirancang untuk menjadi "Buta Terhadap Situs" (Site-Agnostic). Artinya, logika di dalam blueprint tidak boleh mengetahui nama domain atau struktur spesifik sebuah situs secara hardcoded.
- **Aturan:** Semua selektor CSS, pola Regex, dan konfigurasi unik situs harus didefinisikan di dalam `ProviderConstants.kt` melalui pemetaan variabel.
- **Tindakan:** Jika Anda menemukan kasus unik pada sebuah situs, jangan ubah logikanya agar spesifik untuk situs tersebut. Alih-alih, buatlah sebuah konstanta baru atau flag konfigurasi di blueprint yang nilainya dapat disesuaikan melalui file konstanta masing-masing provider.

### 3. Skalabilitas Global
Setiap baris kode yang Anda tambahkan ke blueprint harus mampu menangani puluhan hingga ratusan situs secara bersamaan.
- **Aturan:** Hindari solusi "Quick Fix" yang hanya bekerja untuk satu situs namun berisiko merusak stabilitas provider lain.
- **Tindakan:** Selalu uji perubahan blueprint Anda terhadap berbagai target melalui skrip audit kami untuk memastikan tidak ada regresi global.

---

## 🚀 Alur Kontribusi (Workflow)

Untuk menjaga integritas proyek, kami menggunakan alur kerja yang terstruktur:

### 1. Temukan atau Buat Issue
Sebelum mulai menulis kode, pastikan ada **Issue** yang menjelaskan masalah atau fitur yang ingin Anda kerjakan. Ini membantu kita menghindari duplikasi kerja dan mendiskusikan pendekatan terbaik terlebih dahulu.

### 2. Persiapan Environment
Pastikan Anda memiliki tools yang sesuai dengan ekosistem kami:
- **JDK 17+**
- **Android SDK** (API Level 35)
- **Git**

### 3. Pengembangan & Sinkronisasi
Proyek ini menggunakan arsitektur unik berbasis blueprint. Jika Anda mengerjakan provider berbasis HTML:
- Perhatikan apakah perubahan Anda bersifat umum (berlaku untuk semua) atau spesifik.
- Gunakan skrip internal kami (`scripts/`) untuk memvalidasi perubahan Anda sebelum dikirimkan.

### 5. Prosedur Rilis (Release Process)
OCE menggunakan sistem rilis yang terproteksi:
- **Push ke Master:** Akan memperbarui branch `builds` secara otomatis. Gunakan ini untuk pengujian fungsional.
- **Rilis Resmi (Production):** Dilakukan dengan membuat **Git Tag**. 
    - Gunakan format `vX.Y.Z` (contoh: `git tag v1.0.0`).
    - Jalankan `git push origin v1.0.0` untuk memicu workflow rilis resmi.
    - Sistem akan otomatis membangun aset, membuat halaman rilis di GitHub, dan memperbarui `repo.json` untuk pengguna stabil.

---

## 📝 Standar Komunikasi (Commits)

Kami menggunakan **Conventional Commits** untuk memastikan riwayat proyek tetap rapi dan dapat dibaca oleh manusia maupun mesin:

| Tipe | Deskripsi |
| :--- | :--- |
| `feat` | Penambahan fitur atau provider baru. |
| `fix` | Perbaikan bug atau penyesuaian selektor yang rusak. |
| `docs` | Pembaruan dokumentasi atau file Markdown. |
| `refactor` | Perubahan kode yang tidak mengubah fungsi utama. |
| `chore` | Tugas pemeliharaan, sinkronisasi, atau konfigurasi CI/CD. |

**Contoh:** `fix: update search selector for Anichin provider`

---

## 🧪 Validasi & Pengujian

Sebelum mengirimkan Pull Request (PR), pastikan kontribusi Anda telah melewati "Uji Ketahanan":
1.  **Build Sukses:** Jalankan sistem build lokal untuk memastikan tidak ada kesalahan sintaks.
2.  **Audit Selektor:** Gunakan alat validasi kami untuk memastikan data yang diekstrak akurat.
3.  **CI/CD Ready:** Pastikan tidak ada rahasia (secrets) atau kunci API yang tertinggal di dalam kode.

---

## 📜 Kode Etik & Transparansi

- **Sopan Santun:** Kami menghargai setiap pendapat. Berkomunikasilah dengan profesional dan saling menghormati di kolom Issue maupun PR.
- **Tanggung Jawab:** Kontributor bertanggung jawab atas integritas kode yang mereka kirimkan.
- **Lisensi:** Dengan berkontribusi, Anda setuju bahwa kode Anda akan dilisensikan di bawah **GNU GPLv3**.

---

## 💡 Butuh Bantuan?

Jika Anda memiliki pertanyaan mengenai arsitektur teknis atau alur sinkronisasi kami, jangan ragu untuk:
- Membaca folder [docs/](docs/) untuk panduan mendalam.
- Membuka diskusi di bagian **Issues**.

Mari bersama-sama menjadikan **OCE** sebagai repository ekstensi terbaik di ekosistem CloudStream!

---
<div align="center">
  <b>Terima kasih telah menjadi bagian dari perjalanan kami.</b>
  <br>
  <i>"Bersama kita membangun hiburan yang lebih baik."</i>
</div>
