-- ============================================================================
-- OCE Supabase — Migration 0005: plugin_version di logs/scrape_runs/scrape_steps
-- ============================================================================
-- Latar belakang:
--   kolom `plugin_version` tidak pernah ada, sehingga tidak ada baris log yang
--   bisa diatribusikan ke build tertentu. Akibatnya "fix tidak berefek" tidak
--   bisa dibedakan dari "user masih pakai APK lama" — dan itu penyebab utama
--   diagnosa meleset (lihat skill `logging`).
--
--   Bukti nyata: fix a8a20c3 (06:16 UTC) dan c4b9579 (09-27) sudah hijau di
--   CI, tapi signature log-nya masih muncul di produksi 3.5 jam kemudian karena
--   59 commit belum pernah di-release (tag tertinggal di v3.9.1 / 2026-09-21).
--   Tanpa plugin_version, ini baru ketahuan setelah dilakukan investigasi
--   manual — dan bisa saja disalahartikan sebagai "fix tidak bekerja".
--
--   Nilai = versionCode plugin = OCE_VERSION = epoch menit (mis. 29843203),
--   sama dengan yang dipakai ci-cd.yml (beta) dan release.yml (stable).
--
-- ORDEN PENERAPAN — WAJIB:
--   1. Terapkan migration ini DULU (dashboard Supabase → SQL Editor).
--   2. Baru pakai build yang mengirim plugin_version.
--   Kalau dibalik, INSERT log akan gagal total (bukan partial).
--
-- Kolom `text` (bukan integer) supaya aman walau suatu saat scheme version
-- berubah; query tetap bisa membandingkan sebagai string karena epoch menit
-- panjangnya tetap. Index partial: hanya baris yang punya versi.
--
-- Ketiga tabel dapat kolom yang sama: satu baris log/step selalu berasal dari
-- satu proses plugin, jadi versinya konsisten. `scrape_runs` adalah attributor
-- paling akurat (satu run = satu perangkat); `logs` dipakai untuk query
-- ad-hoc cepat tanpa join.
-- ============================================================================

alter table public.logs
    add column if not exists plugin_version text;   -- versionCode / OCE_VERSION

alter table public.scrape_runs
    add column if not exists plugin_version text;

alter table public.scrape_steps
    add column if not exists plugin_version text;

-- Query ad-hoc: "log mana yang berasal dari build X?" dan "build berapa
-- yang aktif dalam window ini?"
create index if not exists logs_plugin_version_idx on public.logs (plugin_version)
    where plugin_version is not null;

-- Rekap cepat per build untuk semua provider sekaligus.
create index if not exists logs_plugin_version_created_idx
    on public.logs (plugin_version, created_at desc)
    where plugin_version is not null;

-- Adopsi build: berapa run per versi, dan kapan versi terakhir terlihat.
-- Dipakai untuk menjawab "user sudah update belum?" tanpa menebak.
create index if not exists scrape_runs_plugin_version_idx
    on public.scrape_runs (plugin_version, started_at desc)
    where plugin_version is not null;

-- ---------------------------------------------------------------------------
-- Verifikasi setelah menerapkan (jalankan di SQL Editor):
--
--   1. Konfirmasi 3 kolom ada:
--   select table_name, column_name, data_type from information_schema.columns
--    where table_name in ('logs','scrape_runs','scrape_steps')
--      and column_name = 'plugin_version' order by table_name;
--   -- harus mengembalikan 3 baris, semua bertipe text
--
--   2. Setelah build baru dipakai user (bukan langsung setelah migration):
--   select plugin_version, count(*) as runs, max(started_at) as last_seen
--     from scrape_runs where plugin_version is not null
--    group by 1 order by 3 desc;
--
--   3. Filter satu build tertentu saat debugging:
--   select created_at, level, tag, failure_type, message
--     from logs
--    where plugin_version = '29843203'
--      and level <> 'SUCCESS'
--    order by created_at desc limit 30;
-- ---------------------------------------------------------------------------
