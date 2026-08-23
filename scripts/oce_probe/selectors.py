"""Faithful Python port of the OCE runtime selector layer.

  - SelectorResolver.kt   (variants `||`, select/selectFirst/text/textValidated)
  - SelectorValidator.kt  (FieldType rules)
  - ProviderParser.kt     (fixUrlSmart, safeExtractImage, safeCleanBloat,
                           safeDeduplicate, safeExtractEpNum/Year, base64 helpers)
  - ModelHelpers used by mapper/collector

bs4 (html.parser) is used as the Jsoup equivalent. The adaptive fingerprint
relocate is session-scoped in the Kotlin runtime; a fresh probe session has no
fingerprints, so relocate never fires — matching the probe faithfully. It is
implemented as a no-op store unless the caller opts into learning (`--relocate`).
"""

import base64
import re
from urllib.parse import urljoin

from bs4 import BeautifulSoup

# ── Compiled regexes (port of ProviderParser.kt / SelectorResolver.kt) ──

WHITESPACE_REGEX = re.compile(r"\s+")
DEDUPLICATE_REGEX = re.compile(r"^(.*?)\s+\1$", re.IGNORECASE)
YEAR_REGEX = re.compile(r"\d{4}")
EPISODE_KEYWORD_REGEX = re.compile(r"(?i)(?:episode|ep|eps)\s*(\d+(?:\.\d+)?)")
EPISODE_NUMBER_REGEX = re.compile(r"(\d+(?:\.\d+)?)")
JUST_NUMBER_REGEX = re.compile(r"^\d+(\.\d+)?$")

EPISODE_URL_REGEX = re.compile(r"(?i)(?:/eps/|/episode/|/ep/|-episode-|/season-|-season-|/ep-)")
EPISODE_TEXT_REGEX = re.compile(r"(?i)(?:\bepisode\b|\beps?\b)\s*(\d+(?:\.\d+)?)")

# ── Jsoup-equivalent text helper ──


def jsoup_text(el):
    """Port of org.jsoup.nodes.Element.text(): descendant text, whitespace collapsed."""
    if el is None:
        return ""
    t = el.get_text(" ", strip=True)
    return WHITESPACE_REGEX.sub(" ", t).strip()


# ── Port of ProviderParser.kt helpers ──


def safe_extract_image(el, attributes):
    """Port of Element.safeExtractImage: first non-blank attr token."""
    try:
        for name in attributes:
            for attr in [a.strip() for a in name.split(",") if a.strip()]:
                v = el.get(attr, "")
                if v and v != "about:blank":
                    return v.split(" ")[0]
        return ""
    except Exception:
        return ""


def select_attr(el, attr_names):
    """Port of Element.selectAttr (HttpClient.kt)."""
    for name in attr_names:
        v = el.get(name, "")
        if v and v != "about:blank":
            return v
    return None


def safe_clean_bloat(text, original, regex):
    try:
        cleaned = regex.sub("", text).strip()
        return cleaned if cleaned else original
    except Exception:
        return original


def safe_deduplicate(text):
    if not text or not text.strip():
        return text
    s = WHITESPACE_REGEX.sub(" ", text).strip()
    for sep in [" - ", " | ", " : ", " – ", " — "]:
        if sep in s:
            parts = s.split(sep)
            if len(parts) == 2 and parts[0].strip().lower() == parts[1].strip().lower():
                return parts[0].strip()
    if len(s) >= 6:
        mid = len(s) // 2
        if len(s) % 2 == 0:
            if s[:mid].strip().lower() == s[mid:].strip().lower():
                return s[:mid].strip()
        if s[:mid].strip().lower() == s[mid + 1:].strip().lower():
            return s[:mid].strip()
    words = s.split(" ")
    words = [w for w in words if w]
    if len(words) >= 2 and len(words) % 2 == 0:
        half = len(words) // 2
        if " ".join(words[:half]).lower() == " ".join(words[half:]).lower():
            return " ".join(words[:half])
    m = DEDUPLICATE_REGEX.match(s)
    if m:
        return m.group(1).strip()
    return s


def safe_extract_year(text):
    if text is None:
        return None
    try:
        m = YEAR_REGEX.search(text)
        return int(m.group(0)) if m else None
    except Exception:
        return None


def safe_extract_ep_num(text):
    if text is None or not text.strip():
        return None
    try:
        m = EPISODE_KEYWORD_REGEX.search(text)
        if m:
            return int(float(m.group(1)))
        numbers = [x for x in EPISODE_NUMBER_REGEX.findall(text)]
        numbers = [x for x in numbers if is_float(x)]
        for n in numbers:
            if len(n) != 4 or not (1900 <= int(float(n)) <= 2099):
                return int(float(n))
        return None
    except Exception:
        return None


def is_float(s):
    try:
        float(s)
        return True
    except ValueError:
        return False


def safe_httpsify(url):
    try:
        return f"https:{url}" if url.startswith("//") else url
    except Exception:
        return url


def fix_url_smart(url, base_url=None):
    if url is None or not url.strip():
        return ""
    if url.startswith("http"):
        return url
    if url.startswith("//"):
        return f"https:{url}"
    base = base_url or ""
    if not base:
        return url
    try:
        from urllib.parse import urlparse
        u = urlparse(base)
        root = f"{u.scheme}://{u.hostname}"
        if url.startswith("/"):
            return f"{root}{url}"
        path = base if base.endswith("/") else f"{base}/"
        return f"{path}{url}"
    except Exception:
        return url


def get_base_url(url):
    if not url:
        return ""
    try:
        from urllib.parse import urlparse
        u = urlparse(url)
        return f"{u.scheme}://{u.hostname}"
    except Exception:
        return ""


