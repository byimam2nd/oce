-- ============================================================================
-- OCE Supabase — Migration 0003: observability logging (logs kaya + upsert)
-- ============================================================================
-- Tujuan:
--   * `logs` diperkaya: stage/extractor/attempt/duration_ms untuk debugging &
--     pengembangan yang presisi (nilai debugging terbesar).
--   * `sources` & `extractors` mendapat policy INSERT (upsert by natural key)
--     sehingga plugin bisa self-register source/extractor saat runtime, tanpa
--     seeding manual — menutup celah resolve source_id untuk scrape_runs.
--   * Redaksi diperluas ke scrape_runs.start_url & scrape_steps.link_url
--     (URL bisa mengandung token/signature).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. logs: kolom observability baru
-- ---------------------------------------------------------------------------
alter table public.logs
    add column if not exists stage       text,   -- HOME_LIST/SEARCH/DETAIL/EPISODE/COLLECT/EXTRACT/PROBE
    add column if not exists extractor   text,   -- nama extractor/selector/iframe-chain
    add column if not exists attempt     integer,-- percobaan ke-n (retry/paralel)
    add column if not exists duration_ms integer;

-- ---------------------------------------------------------------------------
-- 2. RLS: izinkan anon self-register source/extractor (INSERT-only, upsert)
--    Natural key unik (sources.code, extractors.name) mencegah duplikat;
--    tanpa UPDATE/DELETE policy baris existing aman.
-- ---------------------------------------------------------------------------
create policy sources_insert on public.sources
    for insert to anon, authenticated
    with check (true);

create policy extractors_insert on public.extractors
    for insert to anon, authenticated
    with check (true);

create policy scrape_runs_select on public.scrape_runs
    for select to anon, authenticated
    using (true);

-- ---------------------------------------------------------------------------
-- 3. Redaksi URL pada observability (scrape_runs.start_url, scrape_steps.link_url)
-- ---------------------------------------------------------------------------
create or replace function public.oce_redact_run_row()
returns trigger
language plpgsql
as $$
begin
    new.start_url := public.oce_redact_url(new.start_url);
    return new;
end;
$$;

create trigger scrape_runs_redact_row
    before insert or update of start_url
    on public.scrape_runs
    for each row execute function public.oce_redact_run_row();

create or replace function public.oce_redact_step_row()
returns trigger
language plpgsql
as $$
begin
    new.link_url := public.oce_redact_url(new.link_url);
    return new;
end;
$$;

create trigger scrape_steps_redact_row
    before insert or update of link_url
    on public.scrape_steps
    for each row execute function public.oce_redact_step_row();