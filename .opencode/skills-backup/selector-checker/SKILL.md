---
name: selector-checker
description: OCE Provider Selector Verification — mensimulasikan cara CloudStream mengekstrak data dari HTML website menggunakan selector CSS
license: MIT
compatibility: "*"
metadata:
  project: oce
  type: selector-verification
---

# Selector Checker — OCE Provider Selector Verification

## Tujuan

Mensimulasikan cara CloudStream mengekstrak data dari HTML website menggunakan selector CSS.
Mengecek apakah selector di `config/<name>.json` masih cocok dengan struktur HTML website saat ini.

---

## Prerequisites

```bash
# Tools yang digunakan
curl       # fetch halaman HTML
python3    # parse HTML + regex
base64     # decode base64 (opsional)
```

---

## Flow Pengujian Lengkap

### Phase 1: Main Page (Search/List)

Mensimulasikan `getMainPage()` dan `search()` — ekstraksi item dari halaman utama/daftar.

#### Selectors yang Dicek

| # | Selector | Fungsi |
|---|----------|--------|
| 1 | `SEARCH_ITEMS` | Container setiap item (`.listupd .bsx`, `article`, dll) |
| 2 | `SEARCH_TITLE` | Judul item (`.tt`, `h2.entry-title a`, `a[title]`) |
| 3 | `SEARCH_HREF` | Link tujuan (`a[href]`) |
| 4 | `SEARCH_POSTER` | Gambar poster (`img[src]`, `img[data-src]`) |
| 5 | `SEARCH_RATING` | Rating badge (`.rating`, `.score`, `.rtng`) |
| 6 | `SEARCH_EP_TEXT` | Episode badge (`.epx`, `.eps span`, `.episode`) |

#### Template Script

```bash
URL="https://anichin.cafe/"
PROVIDER="Anichin"  # untuk match config/<name>.json

curl -sL --max-time 15 \
  -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  "$URL" | python3 -c "
import sys, re
c = sys.stdin.read()

def test_selector(name, css_selector, html_content, max_samples=3):
    '''Simulasi select() — cari semua match CSS selector dalam HTML'''
    # Python's re doesn't support CSS selectors natively
    # Gunakan regex pattern untuk simulasi sederhana
    # Untuk selector kompleks, perlu BeautifulSoup
    print(f'━━━ {name} ━━━')
    print(f'  Selector: {css_selector}')
    
    # Simple matching based on common patterns
    if '.bsx' in css_selector and '.tt' in css_selector:
        matches = re.findall(r'<div class=\"bsx\".*?<div class=\"tt\">(.*?)</div>', html_content, re.DOTALL)
    elif '.tt' in css_selector:
        matches = re.findall(r'<div class=\"tt\">(.*?)</div>', html_content, re.DOTALL)
    elif 'h2.entry-title' in css_selector:
        matches = re.findall(r'<h2[^>]*class=\"[^\"]*entry-title[^\"]*\"[^>]*>(.*?)</h2>', html_content, re.DOTALL)
    elif 'a[title]' in css_selector:
        matches = re.findall(r'<a[^>]+title=\"([^\"]+)\"[^>]*>', html_content)
    elif 'img' in css_selector:
        matches = re.findall(r'<img[^>]+src=\"([^\"]+)\"', html_content)
    elif '.rating' in css_selector or '.score' in css_selector:
        matches = re.findall(r'<[^>]*class=\"[^\"]*(?:rating|score|rtng)[^\"]*\"[^>]*>\s*([\d.]+)\s*<', html_content)
    elif '.epx' in css_selector or '.eps' in css_selector:
        matches = re.findall(r'<[^>]*class=\"[^\"]*(?:epx|eps|episode)[^\"]*\"[^>]*>\s*([^<]+)\s*<', html_content)
    else:
        matches = re.findall(rf'<[^>]*class=\"[^\"]*{[css_selector.split(\".\")[-1].split(\"[\")[0]}[^\"]*\"[^>]*>', html_content)
    
    print(f'  Match    : {len(matches)} items')
    for i, m in enumerate(matches[:max_samples]):
        clean = re.sub(r'<[^>]+>', '', m).strip()
        status = '✅' if clean else '❌'
        print(f'  Sample {i+1}: [{clean[:80]}] {status}')
    print()

# Contoh penggunaan
test_selector('SEARCH_ITEMS (.listupd .bsx)', '.listupd .bsx', c)
test_selector('SEARCH_TITLE (.tt)', '.tt', c)
test_selector('SEARCH_HREF (a)', 'a', c)
test_selector('SEARCH_POSTER (img)', 'img', c)
"
```

