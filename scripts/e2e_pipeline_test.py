#!/usr/bin/env python3
"""
E2E Pipeline Test — OCE CloudStream Extension
Simulasi penuh: homelist -> detail -> episode -> loadLinks -> extractor -> links
Mencerminkan logika Kotlin (regex identik, keputusan identik) terhadap situs NYATA.
"""
import json, re, sys, base64, time
from pathlib import Path
from urllib.parse import urlparse
from bs4 import BeautifulSoup
from curl_cffi import requests

ROOT = Path(__file__).resolve().parent.parent
CFG = ROOT / "BaseProvider/src/main/kotlin/com/baseprovider/config"
EXT_CFG = CFG / "extractors"

UA_BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
UA_RAW = "okhttp/4.12.0"

# ── Regex identik dengan Kotlin ──────────────────────────────────────────────
EPISODE_URL_REGEX   = re.compile(r'(?i)(?:/eps/|/episode/|/ep/|-episode-|/season-|-season-|/ep-)')
STRONG_EP_URL       = re.compile(r'(?i)(?:/eps/|/episode/|/ep/|-episode-|/ep-)')
SEASON_ONLY_URL     = re.compile(r'(?i)(?:/season-|-season-)')
EPISODE_TEXT_REGEX  = re.compile(r'(?i)(?:\bepisode\b|\beps?\b)\s*(\d+(?:\.\d+)?)')
LISTING_PATH        = re.compile(r'^https?://[^/]+/(category|categories|tags?|genre|genres|country|countries|director|cast|artist|year|quality|network|production|studio|actor|actress)(/|$)', re.I)
YEAR_SUFFIX         = re.compile(r'-\d{4}/?$')
TV_MARKERS          = ["/tv/", "/series/", "/anime/", "/drama/", "/episode/", "/eps/"]
RUMBLE_SUB          = re.compile(r'\{"mp4(.*?)"evt":\{', re.S)
URL_ANY             = re.compile(r'https?://[^\s"\'<>\\]+')

REPORT = {"stages": [], "verdict": None}
def stage(name, ok, detail=""):
    status = "PASS" if ok else "FAIL"
    REPORT["stages"].append({"stage": name, "status": status, "detail": detail})
    print(f"  [{'✓' if ok else '✗'}] {name}" + (f" — {detail}" if detail else ""))
    return ok

def fetch(url, referer=None, impersonate='chrome', timeout=25):
    h = {'Accept-Language': 'id-ID,id;q=0.9,en;q=0.8'}
    if referer: h['Referer'] = referer
    r = requests.get(url, impersonate=impersonate, timeout=timeout, headers=h)
    return r

def soup(html): return BeautifulSoup(html, 'html.parser')

def select_all(s, css):
    out = []
    for variant in [v.strip() for v in css.split(',')]:
        try: out = s.select(variant)
        except Exception: out = []
        if out: break
    return out

def select_first(el, css):
    for v in [x.strip() for x in css.split(',')]:
        try: f = el.select_one(v)
        except Exception: f = None
        if f: return f
    return None

def fix_url(url, base):
    if not url: return ""
    if url.startswith("http"): return url
    if url.startswith("//"): return "https:" + url
    u = urlparse(base); root = f"{u.scheme}://{u.netloc}"
    if url.startswith("/"): return root + url
    return (base if base.endswith('/') else base + '/') + url

def is_b64(s):
    if not s or len(s) > 10000: return False
    try: base64.b64decode(s, validate=True); return True
    except Exception: return False

def decode_raw(raw):
    """Mirror FallbackPipeline.decodeRawLink"""
    if raw.startswith(("http", "//", "/")) or not is_b64(raw): return raw
    try:
        dec = base64.b64decode(raw + "===").decode('utf-8', 'replace')
        if "<iframe" in dec:
            m = re.search(r'src="([^"]+)"', dec)
            return m.group(1) if m else ""
        if dec.startswith(("http", "//", "/")): return dec
    except Exception: pass
    return ""

def is_listing(url, cfg):
    if LISTING_PATH.match(url): return True
    rest = urlparse(url).path.strip('/')
    if not rest: return False
    segs = {p[0].strip('/').split('/')[0] for p in cfg.get("mainPageLists", []) if p and p[0].strip('/')}
    first = rest.split('/')[0]
    return first in segs and ('/' not in rest or '/page/' in rest)

