"""Faithful Python port of collector/LinkCollector.kt.

Collects candidate media links from a detail/load page:
  - collectAjaxPlayers   (EastPlay / JSON AJAX players, POST to ajaxPlayerUrl)
  - collectLinkOptions   (linkOptions selector, a[data-url]/a[href])
  - collectDownloadItems (downloadItems selector)
  - collectSwitchVideoButtons (onclick switchVideo('...') regex)
  - collectIframes       (iframeTag + iframeSources attrs)
"""

import json
import re

from .selectors import select_attr

SWITCH_VIDEO_REGEX = re.compile(
    r"""switchVideo\s*\(\s*['"]([^'"]+)['"]""", re.IGNORECASE)


class LinkCollector:
    def __init__(self, config, resolver, http):
        self.config = config
        self.resolver = resolver
        self.http = http
        self.key = f"{config.id}"

    # ── collectAjaxPlayers ──

    def collect_ajax_players(self, document, current_url, links):
        cfg = self.config
        if not cfg.ajaxPlayerUrl or not cfg.selectorJsonData:
            return
        if not cfg.ajaxPlayerUrl.startswith("http"):
            return
        el = self.resolver.select_first(
            document, cfg.selectorJsonData, f"{self.key}:selectorJsonData")
        if el is None:
            return
        east_post_id = el.get("data-post", "") or ""
        if east_post_id:
            options = self.resolver.select(
                document, cfg.selectorJsonData, f"{self.key}:selectorJsonData")
            self._collect_east_play_players(
                current_url, east_post_id, options, links)
        else:
            self._collect_json_players(current_url, el, links)

    def _collect_json_players(self, current_url, el, links):
        cfg = self.config
        try:
            text = el.string if el.string is not None else el.get_text()
            data = json.loads(text or "{}")
            pid = data.get("id", "") if isinstance(data, dict) else ""
            if not pid:
                return
            resp = self.http.post_form(
                cfg.ajaxPlayerUrl,
                data={"id": pid},
                headers=cfg.globalHeaders,
                referer=current_url)
            doc = _parse(resp.text)
            for item in doc.select("li, a, option"):
                label = _text(item).strip()
                raw = select_attr(item, cfg.attrValue) or item.get("href") or ""
                if raw:
                    links.add((raw, label or None))
        except Exception:
            pass

    def _collect_east_play_players(self, current_url, post_id, options, links):
        cfg = self.config
        for opt in options:
            nume = opt.get("data-nume", "") or ""
            if not nume:
                continue
            type_ = opt.get("data-type", "") or "schtml"
            label = _text(opt).strip()
            try:
                resp = self.http.post_form(
                    cfg.ajaxPlayerUrl,
                    data={"action": "player_ajax", "post": post_id,
                          "nume": nume, "type": type_},
                    headers=cfg.globalHeaders,
                    referer=current_url)
                doc = _parse(resp.text)
                found = []
                iframes = doc.select("iframe")
                if iframes:
                    for f in iframes:
                        src = f.get("data-src", "") or f.get("src", "") or ""
                        if src:
                            found.append((src, label or None))
                else:
                    for v in doc.select("video source, video"):
                        src = v.get("data-src", "") or v.get("src", "") or ""
                        if src:
                            found.append((src, label or None))
                for item in found:
                    links.add(item)
            except Exception:
                continue

    # ── collectLinkOptions ──

    def collect_link_options(self, document, links):
        cfg = self.config
        if not cfg.linkOptions:
            return
        matches = self.resolver.select(document, cfg.linkOptions, f"{self.key}:linkOptions")
        for container in matches:
            anchors = container.select("a")
            if anchors:
                for a in anchors:
                    link = a.get("data-url", "") or a.get("href", "") or ""
                    if link:
                        links.add((link, _text(a)))
            else:
                raw = select_attr(container, cfg.attrValue) or container.get("href", "") or ""
                if raw:
                    links.add((raw, _text(container)))

    # ── collectDownloadItems ──

    def collect_download_items(self, document, links):
        cfg = self.config
        if not cfg.downloadItems:
            return
        matches = self.resolver.select(document, cfg.downloadItems, f"{self.key}:downloadItems")
        for container in matches:
            for a in container.select("a"):
                href = a.get("href", "") or ""
                if href:
                    links.add((href, _text(a)))

    # ── collectSwitchVideoButtons ──

    def collect_switch_video_buttons(self, document, current_url, links):
        cfg = self.config
        if not cfg.switchVideoSelector:
            return
        matches = self.resolver.select(document, cfg.switchVideoSelector, f"{self.key}:switchVideoSelector")
        for el in matches:
            onclick = el.get("onclick", "") or ""
            if not onclick:
                continue
            for m in SWITCH_VIDEO_REGEX.finditer(onclick):
                from .selectors import fix_url_smart
                url = fix_url_smart(m.group(1).strip(), current_url)
                if url:
                    links.add((url, _text(el).strip()))

    # ── collectIframes ──

    def collect_iframes(self, document, links):
        cfg = self.config
        matches = self.resolver.select(document, cfg.iframeTag, f"{self.key}:iframeTag") \
            if cfg.iframeTag else []
        for el in matches:
            for attr in cfg.iframeSources:
                s = el.get(attr, "") or ""
                if s and s != "about:blank":
                    links.add((s, None))


def _parse(text):
    from .selectors import parse_html
    return parse_html(text or "")


def _text(el):
    from .selectors import jsoup_text
    return jsoup_text(el)