#### Output yang Diharapkan

```
━━━ SEARCH_ITEMS (.listupd .bsx) ━━━
  Selector: .listupd .bsx
  Match    : 46 items
  Sample 1 : [Renegade Immortal Episode 140 Subtitle Indonesia] ✅
  Sample 2 : [Coiling Dragon Episode 05 Subtitle Indonesia] ✅

━━━ SEARCH_TITLE (.tt) ━━━
  Selector: .tt
  Match    : 46 items
  Sample 1 : [Renegade Immortal] ✅
  Sample 2 : [Coiling Dragon] ✅
```

---

### Phase 2: Detail Page (Load)

Mensimulasikan `load()` — ekstraksi metadata seri dari halaman detail.

#### Selectors yang Dicek

| # | Selector | Fungsi |
|---|----------|--------|
| 1 | `LOAD_TITLE` | Judul seri (`h1.entry-title`) |
| 2 | `LOAD_POSTER` | Poster (`div.thumb img`) |
| 3 | `LOAD_BANNER` | Banner background (`.banner img`) |
| 4 | `LOAD_DESC` | Sinopsis (`.entry-content`, `.desc`, `div.mindesc`) |
| 5 | `LOAD_INFO_BOX` | Info box (`.spe`, `.info`) |
| 6 | `LOAD_TAGS` | Genre tags (`.genxed a`, `.genre-info a`) |
| 7 | `LOAD_RATING` | Rating (`.rating`, `.score`) |
| 8 | `LOAD_STATUS` | Status Ongoing/Completed |
| 9 | `LOAD_QUALITY` | Quality badge (`.quality`) |
| 10 | `LOAD_TRAILER` | Trailer YouTube |
| 11 | `LOAD_RECOMMEND` | Rekomendasi terkait |
| 12 | `ACTOR_ITEMS` + `ACTOR_NAME` | Cast/pemain |
| 13 | `SELECTOR_WATCH_BUTTONS` | Tombol play/watch |
| 14 | `SELECTOR_SEASON_CONTAINER` | Container season data |
| 15 | `SELECTOR_IMDB_EXTERNAL` / `SELECTOR_TMDB_EXTERNAL` | ID eksternal |

#### Template Script

```bash
URL="https://anichin.cafe/seri/renegade-immortal/"

curl -sL --max-time 15 \
  -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  "$URL" | python3 -c "
import sys, re
c = sys.stdin.read()

print('=== DETAIL PAGE ===')
print()

# LOAD_TITLE
m = re.search(r'<h1[^>]*class=\"[^\"]*entry-title[^\"]*\"[^>]*>(.*?)</h1>', c, re.DOTALL)
val = re.sub(r'<[^>]+>', '', m.group(1)).strip() if m else '❌ NOT FOUND'
print(f'LOAD_TITLE (h1.entry-title): [{val}]')

# LOAD_POSTER
m = re.search(r'<div class=\"thumb\".*?<img[^>]+src=\"([^\"]+)\"', c, re.DOTALL)
print(f'LOAD_POSTER (div.thumb img): [{m.group(1)[:60] if m else \"❌ NOT FOUND\"}]')

# LOAD_DESC — urutan sesuai config/<name>.json
m = re.search(r'<div class=\"entry-content\"[^>]*>\\s*<p>(.*?)</p>', c, re.DOTALL)
if m:
    txt = re.sub(r'<[^>]+>', '', m.group(1)).strip()
    print(f'LOAD_DESC (.entry-content): [{txt[:100]}...]')
else:
    m = re.search(r'<div class=\"desc\">(.*?)</div>', c, re.DOTALL)
    if m:
        txt = re.sub(r'<[^>]+>', '', m.group(1)).strip()
        print(f'LOAD_DESC (.desc): [{txt[:100]}...]')
    else:
        print(f'LOAD_DESC: ❌ NOT FOUND')

# LOAD_INFO_BOX (.spe)
m = re.search(r'<div class=\"spe\">(.*?)</div>', c, re.DOTALL)
if m:
    info = re.sub(r'<[^>]+>', '', m.group(1)).strip()
    print(f'LOAD_INFO_BOX (.spe): [{info[:120]}]')
else:
    print(f'LOAD_INFO_BOX: ❌ NOT FOUND')

# LOAD_TAGS (.genxed a)
m = re.search(r'<div class=\"genxed\">(.*?)</div>', c, re.DOTALL)
if m:
    tags = re.findall(r'<a[^>]*>(.*?)</a>', m.group(1))
    print(f'LOAD_TAGS (.genxed a): {[re.sub(r\"<[^>]+>\",\"\",t).strip() for t in tags]}')

# LOAD_STATUS
m = re.search(r'Status:</b>\s*(\w+)', c)
print(f'LOAD_STATUS: [{m.group(1) if m else \"❌ NOT FOUND\"}]')

# LOAD_RECOMMEND
bsx = re.findall(r'<div class=\"bsx\".*?<div class=\"tt\">(.*?)</div>', c, re.DOTALL)
print(f'LOAD_RECOMMEND (.bsx .tt): {len(bsx)} items')
for t in bsx[:3]:
    clean = re.sub(r'<[^>]+>', '', t).strip()
    print(f'  [{clean[:60]}]')

# SELECTOR_WATCH_BUTTONS
for btn in ['.play-button', '.watch-now', '.btn-watch']:
    if btn.replace('.', '') in c:
        print(f'SELECTOR_WATCH_BUTTONS ({btn}): ✅ FOUND')
        break
else:
    print(f'SELECTOR_WATCH_BUTTONS: ❌ NOT FOUND')
"
```

