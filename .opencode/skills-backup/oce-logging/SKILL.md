---
name: oce-logging
description: OCE Logging System — Supabase observability, FailureType classification
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: logging
---

# OCE Logging System

## Cara Kerja

Semua error (level FAIL/ERROR/CRITICAL) dikirim ke **Supabase** tabel `logs`
(batch insert, fire-and-forget). **Telegram sudah dihapus** (commit `bf68862`) —
jangan menambahkan kembali.

### Flow
```
logFail / logError / logCritical
  → ProviderLog.log()
    → sendToSupabase()
      → batch buffer (10 baris / 2 detik)
      → POST /rest/v1/logs
```

Selain `logs`, ada observability lifecycle terpisah (`scrape_runs` +
`scrape_steps`) via `com.baseprovider.log.SupabaseObservability`.
Config dari env `SUPABASE_URL`/`SUPABASE_ANON_KEY` atau `SupabaseBakedConfig`
(kosong = no-op). Nama project: `OCEDatabase` (ref `cjjopuwhpcuoaoifhcfj`).

**Cara cek log**: fetch anon key via Management API
(`~/.supabase/access-token`), lalu query PostgREST:

```bash
TOKEN=$(cat ~/.supabase/access-token)
ANON=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.supabase.com/v1/projects/cjjopuwhpcuoaoifhcfj/api-keys" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); \
    print([k['api_key'] for k in d if k['name']=='anon'][0])")
# failure terbaru
curl -s "https://cjjopuwhpcuoaoifhcfj.supabase.co/rest/v1/logs?select=id,created_at,stage,failure_type,message,extractor,host,tag&failure_type=neq.SUCCESS&order=created_at.desc&limit=10" \
  -H "apikey: $ANON" -H "Authorization: Bearer $ANON"
# scrape_steps terbaru
curl -s "https://cjjopuwhpcuoaoifhcfj.supabase.co/rest/v1/scrape_steps?select=created_at,kind,status,error_type,extractor_chain,links_found&order=created_at.desc&limit=30" \
  -H "apikey: $ANON" -H "Authorization: Bearer $ANON"
```

Catatan: error 3002/`PARSING_MANIFEST_MALFORMED` tidak muncul di tabel `logs`
karena sudah dicegah di `MasterLinkGenerator` (verdict `AllMalformed` →
`logFail` `INVALID_URL` stage `VERIFY`). Indikator sehat = semua
`scrape_steps` status `success` dan tidak ada failure baru setelah deploy.

## FailureType Classification

```kotlin
enum class FailureType(val label: String) {
    SUCCESS("SUCCESS"),
    UNKNOWN("N/A"),
    SELECTOR_FAILURE("SELECTOR"),   // Selector tidak match
    EXTRACTOR_FAILURE("EXTRACTOR"), // Extractor gagal extract video
    SHORTLINK_FAILURE("SHORTLINK"), // short.icu dll
    NETWORK_FAILURE("NETWORK"),     // HTTP error
    CLOUDFLARE_FAILURE("CLOUDFLARE"), // Cloudflare challenge
    EMPTY_RESPONSE("EMPTY"),        // Server return 0 bytes
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
logDebug(tag, message)                                   // Hanya logcat
logFail(tag, message, url?, method?, type?, stage?, extractor?, ...)
logError(tag, message, error?, url?, method?, type?, ...)
logCritical(tag, message, error?, url?, method?, type?, ...)
logSuccess(tag, message, url?, method?, stage?, ...)     // level SUCCESS (hanya logcat, tidak ke Supabase)
```

Semua fungsi (kecuali `logDebug`/`logSuccess`) punya param tambahan:
`selectors`, `stage`, `extractor`, `attempt`, `durationMs`, `runId`.

### Cara Pakai

```kotlin
logFail("Anichin", "message", url = url, method = "loadLinks",
    type = FailureType.NETWORK_FAILURE, stage = "EXTRACT")
```

## Emoji per Level

| Level | Emoji |
|---|---|
| FAIL | ⚠️ |
| ERROR | ❌ |
| CRITICAL | 🔥 |

## Emoji per FailureType

| Type | Emoji |
|---|---|
| SELECTOR | 🔍 |
| EXTRACTOR | 🔗 |
| SHORTLINK | 🔗 |
| NETWORK | 🌐 |
| CLOUDFLARE | ☁️ |
| EMPTY | 🔄 |
| IFRAME | 🖼️ |
| METADATA | 📋 |
| CANCELLED | ⏹️ |