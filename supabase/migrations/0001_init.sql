-- ============================================================================
-- OCE Supabase — Migration 0001: baseline schema (fase 1)
-- ============================================================================
-- Prinsip:
--   * Entity layer (sources, series, series_sources, episodes, extractors,
--     streams)  : permanent, idempotent via natural-key unique constraint.
--   * Observability (scrape_runs, scrape_steps, logs) : historical, high-volume,
--     retensi terpisah.
--   * Redact semua data sensitif SEBELUM insert (lihat 0002_redact_helper).
--
-- Konvensi penerapan: `supabase db push` dari repo private (CI). Additive-only.
-- ============================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Trigger helper: updated_at
-- ---------------------------------------------------------------------------
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

-- ===========================================================================
-- 1. SOURCES
-- ===========================================================================
create table public.sources (
    id          uuid primary key default gen_random_uuid(),
    code        text not null unique,        -- providerId ("Anichin")
    name        text not null,               -- config.name
    main_url    text not null,
    series_url  text,
    enabled     boolean not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create trigger sources_updated_at
    before update on public.sources
    for each row execute function public.set_updated_at();

-- ===========================================================================
-- 2. SERIES
--    Identity dianchor di series_sources.url (per-source canonical), BUKAN di
--    title (title bisa transliterasi beda antar-source, tracker sering null).
--    Tanpa unique constraint pada title.
-- ===========================================================================
create table public.series (
    id          uuid primary key default gen_random_uuid(),
    title       text not null,
    alt_titles  jsonb,                       -- array teks
    synopsis    text,
    poster_url  text,
    banner_url  text,
    year        smallint,
    status      text,                        -- ShowStatus label (Ongoing/Completed)
    type        text,                        -- TvType label (Anime/TvSeries/Movie)
    genre       jsonb,                       -- array teks (tags)
    rating      numeric(3,1),
    mal_id      integer,
    anilist_id  integer,
    tmdb_id     integer,
    imdb_id     text,
    search_vector tsvector,                  -- future fuzzy-merge lintas source (deferred)
    metadata    jsonb,                       -- source-specific dinamis
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create trigger series_updated_at
    before update on public.series
    for each row execute function public.set_updated_at();

-- ===========================================================================
-- 3. SERIES_SOURCES (bridge source ↔ series, anchor canonical)
-- ===========================================================================
create table public.series_sources (
    id          uuid primary key default gen_random_uuid(),
    series_id   uuid not null references public.series(id) on delete cascade,
    source_id   uuid not null references public.sources(id) on delete cascade,
    url         text not null,               -- detail page URL (input `load()`)
    external_id text,                        -- slug/id di source
    metadata    jsonb,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (series_id, source_id)            -- satu detail page per seri per source
);

create index series_sources_source_id_idx on public.series_sources (source_id);
create index series_sources_series_id_idx on public.series_sources (series_id);

create trigger series_sources_updated_at
    before update on public.series_sources
    for each row execute function public.set_updated_at();

-- ===========================================================================
-- 4. EPISODES
--    Natural key = (series_source_id, episode_url): episode_url selalu non-null
--    (blank -> episode dibuang di ProviderMapper.extractEpisodes), sedangkan
--    episode_no nullable & season inkonsisten.
-- ===========================================================================
create table public.episodes (
    id               uuid primary key default gen_random_uuid(),
    series_source_id uuid not null references public.series_sources(id) on delete cascade,
    episode_url      text not null,          -- `ep.data` yang masuk `loadLinks()`
    episode_no       smallint,
    season           smallint,
    name             text,
    description      text,
    thumbnail_url    text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    unique (series_source_id, episode_url)
);

-- display/urutan non-unique
create index episodes_series_source_ep_no_idx
    on public.episodes (series_source_id, episode_no, season);

create trigger episodes_updated_at
    before update on public.episodes
    for each row execute function public.set_updated_at();

-- ===========================================================================
-- 5. EXTRACTORS (katalog kecil, seed CI dari ProviderExtractors + config JSON)
-- ===========================================================================
create table public.extractors (
    id         uuid primary key default gen_random_uuid(),
    name       text not null unique,         -- simpleName / config id ("EmTurbovid")
    main_url   text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create trigger extractors_updated_at
    before update on public.extractors
    for each row execute function public.set_updated_at();

-- ===========================================================================
-- 6. STREAMS
--    Hasil akhir per episode. Probe result di-fold (tanpa tabel playback_tests
--    terpisah — tidak ada feedback loop ExoPlayer di kode OCE saat ini).
-- ===========================================================================
create table public.streams (
    id                  uuid primary key default gen_random_uuid(),
    episode_id          uuid not null references public.episodes(id) on delete cascade,
    extractor_id        uuid references public.extractors(id) on delete set null,
    url                 text not null,       -- final URL ke player (redacted token)
    source_url          text,                -- raw link sebelum decode
    quality             smallint,
    type                text,                -- M3U8 / DASH / VIDEO
    headers             jsonb,               -- redacted
    referer             text,                -- redacted
    probe_mode          text,                -- BARE/REFERER/ORIGIN/BROWSER_LIKE/EXPLICIT
    probe_valid         boolean,             -- false => link di-skip (tidak dikirim)
    probe_network_blocked boolean,           -- network error, bukan bukti link rusak
    probe_latency_ms    integer,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique (episode_id, url)
);

create index streams_episode_status_idx on public.streams (episode_id, probe_valid);
create index streams_extractor_id_idx on public.streams (extractor_id);

create trigger streams_updated_at
    before update on public.streams
    for each row execute function public.set_updated_at();

-- ===========================================================================
-- 7. SCRAPE_RUNS
--    Satu baris per eksekusi (episode / detail / home / search). First-valid
--    race => run boleh `returned_early` (background job masih jalan).
-- ===========================================================================
create table public.scrape_runs (
    id              uuid primary key default gen_random_uuid(),
    source_id       uuid not null references public.sources(id) on delete cascade,
    series_id       uuid references public.series(id) on delete set null,
    episode_id      uuid references public.episodes(id) on delete set null,
    context         text,                    -- HOME_LIST / SEARCH / DETAIL / EPISODE
    triggered_by    text,                    -- user_play / prefetch / search / home
    start_url       text,
    status          text,                    -- running / success / failed / partial
    returned_early  boolean not null default false,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    duration_ms     integer,
    error_type      text,                    -- FailureType label (terminal)
    error_message   text
);

create index scrape_runs_source_started_idx on public.scrape_runs (source_id, started_at desc);
create index scrape_runs_episode_id_idx on public.scrape_runs (episode_id);
create index scrape_runs_series_id_idx on public.scrape_runs (series_id);

-- ===========================================================================
-- 8. SCRAPE_STEPS
--    Per LINK ATTEMPT (bukan per stage). Koleksi link = 1 baris kind='COLLECT'.
-- ===========================================================================
create table public.scrape_steps (
    id               uuid primary key default gen_random_uuid(),
    run_id           uuid not null references public.scrape_runs(id) on delete cascade,
    kind             text not null default 'EXTRACT',   -- COLLECT / EXTRACT
    link_url         text,
    extractor_chain  text,                  -- local→global→direct→deepscan
    status           text,                  -- success / failed
    duration_ms      integer,
    links_found      integer,
    error_type       text,                  -- FailureType label
    created_at       timestamptz not null default now()
);

create index scrape_steps_run_id_idx on public.scrape_steps (run_id);

-- ===========================================================================
-- 9. LOGS
--    High-volume observability. Redact url/headers sebelum insert.
-- ===========================================================================
create table public.logs (
    id           bigint generated always as identity primary key,
    run_id       uuid references public.scrape_runs(id) on delete cascade,
    level        text,                      -- DEBUG/SUCCESS/FAIL/ERROR/CRITICAL
    tag          text,                      -- providerId
    message      text,
    method       text,
    failure_type text,                      -- FailureType label
    host         text,
    url          text,
    selectors    text,
    traceback    jsonb,
    created_at   timestamptz not null default now()
);

create index logs_tag_created_idx on public.logs (tag, created_at desc);
create index logs_run_id_idx on public.logs (run_id);

-- partial index: analytics "extractor/selector mana paling gagal"
create index logs_failure_type_idx on public.logs (failure_type)
    where failure_type is not null and failure_type <> 'SUCCESS';

-- ===========================================================================
-- ROW LEVEL SECURITY (fase 1)
-- ---------------------------------------------------------------------------
--   anon (key app):  INSERT + UPDATE (upsert) pada tabel entity & observability.
--                    SELECT/DELETE DENIED. `sources` & `extractors` boleh SELECT
--                    (catalog/reference — app perlu resolve source_id/extractor_id).
--   service_role:    bypass RLS (CI seeding + maintenance + analytics).
-- Risk diterima: anon dengan UPDATE policy USING(true) bisa memodifikasi baris
-- mana pun (bypass idempotency), tapi tidak bisa SELECT/DELETE — cukup untuk
-- fase 1 observability. Dipersempit di fase integrasi bila perlu.
-- ===========================================================================

alter table public.sources        enable row level security;
alter table public.series         enable row level security;
alter table public.series_sources enable row level security;
alter table public.episodes       enable row level security;
alter table public.extractors     enable row level security;
alter table public.streams        enable row level security;
alter table public.scrape_runs    enable row level security;
alter table public.scrape_steps   enable row level security;
alter table public.logs           enable row level security;

-- --- sources (catalog: SELECT boleh, tulis hanya via CI/service_role) ---
create policy sources_select on public.sources
    for select to anon, authenticated using (true);

-- --- extractors (catalog: SELECT boleh) ---
create policy extractors_select on public.extractors
    for select to anon, authenticated using (true);

-- --- series (upsert) ---
create policy series_insert on public.series
    for insert to anon, authenticated with check (true);
create policy series_update on public.series
    for update to anon, authenticated using (true) with check (true);

-- --- series_sources (upsert) ---
create policy series_sources_insert on public.series_sources
    for insert to anon, authenticated with check (true);
create policy series_sources_update on public.series_sources
    for update to anon, authenticated using (true) with check (true);

-- --- episodes (upsert) ---
create policy episodes_insert on public.episodes
    for insert to anon, authenticated with check (true);
create policy episodes_update on public.episodes
    for update to anon, authenticated using (true) with check (true);

-- --- streams (upsert) ---
create policy streams_insert on public.streams
    for insert to anon, authenticated with check (true);
create policy streams_update on public.streams
    for update to anon, authenticated using (true) with check (true);

-- --- scrape_runs (insert + update status/finalisasi) ---
create policy scrape_runs_insert on public.scrape_runs
    for insert to anon, authenticated with check (true);
create policy scrape_runs_update on public.scrape_runs
    for update to anon, authenticated using (true) with check (true);

-- --- scrape_steps (append-only) ---
create policy scrape_steps_insert on public.scrape_steps
    for insert to anon, authenticated with check (true);

-- --- logs (append-only) ---
create policy logs_insert on public.logs
    for insert to anon, authenticated with check (true);