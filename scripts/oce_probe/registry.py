"""Port of DomainHelpers.kt + ExtractorRegistry.kt (matching logic)."""

import re

DOMAIN_ALIASES = {
    "dailyotion.com": "dailymotion.com",
}

DIRECT_MEDIA_EXTENSIONS = [".mp4", ".m3u8", ".mkv", ".mpd"]


def normalize_domain(value, strip_www=False):
    base = re.sub(r"^https?://", "", value or "").split("/")[0].lower()
    if not base:
        return value
    return base[4:] if strip_www and base.startswith("www.") else base


def normalize_extractor_domain(value):
    return normalize_domain(value, strip_www=True)


def fix_known_domain_aliases(value):
    for alias, canonical in DOMAIN_ALIASES.items():
        if alias in (value or "").lower():
            idx = (value or "").lower().index(alias)
            return (value or "")[:idx] + canonical + (value or "")[idx + len(alias):]
    return value


def is_direct_media_url(value):
    return any(ext in (value or "").lower() for ext in DIRECT_MEDIA_EXTENSIONS)


# Legacy extractor ids that are NOT config-driven (runtime uses the Kotlin class).
LEGACY_ONLY_IDS = [
    "Odnoklassniki", "VideoplayerVip", "Anonmp4",
]

# Config-driven ids (runtime: ConfigDrivenExtractor from JSON config).
CONFIG_DRIVEN_IDS = {
    "AnichinStream", "EmTurbovid", "Rumble", "Voe",
    "AWSStream", "Hownetwork", "Cloudhownetwork", "PlayCdn",
    "MegaPlay", "Gdplayer", "Dailymotion", "LuluStream",
    "Filedon", "Xtwap",
    "StreamRuby", "Svanila", "Svilla", "Movearnpre",
    "Minochinos", "Morencius", "Wishfast", "AbyssPlayer",
    "ByseSX", "Vidguardto2",
    "BloggerVideo", "PlayPutarIn", "Lk21PlayerPage",
    "VideoNodePage", "ShortIcu", "PlayStreamplay",
    "Dhcplay", "StreamHG",
    "Krakenfiles",
    "AnichinPlayer",
}

LEGACY_LIST = [
    "Odnoklassniki", "Rumble", "StreamRuby", "Svanila", "Svilla",
    "ByseSX", "Hownetwork", "Cloudhownetwork", "PlayStreamplay",
    "AnichinStream", "AbyssPlayer", "Filedon", "BloggerVideo", "Wishfast",
    "Minochinos", "ShortIcu", "PlayPutarIn", "StreamHG", "Morencius",
    "MegaPlay", "AWSStream", "LuluStream", "Dhcplay", "Voe", "Xtwap",
    "Gdplayer", "Vidguardto2", "Movearnpre", "Lk21PlayerPage",
    "VideoNodePage", "Dailymotion", "PlayCdn", "EmTurbovid", "Krakenfiles",
    "VideoplayerVip", "Anonmp4", "AnichinPlayer",
]


class RegistryEntry:
    __slots__ = ("id", "config", "kind")

    def __init__(self, id, config, kind):
        self.id = id
        self.config = config      # ExtractorConfig or None (legacy)
        self.kind = kind          # "config" | "legacy"


class ExtractorRegistry:
    """Port of ProviderExtractors: build + host matching."""

    def __init__(self, configs_by_id=None):
        self._configs = configs_by_id or {}
        self._list = self._build_list()
        self._normalized = [
            (normalize_extractor_domain(e.config.mainUrl), e)
            for e in self._list if e.config is not None
        ]

    def _build_list(self):
        out = []
        for id in LEGACY_LIST:
            if id in CONFIG_DRIVEN_IDS:
                cfg = self._configs.get(id)
                if cfg is not None:
                    out.append(RegistryEntry(id, cfg, "config"))
                    continue
            out.append(RegistryEntry(id, None, "legacy"))
        return out

    @property
    def entries(self):
        return self._list

    def matching(self, url):
        url_domain = normalize_domain(url)
        return [
            e for (domain, e) in self._normalized
            if url_domain == domain or url_domain.endswith("." + domain)
        ]

    def has_matching(self, url):
        return len(self.matching(url)) > 0