def looks_like_movie(url, cfg):
    if cfg.get("tvPathSegment") and cfg["tvPathSegment"] in url: return False
    if any(m in url.lower() for m in TV_MARKERS): return False
    mrx = cfg.get("movieUrlRegex","")
    if mrx and re.search(mrx, url, re.I): return True
    return cfg.get("moviePathSegment", "").startswith('/') and bool(YEAR_SUFFIX.search(url))

def detect_episode_links(s, current_url):
    """Mirror SelectorResolver.detectEpisodeLinks TERMASUK lapisan weak-season baru"""
    out = []
    for a in s.select('a[href]'):
        href = a.get('href') or ''
        if not href: continue
        absu = fix_url(href, current_url)
        if absu == current_url: continue
        text = a.get_text(strip=True)
        if EPISODE_URL_REGEX.search(absu) or EPISODE_TEXT_REGEX.search(text):
            weak = (not STRONG_EP_URL.search(absu)) and bool(SEASON_ONLY_URL.search(absu))
            if weak and not EPISODE_TEXT_REGEX.search(text): continue
            out.append(absu)
    return out

def extract_episodes(s, current_url, cfg):
    """Mirror ProviderMapper.extractEpisodes (dedupe by HREF)"""
    eps, seen = [], set()
    for li in select_all(s, cfg["episodeItems"]):
        anchor = select_first(li, cfg.get("episodeHref", "")) if cfg.get("episodeHref") else None
        anchor = anchor or li.select_one('a') or (li if li.name == 'a' else None)
        if anchor is None: continue
        href = cfg.get("episodeDataUrlPattern", "{url}").replace("{url}", fix_url(anchor.get('href',''), current_url))
        if not href or href in seen: continue
        seen.add(href)
        num_el = select_first(li, cfg.get("episodeNum","")) if cfg.get("episodeNum") else None
        title_el = select_first(li, cfg.get("episodeTitle","")) if cfg.get("episodeTitle") else None
        raw_num = (num_el or title_el or li).get_text(strip=True)
        m = re.search(r'(?:episode|eps?)\s*(\d+)', raw_num, re.I) or re.search(r'(\d+)', raw_num)
        num = int(m.group(1)) if m else None
        eps.append({"href": href, "num": num, "title": (title_el or li).get_text(strip=True)[:40]})
    return list(reversed(eps))

# ── Extractor step engine (mirror ConfigDrivenExtractor) ────────────────────
EXTRACTORS = {}
for f in EXT_CFG.glob("*.json"):
    d = json.loads(f.read_text())
    EXTRACTORS[d["id"]] = d

def host_of(u): 
    try: return urlparse(u).netloc.lower()
    except Exception: return ""

def match_extractor(url):
    h = host_of(url)
    for eid, cfgd in EXTRACTORS.items():
        mu = cfgd.get("mainUrl","")
        mh = host_of(mu)
        if h == mh or h.endswith("." + mh): return eid, cfgd
    return None, None

import os
_DEBUG = os.environ.get("E2E_DEBUG") == "1"
def _dbg(state, after):
    if _DEBUG:
        print(f"      [dbg] after={after}: " + ", ".join(f"{k}=len{len(v)}" for k,v in state.items() if isinstance(v,str)))

