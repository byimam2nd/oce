"""Faithful Python port of the OCE runtime config layer.

Reads the SAME JSON files the plugin loads at runtime:
  BaseProvider/src/main/kotlin/com/baseprovider/config/<name>.json
  BaseProvider/src/main/kotlin/com/baseprovider/config/extractors/<id>.json

This module never writes config — it is a read-only mirror of
ConfigRegistry / ProviderConfigParser / ExtractorConfigRegistry /
ExtractorConfigParser from the Kotlin runtime.
"""

import json
import os
import re
import sys

# ── TV types (string forms matching TvType names used in configs) ──
TV_MOVIE = "Movie"
TV_SERIES = "TvSeries"
TV_ANIME = "Anime"
TV_ANIME_MOVIE = "AnimeMovie"
TV_ASIAN_DRAMA = "AsianDrama"
TV_CARTOON = "Cartoon"
TV_OVA = "OVA"

VALID_TV_TYPES = {
    TV_MOVIE, TV_SERIES, TV_ANIME, TV_ANIME_MOVIE,
    TV_ASIAN_DRAMA, TV_CARTOON, TV_OVA,
}

SERIES_TYPES = {TV_SERIES, TV_ANIME, TV_ANIME_MOVIE, TV_ASIAN_DRAMA, TV_CARTOON, TV_OVA}
MOVIE_TYPES = {TV_MOVIE, TV_ANIME_MOVIE}

BLOAT_REGEX_DEFAULT = (
    r"(?i)(\bONA\b|\bOngoing\b|\bCompleted\b|\bSpecial\b|\bTAMAT\b|\bIndo\b|\bFull\b"
    r"|\bSeason\b|\bEpisode\s*\d*|Subtitle\s*Indonesia|\bDonghua\b|\bSub\b|Nonton"
    r"|Anime|Movie|TV|Series|Lengkap|HD|Free|\d{3,4}p|Dual\s*Audio|\s*–\s*|\s*\|\s*)"
)


def _warn(tag, message):
    print(f"[{tag}] WARN: {message}", file=sys.stderr)


# ─────────────────────────────────────────────────────────────
# ProviderConfig
# ─────────────────────────────────────────────────────────────

