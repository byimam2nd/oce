---
name: oce-selector-checker
description: OCE Selector Checker — 4-phase selector verification via curl, simulates CloudStream extraction from live HTML
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: selector-verification
---

# OCE Selector Checker

## Purpose

Verifikasi CSS selector provider terhadap HTML live website. Mensimulasikan cara CloudStream mengekstrak data dari website menggunakan selector di `config/<name>.json`.

## When to Use

- Setelah mengubah selector di provider config
- Saat website berubah struktur dan provider gagal
- Sebelum push perubahan selector
- Saat debugging metadata extraction (poster, title, episodes)

## When NOT to Use

- Untuk debug extractor video (→ `extraction`)
- Untuk debug CI/build (→ `build-deploy`)

## Prerequisites

```bash
curl       # fetch HTML
python3    # parse HTML
base64     # decode base64 (opsional)
```

## 4-Phase Verification

### Phase 1: Main Page (Search/List)

**Simulates:** `getMainPage()` + `search()`

**Selectors tested:**
| Selector | Purpose |
|----------|---------|
| `searchItems` | Container per item |
| `searchTitle` | Title element |
| `searchHref` | Link to detail/episode |
| `searchPoster` | Poster image |
| `searchRating` | Rating badge |
| `searchEpText` | Episode badge |

**Template:**
```bash
PROVIDER_URL="<mainUrl dari config>"

curl -sL --max-time 15 \
  -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  "$PROVIDER_URL" | python3 -c "
import sys, re
html = sys.stdin.read()

# searchItems
items = re.findall(r'<article[^>]*class=\"[^\"]*bs[^\"]*\"[^>]*>(.*?)</article>', html, re.DOTALL)
print(f'searchItems: {len(items)} items')

# searchTitle (dalam item pertama)
if items:
    title = re.search(r'<h2[^>]*>(.*?)</h2>', items[0], re.DOTALL)
    if title:
        clean = re.sub(r'<[^>]+>', '', title.group(1)).strip()
        print(f'searchTitle: [{clean}]')

# searchPoster
posters = re.findall(r'<img[^>]+src=\"([^\"]+)\"[^>]*>', html)
print(f'searchPoster: {len(posters)} images')
if posters:
    print(f'  sample: {posters[0][:80]}')
"
```

### Phase 2: Detail Page (Load)

**Simulates:** `load(url)`

**Selectors tested:**
| Selector | Purpose |
|----------|---------|
| `loadTitle` | Series title |
| `loadPoster` | Series poster |
| `loadDesc` | Synopsis |
| `loadInfoBox` | Info box |
| `loadTags` | Genre tags |
| `loadStatus` | Status |
| `loadRating` | Rating |
| `loadRecommend` | Recommendations |

**Template:**
```bash
SERIES_URL="<URL dari search result>"

curl -sL --max-time 15 \
  -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  "$SERIES_URL" | python3 -c "
import sys, re
html = sys.stdin.read()

# loadTitle
m = re.search(r'<h1[^>]*>(.*?)</h1>', html, re.DOTALL)
title = re.sub(r'<[^>]+>', '', m.group(1)).strip() if m else 'NOT FOUND'
print(f'loadTitle: [{title}]')

# loadPoster
m = re.search(r'<div[^>]*class=\"[^\"]*thumb[^\"]*\"[^>]*>.*?<img[^>]+src=\"([^\"]+)\"', html, re.DOTALL)
poster = m.group(1)[:80] if m else 'NOT FOUND'
print(f'loadPoster: [{poster}]')

# loadDesc
m = re.search(r'<div[^>]*class=\"[^\"]*entry-content[^\"]*\"[^>]*>(.*?)</div>', html, re.DOTALL)
desc = re.sub(r'<[^>]+>', '', m.group(1)).strip()[:100] if m else 'NOT FOUND'
print(f'loadDesc: [{desc}]')
"
```

### Phase 3: Episode Page (loadLinks)

**Simulates:** `loadLinks(data)`

**Selectors tested:**
| Selector | Purpose |
|----------|---------|
| `linkOptions` | Server options (option[value], a[data-url]) |
| `episodeItems` | Episode list |
| `downloadItems` | Download links |
| `iframeTag` | Iframe embed |

