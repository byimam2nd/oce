#!/usr/bin/env python3
import os
import re
import time
import requests
from bs4 import BeautifulSoup
from concurrent.futures import ThreadPoolExecutor, as_completed

# Try importing cloudscraper
try:
    import cloudscraper
    HAS_CLOUDSCRAPER = True
except ImportError:
    HAS_CLOUDSCRAPER = False

# --- CONFIGURATION ---
CONSTANTS_PATH = "BaseHtmlProvider/ProviderConstants.kt"
SEARCH_QUERY = "Soul"
ITEM_LIMIT = 5

TEST_TARGETS = [
    {"name": "Anichin", "url": "https://anichin.cafe/anime/soul-land-2-the-unrivaled-tang-sect/", "search": "https://anichin.cafe/?s={q}", "type": "Series", "force_requests": False},
    {"name": "Animasu", "url": "https://v1.animasu.top/anime/mairimashita-iruma-kun-season-4/", "search": "https://v1.animasu.top/?s={q}", "type": "Series", "force_requests": False},
    {"name": "Donghuastream", "url": "https://donghuastream.org/anime/battle-through-the-heavens-season-5th/", "search": "https://donghuastream.org/?s={q}", "type": "Series", "force_requests": False},
    {"name": "LayarKaca21", "url": "https://tv10.lk21official.cc/the-shadow-strays-2024/", "search": "https://tv10.lk21official.cc/search/?s={q}", "type": "Movie", "force_requests": False},
    {"name": "Samehadaku", "url": "https://v1.samehadaku.how/anime/mairimashita-iruma-kun-season-4/", "search": "https://v1.samehadaku.how/?s={q}", "type": "Series", "force_requests": True},
    {"name": "Pencurimovie", "url": "https://ww11.pencurimovie.sbs/the-super-mario-galaxy-movie-2026/", "search": "https://ww11.pencurimovie.sbs/?s={q}", "type": "Movie", "force_requests": False}
]

def extract_list(content, val_name):
    pattern = rf"val {val_name} = listOf\(\n(.*?)\n    \)"
    match = re.search(pattern, content, re.DOTALL)
    if not match: return []
    return [re.sub(r'//.*', '', x).strip().strip(',').strip('"') for x in match.group(1).split("\n") if x.strip()]

def get_bloat_pattern(content):
    match = re.search(r'val BLOAT_REGEX = Regex\(\s*"(.*?)"', content)
    return match.group(1) if match else r"(?i)\b(Nonton|Streaming)\b"

def fuzzy_deduplicate(text, main_title):
    """Menghapus main_title dari text meskipun ada perbedaan simbol/spasi."""
    if not main_title or main_title == "N/A": return text
    
    # Normalisasi (hanya huruf dan angka)
    def normalize(t): return re.sub(r"[^a-zA-Z0-9]", "", t).lower()
    
    n_main = normalize(main_title)
    n_text = normalize(text)
    
    if n_main in n_text:
        # Cari di mana main_title berada dalam text yang asli
        # Kita gunakan pendekatan substring yang lebih berhati-hati
        parts = re.split(r"[:\-\|—]", text)
        clean_parts = [p for p in parts if normalize(p) != n_main]
        if not clean_parts: # Jika semua bagian adalah duplikat judul
             return text # fallback ke aslinya daripada kosong
        return " ".join(clean_parts).strip()
    return text

def master_clean(text, pattern=None, is_title=False, is_url=False, main_title=None):
    if not text: return "N/A"
    if is_url: return text.strip()
    
    # 1. Brutal ASCII Filter
    text = text.encode('ascii', 'ignore').decode('ascii')
    
    # 2. Cleanup Newlines & Tabs
    text = re.sub(r"[\n\r\t]+", " ", text).strip()
    
    # 3. Label Stripping
    labels = ["Status:", "Status", "Genre:", "Genre", "Rating:", "Rating", "Updated on:", "Quality:", "Quality"]
    for label in labels:
        if text.lower().startswith(label.lower()):
            text = text[len(label):].strip()

    # 4. Heavy Title Scrubbing
    if is_title and pattern:
        try:
            # Recursive pass
            for _ in range(2): text = re.sub(pattern, "", text)
            # Remove leftovers
            text = re.sub(r'[|()\[\]]', "", text)
            # Deduplicate
            if main_title: text = fuzzy_deduplicate(text, main_title)
        except: pass
    
    # 5. Synopsis Scrubber
    elif not is_title:
        text = re.sub(r"(?i)\s*(update cepat tanpa iklan|pop-up yang mengganggu|download anime lengkap|nonton streaming|di website).*$", "", text).strip()

    # 6. Global Trim
    text = re.sub(r"^[ :\-\|—\.\s]+|[ :\-\|—\.\s]+$", "", text).strip()
    return " ".join(text.split()).strip()