class ProviderConfig:
    """Mirror of com.baseprovider.config.ProviderConfig (defaults identical)."""

    __slots__ = (
        "id", "name", "mainUrl", "seriesUrl", "searchUrl", "lang", "supportedTypes",
        "searchPathPattern", "mainPagePathPattern", "moviePathSegment", "tvPathSegment",
        "episodeDataUrlPattern", "reverseEpisodes", "isJsonSearch", "searchJsonRoot",
        "searchJsonTitle", "searchJsonHref", "searchJsonPoster", "searchJsonPosterPrefix",
        "searchJsonType", "useDocumentLarge", "cacheTtlMinutes", "isHorizontal",
        "mirrorUrls", "uaPool", "refererPlayerMode", "iframeSelectors", "posterResizeUrl",
        "thumbnailResizeUrl", "qualityStripRegex", "globalHeaders", "googleReferer",
        "mainPageLists", "allowedExtractors", "skipHosts", "dubKeyword", "ongoingKeyword",
        "episodeKeyword", "seriesKeyword", "comingSoonKeywords", "searchItems",
        "searchTitle", "searchHref", "searchPoster", "searchRating", "searchEpText",
        "loadTitle", "loadPoster", "loadBanner", "loadDesc", "loadInfoBox", "loadTags",
        "loadRating", "loadStatus", "loadTrailer", "loadRecommend", "episodeItems",
        "episodeHref", "episodeTitle", "episodeNum", "episodeDesc", "episodeTime",
        "linkOptions", "downloadItems", "actorItems", "actorName", "watchButtons",
        "seasonContainer", "imdbExternal", "tmdbExternal", "iframeTag",
        "followLinkSelector", "switchVideoSelector", "ajaxPlayerUrl", "selectorJsonData",
        "attrImage", "attrHref", "attrValue", "iframeSources", "hrefCleanRegex",
        "hrefCleanReplace", "yearSelector", "yearExtractorRegex", "bloatRegex",
    )

    def __init__(self, id, **kw):
        self.id = id
        self.name = kw.get("name", id)
        self.mainUrl = kw.get("mainUrl", "https://example.com")
        self.seriesUrl = kw.get("seriesUrl", None)
        self.searchUrl = kw.get("searchUrl", None)
        self.lang = kw.get("lang", "id")
        self.supportedTypes = kw.get("supportedTypes", [])
        self.searchPathPattern = kw.get("searchPathPattern", "{baseUrl}/page/{page}/?s={query}")
        self.mainPagePathPattern = kw.get("mainPagePathPattern", "{baseUrl}/{data}{page}")
        self.moviePathSegment = kw.get("moviePathSegment", "/movie/")
        self.tvPathSegment = kw.get("tvPathSegment", "/anime/")
        self.episodeDataUrlPattern = kw.get("episodeDataUrlPattern", "{url}")
        self.reverseEpisodes = kw.get("reverseEpisodes", True)
        self.isJsonSearch = kw.get("isJsonSearch", False)
        self.searchJsonRoot = kw.get("searchJsonRoot", "data")
        self.searchJsonTitle = kw.get("searchJsonTitle", "title")
        self.searchJsonHref = kw.get("searchJsonHref", "slug")
        self.searchJsonPoster = kw.get("searchJsonPoster", "poster")
        self.searchJsonPosterPrefix = kw.get("searchJsonPosterPrefix", "")
        self.searchJsonType = kw.get("searchJsonType", "type")
        self.useDocumentLarge = kw.get("useDocumentLarge", False)
        self.cacheTtlMinutes = kw.get("cacheTtlMinutes", 5)
        self.isHorizontal = kw.get("isHorizontal", False)
        self.mirrorUrls = kw.get("mirrorUrls", [])
        self.uaPool = kw.get("uaPool", [])
        self.refererPlayerMode = kw.get("refererPlayerMode", "current_url")
        self.iframeSelectors = kw.get("iframeSelectors", "iframe")
        self.posterResizeUrl = kw.get("posterResizeUrl", "")
        self.thumbnailResizeUrl = kw.get("thumbnailResizeUrl", "")
        self.qualityStripRegex = kw.get("qualityStripRegex", r"\d{3,4}p|HD|SD|FHD")
        self.globalHeaders = kw.get("globalHeaders", {})
        self.googleReferer = kw.get("googleReferer", False)
        self.mainPageLists = kw.get("mainPageLists", [])
        self.allowedExtractors = kw.get("allowedExtractors", [])
        self.skipHosts = kw.get("skipHosts", [])
        self.dubKeyword = kw.get("dubKeyword", "dub")
        self.ongoingKeyword = kw.get("ongoingKeyword", "Ongoing")
        self.episodeKeyword = kw.get("episodeKeyword", "Episode")
        self.seriesKeyword = kw.get("seriesKeyword", "Series")
        self.comingSoonKeywords = kw.get("comingSoonKeywords", "Coming Soon")
        self.searchItems = kw.get("searchItems", "")
        self.searchTitle = kw.get("searchTitle", "")
        self.searchHref = kw.get("searchHref", "")
        self.searchPoster = kw.get("searchPoster", "")
        self.searchRating = kw.get("searchRating", "")
        self.searchEpText = kw.get("searchEpText", "")
        self.loadTitle = kw.get("loadTitle", "")
        self.loadPoster = kw.get("loadPoster", "")
        self.loadBanner = kw.get("loadBanner", "")
        self.loadDesc = kw.get("loadDesc", "")
        self.loadInfoBox = kw.get("loadInfoBox", "")
        self.loadTags = kw.get("loadTags", "")
        self.loadRating = kw.get("loadRating", "")
        self.loadStatus = kw.get("loadStatus", "")
        self.loadTrailer = kw.get("loadTrailer", "")
        self.loadRecommend = kw.get("loadRecommend", "")
        self.episodeItems = kw.get("episodeItems", "")
        self.episodeHref = kw.get("episodeHref", "")
        self.episodeTitle = kw.get("episodeTitle", "")
        self.episodeNum = kw.get("episodeNum", "")
        self.episodeDesc = kw.get("episodeDesc", "")
        self.episodeTime = kw.get("episodeTime", "")
        self.linkOptions = kw.get("linkOptions", "")
        self.downloadItems = kw.get("downloadItems", "")
        self.actorItems = kw.get("actorItems", "")
        self.actorName = kw.get("actorName", "")
        self.watchButtons = kw.get("watchButtons", ".play-button, .watch-now, .btn-watch")
        self.seasonContainer = kw.get("seasonContainer", ".tvseason, #season-data")
        self.imdbExternal = kw.get("imdbExternal", "a[href*='imdb.com/title/']")
        self.tmdbExternal = kw.get("tmdbExternal", "a[href*='themoviedb.org/']")
        self.iframeTag = kw.get("iframeTag", "iframe")
        self.followLinkSelector = kw.get("followLinkSelector", "")
        self.switchVideoSelector = kw.get("switchVideoSelector", "")
        self.ajaxPlayerUrl = kw.get("ajaxPlayerUrl", "")
        self.selectorJsonData = kw.get("selectorJsonData", "")
        self.attrImage = kw.get("attrImage",
                                ["data-original", "data-src", "data-lazy-src",
                                 "data-litespeed-src", "src", "content"])
        self.attrHref = kw.get("attrHref", ["href"])
        self.attrValue = kw.get("attrValue",
                                ["value", "data-index", "data-id", "data-url",
                                 "data-link", "data-litespeed-src"])
        self.iframeSources = kw.get("iframeSources",
                                    ["src", "data-src", "data-link", "data-litespeed-src"])
        self.hrefCleanRegex = kw.get("hrefCleanRegex", "")
        self.hrefCleanReplace = kw.get("hrefCleanReplace", "")
        self.yearSelector = kw.get("yearSelector", "")
        self.yearExtractorRegex = kw.get("yearExtractorRegex", "")
        self.bloatRegex = kw.get("bloatRegex", BLOAT_REGEX_DEFAULT)

    @property
    def qualityStripRegexCompiled(self):
        try:
            return re.compile(self.qualityStripRegex, re.IGNORECASE)
        except re.error:
            return re.compile(r"\d{3,4}p|HD|SD|FHD", re.IGNORECASE)

    @property
    def bloatRegexCompiled(self):
        try:
            return re.compile(self.bloatRegex)
        except re.error:
            return re.compile(BLOAT_REGEX_DEFAULT)