def run_steps(eid, cfgd, url, log):
    state = {"url": url}
    main = cfgd.get("mainUrl","")
    for step in cfgd.get("steps", []):
        kind = (step.get("step") or "").lower()
        if kind == "fetch":
            target = step.get("url","{url}").replace("{url}", state.get("url", url)).replace("{mainUrl}", main)
            for a,b in (step.get("urlReplace") or {}).items(): target = target.replace(a,b)
            hdrs = {}
            if step.get("referer"): hdrs['Referer'] = step["referer"].replace("{mainUrl}", main).replace("{url}", state.get("url", url))
            try:
                r = fetch(target, referer=hdrs.get('Referer'), impersonate='chrome')
                state[step.get("store","response")] = r.text
                log(f"    fetch {target[:70]} -> {r.status_code}")
            except Exception as e:
                log(f"    fetch FAIL {target[:60]}: {e}")
                state[step.get("store","response")] = ""
        elif kind == "substring":
            src = state.get(step.get("source","response"), "")
            i = src.find(step["startMarker"])
            j = src.find(step["endMarker"], i+1) if i >= 0 else -1
            state[step.get("store")] = src[i+len(step["startMarker"]):j] if (i>=0 and j>i) else ""
        elif kind == "regex":
            text = state.get(step.get("source","response"), "")
            pat = re.compile(step["pattern"].replace('\\"', '"'))
            urls = set()
            for mm in pat.finditer(text):
                g = mm.group(step.get("group",1)) if mm.groups() else mm.group(0)
                if g: urls.add(g.replace('\\/', '/'))
            flt = step.get("filter","")
            res = [u for u in urls if (flt in u)] if flt else list(urls)
            if step.get("universal"):
                res = [m.group(0) for u in text for m in [URL_ANY.search(u)] if m]
                res = [u for u in res if flt in u] if flt else res
            if step.get("store"): state[step["store"]] = res[0] if res else ""
            else: state.setdefault("_out", []).extend(res)
        elif kind == "jsonpath":
            text = state.get(step.get("source","response"), "")
            _dbg(state, f"jsonpath src={step.get('source')} len={len(text)}")
            try:
                # dukung wildcard []: result.sources[].url
                segs = step["path"].split('.')
                results = [json.loads(text)] if text.strip() else []
                for seg in segs:
                    nxt = []
                    for cur in results:
                        if cur is None: continue
                        if "[]" in seg:
                            key = seg.replace("[]","")
                            arr = cur.get(key) if isinstance(cur, dict) else cur
                            if isinstance(arr, list): nxt.extend(arr)
                        elif isinstance(cur, dict):
                            v = cur.get(seg)
                            if v is not None:
                                if isinstance(v, list): nxt.extend(v)
                                else: nxt.append(v)
                    results = nxt
                flt = step.get("filter","")
                vals = [r for r in results if isinstance(r,str)]
                vals = [v for v in vals if flt in v] if flt else vals
                if step.get("store"): state[step["store"]] = vals[0] if vals else ""
                else: state.setdefault("_out", []).extend(vals)
            except Exception as e:
                notes_jp = f"jsonpath err {e}"
        elif kind == "postjson":
            body = step.get("jsonBody","{}")
            for k, v in state.items():
                if isinstance(v, str): body = body.replace("{"+k+"}", v)
            hdrs = {k: v.replace("{mainUrl}", main) for k, v in (step.get("headers") or {}).items()}
            if step.get("referer"): hdrs.setdefault('Referer', step["referer"].replace("{mainUrl}", main))
            try:
                # Mirror NiceHttp postJson: Content-Type application/json otomatis
                hdrs.setdefault('Content-Type', 'application/json')
                try: payload = json.loads(body)
                except Exception: payload = body
                r = requests.post(step["url"], json=payload, headers=hdrs,
                                  timeout=20, impersonate='chrome124')
                state[step.get("store","postResp")] = r.text
                log(f"    postJson {step['url'][:50]} -> {r.status_code}")
                if _DEBUG: print(f"      [dbg] resp[:120]={r.text[:120]}")
            except Exception as e:
                log(f"    postJson FAIL: {e}")
                state[step.get("store","postResp")] = ""
    out = state.get("_out", [])
    of = cfgd.get("outputFilter","")
    if of == "master": out = [u for u in out if '.m3u8' in u]
    return out

def flt_check(v, f): return (f in v) if f else True

# ── Probe header combos (mirror AdaptiveHeaderProbe incl RAW) ────────────────
def probe(url):
    origin = f"{urlparse(url).scheme}://{urlparse(url).netloc}"
    combos = [
        ("BARE",        None,    {"Accept":"*/*","User-Agent":UA_BROWSER}),
        ("REFERER",     origin,  {"Accept":"*/*","User-Agent":UA_BROWSER}),
        ("ORIGIN",      origin,  {"Accept":"*/*","User-Agent":UA_BROWSER,"Origin":origin}),
        ("BROWSER_LIKE",origin,  {"Accept":"*/*","User-Agent":UA_BROWSER,"Accept-Language":"id-ID,id;q=0.9","Origin":origin}),
        ("RAW",         None,    {"Accept":"*/*","User-Agent":UA_RAW}),
    ]
    best = None
    for mode, ref, hh in combos:
        try:
            h = dict(hh); h["Range"] = "bytes=0-1048575"
            r = requests.get(url, headers=h, timeout=12, impersonate=None if mode=="RAW" else 'chrome124')
            if r.status_code in range(200,400):
                body = r.content[:1024*1024]
                best = (mode, ref, dict(hh), body)
                break
        except Exception: continue
    return best