def validate_field(soup, selectors, label, is_list=False, attr_list=None):
    if is_list:
        for selector in selectors:
            try:
                res = soup.select(selector)
                if res: return True, selector, res[:ITEM_LIMIT]
            except: continue
        return False, None, []

    for selector in selectors:
        try:
            if "meta[" in selector:
                n, v = selector.split("[")[1].replace("]", "").split("=")
                res = soup.find("meta", {n: v.strip('"').strip("'")})
                if res:
                    val = res.get("content") or res.text
                    if val: return True, selector, val
            elif ":contains" in selector:
                base_part = selector.split(":contains(")[0]
                inner_target = selector.split(":contains(")[1].split(")")[0].strip()
                suffix = selector.split(")")[-1].strip()
                for el in soup.select(base_part):
                    if inner_target.lower() in el.text.lower():
                        if suffix:
                            child = el.select_one(suffix)
                            if child: return True, selector, child.text.strip()
                        return True, selector, el.text.strip()
            else:
                res = soup.select_one(selector)
                if res:
                    val = None
                    if attr_list:
                        for a in attr_list:
                            v = res.get(a)
                            if v and (v.startswith("http") or v.startswith("//")): val = v; break
                    if not val: val = res.text.strip()
                    return True, selector, val
        except: continue
    return False, None, "NOT FOUND"

def get_session(target, headers):
    if HAS_CLOUDSCRAPER and not target.get("force_requests"):
        return cloudscraper.create_scraper(browser={'browser': 'chrome', 'platform': 'windows', 'mobile': False})
    s = requests.Session(); s.headers.update(headers)
    return s

def check_provider(target, config, headers, bloat_p):
    report = {"name": target['name'], "sections": {}}
    scraper = get_session(target, headers)
    
    try:
        # 1. SEARCH PAGE
        s_resp = scraper.get(target['search'].replace("{q}", SEARCH_QUERY), timeout=15)
        s_soup = BeautifulSoup(s_resp.text, "html.parser")
        ok, sel, items = validate_field(s_soup, config['SEARCH_ITEMS'], "Search", is_list=True)
        search_data = []
        if ok:
            for item in items:
                ok_st, sel_st, val_st = validate_field(item, config['SEARCH_TITLE'], "SearchTitle")
                text = val_st if ok_st else item.text
                search_data.append(master_clean(text, bloat_p, True)[:85])
        report["sections"]["Search Results"] = {"ok": ok, "selector": sel, "data": search_data}

        # 2. DETAIL PAGE
        time.sleep(0.5)
        d_resp = scraper.get(target['url'], timeout=25)
        d_soup = BeautifulSoup(d_resp.text, "html.parser")
        
        load_fields = [
            ("Title", "LOAD_TITLE"), ("Poster", "LOAD_POSTER"), ("Desc", "LOAD_DESC"),
            ("Tags", "LOAD_TAGS"), ("Rating", "LOAD_RATING"), ("Status", "LOAD_STATUS"),
            ("Quality", "LOAD_QUALITY"), ("Trailer", "LOAD_TRAILER")
        ]
        
        report["sections"]["Metadata"] = {}
        main_title_raw = "N/A"
        for label, key in load_fields:
            attr = config['ATTR_IMAGE'] if label in ["Poster", "Trailer"] else None
            if label == "Tags":
                ok, sel, tags = validate_field(d_soup, config[key], label, is_list=True)
                tags_text = [t.get("content") or t.text.strip() for t in tags if (t.get("content") or t.text).strip().replace(",", "")]
                final_val = master_clean(", ".join(tags_text)) if tags_text else "NOT FOUND"
            else:
                ok, sel, val = validate_field(d_soup, config[key], label, attr_list=attr)
                is_url = label in ["Poster", "Trailer"]
                is_title = (label == "Title")
                if is_title: main_title_raw = val
                
                # Logic Quality Scrubber (Don't allow "Ongoing" in Quality)
                if label == "Quality" and ("ongoing" in str(val).lower() or "tayang" in str(val).lower()):
                    final_val = "NOT FOUND"
                else:
                    final_val = master_clean(val, bloat_p, is_title, is_url)
            
            report["sections"]["Metadata"][label] = {"ok": ok, "selector": sel, "val": final_val}

        # 3. EPISODES
        if target['type'] == "Series":
            ok_ep, sel_ep, eps = validate_field(d_soup, config['EPISODES'], "Episodes", is_list=True)
            ep_data = []
            if ok_ep:
                for ep in eps:
                    ok_n, _, val_n = validate_field(ep, config['EPISODE_NUM'], "EpNum")
                    ok_t, _, val_t = validate_field(ep, config['EPISODE_TITLE'], "EpTitle")
                    
                    num_text = f"Episode {val_n}" if ok_n else ""
                    title_text = val_t if ok_t else ""
                    
                    if num_text.lower() in title_text.lower() or str(val_n) in title_text:
                        full_ep_text = title_text
                    else:
                        full_ep_text = f"{num_text} {title_text}".strip()
                    
                    if not full_ep_text: full_ep_text = ep.text
                    final_ep = master_clean(full_ep_text, bloat_p, True, main_title=main_title_raw)
                    ep_data.append(final_ep)
            report["sections"]["Episodes"] = {"ok": ok_ep, "selector": sel_ep, "data": ep_data}
            
        return report
    except Exception as e:
        return {"name": target['name'], "error": str(e)}

