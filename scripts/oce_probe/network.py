"""Faithful Python port of the OCE runtime network layer (HttpClient.kt).

Reproduces fetchDocument semantics:
  - mirror URL fallback (config.mirrorUrls)
  - User-Agent pool rotation (config.uaPool -> globalHeaders UA -> fallback pool)
  - per-host cookie jar (requests Session cookies)
  - retry policy: 404/410/451 -> next mirror; 429 -> backoff (Retry-After);
    5xx -> next host; 403/Cloudflare -> rotate UA then next mirror
  - global 25s budget, 15s per-request timeout

WebView Cloudflare solver is Android-only and is NOT portable to the CLI;
a persistent 403 is reported as cf_blocked (a signal, not a crash).
"""

import threading
import time
from urllib.parse import urlparse

import requests

from .configs import ProviderConfig

DEFAULT_TIMEOUT_MS = 15000
GLOBAL_TIMEOUT_MS = 25000

FALLBACK_UA_POOL = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
]

CLOUDFLARE_MARKERS = (
    "just a moment", "__cf_chl", "cf-chl-", "challenge-platform",
    "cf-ray", "cloudflare", "cf_chl",
)


class FetchError(Exception):
    """All mirror/UA attempts failed. kind in {network, timeout, cf, http, status}."""

    def __init__(self, kind, message, status=None, host=None, url=None):
        super().__init__(message)
        self.kind = kind
        self.status = status
        self.host = host
        self.url = url


class FetchedPage:
    __slots__ = ("text", "final_url", "status_code", "host", "url")

    def __init__(self, text, final_url, status_code, host, url):
        self.text = text
        self.final_url = final_url
        self.status_code = status_code
        self.host = host
        self.url = url


class HttpStatusError(Exception):
    def __init__(self, code, retry_after_seconds=None, message=""):
        super().__init__(message or f"HTTP {code}")
        self.code = code
        self.retry_after_seconds = retry_after_seconds


class NetworkError(Exception):
    def __init__(self, message="", timeout=False):
        super().__init__(message)
        self.timeout = timeout


class RawResponse:
    """Thin wrapper mirroring the subset of NiceResponse the engine uses."""

    __slots__ = ("text", "url", "status_code", "headers")

    def __init__(self, text, url, status_code, headers=None):
        self.text = text
        self.url = url
        self.status_code = status_code
        self.headers = headers or {}


def _parse_retry_after(value):
    if not value:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _looks_like_cloudflare(code, text):
    if code == 403:
        return True
    low = (text or "")[:4000].lower()
    return any(m in low for m in CLOUDFLARE_MARKERS)


def _resolve_ua_variants(config):
    from_config = [u for u in config.uaPool if u]
    if from_config:
        return list(dict.fromkeys(from_config))
    configured = config.globalHeaders.get("User-Agent")
    out = []
    if configured:
        out.append(configured)
    for u in FALLBACK_UA_POOL:
        if u != configured:
            out.append(u)
    return list(dict.fromkeys(out))[:4]


def _resolve_fallback_urls(url, config):
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.hostname:
        return [(url, "")]
    host = parsed.hostname
    port = ""
    try:
        if parsed.port and parsed.port not in (80, 443):
            port = f":{parsed.port}"
    except ValueError:
        pass
    path = parsed.path or ""
    query = f"?{parsed.query}" if parsed.query else ""
    fragment = f"#{parsed.fragment}" if parsed.fragment else ""
    candidates = [(url, host)]
    for mirror in config.mirrorUrls:
        m = urlparse(mirror)
        if not m.hostname or m.hostname == host:
            continue
        candidates.append(
            (f"{parsed.scheme}://{m.hostname}{port}{path}{query}{fragment}", m.hostname))
    return candidates


