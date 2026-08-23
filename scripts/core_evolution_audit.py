#!/usr/bin/env python3
"""
Audit evolusi inti OCE antar tag.
Fingerprint per milestone: daftar fun/val/object/class per file inti + kunci JSON config.
Output: laporan markdown ADDED/REMOVED antar milestone untuk review manual.
"""
import subprocess, re, json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "scripts/evolution_audit.md"

MILESTONES = [
    "v1.0.0", "v2.9.0", "v3.0.0", "v3.4.3", "v3.5.0", "v3.6.0",
    "v3.8.3", "v3.9.0", "v3.10.2", "v3.11.0", "v3.12.0",
    "v3.13.03", "v3.14.0", "v3.15.0", "HEAD",
]

CORE_FILES = [
    "ProviderScrapper.kt", "DetailPageScrapper.kt", "ProviderMapper.kt",
    "BaseProviderEngine.kt", "SelectorResolver.kt", "SelectorValidator.kt",
    "ProviderParser.kt", "LinkCollector.kt", "FallbackPipeline.kt",
    "ExtractorFallback.kt", "ExtractorRegistry.kt", "ConfigDrivenExtractor.kt",
    "MasterLinkGenerator.kt", "AdaptiveHeaderProbe.kt", "M3u8MasterVerifier.kt",
    "Logging.kt", "ProviderConfig.kt", "ProviderConfigParser.kt",
]

def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True,
                          cwd=ROOT).stdout

def show(tag, path):
    return git("show", f"{tag}:{path}")

def ls_kt(tag):
    out = git("ls-tree", "-r", "--name-only", tag)
    return [l for l in out.splitlines() if l.endswith(".kt")]

def find_file(tag, fname):
    for p in ls_kt(tag):
        if p.endswith(fname):
            return p
    return None

FUN_RE = re.compile(r'^\s*(?:suspend\s+)?fun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_`]+)')
DECL_RE = re.compile(r'^\s*(?:private\s+|internal\s+|public\s+)*(?:const\s+)?(?:val|var)\s+([A-Za-z0-9_]+)')
TYPE_RE = re.compile(r'^\s*(?:private\s+|internal\s+)*(?:class|object|interface|enum class)\s+([A-Za-z0-9_]+)')

def fingerprint(tag):
    result = {}   # fname -> {"path":..., "funs":set, "vals":set, "types":set}
    ktfiles = ls_kt(tag)
    for fname in CORE_FILES:
        path = find_file(tag, fname)
        entry = {"path": path, "funs": set(), "vals": set(), "types": set()}
        if path:
            src = show(tag, path)
            for line in src.splitlines():
                for rx, key in ((FUN_RE,"funs"),(DECL_RE,"vals"),(TYPE_RE,"types")):
                    m = rx.match(line)
                    if m: entry[key].add(m.group(1))
        result[fname] = entry
    # config global.json
    gpath = None
    for p in git("ls-tree","-r","--name-only",tag).splitlines():
        if p.endswith("config/global.json"): gpath = p; break
    gkeys = set()
    if gpath:
        try: gkeys = set(json.loads(show(tag,gpath)).keys())
        except Exception: pass
    result["__global_json_keys"] = gkeys
    return result

def main():
    snaps = {}
    for t in MILESTONES:
        try:
            snaps[t] = fingerprint(t)
            print(f"fingerprint {t} ok")
        except Exception as e:
            print(f"{t}: ERR {e}")
    lines = ["# Evolution Audit — Inti OCE antar Tag\n",
             "Format: per file, SIGNATURES YANG HILANG (ada di sebelumnya, tiada di selanjutnya)\n"]
    for i in range(1, len(MILESTONES)):
        a, b = MILESTONES[i-1], MILESTONES[i]
        if a not in snaps or b not in snaps: continue
        lines.append(f"\n## {a} -> {b}\n")
        sa, sb = snaps[a], snaps[b]
        for fname in CORE_FILES:
            ea, eb = sa.get(fname), sb.get(fname)
            if not ea or not ea["path"]:
                continue
            if not eb or not eb["path"]:
                lines.append(f"- **{fname} DIHAPUS** (dulu di `{ea['path']}`)")
                continue
            lost_funs = ea["funs"] - eb["funs"]
            lost_types = ea["types"] - eb["types"]
            if lost_funs or lost_types:
                parts = []
                if lost_funs: parts.append("fun hilang: " + ", ".join(sorted(lost_funs)))
                if lost_types: parts.append("type hilang: " + ", ".join(sorted(lost_types)))
                lines.append(f"- **{fname}**: " + "; ".join(parts))
        ga, gb = sa.get("__global_json_keys",set()), sb.get("__global_json_keys",set())
        lost_keys = ga - gb
        if lost_keys:
            lines.append(f"- **global.json kunci hilang**: {', '.join(sorted(lost_keys))}")
    OUT.write_text("\n".join(lines))
    print(f"\nreport -> {OUT}")

if __name__ == "__main__":
    main()