PROVIDER_FIELDS = set(ProviderConfig.__slots__)


def _warn_unknown_keys(scope, data, known):
    unknown = [k for k in data.keys() if k not in known]
    if unknown:
        _warn(scope, f"unknown/unused keys (mungkin typo): {', '.join(unknown)}")


def _opt_string(data, key, default=""):
    v = data.get(key)
    if v is None:
        return default
    return v if isinstance(v, str) else default


def _opt_int(data, key, default):
    v = data.get(key)
    if isinstance(v, bool):
        return default
    if isinstance(v, int):
        return v
    return default


def _opt_bool(data, key, default):
    v = data.get(key)
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)) and v in (0, 1):
        return bool(v)
    return default


def _opt_long(data, key, default):
    v = data.get(key)
    if isinstance(v, bool):
        return default
    if isinstance(v, (int, float)):
        return int(v)
    return default


def _parse_tv_types(arr):
    if not isinstance(arr, list):
        return []
    out = []
    for v in arr:
        if isinstance(v, str) and v in VALID_TV_TYPES:
            out.append(v)
    return out


def _parse_pairs_list(arr):
    if not isinstance(arr, list):
        return []
    out = []
    for item in arr:
        if not (isinstance(item, list) and len(item) >= 2):
            continue
        out.append((item[0] if isinstance(item[0], str) else "", item[1] if isinstance(item[1], str) else ""))
    return out


def _json_object_to_map(obj):
    if not isinstance(obj, dict):
        return {}
    return {k: (v if isinstance(v, str) else "") for k, v in obj.items()}


def _json_array_to_list(arr, default=None):
    if not isinstance(arr, list):
        return list(default) if default is not None else []
    return [v if isinstance(v, str) else "" for v in arr]


def _json_array_to_set(arr):
    if not isinstance(arr, list):
        return set()
    return {v if isinstance(v, str) else "" for v in arr}


def _validate_regex(pattern):
    if not pattern:
        return ""
    try:
        re.compile(pattern)
        return pattern
    except re.error:
        return ""


