-- ============================================================================
-- OCE Supabase — Dashboard Queries (analisis korelasi failure)
-- ============================================================================
-- Kumpulan query read-only untuk menjawab:
--   * extractor/selector/host mana yang paling sering gagal?
--   * apakah ada error yang tidak tercatat / diabaikan?
--   * bagaimana kesehatan pipeline dari waktu ke waktu?
--
-- Dipakai dari Supabase Studio SQL Editor (service_role / anon), atau via
-- `/rest/v1/rpc`. Semua query hanya SELECT — aman terhadap RLS (0004).
--
-- Kolom penting:
--   logs.failure_type : FailureType LABEL (bukan enum name) —
--                       SELECTOR/EXTRACTOR/SHORTLINK/NETWORK/CLOUDFLARE/EMPTY/
--                       IFRAME/METADATA/CANCELLED/HTTP/URL/TIMEOUT/SUCCESS
--   logs.extractor    : nama extractor/selector/iframe-chain yang gagal
--   logs.host         : host yang bermasalah
--   logs.selectors    : key selector (mis. "Anichin:searchTitle")
--   logs.stage        : HOME_LIST/SEARCH/DETAIL/EPISODE/COLLECT/EXTRACT/PROBE
--   logs.method       : getMainPage/search/load/loadLinks/extractLinks
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Ringkasan: distribusi failure_type (7 hari terakhir)
--    "Masalah terbesar datang dari mana?"
-- ---------------------------------------------------------------------------
select failure_type,
       count(*)                                            as total,
       round(100.0 * count(*) / sum(count(*)) over (), 1)  as pct,
       count(distinct tag)                                  as providers_affected
from public.logs
where failure_type is not null
  and failure_type <> 'SUCCESS'
  and created_at >= now() - interval '7 days'
group by failure_type
order by total desc;


-- ---------------------------------------------------------------------------
-- 2. Host paling bermasalah (NETWORK/CLOUDFLARE/TIMEOUT/HTTP), 7 hari
--    Prioritas perbaikan CDN/mirror berdasarkan host.
-- ---------------------------------------------------------------------------
select host,
       count(*)                                            as failures,
       count(*) filter (where failure_type = 'NETWORK')    as net_fail,
       count(*) filter (where failure_type = 'CLOUDFLARE') as cf_fail,
       count(*) filter (where failure_type = 'TIMEOUT')    as timeout_fail,
       round(avg(duration_ms))                             as avg_duration_ms
from public.logs
where host is not null and host <> ''
  and failure_type in ('NETWORK', 'CLOUDFLARE', 'TIMEOUT', 'HTTP')
  and created_at >= now() - interval '7 days'
group by host
order by failures desc
limit 20;


-- ---------------------------------------------------------------------------
-- 3. Extractor paling sering gagal (EXTRACTOR), 7 hari
--    "Extractor mana yang paling banyak gagal ekstrak?" — kandidat migrasi/
--    perbaikan extractor berikutnya.
-- ---------------------------------------------------------------------------
select extractor,
       count(*)                                            as failures,
       count(distinct run_id)                              as distinct_runs,
       round(100.0 * count(*) / sum(count(*)) over (), 1)  as pct,
       max(created_at)                                     as last_failure
from public.logs
where failure_type = 'EXTRACTOR'
  and extractor is not null and extractor <> ''
  and created_at >= now() - interval '7 days'
group by extractor
order by failures desc
limit 20;


-- ---------------------------------------------------------------------------
-- 4. Selector paling sering rusak (SELECTOR), 7 hari
--    "Struktur situs provider mana yang berubah / selector mana yang mulai
--    gagal match?" — SELECTOR dari logDecay SelectorResolver + jalur
--    fetch/scrape yang gagal.
-- ---------------------------------------------------------------------------
select selectors,
       count(*) as failures,
       max(created_at) as last_failure
from public.logs
where failure_type = 'SELECTOR'
  and selectors is not null and selectors <> ''
  and created_at >= now() - interval '7 days'
group by selectors
order by failures desc
limit 20;


-- ---------------------------------------------------------------------------
-- 5. Kesehatan run: status scrape_runs (7 hari), per provider
--    "Berapa persen play/search/detail berhasil vs gagal?"
-- ---------------------------------------------------------------------------
select s.code                                 as provider,
       r.context,
       r.status,
       count(*)                               as runs,
       round(avg(r.duration_ms))              as avg_duration_ms,
       max(r.finished_at)                     as last_finished
from public.scrape_runs r
join public.sources s on s.id = r.source_id
where r.started_at >= now() - interval '7 days'
group by s.code, r.context, r.status
order by provider, context, status;


-- ---------------------------------------------------------------------------
-- 6. Error yang MUNGKIN tidak tercatat: run status=failed tapi tanpa log
--    row (mis. CANCELLED di-loadLinks, atau run yang gagal sebelum step).
--    Ini "gap detector": run gagal tanpa penjelasan detail = perlu investigasi.
-- ---------------------------------------------------------------------------
select r.id,
       s.code                     as provider,
       r.context,
       r.started_at,
       r.error_type,
       r.error_message,
       r.duration_ms,
       (select count(*) from public.logs l where l.run_id = r.id)         as log_count,
       (select count(*) from public.scrape_steps st where st.run_id = r.id) as step_count
from public.scrape_runs r
join public.sources s on s.id = r.source_id
where r.status = 'failed'
  and r.started_at >= now() - interval '7 days'
  and (select count(*) from public.logs l where l.run_id = r.id) = 0
order by r.started_at desc
limit 30;


-- ---------------------------------------------------------------------------
-- 7. Step-level: extractor chain paling sering gagal di EXTRACT
--    (detail di bawah query #3 — link attempt per episode)
-- ---------------------------------------------------------------------------
select st.extractor_chain,
       st.status,
       st.error_type,
       count(*) as attempts,
       round(avg(st.duration_ms)) as avg_duration_ms
from public.scrape_steps st
where st.created_at >= now() - interval '7 days'
group by st.extractor_chain, st.status, st.error_type
order by attempts desc
limit 20;


-- ---------------------------------------------------------------------------
-- 8. Trend harian: total failure vs success (logs), 14 hari
--    "Apakah sistem makin sehat / makin rusak?"
-- ---------------------------------------------------------------------------
select date_trunc('day', created_at)::date as day,
       count(*) filter (where failure_type = 'SUCCESS')  as success,
       count(*) filter (where failure_type <> 'SUCCESS'
                        and failure_type is not null)    as failures,
       count(*) filter (where level in ('ERROR', 'CRITICAL')) as errors
from public.logs
where created_at >= now() - interval '14 days'
group by day
order by day;