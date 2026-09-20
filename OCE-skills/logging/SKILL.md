---
name: oce-logging
description: OCE Logging — Supabase observability, FailureType classification, log querying
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: logging
---

# OCE Logging

## Purpose

Sistem observability OCE: logging ke Supabase, FailureType classification, dan cara query logs. Skill ini membantu agent debugging via data log produksi.

## When to Use

- Debugging error yang dilaporkan user
- Cek health provider/extractor via Supabase
- Investigasi failure pattern
- Menambah logging ke kode baru

## When NOT to Use

- Untuk debugging syntax error (→ `development`)
- Untuk verifikasi selector (→ `selector-checker`)

## Log Architecture

```
logFail / logError / logCritical
  → ProviderLog.log()
    → sendToSupabase()
      → batch buffer (10 baris / 2 detik)
      → POST /rest/v1/logs
```

**Telegram = SUDAH DIHAPUS** (commit `bf68862`). Jangan menambahkan kembali.

## Supabase Tables

### `logs` — Error logs
| Column | Type | Description |
|--------|------|-------------|
| id | uuid | Primary key |
| run_id | text | Links to scrape_runs |
| level | text | FAIL, ERROR, CRITICAL |
| tag | text | Provider name |
| message | text | Error description |
| method | text | Function name |
| failure_type | text | FailureType label |
| host | text | Website host |
| url | text | Request URL |
| selectors | text | Selector yang dipakai |
| traceback | text | Stack trace |
| created_at | timestamptz | When |
| stage | text | SCRAPE, EXTRACT, VERIFY |
| extractor | text | Extractor name |
| attempt | int | Retry number |
| duration_ms | int | Duration |

### `scrape_runs` — Lifecycle tracking
| Column | Type | Description |
|--------|------|-------------|
| id | uuid | Primary key |
| source_id | text | Provider ID |
| series_id | text | Series identifier |
| episode_id | text | Episode identifier |
| context | text | SCRAPE, LOAD, SEARCH |
| triggered_by | text | Who initiated |
| start_url | text | Initial URL |
| status | text | success, failed, partial |
| returned_early | bool | Stopped before complete |
| started_at | timestamptz | Start |
| finished_at | timestamptz | End |
| duration_ms | int | Duration |
| error_type | text | Error classification |
| error_message | text | Error detail |

### `scrape_steps` — Per-step tracking
| Column | Type | Description |
|--------|------|-------------|
| id | uuid | Primary key |
| run_id | text | Links to scrape_runs |
| kind | text | SCRAPE, EXTRACT, VERIFY |
| link_url | text | Target URL |
| extractor_chain | text | Extractor sequence |
| status | text | success, failed, timeout |
| duration_ms | int | Duration |
| links_found | int | Links discovered |
| error_type | text | Error classification |
| created_at | timestamptz | When |

## FailureType Classification

```kotlin
enum class FailureType(val label: String) {
    SUCCESS("SUCCESS"),              // Tidak ada error
    UNKNOWN("N/A"),                  // Error tidak terklasifikasi
    SELECTOR_FAILURE("SELECTOR"),    // Selector tidak match
    EXTRACTOR_FAILURE("EXTRACTOR"),  // Extractor gagal
    SHORTLINK_FAILURE("SHORTLINK"),  // short.icu dll
    NETWORK_FAILURE("NETWORK"),      // HTTP error
    CLOUDFLARE_FAILURE("CLOUDFLARE"),// Cloudflare challenge
    EMPTY_RESPONSE("EMPTY"),         // Server return 0 bytes
    INVALID_IFRAME("IFRAME"),       // Iframe src kosong
    METADATA_FAILURE("METADATA"),   // Gagal parse metadata
    CANCELLED("CANCELLED"),         // Job cancelled
    HTTP_FAILURE("HTTP"),           // Non-2xx/3xx probe
    INVALID_URL("URL"),             // URL kosong / master malformed
    TIMEOUT("TIMEOUT")              // Timeout
}
```

## Log Functions

```kotlin
// Hanya logcat (tidak ke Supabase)
logDebug(tag, message)
logSuccess(tag, message, url?, method?, stage?, ...)

// Ke Supabase + logcat
logFail(tag, message, url?, method?, type?, stage?, extractor?, ...)
logError(tag, message, error?, url?, method?, type?, ...)
logCritical(tag, message, error?, url?, method?, type?, ...)

// Tambahan params (semua fungsi): selectors, stage, extractor, attempt, durationMs, runId
```

### Cara Pakai
```kotlin
logFail("Anichin", "selector tidak match",
    url = url,
    method = "loadLinks",
    type = FailureType.SELECTOR_FAILURE,
    stage = "SCRAPE"
)
```

## Querying Logs

### Management API SQL (Recommended)
PostgREST timeout untuk queries besar. Gunakan Management API SQL:

