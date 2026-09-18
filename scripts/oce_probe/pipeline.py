"""Ports of the page-scraping pipeline.

  - ProviderScrapper.kt    (getMainPage / search / loadLinks)
  - DetailPageScrapper.kt  (loadRecursive)
  - FallbackPipeline.kt    (processLink / tryManualIframeFetch)
  - network/HttpClient.kt  (fetchDocument) entry wrapper

Plus the probe orchestrator that drives homepage -> search -> load -> links
stages and classifies every outcome for the report matrix.
"""

import json
import re
import time
from dataclasses import dataclass, field
from urllib.parse import urlparse

from .collector import LinkCollector
from .extractor import LinkResult, create_smart_link, load_extractor_with_fallback
from .mapper import TV_ANIME, TV_MOVIE, TV_SERIES, MetadataPackage, ProviderMapper, SearchItem
from .network import FetchError, HttpClient
from .registry import fix_known_domain_aliases, is_direct_media_url
from .selectors import (
    FieldType,
    fix_url_smart,
    jsoup_text,
    parse_html,
    safe_decode,
    safe_extract_image,
    safe_is_base64,
)
from .verify import HeaderProbe


# ── outcome classification ──


def classify_fetch_error(e, url=""):
    if isinstance(e, FetchError):
        if e.kind == "cf":
            return "cf-blocked", f"Cloudflare challenge at {e.host or url}"
        if e.kind == "timeout":
            return "network-blip", f"timeout fetching {e.url or url}"
        if e.kind == "http":
            if e.status in (404, 410, 451):
                return "content-removed", f"HTTP {e.status} at {e.url or url}"
            if e.status == 403:
                return "cf-blocked", f"HTTP 403 (possible CF) at {e.url or url}"
            if e.status and e.status >= 500:
                return "host-down", f"HTTP {e.status} at {e.url or url}"
            return "host-down", f"HTTP {e.status} at {e.url or url}"
        return "network-blip", str(e)
    return "network-blip", str(e)


@dataclass
class StageResult:
    stage: str
    ok: bool = False
    count: int = 0
    reason: str = ""
    detail: str = ""
    samples: list = field(default_factory=list)
    links: list = field(default_factory=list)
    extra: dict = field(default_factory=dict)


@dataclass
class ProviderResult:
    provider_id: str = ""
    provider_name: str = ""
    main_url: str = ""
    stages: dict = field(default_factory=dict)
    elapsed_s: float = 0.0

    def ok(self):
        for s in self.stages.values():
            if s.stage in ("load", "links", "search", "homepage") and s.ok:
                return True
        return False


# ── fetchDocument wrapper ──


def fetch_document(config, http, url, referer=None):
    page = http.fetch_document(url, config, referer=referer)
    return parse_html(page.text)


# ── ProviderScrapper / DetailPageScrapper / FallbackPipeline ports ──


