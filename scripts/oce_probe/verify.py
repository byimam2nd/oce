"""Ports of the runtime video-delivery verifiers.

  - AdaptiveHeaderProbe.kt  -> parallel header-combo probe (first 2xx/3xx wins)
  - M3u8MasterVerifier.kt   -> master playlist variant validation
  - AdaptiveQualityPicker.kt-> resolveUrl helper
"""

import threading
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from urllib.parse import urlparse

from .crypto import DEFAULT_UA

PROBE_TIMEOUT_S = 5
PROBE_READ_BYTES = 1024 * 1024

MINIMAL_HEADERS = {
    "Accept": "*/*",
    "User-Agent": DEFAULT_UA,
}

BROWSER_LIKE_HEADERS = {
    "Accept": "*/*",
    "Accept-Language": "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
    "Connection": "keep-alive",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "cross-site",
    "User-Agent": DEFAULT_UA,
}


def _origin_of(url):
    try:
        u = urlparse(url)
        scheme = u.scheme or "https"
        port = u.port
        if port and port not in (80, 443):
            return f"{scheme}://{u.hostname}:{port}"
        return f"{scheme}://{u.hostname}"
    except Exception:
        return None


class ProbeDecision:
    __slots__ = ("mode", "referer", "headers", "valid", "network_blocked",
                 "captured_body", "body_truncated")

    def __init__(self, mode, referer, headers, valid=True, network_blocked=False,
                 captured_body=None, body_truncated=False):
        self.mode = mode
        self.referer = referer
        self.headers = headers
        self.valid = valid
        self.network_blocked = network_blocked
        self.captured_body = captured_body
        self.body_truncated = body_truncated


class _Ok:
    __slots__ = ("ms", "captured", "truncated")

    def __init__(self, ms, captured=None, truncated=False):
        self.ms = ms
        self.captured = captured
        self.truncated = truncated


class _HttpReject:
    pass


class _NetworkError:
    pass


def _probe_once(http, url, combo, capture_body):
    headers = dict(combo.headers)
    headers["Range"] = f"bytes=0-{PROBE_READ_BYTES - 1}"
    start = time.monotonic()
    try:
        resp = http._request(url, headers, referer=combo.referer,
                             raise_on_error=False, timeout_s=PROBE_TIMEOUT_S)
        if resp.status_code < 200 or resp.status_code > 399:
            return _HttpReject()
        body = ""
        truncated = False
        if capture_body:
            if len(resp.text) >= PROBE_READ_BYTES:
                body = resp.text[:PROBE_READ_BYTES]
                truncated = True
            else:
                body = resp.text
            if body:
                return _Ok((time.monotonic() - start) * 1000,
                           captured=body, truncated=truncated)
        else:
            # consume at most PROBE_READ_BYTES to emulate throughput read
            if len(resp.text) > PROBE_READ_BYTES:
                truncated = True
        return _Ok((time.monotonic() - start) * 1000, truncated=truncated)
    except Exception:
        return _NetworkError()


class HeaderProbe:
    """Port of AdaptiveHeaderProbe.resolve with single-flight per host."""

    def __init__(self, http):
        self.http = http
        self._in_flight = {}
        self._lock = threading.Lock()

    def _build_combos(self, url, referer_hint, explicit_headers):
        origin = _origin_of(url)
        referer = referer_hint or origin
        origin_header = { "Origin": origin } if origin else {}
        combos = [
            ("BARE", None, MINIMAL_HEADERS),
            ("REFERER", referer, MINIMAL_HEADERS),
            ("ORIGIN", referer, {**MINIMAL_HEADERS, **origin_header}),
            ("BROWSER_LIKE", referer, {**BROWSER_LIKE_HEADERS, **origin_header}),
        ]
        if explicit_headers and explicit_headers != MINIMAL_HEADERS:
            combos.append(("EXPLICIT", referer, explicit_headers))
        return combos

    def _probe(self, url, referer_hint, explicit_headers, capture_body):
        combos = self._build_combos(url, referer_hint, explicit_headers)
        any_network_error = False
        with ThreadPoolExecutor(max_workers=len(combos)) as pool:
            futures = {
                pool.submit(_probe_once, self.http, url, _Combo(mode, ref, hdrs),
                            capture_body): (mode, ref, hdrs)
                for mode, ref, hdrs in combos
            }
            pending = set(futures)
            while pending:
                done, pending = wait(pending, timeout=PROBE_TIMEOUT_S + 10,
                                     return_when=FIRST_COMPLETED)
                for fut in done:
                    mode, ref, hdrs = futures[fut]
                    try:
                        result = fut.result()
                    except Exception:
                        result = _NetworkError()
                    if isinstance(result, _Ok):
                        # cancel best-effort: shutdown threads will finish anyway
                        return ProbeDecision(
                            mode, ref, hdrs, valid=True,
                            captured_body=result.captured,
                            body_truncated=result.truncated)
                    if isinstance(result, _HttpReject):
                        continue
                    if isinstance(result, _NetworkError):
                        any_network_error = True
        if any_network_error:
            return ProbeDecision("BARE", None, MINIMAL_HEADERS, valid=True,
                                 network_blocked=True)
        return ProbeDecision("BARE", None, MINIMAL_HEADERS, valid=False)

    def resolve(self, url, referer_hint, explicit_headers=None, capture_body=False):
        host = ""
        try:
            host = urlparse(url).hostname or ""
        except Exception:
            pass
        if not host:
            return ProbeDecision("BARE", None, MINIMAL_HEADERS, valid=False)
        while True:
            with self._lock:
                fut = self._in_flight.get(host)
                if fut is not None:
                    result = fut.result()
                    return ProbeDecision(
                        result.mode, result.referer, result.headers,
                        valid=result.valid, network_blocked=result.network_blocked)
                from concurrent.futures import Future
                my_fut = Future()
                self._in_flight[host] = my_fut
            try:
                decision = self._probe(url, referer_hint, explicit_headers, capture_body)
                my_fut.set_result(decision)
            except Exception as e:
                decision = ProbeDecision("BARE", None, MINIMAL_HEADERS, valid=False)
                my_fut.set_result(decision)
            finally:
                with self._lock:
                    self._in_flight.pop(host, None)
            return decision


