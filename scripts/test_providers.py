#!/usr/bin/env python3
"""OCE provider probe — faithful local port of the runtime scraping pipeline.

Reads ONLY the runtime config JSONs (BaseProvider/.../config) and mirrors the
Kotlin logic. No build needed. Reports a per-provider stage matrix.

Usage:
  python3 scripts/test_providers.py --list
  python3 scripts/test_providers.py --providers anichin,samehadaku --stage load,links
  python3 scripts/test_providers.py --all --limit 3 --json
  python3 scripts/test_providers.py --selftest
"""

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from oce_probe import configs as cfgmod
from oce_probe.network import HttpClient
from oce_probe.pipeline import Orchestrator
from oce_probe.report import (
    compute_exit_code,
    render_json,
    render_text,
    render_tsv,
)
from oce_probe.registry import ExtractorRegistry
from oce_probe.verify import HeaderProbe, SkipProbe


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="OCE provider probe (faithful local port)")
    p.add_argument("--providers", default="",
                   help="comma-separated provider ids (default: all)")
    p.add_argument("--all", action="store_true", help="run all providers")
    p.add_argument("--stage", default="",
                   help="comma-separated stages: homepage,search,load,links (default: all)")
    p.add_argument("--list", action="store_true",
                   help="list providers + extractor coverage and exit")
    p.add_argument("--limit", type=int, default=3,
                   help="max items per stage (default 3)")
    p.add_argument("--timeout", type=int, default=15000, help="per-request ms")
    p.add_argument("--concurrency", type=int, default=1,
                   help="parallel link extraction (default 1)")
    p.add_argument("--delay", type=float, default=0.1, help="politeness delay s")
    p.add_argument("--relocate", action="store_true",
                   help="enable session-scoped fingerprint relocate (off by default)")
    p.add_argument("--no-probe", action="store_true",
                   help="skip the HTTP header probe (no video validity check)")
    p.add_argument("--no-deepscan", action="store_true",
                   help="skip the deep-scan HTML fallback")
    p.add_argument("--json", action="store_true", help="emit JSON report")
    p.add_argument("--tsv", action="store_true", help="emit TSV report")
    p.add_argument("--quiet", action="store_true", help="suppress warnings")
    p.add_argument("--verbose", action="store_true", help="print per-item details")
    p.add_argument("--fail-on", default="logic",
                   help="exit-2 categories: logic,infra (default logic)")
    p.add_argument("--discover", action="store_true",
                   help="analyze extractor links: test HTTP status, HLS detection, "
                        "speed for each streaming URL from the extractor")
    p.add_argument("--selftest", action="store_true",
                   help="run offline deterministic selftests and exit")
    return p.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    root = cfgmod.find_repo_root()
    if root is None:
        print("ERROR: repo root (BaseProvider/) not found", file=sys.stderr)
        return 3

    if args.quiet:
        import warnings
        warnings.filterwarnings("ignore")

    if args.selftest:
        return run_selftest(root)

    providers = cfgmod.load_all_providers(root)
    extractor_configs = cfgmod.load_all_extractor_configs(root)
    registry = ExtractorRegistry(extractor_configs)

    if args.list:
        print(f"repo root: {root}")
        print("\nproviders:")
        for p_ in providers:
            print(f"  {p_.id:<14} {p_.mainUrl:<40} lists={len(p_.mainPageLists)} "
                  f"searchItems={bool(p_.searchItems)} mirror={len(p_.mirrorUrls)}")
        print(f"\nextractor configs on disk: {len(extractor_configs)}")
        cfg_driven = [e for e in registry.entries if e.kind == "config"]
        legacy = [e for e in registry.entries if e.kind == "legacy"]
        print(f"registry: {len(cfg_driven)} config-driven, {len(legacy)} legacy-only "
              f"({', '.join(e.id for e in legacy)})")
        return 0

    if args.providers:
        wanted = {s.strip() for s in args.providers.split(",") if s.strip()}
    else:
        wanted = {p_.id for p_ in providers}
    providers = [p_ for p_ in providers if p_.id in wanted]

    if args.stage:
        stages = [s.strip() for s in args.stage.split(",") if s.strip()]
    else:
        stages = ["homepage", "search", "load", "links"]

    http = HttpClient(timeout_ms=args.timeout, delay_s=args.delay)
    if args.no_probe:
        probe = SkipProbe()
    else:
        probe = HeaderProbe(http)

    results = []
    for p_ in providers:
        if args.discover:
            # ── link analysis mode ─────────────────────────────────────
            orch = Orchestrator(
                p_, http=http, probe=SkipProbe(), registry=registry,
                enable_relocate=args.relocate, no_deepscan=args.no_deepscan,
                concurrency=1, limit=args.limit, stages=[])
            analysis_results, status, reason, summary = orch.analyze_extractor_links(limit=args.limit)
            class _AnalysisResult:
                provider_id = p_.id
                provider_name = p_.id
                main_url = p_.mainUrl
                elapsed_s = summary.get("elapsed_s", 0)
                stages = {}
            results.append(_AnalysisResult())
            setattr(results[-1], "_analysis", analysis_results)
            setattr(results[-1], "_analysis_reason", reason)
            setattr(results[-1], "_analysis_summary", summary)
            setattr(results[-1], "_analysis_status", status)
        else:
            orch = Orchestrator(
                p_, http=http, probe=probe, registry=registry,
                enable_relocate=args.relocate, no_deepscan=args.no_deepscan,
                concurrency=args.concurrency, limit=args.limit, stages=stages)
            results.append(orch.run())

    if args.json:
        print(render_json(results))
    elif args.tsv:
        print(render_tsv(results))
    else:
        print(render_text(results, verbose=args.verbose))
        total = sum(r.elapsed_s for r in results)
        print(f"\ntotal elapsed: {total:.1f}s for {len(results)} provider(s)")

    exit_code = compute_exit_code(results, fail_on={s for s in args.fail_on.split(",")})
    return exit_code