def from_json_provider(id, data):
    """Port of ProviderConfigParser.fromJson."""
    _warn_unknown_keys(f"Config[{id}]", data, PROVIDER_FIELDS)

    series_url = _opt_string(data, "seriesUrl", "") or None
    search_url = _opt_string(data, "searchUrl", "") or None

    bloat_raw = _opt_string(data, "bloatRegex", BLOAT_REGEX_DEFAULT)
    try:
        re.compile(bloat_raw)
        bloat = bloat_raw
    except re.error:
        bloat = BLOAT_REGEX_DEFAULT

    return ProviderConfig(
        id=id,
        name=_opt_string(data, "name", id),
        mainUrl=_opt_string(data, "mainUrl", "https://example.com"),
        seriesUrl=series_url,
        searchUrl=search_url,
        lang=_opt_string(data, "lang", "id"),
        supportedTypes=_parse_tv_types(data.get("supportedTypes")),
        searchPathPattern=_opt_string(data, "searchPathPattern", "{baseUrl}/page/{page}/?s={query}"),
        mainPagePathPattern=_opt_string(data, "mainPagePathPattern", "{baseUrl}/{data}{page}"),
        moviePathSegment=_opt_string(data, "moviePathSegment", "/movie/"),
        tvPathSegment=_opt_string(data, "tvPathSegment", "/anime/"),
        episodeDataUrlPattern=_opt_string(data, "episodeDataUrlPattern", "{url}"),
        reverseEpisodes=_opt_bool(data, "reverseEpisodes", True),
        isJsonSearch=_opt_bool(data, "isJsonSearch", False),
        searchJsonRoot=_opt_string(data, "searchJsonRoot", "data"),
        searchJsonTitle=_opt_string(data, "searchJsonTitle", "title"),
        searchJsonHref=_opt_string(data, "searchJsonHref", "slug"),
        searchJsonPoster=_opt_string(data, "searchJsonPoster", "poster"),
        searchJsonPosterPrefix=_opt_string(data, "searchJsonPosterPrefix", ""),
        searchJsonType=_opt_string(data, "searchJsonType", "type"),
        useDocumentLarge=_opt_bool(data, "useDocumentLarge", False),
        cacheTtlMinutes=_opt_long(data, "cacheTtlMinutes", 5),
        isHorizontal=_opt_bool(data, "isHorizontal", False),
        mirrorUrls=_json_array_to_list(data.get("mirrorUrls")),
        uaPool=_json_array_to_list(data.get("uaPool")),
        refererPlayerMode=_opt_string(data, "refererPlayerMode", "current_url"),
        iframeSelectors=_opt_string(data, "iframeSelectors", "iframe"),
        posterResizeUrl=_opt_string(data, "posterResizeUrl", ""),
        thumbnailResizeUrl=_opt_string(data, "thumbnailResizeUrl", ""),
        qualityStripRegex=_opt_string(data, "qualityStripRegex", r"\d{3,4}p|HD|SD|FHD"),
        globalHeaders=_json_object_to_map(data.get("globalHeaders")),
        googleReferer=_opt_bool(data, "googleReferer", False),
        mainPageLists=_parse_pairs_list(data.get("mainPageLists")),
        allowedExtractors=_json_array_to_set(data.get("allowedExtractors")),
        skipHosts=_json_array_to_set(data.get("skipHosts")),
        dubKeyword=_opt_string(data, "dubKeyword", "dub"),
        ongoingKeyword=_opt_string(data, "ongoingKeyword", "Ongoing"),
        episodeKeyword=_opt_string(data, "episodeKeyword", "Episode"),
        seriesKeyword=_opt_string(data, "seriesKeyword", "Series"),
        comingSoonKeywords=_opt_string(data, "comingSoonKeywords", "Coming Soon"),
        searchItems=_opt_string(data, "searchItems", ""),
        searchTitle=_opt_string(data, "searchTitle", ""),
        searchHref=_opt_string(data, "searchHref", ""),
        searchPoster=_opt_string(data, "searchPoster", ""),
        searchRating=_opt_string(data, "searchRating", ""),
        searchEpText=_opt_string(data, "searchEpText", ""),
        loadTitle=_opt_string(data, "loadTitle", ""),
        loadPoster=_opt_string(data, "loadPoster", ""),
        loadBanner=_opt_string(data, "loadBanner", ""),
        loadDesc=_opt_string(data, "loadDesc", ""),
        loadInfoBox=_opt_string(data, "loadInfoBox", ""),
        loadTags=_opt_string(data, "loadTags", ""),
        loadRating=_opt_string(data, "loadRating", ""),
        loadStatus=_opt_string(data, "loadStatus", ""),
        loadTrailer=_opt_string(data, "loadTrailer", ""),
        loadRecommend=_opt_string(data, "loadRecommend", ""),
        episodeItems=_opt_string(data, "episodeItems", ""),
        episodeHref=_opt_string(data, "episodeHref", ""),
        episodeTitle=_opt_string(data, "episodeTitle", ""),
        episodeNum=_opt_string(data, "episodeNum", ""),
        episodeDesc=_opt_string(data, "episodeDesc", ""),
        episodeTime=_opt_string(data, "episodeTime", ""),
        linkOptions=_opt_string(data, "linkOptions", ""),
        downloadItems=_opt_string(data, "downloadItems", ""),
        actorItems=_opt_string(data, "actorItems", ""),
        actorName=_opt_string(data, "actorName", ""),
        watchButtons=_opt_string(data, "watchButtons", ".play-button, .watch-now, .btn-watch"),
        seasonContainer=_opt_string(data, "seasonContainer", ".tvseason, #season-data"),
        imdbExternal=_opt_string(data, "imdbExternal", "a[href*='imdb.com/title/']"),
        tmdbExternal=_opt_string(data, "tmdbExternal", "a[href*='themoviedb.org/']"),
        iframeTag=_opt_string(data, "iframeTag", "iframe"),
        followLinkSelector=_opt_string(data, "followLinkSelector", ""),
        switchVideoSelector=_opt_string(data, "switchVideoSelector", ""),
        ajaxPlayerUrl=_opt_string(data, "ajaxPlayerUrl", ""),
        selectorJsonData=_opt_string(data, "selectorJsonData", ""),
        attrImage=_json_array_to_list(
            data.get("attrImage"),
            ["data-original", "data-src", "data-lazy-src", "data-litespeed-src", "src", "content"]),
        attrHref=_json_array_to_list(data.get("attrHref"), ["href"]),
        attrValue=_json_array_to_list(
            data.get("attrValue"),
            ["value", "data-index", "data-id", "data-url", "data-link", "data-litespeed-src"]),
        iframeSources=_json_array_to_list(
            data.get("iframeSources"),
            ["src", "data-src", "data-link", "data-litespeed-src"]),
        hrefCleanRegex=_validate_regex(_opt_string(data, "hrefCleanRegex", "")),
        hrefCleanReplace=_opt_string(data, "hrefCleanReplace", ""),
        yearSelector=_opt_string(data, "yearSelector", ""),
        yearExtractorRegex=_validate_regex(_opt_string(data, "yearExtractorRegex", "")),
        bloatRegex=bloat,
    )