def safe_is_base64(s):
    if not s:
        return False
    if len(s) > 10000:
        return False
    try:
        base64.b64decode(s, validate=True)
        return True
    except Exception:
        return False


def safe_decode(s):
    try:
        return base64.b64decode(s).decode("utf-8", errors="replace")
    except Exception:
        return s


def parse_html(text):
    return BeautifulSoup(text or "", "html.parser")


# ── Port of SelectorValidator.kt ──

class FieldType:
    TITLE = "TITLE"
    POSTER = "POSTER"
    URL = "URL"
    EPISODE_TEXT = "EPISODE_TEXT"
    DESC = "DESC"


_URL_PREFIX = re.compile(r"^(https?:)?//")
_DATA_IMAGE = re.compile(r"(?i)^data:image/")
_EPISODE_TOKEN = re.compile(r"(?i)(?:episode|ep|eps)\s*\d+|(?:eps?\.?)")
_SENTENCE_MARKS = ".!?;"


class SelectorValidator:
    @staticmethod
    def is_valid(type_, value):
        v = (value or "").strip()
        if not v:
            return False
        if type_ == FieldType.TITLE:
            return SelectorValidator._is_valid_title(v)
        if type_ == FieldType.POSTER:
            return SelectorValidator._is_valid_poster(v)
        if type_ == FieldType.URL:
            return SelectorValidator._is_valid_url(v)
        if type_ == FieldType.EPISODE_TEXT:
            return SelectorValidator._is_valid_episode_text(v)
        if type_ == FieldType.DESC:
            return len(v) >= 30
        return False

    @staticmethod
    def _is_valid_title(v):
        if not (3 <= len(v) <= 250):
            return False
        if "\n" in v:
            return False
        marks = sum(1 for c in v if c in _SENTENCE_MARKS)
        if len(v) > 40 and marks >= 3:
            return False
        if len(re.split(r"\s+", v)) > 45:
            return False
        return True

    @staticmethod
    def _is_valid_poster(v):
        if _DATA_IMAGE.search(v):
            return True
        if not _URL_PREFIX.search(v):
            return False
        return " " not in v

    @staticmethod
    def _is_valid_url(v):
        if not _URL_PREFIX.search(v):
            return False
        return " " not in v

    @staticmethod
    def _is_valid_episode_text(v):
        if not (2 <= len(v) <= 40):
            return False
        return bool(_EPISODE_TOKEN.search(v))


# ── Port of SelectorResolver.kt ──

SEPARATOR = "||"


class SelectorResolver:
    """Session-scoped selector resolution (fresh session == empty fingerprints)."""

    def __init__(self, provider_id="", enable_relocate=False):
        self.provider_id = provider_id
        self.enable_relocate = enable_relocate
        self.fingerprints = {}
        self.broken_variants = {}
        self.match_log = {}

    def _key(self, key):
        return f"{self.provider_id}:{key}" if key else key

    @staticmethod
    def variants(selector):
        return [v.strip() for v in selector.split(SEPARATOR) if v.strip()]

    def select_first(self, document, selector, key=""):
        if not selector:
            return None
        for variant in self.variants(selector):
            try:
                el = document.select_one(variant)
            except Exception:
                el = None
            if el is not None:
                self._note_match(key, variant, 1)
                return el
        self._note_match(key, selector, 0)
        return None

    def select(self, document, selector, key=""):
        if not selector:
            return []
        for variant in self.variants(selector):
            try:
                els = document.select(variant)
            except Exception:
                els = []
            if els:
                self._note_match(key, variant, len(els))
                return els
        self._note_match(key, selector, 0)
        return []

    def text(self, document, selector, key=""):
        el = self.select_first(document, selector, key)
        return jsoup_text(el) if el is not None else None

    def select_validated(self, document, selector, key, type_, extract):
        if not selector:
            return None
        k = self._key(key)
        for variant in self.variants(selector):
            if self._is_broken(k, variant):
                continue
            try:
                el = document.select_one(variant)
            except Exception:
                el = None
            if el is None:
                continue
            try:
                value = extract(el)
            except Exception:
                value = None
            if SelectorValidator.is_valid(type_, value):
                self._note_match(key, variant, 1)
                self._unmark_broken(k, variant)
                return el
            self._mark_broken(k, variant)
        self._note_match(key, selector, 0)
        return None

    def text_validated(self, document, selector, key, type_):
        el = self.select_validated(document, selector, key, type_,
                                   lambda it: jsoup_text(it).strip())
        return jsoup_text(el).strip() if el is not None else None

    def detect_episode_links(self, document, current_url):
        out = []
        for a in document.select("a[href]"):
            href = a.get("href", "")
            if not href:
                continue
            abs_url = fix_url_smart(href, current_url) or href
            if abs_url == current_url:
                continue
            text = jsoup_text(a)
            if EPISODE_URL_REGEX.search(abs_url) or EPISODE_TEXT_REGEX.search(text):
                out.append(a)
        return out

    # ── fingerprint relocate (session-scoped; empty on fresh probe) ──

    def _note_match(self, key, selector, count):
        if key:
            self.match_log.setdefault(self._key(key), []).append((selector, count))

    def _is_broken(self, key, variant):
        return variant in self.broken_variants.get(key, set())

    def _mark_broken(self, key, variant):
        s = self.broken_variants.setdefault(key, set())
        if len(s) >= 8:
            self.broken_variants[key] = {variant}
        else:
            s.add(variant)

    def _unmark_broken(self, key, variant):
        s = self.broken_variants.get(key)
        if s:
            s.discard(variant)