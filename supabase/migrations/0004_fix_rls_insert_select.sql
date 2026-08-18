-- ============================================================================
-- OCE Supabase — Migration 0004: fix RLS insert-only policy (environment quirk)
-- ============================================================================
-- Latar belakang:
--   Policy `for insert ... with check(true)` SAJA selalu DITOLAK (42501 "new
--   row violates row-level security policy") untuk role anon di environment
--   Supabase ini — meski `with check (true)`. Hasil observasi & tes terisolasi:
--     * `for insert` saja              → INSERT ditolak (42501)
--     * `for insert` + `for select`    → INSERT berhasil
--     * `for all ... using(true) with check(true)` → INSERT berhasil
--   Penyebab pasti tidak di-diagnosis (quirk provider RLS/Postgres), tetapi
--   solusi `for insert` + `for select` terbukti mengizinkan INSERT anon tanpa
--   memberi UPDATE/DELETE (privilege paling sempit).
--
-- Dampak: tanpa fix ini, `logs` & `scrape_steps` tidak bisa menerima data dari
-- plugin (RLS 42501), sehingga observability kosong. Fix diterapkan manual ke
-- DB live; migration ini mempersistenkannya agar `supabase db push` konsisten.
--
-- Konvensi penerapan: `supabase db push` dari repo private (CI). Additive-only;
-- drop policy existing (hasil fix manual `for all`) lalu recreate idempotent.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- logs: ganti policy insert-only menjadi insert + select
-- ---------------------------------------------------------------------------
drop policy if exists logs_insert on public.logs;
create policy logs_insert on public.logs
    for insert to anon, authenticated
    with check (true);

drop policy if exists logs_select on public.logs;
create policy logs_select on public.logs
    for select to anon, authenticated
    using (true);

-- ---------------------------------------------------------------------------
-- scrape_steps: ganti policy insert-only menjadi insert + select
-- ---------------------------------------------------------------------------
drop policy if exists scrape_steps_insert on public.scrape_steps;
create policy scrape_steps_insert on public.scrape_steps
    for insert to anon, authenticated
    with check (true);

drop policy if exists scrape_steps_select on public.scrape_steps;
create policy scrape_steps_select on public.scrape_steps
    for select to anon, authenticated
    using (true);
