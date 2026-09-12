#!/usr/bin/env python3
"""Supabase log cleanup — hapus log yang sudah direview/difix secara otomatis.

Aturan:
  Baris di tabel `logs` yang id-nya tercatat di `log_review_status.json`
  dianggap SUDAH DIREVIEW / SUDAH DIFIX → dihapus.
  Log yang tersisa = failure yang belum diselesaikan (work queue).

Kredensial (prioritas):
  1. env SUPABASE_ACCESS_TOKEN  → Management API → service_role key
  2. file ~/.supabase/access-token → Management API → service_role key
  3. env SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY (langsung)
  4. env SUPABASE_URL + SUPABASE_ANON_KEY (fallback; DELETE bisa kena RLS)

Gunakan service_role untuk DELETE karena anon key umumnya hanya punya policy
INSERT (logging write-only) sehingga DELETE via anon akan ditolak RLS.

Cara pakai:
  python3 scripts/supabase_cleanup.py            # dry-run (aman, default)
  python3 scripts/supabase_cleanup.py --apply    # benar-benar menghapus

Dijalankan otomatis via GitHub Actions (scheduled) → lihat
.github/workflows/supabase-cleanup.yml
"""
import argparse
import json
import os
import sys
import urllib.request

PROJECT_REF = "cjjopuwhpcuoaoifhcfj"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STATUS_FILE = os.path.join(SCRIPT_DIR, "log_review_status.json")
MANAGE_URL = f"https://api.supabase.com/v1/projects/{PROJECT_REF}/api-keys"
CONSOLE_URL = f"https://{PROJECT_REF}.supabase.co/rest/v1/logs"


def load_reviewed_ids():
    """Id log yang sudah direview/difix dari log_review_status.json."""
    if not os.path.exists(STATUS_FILE):
        print(f"❌ Status file tidak ditemukan: {STATUS_FILE}")
        return []
    with open(STATUS_FILE) as f:
        data = json.load(f)
    ids = set()
    for k, v in data.items():
        if k.startswith("_"):
            continue
        try:
            ids.add(int(k))
        except (ValueError, TypeError):
            pass
    return sorted(ids)


def resolve_api_key():
    """Resolve kredensial. Gabungan dari script query_logs.py (management
    API) + dukungan env langsung untuk CI."""
    access_token = os.environ.get("SUPABASE_ACCESS_TOKEN") or ""
    if not access_token:
        token_file = os.path.expanduser("~/.supabase/access-token")
        if os.path.exists(token_file):
            with open(token_file) as f:
                access_token = f.read().strip()

    if access_token:
        try:
            req = urllib.request.Request(
                MANAGE_URL,
                headers={"Authorization": f"Bearer {access_token}"},
            )
            with urllib.request.urlopen(req, timeout=30) as r:
                keys = json.loads(r.read().decode())
            by_name = {k["name"]: k["api_key"] for k in keys}
            svc = by_name.get("service_role") or by_name.get("service_role_legacy")
            if svc:
                return svc, "service_role"
            anon = by_name.get("anon")
            if anon:
                return anon, "anon (RLS!)"
        except Exception as e:
            print(f"⚠️  Management API gagal ({e}); coba env key langsung.")

    url = os.environ.get("SUPABASE_URL", "")
    svc = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if url and svc:
        global CONSOLE_URL
        CONSOLE_URL = f"{url.rstrip('/')}/rest/v1/logs"
        return svc, "service_role"
    anon = os.environ.get("SUPABASE_ANON_KEY", "")
    if url and anon:
        CONSOLE_URL = f"{url.rstrip('/')}/rest/v1/logs"
        return anon, "anon (RLS!)"

    print("❌ Kredensial tidak tersedia. Set salah satu:")
    print("   - env SUPABASE_ACCESS_TOKEN (management PAT)")
    print("   - file ~/.supabase/access-token")
    print("   - env SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY")
    return None, None


def http(method, api_key, path, headers=None):
    req = urllib.request.Request(
        f"{CONSOLE_URL}{path}",
        method=method,
        headers={
            "apikey": api_key,
            "Authorization": f"Bearer {api_key}",
            **(headers or {}),
        },
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read().decode()
        cr = r.headers.get("Content-Range", "")
        return body, cr


def split_ids(ids, chunk=80):
    for i in range(0, len(ids), chunk):
        yield ids[i:i + chunk]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true",
                        help="Benar-benar hapus (default = dry-run)")
    args = parser.parse_args()

    api_key, key_kind = resolve_api_key()
    if not api_key:
        sys.exit(1)

    reviewed = load_reviewed_ids()
    if not reviewed:
        print("ℹ️  Tidak ada id yang direview — tidak ada yang dihapus.")
        return
    print(f"📋 Total id direview/difix: {len(reviewed)}")

    # Id yang direview tapi masih ada di DB
    existing = []
    for chunk in split_ids(reviewed):
        q = f"?select=id,level&id=in.({','.join(map(str, chunk))})&order=id"
        try:
            body, _ = http("GET", api_key, q)
            for row in json.loads(body) or []:
                rid = row.get("id")
                if rid is not None:
                    existing.append((int(rid), row.get("level", "")))
        except Exception as e:
            print(f"⚠️  Query existing gagal (chunk): {e}")
    existing.sort(key=lambda x: x[0])
    print(f"🔎 Id yang masih ada di DB: {len(existing)}")
    if existing:
        sample = ", ".join(str(i) for i, _ in existing[:15])
        print(f"   contoh: {sample}" + (" ..." if len(existing) > 15 else ""))

    if args.apply and existing:
        del_ids = [i for i, _ in existing]
        for chunk in split_ids(del_ids):
            q = f"?id=in.({','.join(map(str, chunk))})"
            try:
                _, cr = http("DELETE", api_key, q,
                             headers={"Prefer": "return=minimal,count=exact"})
                n = cr.split("/")[-1] if cr else "?"
                print(f"🗑️  DELETE chunk ({len(chunk)} id) → {n} terhapus")
            except Exception as e:
                print(f"❌ DELETE gagal (mungkin RLS — pakai service_role): {e}")
                sys.exit(1)
    elif existing:
        print("ℹ️  Dry-run — jalankan dengan --apply untuk benar-benar menghapus.")

    # Sisa failure yang belum diselesaikan (work queue). SUCCESS hanya
    # telemetri, bukan masalah — tidak dihitung sebagai pekerjaan tersisa.
    try:
        body, cr = http("GET", api_key,
                        f"?select=id&level=in.(FAIL,ERROR,CRITICAL)&order=id.desc&limit=1")
        total = cr.split("/")[-1] if cr else "?"
        print(f"\n📌 Sisa log yang HARUS diselesaikan: {total}")
    except Exception as e:
        print(f"⚠️  Hitung sisa gagal: {e}")


if __name__ == "__main__":
    main()