class Scraper:
    def __init__(self, config, http, probe, registry, enable_relocate=False):
        self.config = config
        self.http = http
        self.probe = probe
        self.registry = registry
        from .selectors import SelectorResolver
        self.resolver = SelectorResolver(provider_id=config.id,
                                         enable_relocate=enable_relocate)
        self.mapper = ProviderMapper(config, self.resolver)
        self.link_collector = LinkCollector(config, self.resolver, http)
        self._priority_links = []

    # ── homepage ──

    def get_main_page(self, page, name, data):
        cfg = self.config
        if cfg.seriesKeyword and cfg.seriesKeyword.lower() in (name or "").lower():
            base_url = (cfg.seriesUrl or cfg.mainUrl) if cfg.seriesUrl else cfg.mainUrl
        else:
            base_url = cfg.mainUrl
        if data.startswith("http"):
            d = data.replace("{page}", str(page))
            page_pattern = re.compile(r"(/page/|page=)%d(\b|/|$)" % page)
            if not page_pattern.search(d):
                if d.endswith("/page/"):
                    url = f"{d}{page}"
                else:
                    conn = "&" if "?" in d else "?"
                    url = f"{d}{conn}page={page}"
            else:
                url = d
        else:
            url = (cfg.mainPagePathPattern
                   .replace("{baseUrl}", base_url)
                   .replace("{data}", data)
                   .replace("{page}", str(page)))
        try:
            doc = fetch_document(cfg, self.http, url)
        except FetchError as e:
            status, reason = classify_fetch_error(e, url)
            return [], False, status, reason
        items = []
        if cfg.searchItems:
            els = self.resolver.select(doc, cfg.searchItems, f"{cfg.id}:searchItems")
            for el in els:
                it = self.mapper.to_search_result(el, url)
                if it is not None:
                    items.append(it)
        seen = set()
        dedup = []
        for it in items:
            if it.url and it.url not in seen:
                seen.add(it.url)
                dedup.append(it)
        return dedup, bool(dedup), "ok", ""

    # ── search ──

    def search(self, query, page=1):
        cfg = self.config
        encoded = _urlencode(query)
        base_url = cfg.searchUrl or cfg.mainUrl
        refer = cfg.mainUrl
        if cfg.isJsonSearch:
            url = (cfg.searchPathPattern
                   .replace("{baseUrl}", base_url)
                   .replace("{query}", encoded)
                   .replace("{page}", "1"))
            try:
                resp = self.http.get(url, referer=refer, headers=cfg.globalHeaders)
                root = json.loads(resp.text or "{}")
                items = root.get(cfg.searchJsonRoot or "data", [])
                if not isinstance(items, list):
                    return [], "ok", ""
                results = []
                for item in items:
                    if not isinstance(item, dict):
                        continue
                    title = safe_clean_bloat_str(item.get(cfg.searchJsonTitle, ""),
                                                 cfg.bloatRegexCompiled)
                    slug = str(item.get(cfg.searchJsonHref, "") or "")
                    p_url = str(item.get(cfg.searchJsonPoster, "") or "")
                    if not p_url.startswith("http") and cfg.searchJsonPosterPrefix:
                        p_url = cfg.searchJsonPosterPrefix + p_url
                    type_str = str(item.get(cfg.searchJsonType, "") or "")
                    is_tv = "series" in type_str.lower() or "tv" in type_str.lower()
                    final_url = f"{cfg.seriesUrl or base_url}/{slug}" if is_tv \
                        else f"{cfg.mainUrl}/{slug}"
                    results.append(SearchItem(
                        url=final_url, name=title, poster_url=p_url,
                        type=TV_ANIME if is_tv else TV_MOVIE))
                return results, "ok", ""
            except Exception as e:
                return [], "network-blip", f"JSON search failed: {e}"
        url = (cfg.searchPathPattern
               .replace("{baseUrl}", base_url)
               .replace("{page}", str(page))
               .replace("{query}", encoded))
        try:
            doc = fetch_document(cfg, self.http, url, referer=refer)
        except FetchError as e:
            status, reason = classify_fetch_error(e, url)
            return [], status, reason
        items = []
        if cfg.searchItems:
            els = self.resolver.select(doc, cfg.searchItems, f"{cfg.id}:searchItems")
            for el in els:
                it = self.mapper.to_search_result(el, url)
                if it is not None:
                    items.append(it)
        seen = set()
        dedup = []
        for it in items:
            if it.url and it.url not in seen:
                seen.add(it.url)
                dedup.append(it)
        return dedup, "ok", ""

    # ── detail page (load) ──

    def load(self, url):
        return self._load_recursive(url, 0)

    def _load_recursive(self, url, depth):
        cfg = self.config
        try:
            doc = fetch_document(cfg, self.http, url, referer=cfg.mainUrl)
        except FetchError as e:
            status, reason = classify_fetch_error(e, url)
            return None, status, reason
        current_url = url
        key = cfg.id

        title_present = False
        if cfg.loadTitle:
            v = self.resolver.text_validated(doc, cfg.loadTitle, f"{key}:loadTitle",
                                             FieldType.TITLE)
            title_present = v is not None
        poster_present = False
        if cfg.loadPoster:
            el = self.resolver.select_validated(
                doc, cfg.loadPoster, f"{key}:loadPoster", FieldType.POSTER,
                lambda it: safe_extract_image(it, cfg.attrImage))
            poster_present = el is not None
        ep_items = self.resolver.select(doc, cfg.episodeItems, f"{key}:episodeItems") \
            if cfg.episodeItems else []
        episode_links = []
        if not ep_items and any(t != TV_MOVIE for t in cfg.supportedTypes):
            episode_links = self.resolver.detect_episode_links(doc, current_url)
        effective_ep_items = ep_items if ep_items else episode_links

        if depth < 2 and cfg.followLinkSelector:
            needs_follow = (not title_present) or (not poster_present)
            ep_hints = bool(effective_ep_items)
            missing_data = needs_follow or not ep_hints
            next_anchor = self.resolver.select_first(
                doc, cfg.followLinkSelector, f"{key}:followLinkSelector") \
                if missing_data else None
            next_href = next_anchor.get("href", "") if next_anchor is not None else ""
            if next_href and not next_href.lower().startswith("javascript:"):
                next_url = fix_url_smart(next_href, current_url)
                if next_url and next_url != current_url and next_url != url:
                    return self._load_recursive(next_url, depth + 1)

        metadata = self.mapper.extract_metadata(doc, current_url)
        season_script = self.resolver.select_first(doc, cfg.seasonContainer, f"{key}:seasonContainer") \
            if cfg.seasonContainer else None
        has_tv_path = bool(cfg.tvPathSegment) and cfg.tvPathSegment in current_url
        url_looks_tv = any(h in current_url.lower() for h in
                           ("/tv/", "/series/", "/anime/", "/drama/", "/episode/", "/eps/"))
        is_movie = (season_script is None) and (not has_tv_path) and (not url_looks_tv) and (
            (bool(cfg.moviePathSegment) and cfg.moviePathSegment in current_url)
            or (not effective_ep_items)
        )
        item_type = TV_MOVIE if is_movie else (
            TV_ANIME if TV_ANIME in cfg.supportedTypes else TV_SERIES)

        recommendations = []
        if cfg.loadRecommend:
            for el in self.resolver.select(doc, cfg.loadRecommend, f"{key}:loadRecommend"):
                it = self.mapper.to_search_result(el, current_url)
                if it is not None:
                    recommendations.append(it)
        actors = []
        if cfg.actorItems and cfg.actorName:
            for el in self.resolver.select(doc, cfg.actorItems, f"{key}:actorItems"):
                n_el = self.resolver.select_first(el, cfg.actorName, f"{key}:actorName")
                name = jsoup_text(n_el).strip() if n_el is not None else ""
                img = el.select_one("img") if el else None
                p = safe_extract_image(img, cfg.attrImage) if img is not None else ""
                if name and len(name) < 100:
                    actors.append((name, p))
        episodes = []
        if not is_movie:
            episodes = self.mapper.extract_episodes(
                doc, current_url, season_script, effective_ep_items, metadata.poster)

        return LoadedPage(
            title=metadata.title, url=current_url, poster=metadata.poster,
            description=metadata.description, year=metadata.year,
            is_movie=is_movie, item_type=item_type, tags=metadata.tags,
            episodes=episodes, recommendations=recommendations, actors=actors,
            metadata=metadata), "ok", ""

    # ── load links (episode/movie player page) ──

    def load_links(self, data, concurrency=1, no_deepscan=False):
        cfg = self.config
        try:
            doc = fetch_document(cfg, self.http, data, referer=cfg.mainUrl)
        except FetchError as e:
            status, reason = classify_fetch_error(e, data)
            return [], status, reason, {}
        current_url = data
        links = set()
        self.link_collector.collect_ajax_players(doc, current_url, links)
        self.link_collector.collect_link_options(doc, links)
        self.link_collector.collect_download_items(doc, links)
        self.link_collector.collect_switch_video_buttons(doc, current_url, links)
        self.link_collector.collect_iframes(doc, links)
        if not links:
            return [], "logic-broke", "no media links or iframes found on page", {}
        pending = sorted(
            [(raw, label) for (raw, label) in links if raw and not raw.startswith("#")],
            key=lambda pair: -_priority_of(pair[0]))

        fp = FallbackPipeline(cfg, self.http, self.probe, self.registry, self.resolver)
        outcomes = []
        if concurrency <= 1:
            for raw, label in pending:
                outcomes.append(fp.process_link(raw, label, current_url,
                                                no_deepscan=no_deepscan))
        else:
            from concurrent.futures import ThreadPoolExecutor
            with ThreadPoolExecutor(max_workers=concurrency) as pool:
                futures = [pool.submit(fp.process_link, raw, label, current_url,
                                       no_deepscan=no_deepscan)
                           for raw, label in pending]
                for f in futures:
                    outcomes.append(f.result())
        all_links = []
        summary = {}
        for o in outcomes:
            all_links.extend(o.links)
            key = o.status
            summary[key] = summary.get(key, 0) + 1
        if not all_links and summary:
            top = max(summary, key=summary.get)
            return [], top, " | ".join(f"{k}:{v}" for k, v in summary.items()), summary
        return all_links, "ok" if all_links else "empty", "", summary


