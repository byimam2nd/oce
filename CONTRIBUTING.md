# 🤝 Panduan Kontribusi: Membangun Masa Depan OCE

Terima kasih telah meluangkan waktu untuk berkontribusi! Di **Open Cloudstream Extensions (OCE)**, kami tidak hanya mengumpulkan kode; kami membangun ekosistem yang resilien dan berkualitas tinggi. Kontribusi Anda—sekecil apa pun—sangat berarti bagi ribuan pengguna di komunitas ini.

---

## 🌟 Visi Kontribusi Kami

Kami mengutamakan **kualitas di atas kuantitas**. Setiap kontribusi yang masuk harus selaras dengan prinsip utama kami:
1.  **Stabilitas:** Kode harus tahan terhadap perubahan dinamis pada website sumber.
2.  **Efisiensi:** Performa adalah kunci. Hindari redundansi dan pemuatan data yang berlebihan.
3.  **Kebersihan:** Kode yang bersih adalah kode yang mudah dipelihara oleh siapa pun.

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

### 4. Standar Kode & Kualitas
Kami sangat menjunjung tinggi standar kode yang bersih:
- Gunakan penamaan yang deskriptif dan konsisten.
- Pastikan penanganan error (error handling) dilakukan secara elegan tanpa mengganggu stabilitas aplikasi.
- Gunakan sistem logging CloudStream secara tepat sesuai standar proyek.

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