def verify_master_m3u8(body):
    txt = body.decode('utf-8','replace')
    if '#EXT-X-STREAM-INF' in txt:
        variants = []
        for line in txt.splitlines():
            if line.startswith('#EXT-X-STREAM-INF') and 'URI="' in line:
                u = re.search(r'URI="([^"]+)"', line)
                nxt_ok = True
                if u: variants.append(u.group(1))
        return len([v for v in variants if v.strip()]) > 0, f"{len(variants)} variant"
    if '#EXTINF' in txt: return True, "media playlist"
    return False, "no tags"

# ══════════════════════════════════════════════════════════════════════════════
def pipeline(provider_id, item_url, expect_type=None, expect_ep96_dup=False, label=""):
    cfg_file = CFG / f"{provider_id.lower()}.json"
    cfgp = json.loads(cfg_file.read_text())
    print(f"\n{'='*72}\nPIPELINE [{provider_id}] {label}\n  item: {item_url}\n{'='*72}")
    P = lambda n,o,d="": stage(n,o,d)

    # STAGE 1: HOMELIST (pakai kategori pertama mainPageLists)
    ok_all = True
    seg = cfgp["mainPageLists"][0][0].lstrip('/')
    list_url = f"{cfgp['mainUrl']}/{seg}/"
    try:
        s = soup(fetch(list_url, referer=cfgp["mainUrl"]).text)
        arts = select_all(s, cfgp["searchItems"])
        P("S1 homelist: items ditemukan", len(arts)>0, f"{len(arts)} artikel @ {list_url}")
        bad_list, movies, series = 0, 0, 0
        sample_item = None
        for art in arts:
            href_el = select_first(art, cfgp["searchHref"]) if cfgp.get("searchHref") else art.select_one('a')
            href = fix_url(href_el.get('href',''), cfgp["mainUrl"]) if href_el else ""
            if not href or is_listing(href, cfgp): bad_list += 1; continue
            if looks_like_movie(href, cfgp): movies += 1; sample_item = sample_item or href
            else: series += 1; sample_item = sample_item or href
        P("S1 homelist: 0 URL listing bocor", bad_list==0, f"{bad_list} listing dari {len(arts)} item")
    except Exception as e:
        P("S1 homelist", False, str(e)); ok_all=False; return REPORT

    # STAGE 2: DETAIL PAGE pada item_url yang diminta
    d = fetch(item_url, referer=cfgp["mainUrl"]); ds = soup(d.text)
    title_ok = select_first(ds, cfgp["loadTitle"]) is not None
    poster_ok = select_first(ds, cfgp["loadPoster"]) is not None or ds.select_one('meta[property="og:image"]') is not None
    P("S2 detail: judul+poster terekstrak", title_ok and poster_ok)

    # Mirror PERSIS DetailPageScrapper.loadRecursive
    ep_sel = select_all(ds, cfgp["episodeItems"]) if cfgp.get("episodeItems") else []
    # Gate movie — sinyal KONTEN dulu: player tab ada di halaman = film
    has_player = False
    if cfgp.get("linkOptions"):
        for v in cfgp["linkOptions"].split(','):
            try:
                if ds.select_one(v.strip()): has_player = True; break
            except Exception: pass
    url_looks_tv_ = any(m in item_url.lower() for m in TV_MARKERS)
    tv_seg = cfgp.get("tvPathSegment","")
    depth1 = '/' not in urlparse(item_url).path.strip('/')
    single_video = has_player and (tv_seg not in item_url) and not url_looks_tv_
    gate_movie = single_video or looks_like_movie(item_url, cfgp) or (
        bool(tv_seg) and tv_seg not in item_url and not url_looks_tv_ and depth1)
    if single_video and ep_sel:
        print(f'  [arb] player tab mengabaikan {len(ep_sel)} elemen episode liar')
        ep_sel = []
    fallback_eps = detect_episode_links(ds, item_url) if (not ep_sel and not gate_movie) else []
    effective_ep_nonempty = bool(ep_sel) or bool(fallback_eps)
    has_tv_path = bool(cfgp.get("tvPathSegment") and cfgp["tvPathSegment"] in item_url)
    url_looks_tv = any(m in item_url.lower() for m in TV_MARKERS)
    mps = cfgp.get("moviePathSegment","")
    seg_match = bool(mps) and mps in item_url
    contains_movie_word = "movie" in item_url.lower()
    is_movie_final = (not has_tv_path) and (not url_looks_tv) and (
        seg_match or contains_movie_word or (not effective_ep_nonempty))

    if expect_type == "movie":
        ok2 = P("S2 detail: diputuskan MOVIE (tombol putar)", is_movie_final,
                f"player={has_player} gate={gate_movie} sel={len(ep_sel)} fallback={len(fallback_eps)}")
        ok_all &= ok2
        return sub_pipeline_media(provider_id, cfgp, item_url, ok_all, label)
    else:
        ok2 = P("S2 detail: diputuskan SERIES", not is_movie_final,
                f"sel={len(ep_sel)} fallback={len(fallback_eps)}")
        eps = extract_episodes(ds, item_url, cfgp)
        ok2 &= P("S2 episode list terisi", len(eps)>0, f"{len(eps)} episode")
        if expect_ep96_dup:
            n96 = [e for e in eps if e["num"]==96]
            ok2 &= P("S2 THOG: episode 96 MUNCUL DUA KALI", len(n96)==2,
                     f"dapat {len(n96)}: {[e['href'][-22:] for e in n96]}")
        ok_all &= ok2
        target_ep = next((e["href"] for e in eps if re.search(r'96-subtitle-indonesia/$', e["href"])),
                         eps[0]["href"] if eps else None)
        if target_ep:
            return sub_pipeline_episode(provider_id, cfgp, target_ep, ok_all, label)
        return dict(REPORT)

