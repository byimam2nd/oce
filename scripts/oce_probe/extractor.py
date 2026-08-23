"""Port of the extractor engine: ConfigDrivenExtractor.kt, MasterLinkGenerator.kt,
CompiledRegexPatterns.kt, ExtractorFallback.kt.

Not replicable without Android/JS (faithfully classified as not-replicable):
  - rhinoEval step (needs Rhino JS engine)
  - webview step (needs Android WebView)
  - legacy Kotlin extractor classes (VideoplayerVip, Anonmp4, Odnoklassniki)
  - Lk21 player.js decryption (needs JS eval)
  - built-in CloudStream "global" extractor engine
"""

import json
import re
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import List, Optional

from .crypto import (
    DEFAULT_UA,
    aes_gcm_decrypt,
    decode_packed_js,
    decode_unicode_escapes,
    find_packed_js_in_page,
    sig_decode,
)
from .registry import (
    is_direct_media_url,
    normalize_domain,
    fix_known_domain_aliases,
)
from .verify import HeaderProbe, VerdictAllMalformed, VerdictValid, verify_master

QUALITY_STRIP_DEFAULT = re.compile(r"\d{3,4}p|HD|SD|FHD", re.IGNORECASE)

# ── ExtractorLink ──

M3U8 = "M3U8"
DASH = "DASH"
VIDEO = "VIDEO"


@dataclass
class ExtractorLink:
    source: str = ""
    name: str = ""
    url: str = ""
    referer: str = ""
    link_type: str = VIDEO
    headers: dict = field(default_factory=dict)
    quality: Optional[int] = None


@dataclass
class LinkResult:
    ok: bool = False
    links: List[ExtractorLink] = field(default_factory=list)
    status: str = "empty"      # ok|empty|not-replicable|cf|network|http|timeout
    detail: str = ""
    chain: str = ""