**Template:**
```bash
EP_URL="<URL dari episode>"

curl -sL --max-time 15 \
  -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  "$EP_URL" | python3 -c "
import sys, re, base64
html = sys.stdin.read()

# linkOptions (option[value])
options = re.findall(r'<option[^>]*value=\"([^\"]+)\"[^>]*>(.*?)</option>', html, re.DOTALL)
print(f'linkOptions: {len(options)} options')
for val, label in options[:5]:
    if val:
        try:
            decoded = base64.b64decode(val).decode('utf-8', errors='replace')
            src = re.search(r'src=\"([^\"]+)\"', decoded)
            if src:
                print(f'  [{label.strip()}]: {src.group(1)[:60]}')
        except:
            print(f'  [{label.strip()}]: (decode failed)')

# episodeItems
eps = re.findall(r'<li[^>]*>.*?<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>', html, re.DOTALL)
print(f'episodeItems: {len(eps)} episodes')
"
```

### Phase 4: Base64 Decode (Anichin/Animasu specific)

```bash
# Decode base64 option value
echo "PGlmcmFtZSB..." | base64 -d 2>/dev/null | grep -oP 'src="([^"]+)"'

# Atau dengan python
python3 -c "
import base64, re
val = 'PGlmcmFtZSB...'
decoded = base64.b64decode(val).decode()
src = re.search(r'src=\"([^\"]+)\"', decoded)
if src: print('SRC:', src.group(1))
"
```

## Provider URLs

**SEBELUM verifikasi, ambil URL dari config provider:**
```bash
# Baca mainUrl dari config
python3 -c "
import json
with open('BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json') as f:
    print(json.load(f).get('mainUrl', 'UNKNOWN'))
"
```

**JANGAN hardcode URL.** URL bisa berubah. Selalu baca dari config.

## Output Format

### Per-Item (Phase 1)
```
── Item 1 ──
SEARCH_TITLE   : Example Title
SEARCH_HREF    : https://example.com/series/slug/
SEARCH_POSTER  : https://example.com/poster.webp ✅
SEARCH_EP_TEXT : Ep 140 ✅
SEARCH_RATING  : 8.5 ✅
```

### Per-Selector (Phase 2-3)
```
LOAD_TITLE      : Example Title ✅
LOAD_POSTER     : https://... ✅
LOAD_DESC       : Synopsis text... ✅
LINK_OPTIONS    : 7 options ✅
EPISODE_ITEMS   : 12 episodes ✅
```

### Summary
```
PHASE 1: 46 items — title/href/poster OK ✅
PHASE 2: title/poster/desc/tags/status OK ✅
PHASE 3: 7 server options, 1 iframe ✅
PHASE 4: Base64 decode OK ✅
```

### Status Icons
- ✅ = working
- ⚠️ = attention needed (not critical)
- ❌ = broken / not found

## Troubleshooting

### 0 items found
1. Cek apakah website berubah tema/struktur
2. Inspect HTML manual: `curl -sL "$URL" | grep -c "class-name"`
3. Cari class baru dengan grep
4. Update selector di `config/<name>.json`
5. Multi-variant selector: tambah fallback dengan comma

### Poster not found (❌)
1. Cek apakah src pakai lazy loading (`data-src`, `data-original`)
2. Cek apakah path root-relative (`/wp-content/...`)
3. Cek `attrImage` priority di config
4. Cek `SelectorValidator.isValidPoster` — butuh `http://` atau `//`

### Title has extra words
1. Cek `bloatRegex` — bisa strip bloat dari title
2. Cek selector mengambil element yang benar (bukan `a[title]`)

### Base64 decode failed
1. Pastikan value tidak kosong
2. Coba padding: `val + '=='`
3. Mungkin plain text, bukan base64

## Verification Checklist

Per provider, pastikan:
- [ ] Phase 1: searchItems match, items > 0
- [ ] Phase 1: searchTitle, searchHref, searchPoster valid
- [ ] Phase 2: loadTitle, loadPoster, loadDesc match
- [ ] Phase 3: linkOptions match, episodeItems match
- [ ] Phase 4: base64 decode works (if applicable)
- [ ] Semua URL accessible (no 404, no CF block)

## Related Skills

- `provider` — config fields reference, editing config
- `architecture` — SelectorResolver multi-variant
- `logging` — SELECTOR_FAILURE investigation
- `extraction` — extractor link extraction
