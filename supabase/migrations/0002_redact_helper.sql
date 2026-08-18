-- ============================================================================
-- OCE Supabase — Migration 0002: redact helper (defense-in-depth)
-- ============================================================================
-- Tujuan:
--   * Data sensitif TIDAK pernah tersimpan mentah di PostgreSQL.
--   * Redaksi dilakukan di sisi database (BEFORE INSERT/UPDATE trigger), jadi
--     berlaku otomatis walau client mengirim mentah — tanpa mengubah kode OCE.
--   * Helper `oce_redact_url` / `oce_redact_headers` juga bisa dipanggil
--     eksplisit oleh integrasi jika perlu (mis. sebelum dikirim ke klien).
--
-- Cakupan:
--   * URL query params bernama token/signature/expires/key/dst → nilai diganti
--     "[REDACTED]".
--   * Header bernama Authorization/Cookie/Set-Cookie/cf_clearance/x-api-key
--     → nilai diganti "[REDACTED]".
--   * Fragment URL dihapus.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- oce_redact_url(text) -> text
-- Redaksi query param sensitif pada URL (token, signature, expires, key, dll)
-- dan hapus fragment (#...). Case-insensitive. Immutable & strict.
-- ---------------------------------------------------------------------------
create or replace function public.oce_redact_url(url text)
returns text
language sql
immutable
strict
as $$
    select regexp_replace(
        regexp_replace(
            url,
            '(?i)([?&](token|signature|sig|expires|exp|auth|key|apikey|api_key|x-amz-signature|x-amz-credential|x-amz-security-token|credential|secret|password|code))=([^&#]*)',
            '\1=[REDACTED]',
            'g'
        ),
        '#.*$',
        '',
        'g'
    );
$$;

-- ---------------------------------------------------------------------------
-- oce_redact_headers(jsonb) -> jsonb
-- Redaksi nilai header sensitif (key dicocokkan case-insensitive). Header lain
-- dipertahankan. Null -> null. Nilai non-string dipertahankan.
-- ---------------------------------------------------------------------------
create or replace function public.oce_redact_headers(headers jsonb)
returns jsonb
language sql
immutable
as $$
    select case
        when headers is null then null
        else coalesce(
            (
                select jsonb_object_agg(
                    kv.key,
                    case
                        when lower(kv.key) in (
                            'authorization', 'cookie', 'set-cookie',
                            'cf_clearance', 'x-api-key', 'api-key',
                            'proxy-authorization', 'token', 'session'
                        )
                        then to_jsonb('[REDACTED]'::text)
                        else kv.value
                    end
                )
                from jsonb_each(headers) as kv(key, value)
            ),
            '{}'::jsonb
        )
    end;
$$;

-- ---------------------------------------------------------------------------
-- oce_redact_stream_row() — trigger BEFORE INSERT/UPDATE pada `streams`
-- Redaksi url, source_url, referer, dan headers.
-- ---------------------------------------------------------------------------
create or replace function public.oce_redact_stream_row()
returns trigger
language plpgsql
as $$
begin
    new.url           := public.oce_redact_url(new.url);
    new.source_url    := public.oce_redact_url(new.source_url);
    new.referer       := public.oce_redact_url(new.referer);
    new.headers       := public.oce_redact_headers(new.headers);
    return new;
end;
$$;

create trigger streams_redact_row
    before insert or update of url, source_url, referer, headers
    on public.streams
    for each row execute function public.oce_redact_stream_row();

-- ---------------------------------------------------------------------------
-- oce_redact_log_row() — trigger BEFORE INSERT/UPDATE pada `logs`
-- Redaksi url. Headers/traceback milik log opsional di-redact bila ada.
-- ---------------------------------------------------------------------------
create or replace function public.oce_redact_log_row()
returns trigger
language plpgsql
as $$
begin
    new.url := public.oce_redact_url(new.url);
    if new.traceback is not null and jsonb_typeof(new.traceback) = 'object' then
        if new.traceback ? 'headers' then
            new.traceback := jsonb_set(
                new.traceback,
                '{headers}',
                public.oce_redact_headers(new.traceback -> 'headers'),
                true
            );
        end if;
    end if;
    return new;
end;
$$;

create trigger logs_redact_row
    before insert or update of url, traceback
    on public.logs
    for each row execute function public.oce_redact_log_row();

-- ===========================================================================
-- Catatan integrasi (fase berikutnya, TIDAK dieksekusi di migration ini):
--   * Saat integrasi Supabase ke repository OCE dibuat, helper ini dipakai
--     dua lapis:
--       1) Client-side: redaksi ringan sebelum kirim (kurangi payload).
--       2) Server-side (trigger ini): jaring pengaman — data tetap aman walau
--          client lupa redaksi.
--   * `scrape_steps.link_url` juga URL — jika butuh redaksi, tambahkan trigger
--     serupa atau panggil `oce_redact_url` eksplisit saat insert.
-- ===========================================================================