# ── offline deterministic selftests ──


def run_selftest(root):
    failures = []
    passed = 0

    def check(name, cond, detail=""):
        nonlocal passed
        if cond:
            passed += 1
        else:
            failures.append(f"{name}: {detail}")

    # 1. configs load
    providers = cfgmod.load_all_providers(root)
    check("load_all_providers", len(providers) == 7,
          f"got {len(providers)}")
    check("provider_anichin_main", any(p_.id == "Anichin" and "anichin" in p_.mainUrl
                                       for p_ in providers), "anichin mainUrl")
    ext = cfgmod.load_all_extractor_configs(root)
    check("load_extractor_configs", len(ext) >= 30, f"got {len(ext)}")
    byse = cfgmod.load_extractor_config(root, "ByseSX")
    check("byse_steps", byse is not None and len(byse.steps) == 7,
          f"steps={len(byse.steps) if byse else 0}")

    # 2. packed JS decode
    from oce_probe.crypto import decode_packed_js, find_packed_js_in_page
    payload = "var a=0, b=1;"
    decoded = decode_packed_js(payload, ["one", "two", "three"], 36)
    check("decode_packed_js", decoded == "var a=one, b=two;", decoded)
    # word-boundary containment: numbers inside identifiers are NOT replaced
    out = decode_packed_js("x0y1x", ["zero", "one", "two"], 36)
    check("decode_packed_js_boundary", out == "x0y1x", out)
    page = ('<html><script type="text/javascript">'
            'eval(function(p,a,c,k,e,d){e=function(c){return c};'
            "if(!''.replace(/^/,String)){while(c--){d[c]=k[c]||c}k=[function(e){return d[e]}]}"
            "e=function(){return'\\\\w+'};c=1;while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+"
            "e(c)+'\\\\b','g'),k[c])}}return p;}('0',36,1,'hello'.split('|'),0,{}))"
            "</script></html>")
    found = find_packed_js_in_page(page)
    check("find_packed_js_in_page", found is not None, str(found))

    # 3. m3u8 verifier
    from oce_probe.verify import VerdictAllMalformed, VerdictClean, VerdictValid, classify, parse_variants
    clean = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\n720p/index.m3u8\n"
    v = classify("http://h/master.m3u8", parse_variants(clean))
    check("m3u8_clean", isinstance(v, VerdictClean), str(v))
    malformed = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\n\n"
    v = classify("http://h/master.m3u8", parse_variants(malformed))
    check("m3u8_allmalformed", isinstance(v, VerdictAllMalformed), str(v))
    mixed = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\n"
             "v720/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=500\n\n")
    v = classify("http://h/master.m3u8", parse_variants(mixed))
    check("m3u8_valid", isinstance(v, VerdictValid) and len(v.variants) == 1, str(v))

    # 4. aes-gcm roundtrip
    from oce_probe.crypto import aes_gcm_decrypt, b64_url_decode
    from Crypto.Cipher import AES as PyAES
    import os as _os
    key = _os.urandom(32)
    nonce = _os.urandom(12)
    plain = "sources:[{url:'https://x/v.mp4'}]"
    cipher = PyAES.new(key, PyAES.MODE_GCM, nonce=nonce)
    ct, tag = cipher.encrypt_and_digest(plain.encode())
    import base64 as _b64

    def b64u(b):
        return _b64.urlsafe_b64encode(b).rstrip(b"=").decode()

    key_parts = [b64u(key[:16]), b64u(key[16:])]
    iv = b64u(nonce)
    payload = b64u(ct + tag)
    dec = aes_gcm_decrypt(key_parts, iv, payload)
    check("aes_gcm_roundtrip", dec == plain, dec)

    # 5. universal video url regex
    from oce_probe.extractor import extract_all_video_urls, filter_master_m3u8
    urls = extract_all_video_urls('"https://a.com/v.mp4" and "//cdn.b/x.m3u8?token=1"')
    check("universal_regex", len(urls) == 2 and all(u.startswith("http") for u in urls),
          str(urls))
    masters = filter_master_m3u8(["http://a/1.m3u8", "http://a/master.m3u8"])
    check("filter_master", masters == ["http://a/master.m3u8"], str(masters))

    # 6. domain helpers
    from oce_probe.registry import is_direct_media_url, normalize_domain
    check("normalize_domain", normalize_domain("https://www.Example.com/path") == "www.example.com")
    check("is_direct", is_direct_media_url("http://x/y.m3u8") and not is_direct_media_url("http://x/y.html"))

    # 7. json path
    from oce_probe.extractor import resolve_json_path
    js = json.dumps({"playback": {"key_parts": ["a", "b"], "iv": "iv", "payload": "p"},
                     "sources": [{"url": "u1"}, {"url": "u2"}]})
    check("jsonpath_keyparts", isinstance(resolve_json_path(js, "playback.key_parts"), list))
    check("jsonpath_sources", resolve_json_path(js, "sources[].url") == ["u1", "u2"])

    # 8. blank link rejection
    from oce_probe.extractor import create_smart_link
    from oce_probe.verify import SkipProbe
    from oce_probe.network import HttpClient
    h = HttpClient()
    check("smartlink_blank", create_smart_link(h, SkipProbe(), "X", "  ", None) == [])

    # 9. selector helpers
    from oce_probe.selectors import safe_deduplicate, safe_extract_ep_num, safe_extract_year
    check("dedup", safe_deduplicate("Naruto - Naruto") == "Naruto",
          safe_deduplicate("Naruto - Naruto"))
    check("epnum", safe_extract_ep_num("Episode 12 Full") == 12)
    check("year", safe_extract_year("Sub Indo 2023 HD") == 2023)

    print(f"selftest: {passed} passed, {len(failures)} failed")
    for f in failures:
        print(f"  FAIL {f}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())