```bash
# Dapatkan access token
TOKEN=$(cat /data/data/com.termux/files/home/ubuntu/ubuntu22-fs/root/.supabase/access-token)

# Query: recent failures
curl -s -X POST \
  "https://api.supabase.com/v1/projects/cjjopuwhpcuoaoifhcfj/database/query" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query": "SELECT id, created_at, stage, failure_type, message, extractor, host, tag FROM logs WHERE failure_type != '\''SUCCESS'\'' ORDER BY created_at DESC LIMIT 10"}'
```

### PostgREST (untuk data kecil)
```bash
# Dapatkan anon key
ANON=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.supabase.com/v1/projects/cjjopuwhpcuoaoifhcfj/api-keys" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print([k['api_key'] for k in d if k['name']=='anon'][0])")

# Query scrape_steps
curl -s "https://cjjopuwhpcuoaoifhcfj.supabase.co/rest/v1/scrape_steps?select=created_at,kind,status,error_type,extractor_chain,links_found&order=created_at.desc&limit=30" \
  -H "apikey: $ANON" -H "Authorization: Bearer $ANON"
```

**Catatan:** PostgREST sering timeout untuk tabel `logs` full-scan. Gunakan Management API SQL untuk queries besar.

### Common Queries

```sql
-- Provider failures (last 7 days)
SELECT tag, failure_type, COUNT(*) as cnt
FROM logs
WHERE created_at > now() - interval '7 days'
  AND failure_type != 'SUCCESS'
GROUP BY tag, failure_type
ORDER BY cnt DESC;

-- Extractor failures
SELECT extractor, failure_type, message, created_at
FROM logs
WHERE extractor IS NOT NULL
  AND failure_type != 'SUCCESS'
ORDER BY created_at DESC LIMIT 20;

-- Poster failures (metadata)
SELECT tag, message, host, created_at
FROM logs
WHERE failure_type = 'METADATA'
  AND message LIKE '%Poster%'
ORDER BY created_at DESC;
```

## Case Study: Anichin Cloudflare + Poster Issue (2026-09-20)

**Symptoms:**
- Main page lists (Recently Updated, Popular, etc.) → empty
- Poster not showing → `METADATA_FAILURE: Missing Poster`
- Live curl → HTTP 403 + Cloudflare challenge page

**Debugging Steps:**

1. **Check Supabase logs** → `failure_type: NETWORK_FAILURE` + `CLOUDFLARE_FAILURE` intermittent
2. **Manual curl** → HTML contains `challenge-platform`, `cf-ray`, `cloudflare` → Cloudflare managed challenge
3. **Code inspection** → `HttpStatusException` only carried message "HTTP 403 on URL", **body NOT captured**
4. **Exception handler** → `CLOUDFLARE_HTTP.containsMatchIn(msg)` checked message only → missed CF indicators in body
5. **Regex issue** → `CLOUDFLARE_HTTP` contained `\b403\b` → matched ALL 403s, triggered WebView solver unnecessarily

**Fixes Applied:**
1. Capture body in `HttpStatusException` constructor
2. Check `CLOUDFLARE_HTTP.containsMatchIn(body)` in exception handler
3. Remove `\b403\b` from `CLOUDFLARE_HTTP` regex
4. Remove `retryAfter` from 403 handler (prevented UA rotation timeout)

**Poster Fix:**
- `doc.setBaseUri(res.url)` in `HttpClient.kt` after parse
- `safeExtractImage` use `absUrl()` with fallback to raw
- Root-relative `/wp-content/...` → absolute `https://anichin.moe/wp-content/...`

**Verification:**
- CI green (build + unit tests)
- Live test via curl_cffi (Cloudflare bypass) → 200 + 30 items
- Unit test `SelectorResolverTest.kt` covers poster resolution

## Emoji Convention

| Level | Emoji |
|-------|-------|
| FAIL | ⚠️ |
| ERROR | ❌ |
| CRITICAL | 🔥 |

| FailureType | Emoji |
|-------------|-------|
| SELECTOR | 🔍 |
| EXTRACTOR | 🔗 |
| SHORTLINK | 🔗 |
| NETWORK | 🌐 |
| CLOUDFLARE | ☁️ |
| EMPTY | 🔄 |
| IFRAME | 🖼️ |
| METADATA | 📋 |
| CANCELLED | ⏹️ |

## Health Indicators

- **Healthy:** semua `scrape_steps` status `success`, tidak ada failure baru
- **Warning:** beberapa failure di provider tertentu
- **Critical:** banyak failure berturut-turut atau CRITICAL log muncul

## Verification

- [ ] Tahu cara query logs via Management API SQL
- [ ] Bisa bedakan FailureType yang relevan
- [ ] Bisa identifikasi health provider dari log pattern

## Related Skills

- `development` — debugging workflow
- `extraction` — EXTRACTOR_FAILURE, 3002 protection
- `selector-checker` — SELECTOR_FAILURE investigation
- `architecture` — logging module structure