#### Output yang Diharapkan

```
LOAD_TITLE (h1.entry-title): [Renegade Immortal] ✅
LOAD_POSTER (div.thumb img): [https://.../Renegade-Immortal-sub-indo.webp] ✅
LOAD_DESC (.entry-content): [Renegade Immortal (Xian Ni) Bercerita tentang...] ✅
LOAD_INFO_BOX (.spe): [Status: Ongoing  Network: Tencent...] ✅
LOAD_TAGS (.genxed a): ['Action', 'Adventure', 'Fantasy'] ✅
LOAD_STATUS: [Ongoing] ✅
LOAD_RECOMMEND (.bsx .tt): 12 items ✅
```

---

### Phase 3: Episode Page (loadLinks)

Mensimulasikan `loadLinks()` — ekstraksi link server video + download + episode.

#### Selectors yang Dicek

| # | Selector | Fungsi |
|---|----------|--------|
| 1 | `LINK_OPTIONS` | Opsi server player (`option[value]`, `li a[data-url]`) |
| 2 | `DOWNLOAD_ITEMS` | Link download |
| 3 | `SELECTOR_IFRAME_TAG` + `ATTR_IFRAME_SOURCES` | Iframe embed |
| 4 | `ATTR_VALUE` | Atribut value/data-url |
| 5 | `EPISODE_ITEMS` | Daftar episode |
| 6 | `EPISODE_HREF` | Link episode |
| 7 | `EPISODE_TITLE` / `EPISODE_NUM` | Judul/nomor episode |
| 8 | `EPISODE_DESC` / `EPISODE_TIME` | Deskripsi/durasi episode |
| 9 | `FOLLOW_LINK_SELECTOR` | Link follow/redirect |
| 10 | `CONFIG_AJAX_PLAYER_URLS` + `SELECTOR_JSON_DATA` | AJAX player |

#### Template Script

```bash
URL="https://anichin.cafe/renegade-immortal-episode-140-subtitle-indonesia/"

curl -sL --max-time 15 \
  -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  "$URL" | python3 -c "
import sys, re, base64
c = sys.stdin.read()

print('=== EPISODE PAGE ===')
print()

# LINK_OPTIONS — option[value] (base64 encoded iframe)
options = re.findall(r'<option[^>]*value=\"([^\"]+)\"[^>]*>(.*?)</option>', c, re.DOTALL)
print(f'LINK_OPTIONS (option[value]): {len(options)} options')
for val, label in options:
    if val:
        try:
            decoded = base64.b64decode(val).decode('utf-8', errors='replace')
            src = re.search(r'src=\"([^\"]+)\"', decoded)
            if src:
                print(f'  [{label.strip()}]: {src.group(1)[:80]} ✅')
        except:
            print(f'  [{label.strip()}]: (base64 decode failed) ❌')

# LINK_OPTIONS — ul#player-list li a[data-url]
data_urls = re.findall(r'<a[^>]+data-url=\"([^\"]+)\"[^>]*>(.*?)</a>', c)
print(f'\\nLINK_OPTIONS (a[data-url]): {len(data_urls)} links')
for url, label in data_urls:
    print(f'  [{label.strip()}]: {url[:60]}')

# SELECTOR_IFRAME_TAG — iframe[src]
iframes = re.findall(r'<iframe[^>]+src=\"([^\"]+)\"[^>]*>', c)
print(f'\\nSELECTOR_IFRAME_TAG (iframe[src]): {len(iframes)} iframes')
for s in iframes[:3]:
    print(f'  [{s[:80]}]')

# EPISODE_ITEMS — daftar episode
eps = re.findall(r'<li[^>]*class=\"[^\"]*eplister[^\"]*\"[^>]*>', c)
print(f'\\nEPISODE_ITEMS (.eplister li): {len(eps)} items')

# DOWNLOAD_ITEMS
dls = re.findall(r'<div[^>]*id=\"downloadb\"[^>]*>(.*?)</div>', c, re.DOTALL)
print(f'\\nDOWNLOAD_ITEMS (#downloadb): {len(dls)} sections')
"
```