class SkipProbe:
    """Used with --no-probe: bypasses the HTTP header probe (no network)."""

    def resolve(self, url, referer_hint, explicit_headers=None, capture_body=False):
        return ProbeDecision("BARE", referer_hint, MINIMAL_HEADERS, valid=True)


class _Combo:
    __slots__ = ("mode", "referer", "headers")

    def __init__(self, mode, referer, headers):
        self.mode = mode
        self.referer = referer
        self.headers = headers


# ── M3u8MasterVerifier.kt ──

_BANDWIDTH_RE = __import__("re").compile(r"BANDWIDTH=(\d+)")
_RESOLUTION_RE = __import__("re").compile(r"RESOLUTION=\d+x(\d+)")


class MasterVariant:
    __slots__ = ("url", "bandwidth", "height")

    def __init__(self, url, bandwidth, height):
        self.url = url
        self.bandwidth = bandwidth
        self.height = height


class Verdict:
    pass


class VerdictClean(Verdict):
    pass


class VerdictValid(Verdict):
    def __init__(self, variants):
        self.variants = variants


class VerdictAllMalformed(Verdict):
    pass


def resolve_url(base_url, path):
    """Port of AdaptiveQualityPicker.resolveUrl."""
    if path.startswith("http://") or path.startswith("https://"):
        return path
    try:
        uri = urlparse(base_url)
    except Exception:
        return path
    if not uri.hostname:
        return path
    origin = f"{uri.scheme or 'https'}://{uri.hostname}"
    if uri.port and uri.port not in (80, 443):
        origin += f":{uri.port}"
    if path.startswith("/"):
        return origin + path
    base_path = uri.path or ""
    dir_ = base_path.rsplit("/", 1)[0] if "/" in base_path else ""
    return origin + dir_ + "/" + path


def parse_variants(master_text):
    """Port of M3u8MasterVerifier.parseVariants."""
    variants = []
    lines = (master_text or "").splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line.startswith("#EXT-X-STREAM-INF"):
            bm = _BANDWIDTH_RE.search(line)
            bandwidth = int(bm.group(1)) if bm else 0
            rm = _RESOLUTION_RE.search(line)
            height = int(rm.group(1)) if rm else None
            uri_line = lines[i + 1].strip() if i + 1 < len(lines) else ""
            malformed = (not uri_line) or uri_line.startswith("#")
            variants.append(MasterVariant(
                url=None if malformed else uri_line,
                bandwidth=bandwidth, height=height))
            i += 2
        else:
            i += 1
    return variants


def classify(master_url, parsed):
    """Port of M3u8MasterVerifier.classify."""
    if not parsed:
        return VerdictClean()
    valid = []
    for v in parsed:
        if v.url is None:
            continue
        resolved = resolve_url(master_url, v.url)
        if resolved == master_url or resolved == master_url.rstrip("/"):
            continue
        valid.append((resolved, v.height))
    if not valid:
        return VerdictAllMalformed()
    if len(valid) < len(parsed):
        return VerdictValid(valid)
    return VerdictClean()


def verify_master(http, master_url, referer, headers):
    """Port of M3u8MasterVerifier.verify (fetch + classify, Clean on failure)."""
    try:
        resp = http.get(master_url, headers=headers, referer=referer)
        return classify(master_url, parse_variants(resp.text))
    except Exception:
        return VerdictClean()