class HttpClient:
    """Port of the OCE runtime fetchDocument / app.get/app.post layer."""

    def __init__(self, timeout_ms=DEFAULT_TIMEOUT_MS, global_budget_ms=GLOBAL_TIMEOUT_MS,
                 delay_s=0.1, max_retries=3):
        self.timeout_ms = timeout_ms
        self.global_budget_ms = global_budget_ms
        self.delay_s = delay_s
        self.max_retries = max_retries
        self._local = threading.local()
        self._lock = threading.Lock()
        self._last_request = 0.0

    def _session(self):
        s = getattr(self._local, "session", None)
        if s is None:
            s = requests.Session()
            s.headers["Accept-Language"] = "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
            self._local.session = s
        return s

    def _polite(self):
        if self.delay_s <= 0:
            return
        with self._lock:
            now = time.monotonic()
            wait = self.delay_s - (now - self._last_request)
            self._last_request = now
        if wait > 0:
            time.sleep(wait)

    def _request(self, url, headers, referer=None, method="GET", data=None,
                 json_body=None, raise_on_error=False, timeout_s=None):
        """Single HTTP request with 429 retry/backoff. Returns RawResponse.

        raise_on_error=True -> raises HttpStatusError on status>=400 (mirrors the
        explicit check in fetchDocument). False -> returns body even on 4xx/5xx
        (mirrors extractor-step app.get(...).text usage).
        """
        timeout = timeout_s or (self.timeout_ms / 1000.0)
        session = self._session()
        final_headers = dict(headers)
        if referer:
            final_headers["Referer"] = referer

        last_exc = None
        for attempt in range(self.max_retries):
            self._polite()
            try:
                if method == "POST":
                    if json_body is not None:
                        resp = session.post(url, headers=final_headers, data=json_body,
                                            timeout=timeout,
                                            allow_redirects=True)
                    elif data is not None:
                        resp = session.post(url, headers=final_headers, data=data,
                                            timeout=timeout,
                                            allow_redirects=True)
                    else:
                        resp = session.post(url, headers=final_headers, timeout=timeout,
                                            allow_redirects=True)
                else:
                    resp = session.get(url, headers=final_headers, timeout=timeout,
                                       allow_redirects=True)
            except requests.exceptions.Timeout as e:
                last_exc = NetworkError(f"timeout on {url}", timeout=True)
                raise last_exc
            except requests.exceptions.RequestException as e:
                last_exc = NetworkError(f"{type(e).__name__} on {url}: {e}")
                raise last_exc

            if resp.status_code >= 400 and raise_on_error:
                retry_after = _parse_retry_after(resp.headers.get("Retry-After"))
                if resp.status_code == 429 and attempt < self.max_retries - 1:
                    wait = retry_after if retry_after is not None else (1 << attempt)
                    time.sleep(min(wait, 10.0))
                    continue
                raise HttpStatusError(resp.status_code, retry_after, f"HTTP {resp.status_code} on {url}")

            body = resp.text
            return RawResponse(body, resp.url, resp.status_code,
                               {k: v for k, v in resp.headers.items()})

        raise HttpStatusError(429, None, f"HTTP 429 on {url}")

    def get(self, url, headers=None, referer=None, raise_on_error=False):
        return self._request(url, headers or {}, referer=referer, method="GET",
                             raise_on_error=raise_on_error)

    def post(self, url, headers=None, referer=None, data=None, json_body=None,
             raise_on_error=False):
        return self._request(url, headers or {}, referer=referer, method="POST",
                             data=data, json_body=json_body,
                             raise_on_error=raise_on_error)

    def fetch_document(self, url, config, referer=None):
        """Port of HttpClient.fetchDocument. Raises FetchError when all fail."""
        fallback_urls = _resolve_fallback_urls(url, config)
        ua_variants = _resolve_ua_variants(config)
        deadline = time.monotonic() + self.global_budget_ms / 1000.0
        last_error = None
        cf_seen = False

        for attempt_url, host in fallback_urls:
            host_failed = False
            should_penalize = False
            for ua in ua_variants:
                if time.monotonic() > deadline:
                    last_error = FetchError(
                        "timeout", f"global fetch budget exceeded for {url}",
                        host=host, url=url)
                    break
                headers = dict(config.globalHeaders)
                headers["User-Agent"] = ua
                try:
                    resp = self._request(attempt_url, headers, referer=referer,
                                         raise_on_error=True)
                    page = FetchedPage(resp.text, resp.url, resp.status_code,
                                       host, attempt_url)
                    return page
                except HttpStatusError as e:
                    if _looks_like_cloudflare(e.code, None):
                        cf_seen = True
                        should_penalize = True
                        continue
                    if e.code == 429:
                        should_penalize = True
                        continue
                    if e.code in (404, 410, 451):
                        break
                    if 500 <= e.code <= 599:
                        should_penalize = True
                        host_failed = True
                        last_error = FetchError(
                            "http", f"HTTP {e.code} on {attempt_url}",
                            status=e.code, host=host, url=attempt_url)
                        break
                    host_failed = True
                    last_error = FetchError(
                        "http", f"HTTP {e.code} on {attempt_url}",
                        status=e.code, host=host, url=attempt_url)
                    break
                except NetworkError as e:
                    host_failed = True
                    should_penalize = True
                    last_error = FetchError(
                        "timeout" if e.timeout else "network",
                        str(e), host=host, url=attempt_url)
                    break

            if last_error and host == last_error.host:
                if last_error.kind == "http" and last_error.status in (404, 410, 451):
                    continue
            if not cf_seen and time.monotonic() > deadline:
                break

        if last_error is None:
            last_error = FetchError("network", f"All mirrors failed for {url}", url=url)
        if cf_seen:
            last_error = FetchError(
                "cf", f"Cloudflare challenge not solvable locally for {url}",
                host=last_error.host or "", url=url)
        raise last_error

    def get_json(self, url, referer=None, headers=None, timeout_s=None):
        """GET and return parsed JSON (or None on any failure)."""
        resp = self._request(url, headers or {}, referer=referer,
                             raise_on_error=False, timeout_s=timeout_s)
        return resp.text

    def post_form(self, url, data, referer=None, headers=None, timeout_s=None):
        return self._request(url, headers or {}, referer=referer, method="POST",
                             data=data, raise_on_error=False, timeout_s=timeout_s)

    def post_json(self, url, json_body, referer=None, headers=None, timeout_s=None):
        return self._request(url, headers or {}, referer=referer, method="POST",
                             json_body=json_body, raise_on_error=False, timeout_s=timeout_s)