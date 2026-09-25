#!/usr/bin/env python3
"""Provider health-check report dari observability Supabase (OCE).

Membaca scrape_runs + scrape_steps (anon key, SELECT-only), membandingkan
jendela saat ini (default 24h) dengan baseline (24h sebelumnya), lalu
menghasilkan laporan Markdown yang menandai provider menurun / mati dan
signature extractor yang gagal. Dipakai oleh workflow health-check.

Env:
  SUPABASE_URL, SUPABASE_ANON_KEY  (wajib)
  HEALTH_WINDOW_HOURS              (default 24)
  HEALTH_BASELINE_HOURS            (default 24)

Output: laporan Markdown ke stdout. `--fail-on-issues` → exit 1 bila ada
GEGALA (regresi baru/outage baru/signature gagal tak dikenal).
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from collections import Counter

BASE_URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
ANON_KEY = os.environ.get("SUPABASE_ANON_KEY", "")
WINDOW_H = int(os.environ.get("HEALTH_WINDOW_HOURS", "24"))
BASELINE_H = int(os.environ.get("HEALTH_BASELINE_HOURS", "24"))
PAGE = 500
FLAGS = []


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(ts: datetime) -> str:
    return ts.isoformat(timespec="seconds")


def api_get(path: str, q: list[tuple[str, str]]) -> list:
    url = f"{BASE_URL}/rest/v1/{path}?{urllib.parse.urlencode(q)}"
    rows: list = []
    offset = 0
    while True:
        req = urllib.request.Request(url, headers={
            "apikey": ANON_KEY,
            "Authorization": f"Bearer {ANON_KEY}",
            "Accept": "application/json",
            "Range-Unit": "items",
            "Range": f"{offset}-{offset + PAGE - 1}",
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            raise SystemExit(
                f"Supabase query gagal ({path}): HTTP {e.code} {e.read().decode()[:300]}")
        batch = json.loads(raw) if raw else []
        rows.extend(batch)
        if len(batch) < PAGE:
            break
        offset += PAGE
    return rows


def classify(run: dict) -> str | None:
    status = run.get("status")
    if status == "success":
        return "ok"
    if status == "partial":
        return "partial"
    if status == "failed":
        msg = run.get("error_message") or ""
        if "EXTRACT=" in msg:
            return "broken_extract"
        return "broken_scrape"
    return None


def sig_of(chain: str | None) -> str:
    if not chain:
        return "<none>"
    return chain.split(":", 1)[0].strip()


def main() -> None:
    if not BASE_URL or not ANON_KEY:
        raise SystemExit("SUPABASE_URL / SUPABASE_ANON_KEY wajib di-set")
    now = utc_now()
    win_from = now - timedelta(hours=WINDOW_H)
    base_from = win_from - timedelta(hours=BASELINE_H)

    # sumber: id -> (code, name)
    sources = {}
    for s in api_get("sources", [
        ("select", "id,code,name"), ("order", "code.asc"),
        ("limit", "1000")]):
        sources[s["id"]] = (s.get("code") or "?", s.get("name") or "?")
    if not sources:
        print("# Provider Health Report\n\n>Tidak ada sumber terdaftar — database kosong?\n")
        return

    runs = api_get("scrape_runs", [
        ("select", "id,source_id,context,status,started_at,error_message,error_type"),
        ("context", "eq.EPISODE"),
        ("started_at", f"gte.{iso(base_from)}"),
        ("order", "started_at.desc"),
    ])

    def bucket(lo: datetime, hi: datetime):
        agg = {}
        for r in runs:
            st = r.get("started_at") or ""
            try:
                t = datetime.fromisoformat(st)
            except ValueError:
                continue
            if not (lo <= t < hi):
                continue
            klaz = classify(r)
            if klaz is None:
                continue
            sid = r.get("source_id")
            a = agg.setdefault(sid, Counter())
            a[klaz] += 1
        return agg

    cur = bucket(win_from, now)
    base = bucket(base_from, win_from)

    # steps: pola extractor gagal pada jendela berjalan
    steps_cur = api_get("scrape_steps", [
        ("select", "extractor_chain,error_type,created_at"),
        ("kind", "eq.EXTRACT"), ("status", "in.(failed,timeout)"),
        ("created_at", f"gte.{iso(win_from)}"),
        ("order", "created_at.desc"),
    ])
    steps_base = api_get("scrape_steps", [
        ("select", "extractor_chain,error_type,created_at"),
        ("kind", "eq.EXTRACT"), ("status", "in.(failed,timeout)"),
        ("created_at", f"gte.{iso(base_from)}"),
        ("created_at", f"lt.{iso(win_from)}"),
        ("order", "created_at.desc"),
    ])
    sig_c = Counter(sig_of(s.get("extractor_chain")) for s in steps_cur)
    sig_b = Counter(sig_of(s.get("extractor_chain")) for s in steps_base)
    err_c = Counter((s.get("error_type") or "?") for s in steps_cur)

    lines = []
    w = lines.append
    w(f"# Provider Health Report")
    w("")
    w(f"- Window: `{iso(win_from)}` .. `{iso(now)}` (UTC, {WINDOW_H}h)")
    w(f"- Baseline: `{iso(base_from)}` .. `{iso(win_from)}` ({BASELINE_H}h sebelumnya)")
    w(f"- Sumber: {len(sources)} | runs: {len(runs)} | step gagal (window): "
      f"{len(steps_cur)} | (baseline): {len(steps_base)}")
    w("")

    w("## Provider")
    w("")
    w("| provider | runs | ok | partial | broken | ok-rate | baseline | flag |")
    w("|---|---|---|---|---|---|---|---|")
    providers = []
    for sid in sorted(set(cur) | set(base), key=lambda x: sources.get(x, ("?", "?"))[0].lower()):
        c = cur.get(sid, Counter())
        b = base.get(sid, Counter())
        t_c = sum(c.values())
        t_b = sum(b.values())
        ok_c = c.get("ok", 0)
        ok_b = b.get("ok", 0)
        rate_c = ok_c / t_c if t_c else None
        rate_b = ok_b / t_b if t_b else None
        prov = sources.get(sid, ("?", "?"))
        name = f"{prov[0]} ({prov[1]})"
        flag = ""
        if t_c >= 2 and ok_c == 0 and rate_b is not None and rate_b >= 0.5:
            flag = "NEW_OUTAGE"
            FLAGS.append(f"{name}: outage baru (0/{t_c})")
        elif (t_c >= 2 and rate_c is not None and rate_b is not None
              and rate_c < 0.9 and rate_b - rate_c >= 0.2):
            flag = "REGRESSION"
            FLAGS.append(f"{name}: ok-rate {rate_b:.0%} -> {rate_c:.0%}")
        providers.append((sid, c, b))
        w(f"| {name} | {t_c} | {ok_c} | {c.get('partial',0)} | "
          f"{c.get('broken_extract',0)}+{c.get('broken_scrape',0)} | "
          f"{f'{rate_c:.0%}' if rate_c is not None else '-'} | "
          f"{f'{rate_b:.0%}' if rate_b is not None else '-'} | {flag} |")
    w("")

    w("## Error type (step gagal, window)")
    w("")
    w("| error_type | count |")
    w("|---|---|")
    for k, v in err_c.most_common():
        w(f"| {k} | {v} |")
    w("")

    w("## Top signature extractor gagal (window vs baseline)")
    w("")
    w("| signature | window | baseline | baru? |")
    w("|---|---|---|---|")
    for k, v in sig_c.most_common(10):
        fresh = "YA" if v >= 3 and sig_b.get(k, 0) == 0 else ""
        if fresh:
            FLAGS.append(f"signature gagal baru: {k} ({v}x)")
        w(f"| {k} | {v} | {sig_b.get(k, 0)} | {fresh} |")
    w("")

    if FLAGS:
        w("## Gejala")
        w("")
        for f_ in dict.fromkeys(FLAGS):
            w(f"- {f_}")
        w("")
    w("---")
    w("_Dihasilkan otomatis. 'ok' = semua link episode ter-ekstrak; 'partial' "
      "= sebagian link gagal; 'broken' = tak ada video. Detail per link di "
      "scrape_steps (extractor_chain)._")

    print("\n".join(lines))
    if "--fail-on-issues" in sys.argv and FLAGS:
        raise SystemExit(1)


if __name__ == "__main__":
    main()