# ─────────────────────────────────────────────────────────────
# ExtractorConfig (config-driven extractors)
# ─────────────────────────────────────────────────────────────

class IdSource:
    __slots__ = ("type", "param", "pattern", "group", "selector", "attr")

    def __init__(self, type="none", param="", pattern="", group=1, selector="", attr="src"):
        self.type = type
        self.param = param
        self.pattern = pattern
        self.group = group
        self.selector = selector
        self.attr = attr


class ExtractorVariant:
    __slots__ = ("name", "headers", "referer", "userAgent")

    def __init__(self, name="default", headers=None, referer="", userAgent=""):
        self.name = name
        self.headers = headers or {}
        self.referer = referer
        self.userAgent = userAgent


class Step:
    pass


class StepFetch(Step):
    step = "fetch"
    __slots__ = ("url", "referer", "headers", "store", "urlReplace", "storeFinalUrl")

    def __init__(self, url="{url}", referer="", headers=None, store="response",
                 urlReplace=None, storeFinalUrl=""):
        self.url = url
        self.referer = referer
        self.headers = headers or {}
        self.store = store
        self.urlReplace = urlReplace or {}
        self.storeFinalUrl = storeFinalUrl


class StepPostForm(Step):
    step = "postForm"
    __slots__ = ("url", "data", "referer", "headers", "store")

    def __init__(self, url="", data=None, referer="", headers=None, store="response"):
        self.url = url
        self.data = data or {}
        self.referer = referer
        self.headers = headers or {}
        self.store = store


class StepPostJson(Step):
    step = "postJson"
    __slots__ = ("url", "jsonBody", "referer", "headers", "store")

    def __init__(self, url="", jsonBody="", referer="", headers=None, store="response"):
        self.url = url
        self.jsonBody = jsonBody
        self.referer = referer
        self.headers = headers or {}
        self.store = store


class StepRegex(Step):
    step = "regex"
    __slots__ = ("pattern", "group", "source", "filter", "universal", "decodeUnicode", "store")

    def __init__(self, pattern="", group=1, source="response", filter="", universal=False,
                 decodeUnicode=False, store=""):
        self.pattern = pattern
        self.group = group
        self.source = source
        self.filter = filter
        self.universal = universal
        self.decodeUnicode = decodeUnicode
        self.store = store


class StepJsonPath(Step):
    step = "jsonPath"
    __slots__ = ("path", "source", "filter", "store")

    def __init__(self, path="", source="response", filter="", store=""):
        self.path = path
        self.source = source
        self.filter = filter
        self.store = store


class StepConstructUrl(Step):
    step = "constructUrl"
    __slots__ = ("template", "store")

    def __init__(self, template="", store=""):
        self.template = template
        self.store = store


