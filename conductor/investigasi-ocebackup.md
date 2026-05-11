# Rencana Investigasi dan Sinkronisasi oceBackup

## 1. Tujuan (Objective)
Tujuan dari rencana ini adalah untuk melakukan investigasi mendalam terhadap implementasi individual provider yang ada di direktori `oceBackup`. Versi backup ini diketahui stabil (poster muncul dan halaman episode tidak error). Setelah menemukan perbedaan logika atau *missing links* pada engine sentralisasi kita (`BaseHtmlProvider`), saya akan merancang **Penyempurnaan Sentralisasi V8.0**.

## 2. Kebutuhan Akses
Saat ini saya berada di **Plan Mode** dengan akses baca (`read_file`) yang dibatasi secara keamanan hanya pada direktori *workspace* (`.../oce/`). Folder `oceBackup` berada di luar *workspace*, sehingga saya tidak bisa membacanya di mode ini.

## 3. Langkah-langkah (Implementation Steps)
1.  **Keluar dari Plan Mode:** Setelah Anda menyetujui rencana awal ini, saya akan menggunakan alat `exit_plan_mode` untuk beralih ke mode eksekusi biasa.
2.  **Investigasi Mendalam:** Menggunakan `run_shell_command`, saya akan membaca file `{Provider}.kt` dari `oceBackup` (seperti Anichin, LayarKaca21, dll.).
3.  **Analisis Komparatif:** Saya akan membandingkan:
    *   Metode `toSearchResult()`: Untuk melihat bagaimana poster diekstraksi dan apakah ada flag `isHorizontalImages` yang terlewat.
    *   Metode `load()`: Untuk melihat bagaimana episode di-parsing, bagaimana URL data dikonstruksi, dan mengapa tipe konten kadang terdeteksi salah.
    *   Metode `getMainPage()`: Melihat konstruksi URL yang paling stabil.
4.  **Penyempurnaan Master Constants:** Merumuskan konfigurasi sentral baru yang mampu mencakup logika kompleks dari versi aslinya.
5.  **Penerapan & Sinkronisasi:** Menerapkan perubahan ke `BaseHtmlProvider/Provider.kt` dan melakukan sinkronisasi terakhir.

## 4. Persetujuan
Apakah Anda setuju dengan langkah investigasi ini agar saya bisa keluar dari Plan Mode dan mulai mengumpulkan data dari `oceBackup`?