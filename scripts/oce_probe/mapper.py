"""Faithful Python port of core/ProviderMapper.kt.

  - toSearchResult(element, baseUrl)   -> SearchItem
  - extractMetadata(document, url)     -> MetadataPackage
  - extractEpisodes(document, url, seasonScript, epItems, poster) -> list[Episode]
"""

import json
import re
from dataclasses import dataclass, field
from typing import List, Optional

from .selectors import (
    EPISODE_NUMBER_REGEX,
    JUST_NUMBER_REGEX,
    FieldType,
    jsoup_text,
    safe_clean_bloat,
    safe_deduplicate,
    safe_extract_ep_num,
    safe_extract_image,
    safe_extract_year,
    select_attr,
)

TV_MOVIE = "Movie"
TV_SERIES = "TvSeries"
TV_ANIME = "Anime"
TV_ANIME_MOVIE = "AnimeMovie"
TV_ASIAN_DRAMA = "AsianDrama"
TV_CARTOON = "Cartoon"
TV_OVA = "OVA"

_TV_PATH_HINTS = ["/tv/", "/series/", "/anime/", "/drama/", "/episode/", "/eps/"]


@dataclass
class SearchItem:
    url: str = ""
    name: str = ""
    poster_url: str = ""
    year: Optional[int] = None
    description: str = ""
    badge: str = ""
    episode: Optional[int] = None
    episode_text: str = ""
    type: str = TV_SERIES
    movie_status: Optional[bool] = None
    score: Optional[float] = None
    dub_exist: bool = False


@dataclass
class MetadataPackage:
    title: str = "Unknown Title"
    poster: str = ""
    banner: str = ""
    description: str = ""
    year: Optional[int] = None
    status_text: str = ""
    status: str = "Completed"   # Ongoing | Completed
    tags: List[str] = field(default_factory=list)
    rating: str = ""
    imdb_id: str = ""
    tmdb_id: int = 0
    trailer: str = ""


@dataclass
class Episode:
    url: str = ""
    name: str = ""
    episode: Optional[int] = None
    season: Optional[int] = None
    description: str = ""
    runtime: Optional[int] = None
    poster_url: str = ""


def _select_validated_with_parent(resolver, element, selector, key, type_, extract):
    el = resolver.select_validated(element, selector, key, type_, extract)
    if el is None and element.parent is not None:
        el = resolver.select_validated(element.parent, selector, key, type_, extract)
    return el


def _select_first_with_parent(resolver, element, selector, key):
    el = resolver.select_first(element, selector, key)
    if el is None and element.parent is not None:
        el = resolver.select_first(element.parent, selector, key)
    return el


def _is_movie_href(config, href):
    has_tv_path = bool(config.tvPathSegment) and config.tvPathSegment in href
    url_looks_tv = any(h in href.lower() for h in _TV_PATH_HINTS)
    if has_tv_path or url_looks_tv:
        return False
    return (bool(config.moviePathSegment) and config.moviePathSegment in href) \
        or "movie" in href.lower()


def _guess_type(config, is_movie):
    if is_movie:
        return TV_MOVIE
    return TV_ANIME if TV_ANIME in config.supportedTypes else TV_SERIES