class StepSubstring(Step):
    step = "substring"
    __slots__ = ("startMarker", "endMarker", "source", "store")

    def __init__(self, startMarker="", endMarker="", source="response", store=""):
        self.startMarker = startMarker
        self.endMarker = endMarker
        self.source = source
        self.store = store


class StepResolveUrl(Step):
    step = "resolveUrl"
    __slots__ = ("base", "source")

    def __init__(self, base="{url}", source=""):
        self.base = base
        self.source = source


class StepPackedJs(Step):
    step = "packedJs"
    __slots__ = ("source", "store")

    def __init__(self, source="response", store="decoded"):
        self.source = source
        self.store = store


class StepAesGcm(Step):
    step = "aesGcm"
    __slots__ = ("source", "keyPartsPath", "ivPath", "payloadPath", "store")

    def __init__(self, source="response", keyPartsPath="playback.key_parts",
                 ivPath="playback.iv", payloadPath="playback.payload", store="plaintext"):
        self.source = source
        self.keyPartsPath = keyPartsPath
        self.ivPath = ivPath
        self.payloadPath = payloadPath
        self.store = store


class StepRhinoEval(Step):
    step = "rhinoEval"
    __slots__ = ("source", "objectName", "store")

    def __init__(self, source="response", objectName="svg", store="jsonResult"):
        self.source = source
        self.objectName = objectName
        self.store = store


class StepXorSig(Step):
    step = "xorSig"
    __slots__ = ("source", "store")

    def __init__(self, source="jsonResult", store="watchlink"):
        self.source = source
        self.store = store


class StepDelegate(Step):
    step = "delegate"
    __slots__ = ("url", "queryParam")

    def __init__(self, url="{url}", queryParam=""):
        self.url = url
        self.queryParam = queryParam


class StepIframe(Step):
    step = "iframe"
    __slots__ = ("source", "selector", "attribute", "exclude", "include", "base")

    def __init__(self, source="response", selector="iframe[src]", attribute="src",
                 exclude="", include="", base="{url}"):
        self.source = source
        self.selector = selector
        self.attribute = attribute
        self.exclude = exclude
        self.include = include
        self.base = base


class StepRedirect(Step):
    step = "redirect"
    __slots__ = ("source", "url")

    def __init__(self, source="finalUrl", url="{url}"):
        self.source = source
        self.url = url


class StepWebview(Step):
    step = "webview"
    __slots__ = ("url", "referer", "headers", "interceptPattern", "timeoutMs")

    def __init__(self, url="{url}", referer="", headers=None,
                 interceptPattern="(m3u8|master\\.txt)", timeoutMs=15000):
        self.url = url
        self.referer = referer
        self.headers = headers or {}
        self.interceptPattern = interceptPattern
        self.timeoutMs = timeoutMs


class ExtractorConfig:
    __slots__ = ("id", "name", "mainUrl", "requiresReferer", "idSource",
                 "variants", "steps", "videoReferer", "outputFilter")

    def __init__(self, id, name=None, mainUrl="https://example.com", requiresReferer=True,
                 idSource=None, variants=None, steps=None, videoReferer="", outputFilter="adaptive"):
        self.id = id
        self.name = name or id
        self.mainUrl = mainUrl
        self.requiresReferer = requiresReferer
        self.idSource = idSource
        self.variants = variants if variants else [ExtractorVariant()]
        self.steps = steps if steps is not None else []
        self.videoReferer = videoReferer
        self.outputFilter = outputFilter


KNOWN_EXTRACTOR_KEYS = {
    "id", "name", "mainUrl", "requiresReferer", "idSource", "variants", "steps",
    "videoReferer", "outputFilter", "type", "param", "pattern", "group", "selector",
    "attr", "headers", "referer", "userAgent", "url", "data", "jsonBody", "store",
    "filter", "universal", "startMarker", "endMarker", "template", "source", "path",
    "decodeUnicode", "base", "urlReplace", "keyPartsPath", "ivPath", "payloadPath",
    "objectName", "attribute", "exclude", "include", "queryParam", "interceptPattern",
    "timeoutMs", "storeFinalUrl", "step",
}


def _parse_extractor_variant(data):
    _warn_unknown_keys("Variant", data, KNOWN_EXTRACTOR_KEYS)
    return ExtractorVariant(
        name=_opt_string(data, "name", "default"),
        headers=_json_object_to_map(data.get("headers")),
        referer=_opt_string(data, "referer", ""),
        userAgent=_opt_string(data, "userAgent", ""),
    )