#### Output yang Diharapkan

```
LINK_OPTIONS (option[value]): 6 options
  [Premium]: https://anichin.stream/?id=v77gg3g ✅
  [OK.ru]: https://ok.ru/videoembed/14181976050354 ✅
  [Dailymotion [Ads]]: https://geo.dailymotion.com/player/... ✅
  [Rumble [Ads]]: https://rumble.com/embed/v77gg3g/ ✅
  [Drive 1 [Ads]]: https://short.icu/6-UXR_DJY ❌ (dead)
  [Drive 2 [Ads]]: https://rubyvidhub.com/embed-xxx.html ❌ (ads)

SELECTOR_IFRAME_TAG (iframe[src]): 1 iframe
  [https://anichin.stream/?id=v77gg3g] ✅
```

---

### Phase 4: Verifikasi Base64 Decode (khusus Anichin/Animasu)

```bash
# Test decode base64 option value
echo "PGlmcmFtZSB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBzcmM9Imh0dHBzOi8vYW5pY2hpbi5zdHJlYW0vP2lkPXY3N2dnM2ci..." | base64 -d 2>/dev/null

# Atau dengan python
python3 -c "
import base64
val = 'PGlmcmFtZSB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBzcmM9Imh0dHBzOi8vb2sucnUvdmlkZW9lbWJlZC8xNDE4MTk3NjA1MDM1NCI...'
decoded = base64.b64decode(val).decode()
print(decoded)
# Ekstrak src
import re
src = re.search(r'src=\"([^\"]+)\"', decoded)
if src:
    print('SRC:', src.group(1))
"
```

---

## Checklist Lengkap Per Provider

Gunakan template di atas untuk setiap provider dengan mengganti `URL` dan `PROVIDER`.

### HTML-Based Providers (config-driven)

| Provider | Main URL | SERI URL (contoh) | Episode URL (contoh) |
|---|---|---|---|
| Anichin | `https://anichin.cafe/` | `/seri/renegade-immortal/` | `/renegade-immortal-episode-140-.../` |
| Animasu | `https://v2.animasu.work/` | `/anime/xxx/` | `/episode-xxx/` |
| Donghuastream | `https://donghuastream.org/` | `/anime/xxx/` | `/xxx-episode-1/` |
| Dutamovie21 | `https://vikingsgab.com/` | `/movie/xxx/` | `/xxx-episode-1/` |
| IndoDrama21 | `https://indodr21.putar.in/` | `/series/xxx/` | `/xxx-episode-1/` |
| LayarKaca21 | `https://tv12.lk21official.cc/` | `/nonton-xxx/` | `/xxx/` |
| Samehadaku | `https://v2.samehadaku.how/` | `/anime/xxx/` | `/xxx-episode-1-.../` |

> Daftar aktual ambil dari `config/<name>.json` di BaseProvider — main URL bisa
> berubah; selalu cek `mainUrl` di config saat verifikasi.

---

## Presentasi Hasil ke User

Setelah menjalankan semua 4 phase check, hasil harus disajikan ke user secara terstruktur.

### Format Output Wajib

#### 1. Header Provider

```
╔══════════════════════════════════════════════════════════╗
║     PROVIDER NAME — FULL 4-PHASE ALL SELECTORS VERIFICATION  ║
╚══════════════════════════════════════════════════════════╝
```

#### 2. Phase 1 — Main Page

Tampilkan 5 item pertama dengan detail lengkap:

```
PHASE 1: MAIN PAGE (46 items)

  ── Item 1 ──
  📝 SEARCH_TITLE   : Renegade Immortal
  🔗 SEARCH_HREF    : https://anichin.cafe/renegade-immortal-episode-140-...
  🖼️ SEARCH_POSTER  : https://.../Renegade-Immortal-sub-indo.webp ✅
  🔢 SEARCH_EP_TEXT : Ep 140

  ── Item 2 ──
  📝 SEARCH_TITLE   : Coiling Dragon
  🔗 SEARCH_HREF    : https://anichin.cafe/coiling-dragon-episode-05-...
  🖼️ SEARCH_POSTER  : https://.../Coiling-Dragon...webp ✅
  🔢 SEARCH_EP_TEXT : Ep 05
```

Setiap item WAJIB menampilkan:
- `SEARCH_TITLE` — title setelah melalui `safeCleanBloat` + `safeDeduplicate`
- `SEARCH_HREF` — link episode/series
- `SEARCH_POSTER` — URL poster ✅/❌
- `SEARCH_EP_TEXT` — badge episode ✅/❌
- `SEARCH_RATING` — rating ✅/❌ atau "N/A"

#### 3. Phase 2 — Detail Page

Tampilkan format tabel per selector:

```
PHASE 2: DETAIL PAGE

  📝 LOAD_TITLE      : Renegade Immortal ✅
  🖼️ LOAD_POSTER     : https://.../Renegade-Immortal...webp ✅
  🌄 LOAD_BANNER     : NOT FOUND ❌ (tidak ada)
  📖 LOAD_DESC       : Synopsis asli ✅
  📋 LOAD_INFO_BOX   : Status[Ongoing] Network[Tencen...] ✅
  🏷️ LOAD_TAGS       : Action, Adventure, Fantasy ✅
  📊 LOAD_STATUS     : Ongoing ✅
  ⭐ LOAD_RATING     : 8.83 ✅
  ▶️ LOAD_TRAILER    : NOT FOUND ❌
  📌 LOAD_RECOMMEND  : 0 items ⚠️ (di halaman episode)
```

Setiap selector WAJIB menampilkan:
- Nama selector + value
- Status ✅ / ❌
- Jika ❌, beri alasan: `(tidak ada)`, `(tema berbeda)`, `(wajar)`

#### 4. Phase 3 — Episode Page

Tampilkan server options dan iframe:

```
PHASE 3: EPISODE PAGE

  🔗 LINK_OPTIONS : 7 server options
      [Premium]      : https://anichin.stream/?id=xxx ✅
      [OK.ru]        : https://ok.ru/videoembed/xxx ✅
      [Dailymotion]  : https://geo.dailymotion.com/... ✅
      [Rumble]       : https://rumble.com/embed/xxx ✅
      [Drive 1]      : https://short.icu/xxx ❌ (dead)
      [Drive 2]      : https://rubyvidhub.com/embed-xxx ✅

  🖼️ IFRAME_TAG  : 1 iframe ✅
      https://anichin.stream/?id=xxx
```

#### 5. Phase 4 — Base64 Decode

```
PHASE 4: BASE64 DECODE

  [Premium]    : Decoded OK → src: https://anichin.stream/?id=xxx ✅
  [OK.ru]      : Decoded OK → src: https://ok.ru/videoembed/xxx ✅
```

#### 6. Ringkasan Akhir

Tabel ringkasan per phase, highlight yang perlu diperbaiki:

```
RINGKASAN

✅ 46 items — SEARCH sempurna
✅ LOAD — title, poster, desc, tags, status, rating OK
⚠️ LOAD_RECOMMEND: 0 di halaman detail (hanya di episode)
❌ LOAD_TRAILER: tidak tersedia
❌ EPISODE_ITEMS: selector .eplister tidak match (website pakai .episodelist)
```

### Aturan Presentasi

1. **Jangan hanya menampilkan kode mentah** — hasil harus sudah di-parse dan dibersihkan
2. **Setiap item/selector harus ada status** — ✅ / ⚠️ / ❌
3. **Jika ❌ berikan alasan** — jangan cuma "NOT FOUND", tapi jelaskan kenapa
4. **Highlight yang perlu diperbaiki** — pisahkan antara "wajar" dan "perlu tindakan"
5. **Gunakan emoji yang konsisten**:
   - ✅ = berfungsi
   - ⚠️ = perlu perhatian (wajar/tidak kritis)
   - ❌ = error/broken

### Contoh Presentasi Lengkap ke User