def sub_pipeline_episode(pid, cfgp, ep_url, ok_all, label=""):
    P = lambda n,o,d="": stage(n,o,d)
    d = fetch(ep_url, referer=cfgp["mainUrl"]); ds = soup(d.text)
    P("S3 loadLinks: halaman episode ter-fetch", d.status_code==200, ep_url[-45:])
    cands = collect_candidates(ds, cfgp, ep_url)
    ok3 = P("S3 loadLinks: kandidat link terkumpul", len(cands)>0, f"{len(cands)} kandidat: {[c[0][:50] for c in cands[:4]]}")
    ok_all &= ok3
    return extract_and_probe(pid, cfgp, cands, ep_url, ok_all, label)

def sub_pipeline_media(pid, cfgp, media_url, ok_all, label=""):
    P = lambda n,o,d="": stage(n,o,d)
    d = fetch(media_url, referer=cfgp["mainUrl"]); ds = soup(d.text)
    P("S3 loadLinks: halaman film ter-fetch", d.status_code==200)
    cands = collect_candidates(ds, cfgp, media_url)
    ok3 = P("S3 loadLinks: kandidat link terkumpul", len(cands)>0, f"{len(cands)} kandidat: {[c[0][:50] for c in cands[:4]]}")
    ok_all &= ok3
    return extract_and_probe(pid, cfgp, cands, media_url, ok_all, label)

def collect_candidates(ds, cfgp, current_url):
    cands = {}
    def add(u, lbl=None):
        if not u or u.startswith('#'): return
        u = fix_url(decode_raw(u), current_url).split('#')[0]
        if u and u.startswith('http'): cands.setdefault(u, lbl)
    if cfgp.get("linkOptions"):
        for cont in select_all(ds, cfgp["linkOptions"]):
            anchors = cont.select('a')
            if anchors:
                for a in anchors:
                    add(a.get('data-url') or a.get('href',''), a.get_text(strip=True))
            else:
                raw = cont.get(cfgp.get("attrValue","value")) or cont.get('href') or ''
                if raw: add(raw, cont.get_text(strip=True))
    if cfgp.get("iframeSelectors"):
        for ifr in select_all(ds, cfgp["iframeSelectors"]):
            for attr in ['src','data-src','data-litespeed-src']:
                v = ifr.get(attr)
                if v and v != 'about:blank': add(v)
    return list(cands.items())

