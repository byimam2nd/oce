"""Report rendering for the probe matrix (text / JSON / TSV)."""

import json
from .pipeline import ProviderResult


STAGE_LABELS = ["homepage", "search", "load", "links"]

# reason -> category (used for exit-code / --fail-on).
# "empty" (0 items, mis. CF challenge / query tanpa hasil) = perilaku runtime yang
# sama persis (extract mengembalikan list kosong) -> dilaporkan, bukan kegagalan.
CATEGORY_LOGIC = {"logic-broke"}
CATEGORY_INFRA = {"cf-blocked", "host-down", "content-removed", "network-blip"}
CATEGORY_OTHER = {"ok", "skip", "empty", "not-replicable", "timeout"}


def stage_category(reason):
    if not reason or reason in CATEGORY_OTHER:
        return "other"
    if reason in CATEGORY_LOGIC:
        return "logic"
    if reason in CATEGORY_INFRA:
        return "infra"
    return "other"


def _fmt_reason(sr):
    if sr is None or not getattr(sr, "reason", ""):
        return "-"
    return sr.reason


def _disc_link_summary(r):
    """Helper to display discovery links in render_text."""
    links = getattr(r, "_disc_links", None)
    reason = getattr(r, "_disc_reason", "")
    if not links:
        return reason or "-"
    # Show first few links
    preview = "\n        ".join(f"- [{ln.split('//')[-1].split('/')[0]}] {ln[:80]}" for ln in links[:5])
    return f"{reason}\n        {preview}"


def render_text(results, verbose=False):
    lines = []
    header = f"{'provider':<14} {'mainUrl':<34} {'home':<7} {'search':<8} {'load':<7} {'links':<7} {'time':<6}"
    lines.append(header)
    lines.append("-" * len(header))
    for r in results:
        home = _fmt_reason(r.stages.get("homepage"))
        search = _fmt_reason(r.stages.get("search"))
        load = _fmt_reason(r.stages.get("load"))
        links = _fmt_reason(r.stages.get("links"))
        disc_info = ""
        if hasattr(r, "_analysis"):
            analysis = getattr(r, "_analysis", [])
            summary = getattr(r, "_analysis_summary", {})
            ok_count = summary.get("ok_200", 0)
            hls_count = summary.get("hls", 0)
            total = summary.get("total_links", 0)
            disc_info = f"  [analysis] {ok_count}/{total} ok, {hls_count} HLS"
            if analysis:
                disc_info += "\n"
                for a in analysis[:3]:
                    status_sym = "OK" if a["status"] == 200 else f"ERR:{a['status']}"
                    type_sym = "HLS" if a["is_hls"] else ("DIR" if a["is_direct"] else "---")
                    disc_info += f"          [{status_sym}] [{type_sym}] {a['response_ms']}ms {a['url'][:60]}\n"
        lines.append(
            f"{r.provider_id:<14} {r.main_url:<34} {home:<7} {search:<8} {load:<7} {links:<7} {r.elapsed_s:>5.1f}{disc_info}")
    lines.append("")
    if verbose:
        for r in results:
            lines.append(f"=== {r.provider_id} ===")
            for stage in ["homepage", "search", "load", "links"]:
                sr = r.stages.get(stage)
                if sr is None:
                    continue
                lines.append(f"  [{stage}] ok={sr.ok} reason={sr.reason} count={sr.count}")
                if sr.detail:
                    lines.append(f"          detail: {sr.detail}")
            if hasattr(r, "_analysis"):
                analysis = getattr(r, "_analysis", [])
                summary = getattr(r, "_analysis_summary", {})
                lines.append(f"  [analysis] {summary.get('ok_200', 0)}/{summary.get('total_links', 0)} ok, {summary.get('hls', 0)} HLS")
                for a in analysis:
                    status_sym = "OK" if a["status"] == 200 else f"ERR:{a['status']}"
                    type_sym = "HLS" if a["is_hls"] else ("DIR" if a["is_direct"] else "---")
                    err_info = f" err={a['error']}" if a.get("error") else ""
                    lines.append(f"    [{status_sym}] [{type_sym}] {a['response_ms']}ms {a['title'][:30]}")
                    lines.append(f"           {a['url'][:80]}{err_info}")
            links_stage = r.stages.get("links")
            if links_stage and links_stage.links:
                lines.append("  links:")
                for l in links_stage.links:
                    lines.append(f"    - [{l.link_type}] {l.name} {l.url}")
    return "\n".join(lines)


def render_json(results):
    out = []
    for r in results:
        entry = {
            "provider_id": r.provider_id,
            "provider_name": r.provider_name,
            "main_url": r.main_url,
            "elapsed_s": round(r.elapsed_s, 2),
            "stages": {},
        }
        for stage in STAGE_LABELS:
            sr = r.stages.get(stage)
            if sr is None:
                continue
            entry["stages"][stage] = {
                "ok": sr.ok,
                "count": sr.count,
                "reason": sr.reason,
                "detail": sr.detail,
                "samples": sr.samples[:10],
                "links": [
                    {"type": l.link_type, "name": l.name, "url": l.url,
                     "quality": l.quality}
                    for l in sr.links[:20]
                ],
            }
        if hasattr(r, "_analysis"):
            entry["analysis"] = getattr(r, "_analysis", [])
            entry["analysis_summary"] = getattr(r, "_analysis_summary", {})
            entry["analysis_status"] = getattr(r, "_analysis_status", "")
        out.append(entry)
    return json.dumps(out, indent=2)


def render_tsv(results):
    lines = ["provider\tstage\tok\tcount\treason\tdetail"]
    for r in results:
        for stage in STAGE_LABELS:
            sr = r.stages.get(stage)
            if sr is None:
                continue
            lines.append("\t".join([
                r.provider_id, stage, str(sr.ok), str(sr.count),
                sr.reason or "", (sr.detail or "").replace("\t", " ")]))
        if hasattr(r, "_analysis"):
            for a in getattr(r, "_analysis", []):
                status_sym = "ok" if a["status"] == 200 else f"err:{a['status']}"
                type_sym = "hls" if a["is_hls"] else ("direct" if a["is_direct"] else "unknown")
                lines.append(f"{r.provider_id}\tanalysis\t{status_sym}\t{a['response_ms']}\t{type_sym}\t{a['url'][:80]}")
    return "\n".join(lines)


def compute_exit_code(results, fail_on):
    """Exit 0 = no logic failure; 1 = logic-broke present; 2 = only infra fails."""
    logic_fail = False
    infra_fail = False
    for r in results:
        for stage in STAGE_LABELS:
            sr = r.stages.get(stage)
            if sr is None:
                continue
            if sr.reason == "skip" or sr.reason == "ok":
                continue
            cat = stage_category(sr.reason)
            if cat == "logic":
                logic_fail = True
            if cat == "infra":
                infra_fail = True
    if logic_fail:
        return 1
    if "infra" in fail_on and infra_fail:
        return 2
    return 0