Setelah menjalankan check, agent WAJIB menampilkan hasil seperti ini persis:

```
╔══════════════════════════════════════════════════════════════════╗
║     ANICHIN — FULL 4-PHASE ALL SELECTORS VERIFICATION              ║
╚══════════════════════════════════════════════════════════════════╝

======================================================================
  PHASE 1: MAIN PAGE (46 items)
======================================================================

  ── Item 1 ──
  📝 SEARCH_TITLE   : Renegade Immortal
  🔗 SEARCH_HREF    : https://anichin.cafe/renegade-immortal-episode-140-...
  🖼️ SEARCH_POSTER  : https://.../Renegade-Immortal...webp ✅
  🔢 SEARCH_EP_TEXT : Ep 140

  ── Item 2 ──
  📝 SEARCH_TITLE   : Coiling Dragon
  🔗 SEARCH_HREF    : https://anichin.cafe/coiling-dragon-episode-05-...
  🖼️ SEARCH_POSTER  : https://.../Coiling-Dragon...webp ✅
  🔢 SEARCH_EP_TEXT : Ep 05

======================================================================
  PHASE 2: DETAIL PAGE
======================================================================

  📝 LOAD_TITLE      : Renegade Immortal ✅
  🖼️ LOAD_POSTER     : ✅
  📖 LOAD_DESC       : Synopsis asli ✅
  📋 LOAD_INFO_BOX   : Status=Ongoing ✅
  🏷️ LOAD_TAGS       : Action, Adventure, Fantasy ✅
  📊 LOAD_STATUS     : Ongoing ✅
  ⭐ LOAD_RATING     : 8.83 ✅
  📌 LOAD_RECOMMEND  : 0 (hanya di episode) ⚠️
  ▶️ LOAD_TRAILER    : ❌ TIDAK ADA

======================================================================
  PHASE 3: EPISODE PAGE
======================================================================

  🔗 LINK_OPTIONS    : 7 options
      [Select Video Server]
      [Premium]      : https://anichin.stream/?id=v77gslu ✅
      [OK.ru]        : https://ok.ru/videoembed/14188645714610 ✅
      [Dailymotion]  : https://geo.dailymotion.com/player/... ✅
      [Rumble]       : https://rumble.com/embed/v77gslu/ ✅
      [Drive 1]      : https://short.icu/6u_4YP_a7 (dead)
      [Drive 2]      : https://rubyvidhub.com/embed-xxx ✅

  🖼️ IFRAME_TAG      : 1 iframes
      https://anichin.stream/?id=v77gslu

  📋 EPISODE_ITEMS   : 0 (selector masih .eplister, harus .episodelist) ⚠️
  📌 RELATED EPISODES: 1 items ✅

======================================================================
  PHASE 4: BASE64 DECODE
======================================================================

  [Premium]    : ✅ → https://anichin.stream/?id=v77gslu
  [OK.ru]      : ✅ → https://ok.ru/videoembed/14188645714610

======================================================================
  RINGKASAN
======================================================================

  ✅ PHASE 1: 46 items — title/href/poster/ep_text OK
  ✅ PHASE 2: title/poster/desc/info/tags/status/rating OK
  ✅ PHASE 3: 7 server options, 1 iframe, related episodes
  ✅ PHASE 4: Base64 decode OK

  ⚠️  Perbaiki:
     • EPISODE_ITEMS: selector .eplister → .episodelist
     • LOAD_TRAILER : tidak tersedia (wajar)
```

---

### Selector Tidak Match (0 items)

1. Cek apakah website berubah tema/struktur
2. Cari class baru dengan curl + grep
3. Update selector di `config/<name>.json` (BaseProvider/.../config/)
4. Multi-variant selector didukung dengan koma — tambahkan fallback selector baru

### Base64 Decode Gagal

1. Pastikan value tidak kosong
2. Coba dengan python `base64.b64decode(val + '==')` untuk padding
3. Jika masih gagal, value mungkin plain text (bukan base64)

### Title Ada Kata Tambahan

1. Cek `bloatRegex` di `config/<name>.json`
2. Cek `safeDeduplicate()` — duplicate judul
3. Cek apakah selector mengambil element yang salah (title attribute dari link logo)

---

## Referensi

- File selectors: `BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json`
- `global.json` — default bersama antar provider
- Multi-variant selector: `"h1.entry-title, h1.title"` — diproses `SelectorResolver`
  (relokasi/fingerprint saat selector berubah situs)