def run_scrub_audit():
    with open(CONSTANTS_PATH, "r") as f: content = f.read()
    
    config = {k: extract_list(content, k) for k in [
        'SEARCH_ITEMS', 'SEARCH_TITLE', 'LOAD_TITLE', 'LOAD_POSTER', 'LOAD_DESC', 'LOAD_TAGS', 
        'LOAD_RATING', 'LOAD_STATUS', 'LOAD_QUALITY', 'LOAD_TRAILER', 
        'EPISODE_ITEMS', 'EPISODE_TITLE', 'EPISODE_NUM', 'ATTR_IMAGE'
    ]}
    config['EPISODES'] = config['EPISODE_ITEMS']
    
    bloat_p = get_bloat_pattern(content)
    ua = re.search(r'"User-Agent" to "(.*?)"', content).group(1)
    headers = {"User-Agent": ua}

    print("\nULTRA PRECISION AUDIT REPORT | Nuclear Edition v4.3 Final")
    print(f"QUERY: {SEARCH_QUERY} | Target: 6 Providers | Focus: Diamond Clean\n")

    with ThreadPoolExecutor(max_workers=6) as executor:
        futures = {executor.submit(check_provider, target, config, headers, bloat_p): target['name'] for target in TEST_TARGETS}
        count = 0
        for f in as_completed(futures):
            count += 1
            rep = f.result()
            print(f"[{count}/6] PROCESSED: {rep['name'].upper()}")
            if "error" in rep:
                print(f"ERROR: {rep['error']}\n"); continue
            
            s = rep["sections"].get("Search Results")
            print(f"SEARCH RESULTS ({'OK' if s['ok'] else 'FAIL'}):")
            if s['ok']:
                print(f"selector: {s['selector']}")
                for i, item in enumerate(s['data'], 1): print(f"-Result {i}: {item}")
            print()

            print("METADATA:")
            for label, data in rep["sections"]["Metadata"].items():
                print(f"selector: {data['selector'] if data['ok'] else 'NONE'}")
                print(f"-{label}: {data['val']}")
                print()

            if "Episodes" in rep["sections"]:
                e = rep["sections"]["Episodes"]
                print(f"EPISODES ({'OK' if e['ok'] else 'FAIL'}):")
                if e['ok']:
                    print(f"selector: {e['selector']}")
                    for i, item in enumerate(e['data'], 1): print(f"-Ep {i}: {item}")
                else: print("selector: NONE")
                print()
            print("-" * 50 + "\n")

    print("AUDIT COMPLETE")

if __name__ == "__main__":
    run_scrub_audit()