@dataclass
class LoadedPage:
    title: str = ""
    url: str = ""
    poster: str = ""
    description: str = ""
    year: int = 0
    is_movie: bool = False
    item_type: str = TV_SERIES
    tags: list = field(default_factory=list)
    episodes: list = field(default_factory=list)
    recommendations: list = field(default_factory=list)
    actors: list = field(default_factory=list)
    metadata: MetadataPackage = field(default_factory=MetadataPackage)


class FallbackPipeline:
    def __init__(self, config, http, probe, registry, resolver):
        self.config = config
        self.http = http
        self.probe = probe
        self.registry = registry
        self.resolver = resolver

    def process_link(self, raw, label, current_url, no_deepscan=False):
        decoded = self._decode_raw_link(raw)
        fixed = fix_url_smart(decoded, current_url)
        fixed = _httpsify(fixed)
        fixed = fixed.split("#")[0]
        fixed = fix_known_domain_aliases(fixed)
        if not fixed:
            return LinkResult(ok=False, status="empty",
                              detail=f"INVALID_URL: {raw}", chain="decode")
        host = ""
        try:
            host = (urlparse(fixed).hostname or "").lower()
        except Exception:
            pass
        for h in self.config.skipHosts:
            if not h:
                continue
            hl = h.lower()
            if host == hl or host.endswith("." + hl):
                return LinkResult(ok=False, status="empty",
                                  detail=f"skipped host {host}", chain="skipHosts")

        def bucket(result):
            return result

        partial = []
        load_extractor_with_fallback(self.http, self.probe, self.registry,
                                     fixed, current_url,
                                     lambda r: partial.append(r),
                                     headers=self.config.globalHeaders,
                                     chain=label or "")
        outcome = partial[-1] if partial else LinkResult(ok=False, status="empty",
                                                         detail="no outcome")
        if outcome.ok:
            return outcome
        if self.registry.has_matching(fixed):
            outcome.detail = (outcome.detail or "") + "; manual iframe skipped (extractor tried)"
            return outcome
        # manual iframe fetch
        manual = self._try_manual_iframe(fixed, label, current_url, no_deepscan)
        if manual.ok:
            return manual
        if not outcome.ok and outcome.status == "empty":
            return manual
        return outcome

    def _try_manual_iframe(self, fixed_url, label, current_url, no_deepscan=False):
        cfg = self.config
        base_for_referer = cfg.seriesUrl or cfg.mainUrl
        if cfg.refererPlayerMode == "series_url":
            referer_for_player = f"{base_for_referer.rstrip('/')}/"
        else:
            referer_for_player = current_url
        try:
            player_doc = fetch_document(cfg, self.http, fixed_url,
                                        referer=referer_for_player)
        except FetchError as e:
            status, reason = classify_fetch_error(e, fixed_url)
            return LinkResult(ok=False, status=status, detail=reason,
                              chain="manual-iframe")
        if cfg.iframeSelectors:
            iframe_el = self.resolver.select_first(player_doc, cfg.iframeSelectors,
                                                   f"{cfg.id}:iframeSelectors")
        else:
            iframe_el = None
        if iframe_el is None:
            return LinkResult(ok=False, status="empty",
                              detail=f"no iframe found at {fixed_url}",
                              chain="manual-iframe")
        iframe_src = None
        for attr in cfg.iframeSources:
            v = iframe_el.get(attr, "") or ""
            if v and v != "about:blank":
                iframe_src = v
                break
        if iframe_src is None:
            return LinkResult(ok=False, status="empty",
                              detail=f"iframe has no src at {fixed_url}",
                              chain="manual-iframe")
        final_iframe = fix_url_smart(iframe_src, fixed_url)
        referer_for_extractor = get_base_url(fixed_url)
        partial = []
        load_extractor_with_fallback(self.http, self.probe, self.registry,
                                     final_iframe, referer_for_extractor,
                                     lambda r: partial.append(r),
                                     headers=cfg.globalHeaders)
        outcome = partial[-1] if partial else LinkResult(ok=False, status="empty",
                                                         detail="iframe no links")
        if not outcome.ok and is_direct_media_url(final_iframe):
            links = create_smart_link(self.http, self.probe,
                                      label or cfg.name, final_iframe,
                                      referer_for_extractor,
                                      headers=cfg.globalHeaders)
            if links:
                return LinkResult(ok=True, links=links, status="ok",
                                  chain=f"manual-iframe:{label or ''}")
        return outcome

    def _decode_raw_link(self, raw):
        if raw.startswith("http") or raw.startswith("//") or raw.startswith("/") \
                or not safe_is_base64(raw):
            return raw
        lk21 = _decrypt_lk21(raw)  # not replicable -> None
        if lk21:
            return lk21
        dec = safe_decode(raw)
        if "iframe" in dec:
            doc = parse_html(dec)
            iframe = doc.select_one("iframe")
            return iframe.get("src", "") if iframe is not None else ""
        if dec.startswith("http") or dec.startswith("//") or dec.startswith("/"):
            return dec
        return ""

    def analyze_extractor_links(self, limit=5):
        """Analisis streaming URLs dari extractor provider.
        
        Jalankan pipeline extractor → dapat episode URLs → test HTTP HEAD →
        tentukan: status, content-type, HLS/direct, kecepatan.
        Returns (results_list, status, reason, summary).
        """
        cfg = self.config
        start = time.monotonic()

        scraper = Scraper(cfg, self.http, self.probe, self.registry)
        all_links = []
        reasons = []

        # Homepage → ambil items
        home_items = []
        for data, name in cfg.mainPageLists:
            items, _, status, reason = scraper.get_main_page(1, name, data)
            if status == "ok":
                home_items.extend(items[:2])
            else:
                reasons.append(f"homepage:{name}:{reason}")

        if not home_items:
            return [], "empty", "no homepage items", {"reasons": reasons}

        # Load series pages → dapat episode URLs
        episode_urls = []
        for item in home_items[:limit]:
            url = item.url or ""
            if not url:
                continue
            if not url.startswith("http"):
                url = cfg.mainUrl.rstrip("/") + "/" + url.lstrip("/")
            
            page, status, reason = scraper.load(url)
            if status == "ok" and page:
                # Dapatkan episode URLs dari LoadedPage
                for ep in page.episodes:
                    ep_url = ep.url or ""
                    if ep_url and not ep_url.startswith("http"):
                        ep_url = cfg.mainUrl.rstrip("/") + "/" + ep_url.lstrip("/")
                    if ep_url:
                        episode_urls.append((item.name, ep_url))
            elif status != "ok":
                reasons.append(f"load:{item.name}:{reason}")

        if not episode_urls:
            return [], "empty", "no episode URLs found", {"loaded": len(home_items), "reasons": reasons}

        # Load episode pages → collect streaming URLs
        loaded_count = 0
        for title, ep_url in episode_urls[:limit * 3]:
            links, status, reason, summary = scraper.load_links(ep_url, concurrency=1)
            if links:
                loaded_count += 1
                for link in links:
                    all_links.append({
                        "title": title,
                        "label": link.link_type,
                        "url": link.url,
                        "name": link.name,
                    })
            elif status != "ok":
                reasons.append(f"links:{title}:{reason}")

        if not all_links:
            return [], "empty", "no streaming links found", {
                "episodes_found": len(episode_urls),
                "episodes_loaded": loaded_count,
                "reasons": reasons
            }

        # Analisis setiap URL
        results = []
        seen_urls = set()
        for link_info in all_links[:limit * 5]:
            url = link_info["url"]
            if url in seen_urls:
                continue
            seen_urls.add(url)
            
            analysis = self._analyze_single_url(url, cfg.mainUrl)
            results.append({
                "title": link_info["title"],
                "label": link_info["label"],
                "url": url[:150],
                "name": link_info["name"],
                **analysis,
            })

        elapsed = time.monotonic() - start
        ok_count = sum(1 for r in results if r["status"] == 200)
        hls_count = sum(1 for r in results if r["is_hls"])
        direct_count = sum(1 for r in results if r["is_direct"])
        summary = {
            "total_links": len(results),
            "ok_200": ok_count,
            "hls": hls_count,
            "direct": direct_count,
            "errors": len(results) - ok_count,
            "episodes_scanned": len(episode_urls),
            "elapsed_s": round(elapsed, 1),
        }
        reason = f"{ok_count}/{len(results)} ok, {hls_count} HLS, {direct_count} direct"
        return results, "ok" if ok_count > 0 else "empty", reason, summary

    def _analyze_single_url(self, url, referer):
        """HTTP HEAD untuk analisis satu URL: status, content-type, HLS, speed."""
        result = {
            "status": 0,
            "content_type": "",
            "is_hls": False,
            "is_direct": False,
            "response_ms": 0,
            "error": "",
        }
        try:
            import time as _time
            t0 = _time.monotonic()
            session = self.http._session()
            resp = session.head(
                url, headers={"Referer": referer},
                timeout=8, allow_redirects=True)
            elapsed_ms = int((_time.monotonic() - t0) * 1000)

            result["status"] = resp.status_code
            result["content_type"] = resp.headers.get("Content-Type", "")
            result["response_ms"] = elapsed_ms
            result["is_hls"] = "mpegurl" in result["content_type"].lower() or ".m3u8" in url.lower()
            result["is_direct"] = any(t in result["content_type"].lower()
                                      for t in ("video/", "application/octet"))
        except Exception as e:
            result["error"] = str(e)[:100]
        return result


