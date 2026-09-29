-- ============================================================================
-- OCE Supabase — Migration 0007: jadwal retensi observability (pg_cron)
-- ============================================================================
-- Tujuan:
--   `logs` tumbuh ~14 MB/hari (~36k baris/hari) dari satu build yang sedang
--   beta. Kuota 500 MB habis dalam ~5 minggu kalau tidak dipangkas, dan
--   Supabase tidak memberi notifikasi sebelum project kena batas.
--
--   Fungsi `prune_observability(days)` sudah ada di 0006. Yang belum ada
--   adalah pemanggil terjadwal. sebelumnya satu-satunya pilihan adalah cron
--   dari luar repo; sekarang jadwalnya hidup DI DALAM database lewat pg_cron,
--   jadi tidak bergantung pada mesin, GitHub Actions, atau repo ini tetap hidup.
--
-- Jendela retensi: 7 hari (CONFIGURABLE — lihat catatan Ubah Retensi di bawah).
--
-- CATATAN KEAMANAN: job ini berjalan sebagai `postgres`, jadi RLS dilewati.
--   Jangan pernah memberi EXECUTE prune_observability ke anon/authenticated —
--   revoke-nya sudah ada di 0006 dan harus tetap begitu.
-- ============================================================================

-- pg_cron = penjadwal bawaan Supabase, terpasang per-database.
create extension if not exists pg_cron;

-- Buang jadwal lama lebih dulu supaya file ini idempoten (menjalankan ulang
-- tidak akan menduplikasi job).
select cron.unschedule(jobid)
  from cron.job
 where jobname = 'oce-prune-observability';

-- 03:17 UTC setiap hari. Menit 17 supaya tidak berdesakan dengan job lain
-- yang biasanya jalan tepat jam 0.
select cron.schedule(
    'oce-prune-observability',
    '17 3 * * *',
    'select public.prune_observability(7);'
);

-- ---------------------------------------------------------------------------
-- Ubah Retensi
--   Ubah angka 7 di perintah di atas, lalu jalankan ulang file ini.
--   Contoh 14 hari: 'select public.prune_observability(14);'
--
-- Matikan sementara:
--   select cron.unschedule('oce-prune-observability');
--   (diseret sekali lagi baris schedule di atas untuk menghidupkannya)
--
-- Cek hasil eksekusi terakhir:
--   select jobid, run_time, status, return_message
--     from cron.job_run_details
--    where jobname = 'oce-prune-observability'
--    order by run_time desc limit 5;
--
-- Pratinjau SEBELUM memangkas (tidak menghapus apa pun):
--   select count(*) from public.logs
--    where created_at < now() - interval '7 days';
-- ---------------------------------------------------------------------------