class ProviderMapper:
    def __init__(self, config, resolver):
        self.config = config
        self.resolver = resolver
        self._compiled = {}
        self.key = config.id

    def _compiled_regex(self, pattern):
        if pattern not in self._compiled:
            try:
                self._compiled[pattern] = re.compile(pattern)
            except re.error:
                self._compiled[pattern] = None
        return self._compiled[pattern]

    def _href_clean(self, href):
        pattern = self.config.hrefCleanRegex
        if not pattern or not self.config.hrefCleanReplace:
            return href
        rx = self._compiled_regex(pattern)
        if rx is None:
            return href
        try:
            return rx.sub(self.config.hrefCleanReplace, href)
        except Exception:
            return href

    # ── toSearchResult ──

    def to_search_result(self, element, base_url=None):
        config = self.config
        base = base_url or config.mainUrl
        key = self.key
        try:
            if config.searchTitle:
                title_el = _select_validated_with_parent(
                    self.resolver, element, config.searchTitle, f"{key}:searchTitle",
                    FieldType.TITLE, lambda it: (jsoup_text(it) or "").strip())
            else:
                title_el = element.select_one("h2, h3") if element else None
            if title_el is None:
                return None
            raw_title = (jsoup_text(title_el) or "").strip()
            if not raw_title:
                raw_title = select_attr(title_el, config.attrImage) or ""
            if not raw_title:
                raw_title = title_el.get("title", "") or ""
            if not raw_title:
                return None
            title = safe_clean_bloat(raw_title, raw_title, config.bloatRegexCompiled)
            title = safe_deduplicate(title)

            if config.searchHref:
                href_el = _select_first_with_parent(self.resolver, element, config.searchHref, f"{key}:searchHref")
            else:
                href_el = element.select_one("a") if element else None
                if href_el is None and element is not None and element.parent is not None:
                    href_el = element.parent.select_one("a")
            from .selectors import fix_url_smart
            href = fix_url_smart(href_el.get("href", "") if href_el is not None else "", base)
            href = self._href_clean(href)

            if config.searchPoster:
                poster_el = self.resolver.select_validated(
                    element, config.searchPoster, f"{key}:searchPoster",
                    FieldType.POSTER, lambda it: safe_extract_image(it, config.attrImage))
                poster = safe_extract_image(poster_el, config.attrImage) if poster_el is not None else ""
            else:
                img = element.select_one("img") if element else None
                poster = safe_extract_image(img, config.attrImage) if img is not None else ""
            poster = self._resize(poster, config.posterResizeUrl)

            rating = None
            if config.searchRating:
                el = self.resolver.select_first(element, config.searchRating, f"{key}:searchRating")
                rating = jsoup_text(el) if el is not None else None
            eps = None
            if config.searchEpText:
                el = self.resolver.select_first(element, config.searchEpText, f"{key}:searchEpText")
                eps = safe_extract_ep_num(jsoup_text(el)) if el is not None else None

            is_movie = _is_movie_href(config, href)
            item_type = _guess_type(config, is_movie)
            dub = bool(config.dubKeyword) and config.dubKeyword.lower() in (jsoup_text(element) or "").lower()

            return SearchItem(
                url=href, name=title, poster_url=poster,
                type=item_type, episode=eps, score=_to_score(rating),
                dub_exist=dub)
        except Exception:
            return None

    def _resize(self, url, resize_url):
        if not url or not resize_url:
            return url
        try:
            return resize_url.replace("{url}", url)
        except Exception:
            return url

    # ── extractMetadata ──

    def extract_metadata(self, document, current_url):
        config = self.config
        key = self.key
        raw_title = "Unknown Title"
        if config.loadTitle:
            v = self.resolver.text_validated(document, config.loadTitle, f"{key}:loadTitle", FieldType.TITLE)
            if v is None:
                v = self.resolver.text(document, config.loadTitle, f"{key}:loadTitle")
            raw_title = (v or "").strip() or "Unknown Title"
        title = safe_clean_bloat(raw_title, raw_title, config.bloatRegexCompiled)
        title = safe_deduplicate(title)

        poster = ""
        if config.loadPoster:
            el = self.resolver.select_validated(
                document, config.loadPoster, f"{key}:loadPoster", FieldType.POSTER,
                lambda it: safe_extract_image(it, config.attrImage))
            if el is not None:
                poster = safe_extract_image(el, config.attrImage) or ""

        banner = None
        if config.loadBanner:
            el = self.resolver.select_first(document, config.loadBanner, f"{key}:loadBanner")
            if el is not None:
                banner = safe_extract_image(el, config.attrImage)
        description = ""
        if config.loadDesc:
            description = self.resolver.text(document, config.loadDesc, f"{key}:loadDesc") or ""
        info_text = ""
        if config.loadInfoBox:
            info_text = self.resolver.text(document, config.loadInfoBox, f"{key}:loadInfoBox") or ""
        year = safe_extract_year(info_text)
        if year is None and config.yearSelector and config.yearExtractorRegex:
            el = self.resolver.select_first(document, config.yearSelector, f"{key}:yearSelector")
            rx = self._compiled_regex(config.yearExtractorRegex)
            if el is not None and rx is not None:
                m = rx.search(jsoup_text(el) or "")
                if m and m.groups():
                    try:
                        year = int(m.group(1))
                    except (TypeError, ValueError):
                        year = None
        status_text = self.resolver.text(document, config.loadStatus, f"{key}:loadStatus") \
            if config.loadStatus else None
        status = "Ongoing" if status_text and config.ongoingKeyword and \
            config.ongoingKeyword.lower() in status_text.lower() else "Completed"

        tags = []
        if config.loadTags:
            tags = [jsoup_text(el) for el in self.resolver.select(document, config.loadTags, f"{key}:loadTags")]
        rating = self.resolver.text(document, config.loadRating, f"{key}:loadRating") \
            if config.loadRating else None

        imdb_id = ""
        if config.imdbExternal:
            el = self.resolver.select_first(document, config.imdbExternal, f"{key}:imdbExternal")
            if el is not None:
                href = select_attr(el, config.attrHref) or ""
                for part in href.split("/"):
                    if part.startswith("tt"):
                        imdb_id = part
                        break
        tmdb_id = 0
        if config.tmdbExternal:
            el = self.resolver.select_first(document, config.tmdbExternal, f"{key}:tmdbExternal")
            if el is not None:
                href = select_attr(el, config.attrHref) or ""
                last = href.rstrip("/").split("/")[-1]
                try:
                    tmdb_id = int(last)
                except (TypeError, ValueError):
                    tmdb_id = 0
        trailer = ""
        if config.loadTrailer:
            el = self.resolver.select_first(document, config.loadTrailer, f"{key}:loadTrailer")
            if el is not None:
                if el.name == "iframe":
                    trailer = safe_extract_image(el, config.attrImage)
                else:
                    trailer = select_attr(el, config.attrHref) or ""

        return MetadataPackage(
            title=title, poster=poster, banner=banner, description=description,
            year=year, status_text=status_text, status=status, tags=tags,
            rating=rating, imdb_id=imdb_id, tmdb_id=tmdb_id, trailer=trailer)

    # ── extractEpisodes ──

    def extract_episodes(self, document, current_url, season_script, ep_items, poster):
        config = self.config
        episodes = []
        if season_script is not None:
            try:
                text = season_script.string if season_script.string is not None \
                    else season_script.get_text()
                root = json.loads(text or "{}")
                if isinstance(root, dict):
                    for arr in root.values():
                        if not isinstance(arr, list):
                            continue
                        for ep in arr:
                            if not isinstance(ep, dict):
                                continue
                            slug = ep.get("slug", "") or ""
                            if slug:
                                from .selectors import fix_url_smart
                                episodes.append(Episode(
                                    url=fix_url_smart(slug, current_url),
                                    season=_safe_int(ep.get("s")),
                                    episode=_safe_int(ep.get("episode_no")),
                                    name=f"{config.episodeKeyword} {_safe_int(ep.get('episode_no'))}",
                                ))
            except Exception:
                pass
        if not episodes:
            seen_nums = set()
            key = self.key
            for ep in ep_items:
                try:
                    anchor = None
                    if config.episodeHref:
                        anchor = self.resolver.select_first(ep, config.episodeHref, f"{key}:episodeHref")
                    if anchor is None:
                        anchor = ep.select_one("a") if ep else None
                    if anchor is None and ep is not None and ep.name == "a":
                        anchor = ep
                    if anchor is None:
                        continue
                    from .selectors import fix_url_smart
                    href = config.episodeDataUrlPattern.replace(
                        "{url}", fix_url_smart(anchor.get("href", "") or "", current_url))
                    if not href:
                        continue
                    title_el = None
                    if config.episodeTitle:
                        title_el = self.resolver.select_first(ep, config.episodeTitle, f"{key}:episodeTitle")
                    if title_el is None:
                        title_el = ep.select_one("a") if ep else None
                    if title_el is None and ep is not None and ep.name == "a":
                        title_el = ep
                    ep_num = None
                    if config.episodeNum:
                        el = self.resolver.select_first(ep, config.episodeNum, f"{key}:episodeNum")
                        if el is not None:
                            ep_num = safe_extract_ep_num(jsoup_text(el))
                    if ep_num is None and title_el is not None:
                        ep_num = safe_extract_ep_num(jsoup_text(title_el))
                    if ep_num is None:
                        ep_num = safe_extract_ep_num(jsoup_text(ep))
                    if ep_num is not None:
                        if ep_num in seen_nums:
                            continue
                        seen_nums.add(ep_num)
                    raw_name = (jsoup_text(title_el) if title_el is not None else "") or ""
                    raw_name = raw_name.strip()
                    is_just_number = bool(re.fullmatch(JUST_NUMBER_REGEX, raw_name))
                    name = "" if is_just_number else raw_name
                    desc = None
                    if config.episodeDesc:
                        d = self.resolver.text(ep, config.episodeDesc, f"{key}:episodeDesc")
                        desc = d
                    runtime = None
                    if config.episodeTime:
                        t = self.resolver.text(ep, config.episodeTime, f"{key}:episodeTime")
                        if t:
                            digits = "".join(ch for ch in t if ch.isdigit())
                            try:
                                runtime = int(digits) if digits else None
                            except (TypeError, ValueError):
                                runtime = None
                    img = ep.select_one("img") if ep else None
                    ep_poster = safe_extract_image(img, config.attrImage) if img is not None else poster
                    ep_poster = self._resize(
                        ep_poster,
                        config.thumbnailResizeUrl if config.thumbnailResizeUrl else config.posterResizeUrl)
                    episodes.append(Episode(
                        url=href, name=name, episode=ep_num,
                        description=desc, runtime=runtime, poster_url=ep_poster))
                except Exception:
                    continue
        if config.reverseEpisodes and season_script is None:
            episodes.reverse()
        return episodes


def _to_score(rating):
    if not rating:
        return None
    try:
        return float(rating.replace(",", "."))
    except (TypeError, ValueError):
        return None


def _safe_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0