def _parse_id_source(data):
    if not isinstance(data, dict):
        return None
    _warn_unknown_keys("idSource", data, KNOWN_EXTRACTOR_KEYS)
    return IdSource(
        type=_opt_string(data, "type", "none"),
        param=_opt_string(data, "param", ""),
        pattern=_opt_string(data, "pattern", ""),
        group=_opt_int(data, "group", 1),
        selector=_opt_string(data, "selector", ""),
        attr=_opt_string(data, "attr", "src"),
    )


def _parse_extractor_step(data):
    step = _opt_string(data, "step", "")
    if not step:
        _warn("Step", f"Step without \"step\" key (mungkin typo): {str(data)[:80]}")
        return None
    _warn_unknown_keys(f"Step({step})", data, KNOWN_EXTRACTOR_KEYS)
    if step == "fetch":
        return StepFetch(
            url=_opt_string(data, "url", "{url}"),
            referer=_opt_string(data, "referer", ""),
            headers=_json_object_to_map(data.get("headers")),
            store=_opt_string(data, "store", "response"),
            urlReplace=_json_object_to_map(data.get("urlReplace")),
            storeFinalUrl=_opt_string(data, "storeFinalUrl", ""),
        )
    if step == "postForm":
        return StepPostForm(
            url=_opt_string(data, "url", ""),
            data=_json_object_to_map(data.get("data")),
            referer=_opt_string(data, "referer", ""),
            headers=_json_object_to_map(data.get("headers")),
            store=_opt_string(data, "store", "response"),
        )
    if step == "postJson":
        return StepPostJson(
            url=_opt_string(data, "url", ""),
            jsonBody=_opt_string(data, "jsonBody", ""),
            referer=_opt_string(data, "referer", ""),
            headers=_json_object_to_map(data.get("headers")),
            store=_opt_string(data, "store", "response"),
        )
    if step == "regex":
        return StepRegex(
            pattern=_opt_string(data, "pattern", ""),
            group=_opt_int(data, "group", 1),
            source=_opt_string(data, "source", "response"),
            filter=_opt_string(data, "filter", ""),
            universal=_opt_bool(data, "universal", False),
            decodeUnicode=_opt_bool(data, "decodeUnicode", False),
            store=_opt_string(data, "store", ""),
        )
    if step == "jsonPath":
        return StepJsonPath(
            path=_opt_string(data, "path", ""),
            source=_opt_string(data, "source", "response"),
            filter=_opt_string(data, "filter", ""),
            store=_opt_string(data, "store", ""),
        )
    if step == "constructUrl":
        return StepConstructUrl(
            template=_opt_string(data, "template", ""),
            store=_opt_string(data, "store", ""),
        )
    if step == "substring":
        return StepSubstring(
            startMarker=_opt_string(data, "startMarker", ""),
            endMarker=_opt_string(data, "endMarker", ""),
            source=_opt_string(data, "source", "response"),
            store=_opt_string(data, "store", ""),
        )
    if step == "resolveUrl":
        return StepResolveUrl(
            base=_opt_string(data, "base", "{url}"),
            source=_opt_string(data, "source", ""),
        )
    if step == "packedJs":
        return StepPackedJs(
            source=_opt_string(data, "source", "response"),
            store=_opt_string(data, "store", "decoded"),
        )
    if step == "aesGcm":
        return StepAesGcm(
            source=_opt_string(data, "source", "response"),
            keyPartsPath=_opt_string(data, "keyPartsPath", "playback.key_parts"),
            ivPath=_opt_string(data, "ivPath", "playback.iv"),
            payloadPath=_opt_string(data, "payloadPath", "playback.payload"),
            store=_opt_string(data, "store", "plaintext"),
        )
    if step == "rhinoEval":
        return StepRhinoEval(
            source=_opt_string(data, "source", "response"),
            objectName=_opt_string(data, "objectName", "svg"),
            store=_opt_string(data, "store", "jsonResult"),
        )
    if step == "xorSig":
        return StepXorSig(
            source=_opt_string(data, "source", "jsonResult"),
            store=_opt_string(data, "store", "watchlink"),
        )
    if step == "delegate":
        return StepDelegate(
            url=_opt_string(data, "url", "{url}"),
            queryParam=_opt_string(data, "queryParam", ""),
        )
    if step == "iframe":
        return StepIframe(
            source=_opt_string(data, "source", "response"),
            selector=_opt_string(data, "selector", "iframe[src]"),
            attribute=_opt_string(data, "attribute", "src"),
            exclude=_opt_string(data, "exclude", ""),
            include=_opt_string(data, "include", ""),
            base=_opt_string(data, "base", "{url}"),
        )
    if step == "redirect":
        return StepRedirect(
            source=_opt_string(data, "source", "finalUrl"),
            url=_opt_string(data, "url", "{url}"),
        )
    if step == "webview":
        return StepWebview(
            url=_opt_string(data, "url", "{url}"),
            referer=_opt_string(data, "referer", ""),
            headers=_json_object_to_map(data.get("headers")),
            interceptPattern=_opt_string(data, "interceptPattern", "(m3u8|master\\.txt)"),
            timeoutMs=_opt_long(data, "timeoutMs", 15000),
        )
    _warn("Step", f"Unknown step type: {step}")
    return None