class NotReplicableError(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


# ── CompiledRegexPatterns.kt ──

UNIVERSAL_VIDEO_URL = re.compile(
    r""""([^"]*?\.(?:mp4|m3u8|mkv|mpd|webm|ts|mov)(?:\?[^"]*?)?)""")

MLG_QUALITY_1080 = re.compile(r"(1080|p1080|fhd|fullhd)", re.IGNORECASE)
MLG_QUALITY_720 = re.compile(r"(720|p720|hd)", re.IGNORECASE)
MLG_QUALITY_480 = re.compile(r"(480|p480|sd)", re.IGNORECASE)
MLG_QUALITY_360 = re.compile(r"(360|p360)", re.IGNORECASE)


def extract_all_video_urls(text):
    urls = set()
    for m in UNIVERSAL_VIDEO_URL.finditer(text or ""):
        url = m.group(1).replace("\\/", "/").strip()
        if url.startswith("http") or url.startswith("//"):
            urls.add(f"https:{url}" if url.startswith("//") else url)
    return urls


def filter_master_m3u8(urls):
    urls = list(urls)
    if not urls:
        return []
    m3u8s = [u for u in urls if ".m3u8" in u or ".mpd" in u]
    if not m3u8s:
        return urls
    masters = [u for u in m3u8s
               if "master" in u.lower() or "manifest" in u.lower() or "playlist" in u.lower()]
    if masters:
        return list(dict.fromkeys(masters))
    return [m3u8s[0]]


def prioritize_adaptive_urls(urls):
    urls = list(urls)
    if not urls:
        return []
    m3u8s = [u for u in urls if ".m3u8" in u.lower()]
    if m3u8s:
        masters = [u for u in m3u8s
                   if "master" in u.lower() or "manifest" in u.lower() or "playlist" in u.lower()]
        if masters:
            return list(dict.fromkeys(masters))
        return [m3u8s[0]]
    mpds = [u for u in urls if ".mpd" in u.lower()]
    if mpds:
        return list(dict.fromkeys(mpds))
    return list(dict.fromkeys(urls))


def detect_quality_from_url(url):
    low = (url or "").lower()
    if MLG_QUALITY_1080.search(low):
        return 1080
    if MLG_QUALITY_720.search(low):
        return 720
    if MLG_QUALITY_480.search(low):
        return 480
    if MLG_QUALITY_360.search(low):
        return 360
    return 480


MINIMAL_VIDEO_HEADERS = {
    "Accept": "*/*",
    "User-Agent": DEFAULT_UA,
}


def create_smart_link(http, probe, source, url, referer, quality=None,
                      headers=None, bare_headers=False,
                      quality_strip_regex=QUALITY_STRIP_DEFAULT):
    """Port of MasterLinkGenerator.createSmartLink. Returns list[ExtractorLink]."""
    if not url or not url.strip():
        return []
    url = url.strip()
    is_adaptive = ".m3u8" in url or ".mpd" in url
    safe_headers = headers if bare_headers and headers else MINIMAL_VIDEO_HEADERS
    if not bare_headers:
        safe_headers = headers if headers else MINIMAL_VIDEO_HEADERS

    effective_referer = referer
    effective_headers = safe_headers
    probe_body = None
    probe_body_truncated = False

    if bare_headers:
        decision = probe.resolve(url, referer, headers,
                                 capture_body=is_adaptive and ".m3u8" in url)
        if not decision.valid:
            return []
        effective_referer = decision.referer
        effective_headers = decision.headers
        probe_body = decision.captured_body
        probe_body_truncated = decision.body_truncated

    clean_name = quality_strip_regex.sub("", source).strip()

    if is_adaptive and ".m3u8" in url:
        if probe_body is not None and not probe_body_truncated:
            from .verify import parse_variants, classify
            verdict = classify(url, parse_variants(probe_body))
        else:
            verdict = verify_master(http, url, effective_referer, effective_headers)
        if isinstance(verdict, VerdictValid):
            out = []
            for variant_url, height in verdict.variants:
                out.append(ExtractorLink(
                    source=source, name=clean_name, url=variant_url,
                    link_type=M3U8, referer=effective_referer or "",
                    headers=effective_headers,
                    quality=height or detect_quality_from_url(variant_url)))
            return out
        if isinstance(verdict, VerdictAllMalformed):
            return []

    link_type = DASH if ".mpd" in url else (M3U8 if is_adaptive else VIDEO)
    q = quality if not is_adaptive else None
    return [ExtractorLink(
        source=source, name=clean_name, url=url, link_type=link_type,
        referer=effective_referer or "", headers=effective_headers,
        quality=(quality or detect_quality_from_url(url)) if not is_adaptive else None)]


# ── ConfigDrivenExtractor.kt port ──

class RunState:
    def __init__(self, url, referer, vid, variant):
        self.url = url
        self.referer = referer
        self.id = vid
        self.variant = variant
        self.variables = {}
        self.video_urls = set()

    def resolve_template(self, template):
        base = self.url
        try:
            after = self.url.split("://", 1)[1]
            base = self.url.split("://", 1)[0] + "://" + after.split("/", 1)[0]
        except Exception:
            pass
        out = (template
               .replace("{mainUrl}", self.variant_main_url)
               .replace("{url}", self.url)
               .replace("{base}", base)
               .replace("{id}", self.id or "")
               .replace("{referer}", self.referer or "")
               .replace("{ts}", str(int(time.time() * 1000))))
        for key, value in self.variables.items():
            out = out.replace("{" + key + "}", value)
        return out

    def resolve_headers(self, step_headers):
        merged = dict(self.variant.headers)
        merged.update(step_headers)
        if getattr(self.variant, "userAgent", "") and self.variant.userAgent.strip():
            merged["User-Agent"] = self.variant.userAgent
        return {k: self.resolve_template(v) for k, v in merged.items()}

    def resolve_referer(self, step_referer):
        tpl = step_referer or self.variant.referer
        if not tpl:
            return self.referer
        resolved = self.resolve_template(tpl)
        return resolved if resolved else None


def extract_id(url, id_source):
    if id_source is None:
        return None
    t = id_source.type
    if t == "query":
        m = re.search(r"[?&]" + re.escape(id_source.param) + r"=([^&]+)", url)
        return m.group(1) if m else None
    if t == "path":
        return url.rstrip("/").split("/")[-1].split("?")[0] or None
    if t == "regex":
        try:
            m = re.search(id_source.pattern, url)
            return m.group(id_source.group) if m else None
        except Exception:
            return None
    return None


def resolve_json_path(text, path):
    """Port of ConfigDrivenExtractor.resolveJsonPath / resolveJsonPathRecursive."""
    if not path or not text:
        return None
    try:
        root = json.loads(text)
    except Exception:
        return None
    return _resolve_recursive(root, path.split("."))


def _resolve_recursive(current, segments):
    if not segments:
        if isinstance(current, str):
            return current
        if isinstance(current, list):
            return current[0] if len(current) == 1 else current
        return current
    segment = segments[0]
    wild = re.match(r"^(\w+)\[\]$", segment)
    if wild:
        name = wild.group(1)
        children = None
        if isinstance(current, dict):
            v = current.get(name)
            if isinstance(v, list):
                children = v
            elif isinstance(v, dict):
                children = list(v.values())
        if children is None:
            return None
        results = []
        rest = segments[1:]
        for child in children:
            value = _resolve_recursive(child, list(rest))
            if value is not None:
                if isinstance(value, list):
                    results.extend(value)
                else:
                    results.append(value)
        if not results:
            return None
        if len(results) == 1 and isinstance(results[0], str):
            return results[0]
        return results
    array_idx = re.match(r"^(\w+)\[(\d+)\]$", segment)
    if array_idx:
        name = array_idx.group(1)
        idx = int(array_idx.group(2))
        if not isinstance(current, dict):
            return None
        v = current.get(name)
        if not isinstance(v, list) or idx >= len(v):
            return None
        return _resolve_recursive(v[idx], segments[1:])
    if isinstance(current, dict):
        v = current.get(segment)
        if isinstance(v, list) and len(v) == 1:
            v = v[0]
        if v is None:
            return None
        return _resolve_recursive(v, segments[1:])
    return None


def _opt_string_of(value):
    if isinstance(value, str):
        return value
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


class ConfigDrivenRunner:
    """Executes one ExtractorConfig against a URL; collects ExtractorLinks."""

    def __init__(self, config, http, probe, registry, outcome_collector):
        self.config = config
        self.http = http
        self.probe = probe
        self.registry = registry
        self.collect = outcome_collector  # callable(LinkResult)

    def run(self, url, referer):
        vid = extract_id(url, self.config.idSource)
        config = self.config
        for variant in config.variants:
            state = RunState(url, referer, vid, variant)
            state.variant_main_url = config.mainUrl
            try:
                for step in config.steps:
                    self._execute_step(step, state, url, referer)
                    if state.video_urls:
                        break
            except NotReplicableError as e:
                self.collect(LinkResult(
                    ok=False, status="not-replicable",
                    detail=f"{config.name}: {e.reason}", chain=config.name))
                return
            except Exception:
                continue
            if state.video_urls:
                links = self._deliver(state, config)
                self.collect(LinkResult(
                    ok=bool(links), links=links, status="ok" if links else "empty",
                    chain=config.name))
                return
        self.collect(LinkResult(
            ok=False, status="empty",
            detail=f"{config.name}: no links from any variant", chain=config.name))

    def _execute_step(self, step, state, url, referer):
        s = step
        stype = s.step
        if stype == "fetch":
            target = state.resolve_template(s.url)
            for frm, to in s.urlReplace.items():
                target = target.replace(frm, to)
            resp = self.http.get(target, headers=state.resolve_headers(s.headers),
                                 referer=state.resolve_referer(s.referer))
            state.variables[s.store] = resp.text
            if s.storeFinalUrl:
                state.variables[s.storeFinalUrl] = resp.url
        elif stype == "postForm":
            target = state.resolve_template(s.url)
            data = {k: state.resolve_template(v) for k, v in s.data.items()}
            resp = self.http.post_form(target, data, headers=state.resolve_headers(s.headers),
                                       referer=state.resolve_referer(s.referer))
            state.variables[s.store] = resp.text
        elif stype == "postJson":
            target = state.resolve_template(s.url)
            body = state.resolve_template(s.jsonBody)
            headers = state.resolve_headers(s.headers)
            headers["Content-Type"] = "application/json"
            resp = self.http.post_json(target, body, headers=headers,
                                       referer=state.resolve_referer(s.referer))
            state.variables[s.store] = resp.text
        elif stype == "regex":
            text = state.variables.get(s.source, "") or ""
            if s.universal:
                urls = extract_all_video_urls(text)
            else:
                urls = set()
                if s.pattern:
                    try:
                        urls = {m.group(s.group) for m in re.finditer(s.pattern, text)
                                if len(m.groups()) >= s.group}
                    except Exception:
                        urls = set()
            decoded = []
            for u in urls:
                u2 = u.replace("\\/", "/")
                if s.decodeUnicode:
                    u2 = decode_unicode_escapes(u2)
                decoded.append(u2)
            filtered = [u for u in decoded if not s.filter or s.filter in u]
            if s.store:
                if filtered:
                    state.variables[s.store] = filtered[0]
            else:
                state.video_urls.update(filtered)
        elif stype == "jsonPath":
            text = state.variables.get(s.source, "") or ""
            resolved = resolve_json_path(text, s.path)

            def emit(value):
                if not s.filter or s.filter in value:
                    if s.store:
                        state.variables[s.store] = value
                    else:
                        state.video_urls.add(value)

            if isinstance(resolved, str):
                if resolved.strip():
                    emit(resolved)
            elif isinstance(resolved, list):
                for v in resolved:
                    sv = _opt_string_of(v)
                    if sv.strip():
                        emit(sv)
                        if s.store and s.store in state.variables:
                            break
        elif stype == "constructUrl":
            built = state.resolve_template(s.template)
            if built.strip():
                if s.store:
                    state.variables[s.store] = built
                else:
                    state.video_urls.add(built)
        elif stype == "substring":
            text = state.variables.get(s.source, "") or ""
            start = text.find(s.startMarker)
            if start >= 0:
                frm = start + len(s.startMarker)
                end = text.find(s.endMarker, frm)
                if end >= 0:
                    value = text[frm:end]
                    if value.strip():
                        if s.store:
                            state.variables[s.store] = value
                        else:
                            state.video_urls.add(value)
        elif stype == "resolveUrl":
            base = state.resolve_template(s.base)
            from .selectors import fix_url_smart
            if s.source:
                raw = state.variables.get(s.source, "") or ""
                resolved = fix_url_smart(raw, base)
                resolved = resolved if resolved else raw
                if resolved.strip():
                    state.video_urls.add(resolved)
            else:
                resolved_list = []
                for u in state.video_urls:
                    r = fix_url_smart(u, base)
                    resolved_list.append(r if r else u)
                state.video_urls.clear()
                state.video_urls.update(u for u in resolved_list if u.strip())
        elif stype == "packedJs":
            text = state.variables.get(s.source, "") or ""
            found = find_packed_js_in_page(text)
            decoded = decode_packed_js(*found) if found else text
            state.variables[s.store] = decoded
        elif stype == "aesGcm":
            text = state.variables.get(s.source, "") or ""
            key_parts = resolve_json_path(text, s.keyPartsPath)
            if not isinstance(key_parts, list):
                key_parts = []
            iv = resolve_json_path(text, s.ivPath)
            payload = resolve_json_path(text, s.payloadPath)
            iv = iv if isinstance(iv, str) else ""
            payload = payload if isinstance(payload, str) else ""
            decrypted = aes_gcm_decrypt(key_parts, iv, payload)
            state.variables[s.store] = decrypted
        elif stype == "rhinoEval":
            raise NotReplicableError(f"rhinoEval step ({self.config.name})")
        elif stype == "xorSig":
            val = state.variables.get(s.source, "") or ""
            decoded = sig_decode(val)
            if decoded.strip():
                if s.store:
                    state.variables[s.store] = decoded
                else:
                    state.video_urls.add(decoded)
        elif stype == "delegate":
            target = state.resolve_template(s.url)
            resolved = target
            if s.queryParam:
                try:
                    raw = target.split(f"?{s.queryParam}=", 1)[1].split("&", 1)[0]
                    resolved = urllib.parse.unquote(raw)
                except Exception:
                    resolved = ""
            if resolved.startswith("http"):
                load_extractor_with_fallback(
                    self.http, self.probe, self.registry, resolved,
                    state.url, self.collect)
        elif stype == "iframe":
            html = state.variables.get(s.source, "") or ""
            base = state.resolve_template(s.base)
            include_re = re.compile(s.include) if s.include else None
            from .selectors import parse_html, fix_url_smart
            try:
                doc = parse_html(html)
                for el in doc.select(s.selector):
                    src = el.get(s.attribute, "") or ""
                    if not src:
                        continue
                    if s.exclude and s.exclude in src:
                        continue
                    if include_re is not None and not include_re.search(src):
                        continue
                    resolved = fix_url_smart(src, base)
                    if resolved.strip():
                        load_extractor_with_fallback(
                            self.http, self.probe, self.registry, resolved,
                            state.url, self.collect)
            except Exception:
                pass
        elif stype == "redirect":
            final_url = state.variables.get(s.source, "") or ""
            original = state.resolve_template(s.url)
            if final_url.strip() and final_url != original:
                load_extractor_with_fallback(
                    self.http, self.probe, self.registry, final_url,
                    state.url, self.collect)
        elif stype == "webview":
            raise NotReplicableError(f"webview step ({self.config.name})")
        else:
            raise NotReplicableError(f"unknown step '{stype}' ({self.config.name})")

    def _deliver(self, state, config):
        urls = set(state.video_urls)
        if config.outputFilter == "master":
            ordered = filter_master_m3u8(urls)
        elif config.outputFilter == "none":
            ordered = list(urls)
        else:
            ordered = prioritize_adaptive_urls(urls)
        video_ref = state.resolve_template(config.videoReferer)
        video_ref = video_ref if video_ref.strip() else None
        out = []
        for u in ordered:
            out.extend(create_smart_link(
                self.http, self.probe, config.name, u, video_ref,
                headers=MINIMAL_VIDEO_HEADERS, bare_headers=True))
        return out


# ── ExtractorFallback.kt port ──

def run_matching_extractors(http, probe, registry, url, referer, collect):
    """Port of the local-extractor block: run matching config extractors."""
    matches = registry.matching(url)
    for entry in matches:
        if entry.kind == "legacy":
            collect(LinkResult(
                ok=False, status="not-replicable",
                detail=f"legacy Kotlin extractor {entry.id}", chain=entry.id))
            continue
        ConfigDrivenRunner(entry.config, http, probe, registry, collect).run(url, referer)


def load_extractor_with_fallback(http, probe, registry, url, referer, collect,
                                 headers=None, chain=""):
    """Port of loadExtractorWithFallbackCustom (probe subset).

    Order: matching local extractors -> direct media -> deep scan.
    Global built-in extractor engine is not replicable (classified when used).
    """
    outcome = LinkResult()
    partial = []

    def bucket(result):
        partial.append(result)

    matches = registry.matching(url)
    if not matches:
        outcome.detail = "no matching extractor; global engine (not replicated)"
    else:
        run_matching_extractors(http, probe, registry, url, referer, bucket)

    if any(r.links for r in partial):
        outcome.ok = True
        outcome.links = [l for r in partial for l in r.links]
        outcome.status = "ok"
        outcome.chain = chain or "|".join(r.chain for r in partial if r.chain)
        collect(outcome)
        return outcome

    if is_direct_media_url(url):
        links = create_smart_link(http, probe, "Direct", url, None,
                                  headers=headers, bare_headers=True)
        if links:
            outcome.ok = True
            outcome.links = links
            outcome.status = "ok"
            outcome.chain = "Direct"
            collect(outcome)
            return outcome

    # Deep scan: fetch page, extract video URLs, deliver.
    try:
        resp = http.get(url, headers=headers or {}, referer=referer)
        urls = extract_all_video_urls(resp.text)
        filtered = filter_master_m3u8(urls)
        links = []
        for video_url in filtered:
            links.extend(create_smart_link(http, probe, "DeepScan", video_url,
                                           None, headers=headers, bare_headers=True))
        if links:
            outcome.ok = True
            outcome.links = links
            outcome.status = "ok"
            outcome.chain = "DeepScan"
        else:
            outcome.status = "empty"
            outcome.detail = f"deep scan found no video URLs in {url}"
            outcome.chain = "DeepScan"
    except Exception as e:
        outcome.status = "network"
        outcome.detail = f"deep scan network failure for {url}: {e}"

    if not outcome.ok and matches and any(r.status == "not-replicable" for r in partial):
        outcome.status = "not-replicable"
        outcome.detail = outcome.detail or "non-replicable extractor path"
    collect(outcome)
    return outcome


def refine_and_deliver(links, quality_strip_regex=QUALITY_STRIP_DEFAULT):
    """Port of MasterLinkGenerator.refineAndDeliver."""
    seen_m3u8_sources = set()
    out = []
    for link in links:
        if link.link_type in (M3U8, DASH):
            if link.source not in seen_m3u8_sources:
                seen_m3u8_sources.add(link.source)
                out.append(ExtractorLink(
                    source=link.source,
                    name=link.name or quality_strip_regex.sub("", link.source).strip(),
                    url=link.url, referer=link.referer, link_type=link.link_type,
                    headers=link.headers, quality=link.quality))
        else:
            out.append(ExtractorLink(
                source=link.source,
                name=link.name or quality_strip_regex.sub("", link.source).strip(),
                url=link.url, referer=link.referer, link_type=link.link_type,
                headers=link.headers, quality=link.quality))
    return out