def _decrypt_lk21(_encrypted):
    return None


def get_base_url(url):
    try:
        u = urlparse(url)
        return f"{u.scheme}://{u.hostname}"
    except Exception:
        return ""


def _priority_of(raw):
    u = raw.lower()
    if any(k in u for k in (".m3u8", ".mpd", "master", "playlist", ".ts")):
        return 100
    if any(k in u for k in (".mp4", ".webm", ".mkv", ".mov")):
        return 90
    if any(k in u for k in ("anichin.stream", "abyssplayer", "gdriveplayer",
                            "sibnet", "dailymotion")):
        return 80
    if any(k in u for k in ("youtube", "ok.ru", "rumble", "vimeo")):
        return 70
    if "short." in u:
        return 20
    return 50


def _httpsify(url):
    return f"https:{url}" if url.startswith("//") else url


def _urlencode(query):
    try:
        import urllib.parse
        return urllib.parse.quote(query, safe="")
    except Exception:
        return query


def safe_clean_bloat_str(text, regex):
    try:
        return regex.sub("", text).strip() if text else text
    except Exception:
        return text


# ── orchestrator ──


class Orchestrator:
    def __init__(self, config, http=None, probe=None, registry=None,
                 enable_relocate=False, no_deepscan=False, concurrency=1,
                 limit=3, stages=None):
        self.config = config
        self.http = http or HttpClient()
        self.probe = probe or HeaderProbe(self.http)
        self.registry = registry
        self.enable_relocate = enable_relocate
        self.no_deepscan = no_deepscan
        self.concurrency = concurrency
        self.limit = limit
        self.stages = stages or ["homepage", "search", "load", "links"]

    def _scraper(self):
        return Scraper(self.config, self.http, self.probe, self.registry,
                       enable_relocate=self.enable_relocate)

    def run(self):
        start = time.monotonic()
        result = ProviderResult(
            provider_id=self.config.id,
            provider_name=self.config.name,
            main_url=self.config.mainUrl)
        scraper = self._scraper()

        # ── homepage ──
        home_items = []
        if "homepage" in self.stages:
            sr = StageResult(stage="homepage")
            total = 0
            ok_lists = 0
            reasons = []
            for data, name in self.config.mainPageLists:
                items, has_next, status, reason = scraper.get_main_page(1, name, data)
                total += len(items)
                if status == "ok":
                    ok_lists += 1
                else:
                    reasons.append(f"{name}: {reason}")
                home_items.extend(items[:3])
            sr.count = total
            sr.ok = ok_lists > 0
            sr.reason = "ok" if sr.ok else (reasons[0] if reasons else "empty")
            sr.detail = f"{ok_lists}/{len(self.config.mainPageLists)} lists ok, {total} items"
            sr.samples = [it.name for it in home_items[:5]]
            result.stages["homepage"] = sr

        # ── search ──
        search_items = []
        if "search" in self.stages:
            sr = StageResult(stage="search")
            queries = []
            for it in home_items:
                if it.name:
                    word = it.name.split()[0] if it.name.split() else ""
                    if word and word not in queries:
                        queries.append(word)
                if len(queries) >= self.limit:
                    break
            if not queries:
                queries = ["over"]
            sr.extra["queries"] = queries
            total = 0
            ok_q = 0
            reasons = []
            for q in queries:
                items, status, reason = scraper.search(q)
                total += len(items)
                search_items.extend(items[:3])
                if status == "ok" and items:
                    ok_q += 1
                elif status != "ok":
                    reasons.append(f"{q}: {reason}")
            sr.count = total
            sr.ok = ok_q > 0
            sr.reason = "ok" if sr.ok else (reasons[0] if reasons else "empty")
            sr.detail = f"{ok_q}/{len(queries)} queries ok, {total} items"
            sr.samples = [it.name for it in search_items[:5]]
            result.stages["search"] = sr

        # ── load (detail) ──
        load_items = []
        if "load" in self.stages:
            sr = StageResult(stage="load")
            candidates = [it for it in search_items if it.url]
            if len(candidates) < self.limit:
                candidates.extend(it for it in home_items if it.url)
            seen = set()
            picked = []
            for it in candidates:
                if it.url in seen:
                    continue
                seen.add(it.url)
                picked.append(it)
                if len(picked) >= self.limit:
                    break
            ok_n = 0
            reasons = []
            for it in picked:
                loaded, status, reason = scraper.load(it.url)
                if loaded is not None:
                    ok_n += 1
                    load_items.append(loaded)
                    sr.samples.append(f"{loaded.title} ({loaded.item_type}, {len(loaded.episodes)} eps)")
                else:
                    reasons.append(f"{it.name}: {reason}")
            sr.count = len(picked)
            sr.ok = ok_n > 0
            sr.reason = "ok" if sr.ok else (reasons[0] if reasons else "empty")
            sr.detail = f"{ok_n}/{len(picked)} pages loaded"
            result.stages["load"] = sr

        # ── links (video extraction) ──
        if "links" in self.stages:
            sr = StageResult(stage="links")
            targets = []
            for lp in load_items:
                if lp.is_movie:
                    targets.append((lp.title, lp.url, TV_MOVIE))
                elif lp.episodes:
                    targets.append((lp.title, lp.episodes[0].url, TV_SERIES))
            if not targets:
                sr.reason = "skip"
                sr.detail = "no loaded target with a playable URL"
                result.stages["links"] = sr
            else:
                ok_n = 0
                reasons = []
                for title, url, kind in targets:
                    links, status, reason, summary = scraper.load_links(
                        url, concurrency=self.concurrency,
                        no_deepscan=self.no_deepscan)
                    if links:
                        ok_n += 1
                        sr.links.extend(links)
                        sr.samples.append(f"{title}: {len(links)} link(s)")
                    elif status != "ok":
                        reasons.append(f"{title}: {status} {reason}")
                sr.count = len(targets)
                sr.ok = ok_n > 0
                sr.reason = "ok" if sr.ok else (reasons[0] if reasons else "empty")
                sr.detail = f"{ok_n}/{len(targets)} targets produced video links"
                result.stages["links"] = sr

        result.elapsed_s = time.monotonic() - start
        return result

    def analyze_extractor_links(self, limit=5):
        fp = FallbackPipeline(self.config, self.http, self.probe, self.registry, None)
        return fp.analyze_extractor_links(limit=limit)