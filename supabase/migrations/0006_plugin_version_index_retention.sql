-- ============================================================================
-- OCE Supabase — Migration 0006: index plugin_version + retensi observability
-- ============================================================================
-- Tujuan:
--   * `scrape_steps` belum punya index plugin_version, padahal `logs` dan
--     `scrape_runs` sudah. Padahal `scrape_steps` adalah tabel yang paling
--     besar per link (satu baris per percobaan extractor) dan justru tabel
--     yang dipakai untuk menghitung adopsi versi per provider. Tanpa index,
--     query adopsi harus seq-scan seluruh tabel.
--   * `logs` tumbuh ~14 MB/hari (~36k baris/hari) pada 1 build yang sedang
--     beta. Free tier 500 MB habis dalam ~5 minggu tanpa pemotongan.
--     Fungsi prune dipakai untuk memangkas sesuai jendela retensi.
--
-- CATATAN KEAMANAN — fungsi ini `security definer` (RLS dilewati) dan
-- menghapus baris. Postgres memberi EXECUTE ke PUBLIC untuk setiap fungsi
-- baru, jadi tanpa revoke di bawah, role anon bisa menghapus seluruh tabel
-- observability. Revoke itu wajib, bukan opsional.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Index plugin_version untuk scrape_steps
-- ---------------------------------------------------------------------------
create index if not exists scrape_steps_plugin_version_idx
    on public.scrape_steps (plugin_version);

-- Query yang memakai index ini:
--   select plugin_version, count(*) from scrape_steps group by 1;
--   select * from scrape_steps where plugin_version = '29843962';
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 2. Fungsi retensi observability
-- ---------------------------------------------------------------------------
-- Aman dipanggil berulang; tidak ada efek samping selain menghapus.
-- Pratinjau dulu tanpa menghapus:
--   select count(*) from public.logs
--    where created_at < now() - interval '7 days';
--
-- Urutan hapus mengikuti ketergantungan FK: step lebih dulu, lalu run-nya.
create or replace function public.prune_observability(days integer default 7)
returns table (steps_deleted bigint, runs_deleted bigint, logs_deleted bigint)
language plpgsql
security definer
set search_path = public
as $$
declare
    cutoff timestamptz := now() - make_interval(days => greatest(days, 1));
    s bigint := 0;
    r bigint := 0;
    l bigint := 0;
begin
    if days is null or days < 1 then
        raise exception 'prune_observability: days harus >= 1, dapat %', days;
    end if;

    delete from public.scrape_steps where created_at < cutoff;
    get diagnostics s = row_count;

    delete from public.scrape_runs where started_at < cutoff;
    get diagnostics r = row_count;

    delete from public.logs where created_at < cutoff;
    get diagnostics l = row_count;

    return query select s, r, l;
end;
$$;

-- Hanya service_role (backend/CI) yang boleh memanggil. Hapus EXECUTE dari
-- PUBLIC/anon/authenticated supaya tabel observability tidak bisa dihapus dari
-- sisi client.
revoke all on function public.prune_observability(integer)
    from public, anon, authenticated;
grant execute on function public.prune_observability(integer)
    to service_role;

-- Konsekuensi tabel observability tetap utuh: RLS tidak diubah di migration ini.