def extract_and_probe(pid, cfgp, cands, page_url, ok_all, label=""):
    P = lambda n,o,d="": stage(n,o,d)
    delivered, notes = [], []
    skip_hosts = set(h.lower() for h in (cfgp.get("skipHosts") or [])) | {'www.youtube.com','youtube.com'}
    for raw, lbl in cands:
        host = host_of(raw)
        if any(host==s or host.endswith('.'+s) for s in skip_hosts):
            notes.append(f"skip {host}"); continue
        eid, ecfg = match_extractor(raw)
        if ecfg:
            outs = run_steps(eid, ecfg, raw, lambda m: notes.append(m))
            notes.append(f"{eid}: {len(outs)} hasil")
            for o in outs: delivered.append((o, eid))
        else:
            # Mirror tryManualIframeFetch: fetch halaman, cari video-url LANGSUNG
            # + iframe (rekursi depth-1 → jalankan extractor pada iframe tsb)
            try:
                pg = fetch(raw, referer=page_url).text
                vids = [u for u in URL_ANY.findall(pg) if '.m3u8' in u or '.mp4' in u]
                ifr = re.findall(r'<iframe[^>]+src="([^"]+)"', pg)
                notes.append(f"manual {host}: {len(vids)} direct, {len(ifr)} iframe")
                for v in vids[:3]: delivered.append((v, "deepscan"))
                for iu in ifr[:6]:
                    iu_f = fix_url(iu, raw).split('#')[0]
                    ih = host_of(iu_f)
                    if any(ih==s or ih.endswith('.'+s) for s in skip_hosts): continue
                    eid2, ecfg2 = match_extractor(iu_f)
                    if ecfg2:
                        outs2 = run_steps(eid2, ecfg2, iu_f, lambda m: notes.append(m))
                        notes.append(f"  ↳ {eid2}: {len(outs2)} hasil")
                        delivered.extend((o, eid2) for o in outs2)
            except Exception:
                notes.append(f"manual {host} ERR")
    ok4 = P("D4 extractor: ada output video", len(delivered)>0, "; ".join(notes[:14]))
    ok_all &= ok4
    final_links = []
    probe_modes = []
    for u, src in delivered:
        pr = probe(u)
        if not pr: notes.append(f"probe gagal semua combo: {u[:60]}"); continue
        mode, ref, hh, body = pr
        if '.m3u8' in u:
            ok_v, info = verify_master_m3u8(body)
            if not ok_v: notes.append(f"master invalid {u[:50]}"); continue
            probe_modes.append(f"{src}:{mode}({info})")
        else:
            probe_modes.append(f"{src}:{mode}(direct)")
        final_links.append(u)
    ok5 = P("E5 probe+verify: LINK SIAP PUTAR", len(final_links)>0,
            f"{len(final_links)} link; combo menang: {probe_modes[:8]}")
    REPORT["verdict"] = "SUCCESS" if (ok_all and ok5) else "FAILED"
    P("VERDICT PIPELINE", ok_all and ok5, f"{pid} {label}".strip())
    return dict(REPORT)

if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv)>1 else "all"
    cases = set((which.split() if which != "all" else ["all"]))
    run_all = "all" in cases
    results = []
    if run_all or "dm_movie" in cases:
        results.append(pipeline("Dutamovie21", "https://cyber-junkie.com/unusual-deal-2025/",
                                expect_type="movie", label="MOVIE unusual-deal"))
    if run_all or "dm_sacrifice" in cases:
        results.append(pipeline("Dutamovie21", "https://cyber-junkie.com/sacrifice-2026/",
                                expect_type="movie", label="MOVIE sacrifice (kasus device)"))
    if run_all or "dm_series" in cases:
        results.append(pipeline("Dutamovie21", "https://cyber-junkie.com/tv/ludwig-season-2-2026/",
                                expect_type="series", label="SERIES ludwig-s2"))
    if run_all or "thog" in cases:
        results.append(pipeline("Anichin", "https://anichin.cafe/seri/tales-of-herding-gods/",
                                expect_type="series", expect_ep96_dup=True, label="THOG ep96-dobel"))
    fails = sum(1 for r in results if r["verdict"]=="FAILED")
    print(f"\n{'#'*72}\nTOTAL PIPELINE: {len(results)-fails}/{len(results)} SUCCESS\n{'#'*72}")
    sys.exit(1 if fails else 0)
