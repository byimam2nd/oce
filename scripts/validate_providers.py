#!/usr/bin/env python3
"""Validate OCE provider config JSON files.

Mimics the runtime parsing in BaseProvider (ProviderConfigParser.kt) and
catches the class of configuration mistakes that are invisible at build time
(e.g. the missing `watchButtons` that broke Anichin movies).

Usage:
    python3 scripts/validate_providers.py [repo_root]
"""

import glob
import json
import os
import re
import sys

VALID_TYPES = {
    "Movie", "TvSeries", "Anime", "AnimeMovie",
    "AsianDrama", "Cartoon", "OVA",
}
SERIES_TYPES = {"TvSeries", "Anime", "AnimeMovie", "AsianDrama", "Cartoon", "OVA"}
MOVIE_TYPES = {"Movie", "AnimeMovie"}
REGEX_FIELDS = ["bloatRegex", "yearExtractorRegex", "hrefCleanRegex", "qualityStripRegex"]
REGISTRY_RE = re.compile(r"([A-Za-z0-9_]+)\(\)")


def find_root(start=None):
    """Find repo root (directory containing BaseProvider/)."""
    candidates = []
    if start:
        candidates.append(start)
    candidates.append(os.getcwd())
    for base in candidates:
        if base and os.path.isdir(os.path.join(base, "BaseProvider")):
            return base
    raise SystemExit(f"BaseProvider/ not found under {os.getcwd()}, pass repo_root as argument")


def load_registry_extractors(root):
    """Extract registered extractor names from ExtractorRegistry.kt listOf block."""
    path = os.path.join(
        root, "BaseProvider", "src", "main", "kotlin", "com", "baseprovider",
        "extractor", "ExtractorRegistry.kt")
    if not os.path.isfile(path):
        return set()
    with open(path, encoding="utf-8") as f:
        content = f.read()
    start = content.find("val list = listOf(")
    end = content.find("normalizedList", start)
    block = content[start:end] if start != -1 else content
    return set(REGISTRY_RE.findall(block))


def validate_config(path, extractors):
    errors, warnings = [], []
    with open(path, encoding="utf-8") as f:
        try:
            data = json.load(f)
        except json.JSONDecodeError as e:
            return [f"{os.path.basename(path)}: invalid JSON: {e}"], []

    name = data.get("id") or os.path.basename(path)

    if not isinstance(data.get("id"), str) or not data.get("id"):
        errors.append(f"{name}: missing string 'id'")
    elif os.path.basename(path)[:-5].lower() != data["id"].lower():
        errors.append(
            f"{name}: filename '{os.path.basename(path)}' does not match id='{data['id']}'")

    main_url = data.get("mainUrl", "")
    if not isinstance(main_url, str) or not main_url.startswith("http"):
        errors.append(f"{name}: mainUrl must be a non-empty http(s) URL, got {main_url!r}")

    types = data.get("supportedTypes", [])
    if not isinstance(types, list) or not types:
        errors.append(f"{name}: supportedTypes must be a non-empty list")
    else:
        unknown = [t for t in types if t not in VALID_TYPES]
        if unknown:
            errors.append(f"{name}: unknown supportedTypes {unknown} (valid: {sorted(VALID_TYPES)})")

    types_set = set(types) if isinstance(types, list) else set()

    for field in REGEX_FIELDS:
        val = data.get(field)
        if isinstance(val, str) and val:
            try:
                re.compile(val)
            except re.error as e:
                errors.append(f"{name}: {field} is not a valid regex: {e}")

    allowed = data.get("allowedExtractors", [])
    if isinstance(allowed, list):
        unknown_ext = [a for a in allowed if a and a not in extractors]
        if unknown_ext:
            errors.append(
                f"{name}: allowedExtractors reference unregistered extractors: {unknown_ext}")

    if types_set & MOVIE_TYPES and not (data.get("watchButtons") or ""):
        warnings.append(
            f"{name}: Movie/AnimeMovie supported but 'watchButtons' is empty — "
            "movie load will fall back to the page URL (root cause of the Anichin bug)")
    if types_set & SERIES_TYPES:
        if not (data.get("episodeItems") or ""):
            warnings.append(
                f"{name}: series types supported but 'episodeItems' is empty — "
                "episodes may not load")
        if not (data.get("episodeHref") or ""):
            warnings.append(
                f"{name}: series types supported but 'episodeHref' is empty — "
                "episode links unavailable")
    if not data.get("isJsonSearch") and not (data.get("searchItems") or ""):
        warnings.append(
            f"{name}: searchItems is empty — search results page may return nothing")

    lists = data.get("mainPageLists", [])
    if isinstance(lists, list):
        for item in lists:
            if not (isinstance(item, list) and len(item) == 2):
                errors.append(f"{name}: mainPageLists entry must be [url, label]")
                break

    return errors, warnings


def main():
    root = find_root(sys.argv[1] if len(sys.argv) > 1 else None)
    extractors = load_registry_extractors(root)
    config_dir = os.path.join(
        root, "BaseProvider", "src", "main", "kotlin", "com", "baseprovider",
        "config")
    files = sorted(glob.glob(os.path.join(config_dir, "*.json")))
    if not files:
        sys.exit(f"no config files found in {config_dir}")

    total_errors, total_warnings = 0, 0
    for path in files:
        errors, warnings = validate_config(path, extractors)
        total_errors += len(errors)
        total_warnings += len(warnings)
        for w in warnings:
            print(f"WARN  {w}")
        for e in errors:
            print(f"ERROR {e}")

    print(f"\n{len(files)} configs validated, "
          f"{total_errors} error(s), {total_warnings} warning(s)")
    if total_errors:
        sys.exit(1)


if __name__ == "__main__":
    main()