def from_extractor_json(id, data):
    """Port of ExtractorConfigParser.fromExtractorJson."""
    _warn_unknown_keys(f"Config[{id}]", data, KNOWN_EXTRACTOR_KEYS)

    steps = []
    steps_arr = data.get("steps")
    if isinstance(steps_arr, list):
        for item in steps_arr:
            if isinstance(item, dict):
                s = _parse_extractor_step(item)
                if s is not None:
                    steps.append(s)

    variants = []
    variants_arr = data.get("variants")
    if isinstance(variants_arr, list):
        for item in variants_arr:
            if isinstance(item, dict):
                variants.append(_parse_extractor_variant(item))

    return ExtractorConfig(
        id=id,
        name=_opt_string(data, "name", id),
        mainUrl=_opt_string(data, "mainUrl", "https://example.com"),
        requiresReferer=_opt_bool(data, "requiresReferer", True),
        idSource=_parse_id_source(data.get("idSource")),
        variants=variants if variants else [ExtractorVariant()],
        steps=steps,
        videoReferer=_opt_string(data, "videoReferer", ""),
        outputFilter=_opt_string(data, "outputFilter", "adaptive"),
    )


# ─────────────────────────────────────────────────────────────
# Registries (read the runtime JSON files from disk)
# ─────────────────────────────────────────────────────────────

PROVIDER_IDS = {
    "Anichin": "anichin",
    "Animasu": "animasu",
    "Donghuastream": "donghuastream",
    "Dutamovie21": "dutamovie21",
    "IndoDrama21": "indodrama21",
    "LayarKaca21": "layarkaca21",
    "Samehadaku": "samehadaku",
}


def find_repo_root(start=None):
    """Find repo root (directory containing BaseProvider/)."""
    candidates = []
    if start:
        candidates.append(start)
    candidates.append(os.getcwd())
    for base in candidates:
        if base and os.path.isdir(os.path.join(base, "BaseProvider")):
            return os.path.abspath(base)
    return None


def config_dir(root):
    return os.path.join(root, "BaseProvider", "src", "main", "kotlin",
                        "com", "baseprovider", "config")


def extractor_config_dir(root):
    return os.path.join(config_dir(root), "extractors")


def load_bundled_provider(root, file_name):
    path = os.path.join(config_dir(root), f"{file_name}.json")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    pid = data.get("id", file_name)
    return from_json_provider(pid, data)


def get_provider(root, id):
    """Port of ConfigRegistry.get."""
    file_name = PROVIDER_IDS.get(id)
    if file_name is None:
        _warn("ConfigRegistry", f"Unknown provider: {id}, using GLOBAL fallback")
        return load_bundled_provider(root, "global") or ProviderConfig(id="GLOBAL")
    bundled = load_bundled_provider(root, file_name)
    if bundled is not None:
        return bundled
    _warn("ConfigRegistry", f"No config found for {id}, using GLOBAL fallback")
    return load_bundled_provider(root, "global") or ProviderConfig(id="GLOBAL")


def load_all_providers(root):
    """All configured providers in ConfigRegistry order."""
    return [get_provider(root, pid) for pid in PROVIDER_IDS.keys()]


def load_extractor_config(root, id):
    """Port of ExtractorConfigRegistry.get."""
    path = os.path.join(extractor_config_dir(root), f"{id}.json")
    if not os.path.isfile(path):
        _warn("ExtractorConfigRegistry", f"No config found for extractor: {id}")
        return None
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    config_id = data.get("id", id)
    return from_extractor_json(config_id, data)


def load_all_extractor_configs(root):
    """All config-driven extractor configs on disk."""
    d = extractor_config_dir(root)
    out = {}
    if not os.path.isdir(d):
        return out
    for name in sorted(os.listdir(d)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(d, name)
        with open(path, encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError:
                continue
        cid = data.get("id", name[:-5])
        out[cid] = from_extractor_json(cid, data)
    return out