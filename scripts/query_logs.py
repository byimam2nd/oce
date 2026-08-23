#!/usr/bin/env python3
"""Query Supabase logs with review status cross-reference."""
import json, os, sys, subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STATUS_FILE = os.path.join(SCRIPT_DIR, "log_review_status.json")

def load_status():
    if os.path.exists(STATUS_FILE):
        with open(STATUS_FILE) as f:
            data = json.load(f)
        return {k: v for k, v in data.items() if not k.startswith("_")}
    return {}

def query_logs(limit=20, failure_only=True):
    env = os.environ.copy()
    token = open(os.path.expanduser("~/.supabase/access-token")).read().strip()
    proj = "cjjopuwhpcuoaoifhcfj"
    
    api_key_cmd = f'curl -s -H "Authorization: Bearer {token}" "https://api.supabase.com/v1/projects/{proj}/api-keys"'
    api_key = subprocess.check_output(api_key_cmd, shell=True).decode()
    api_key = json.loads(api_key)
    api_key = [k['api_key'] for k in api_key if k['name'] == 'anon'][0]
    
    select = "id,created_at,stage,failure_type,message,extractor,host,tag"
    where = "failure_type=neq.SUCCESS" if failure_only else ""
    url = f"https://{proj}.supabase.co/rest/v1/logs?select={select}&{where}&order=created_at.desc&limit={limit}"
    
    cmd = f'curl -s "{url}" -H "apikey: {api_key}" -H "Authorization: Bearer {api_key}"'
    result = subprocess.check_output(cmd, shell=True).decode()
    return json.loads(result)

def main():
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    status_map = load_status()
    logs = query_logs(limit=limit)
    
    print(f"{'ID':>5} {'Date':>12} {'Status':>14} {'FailureType':>12} {'Host':>20} {'Message'}")
    print("-" * 120)
    
    for log in logs:
        lid = str(log.get("id", ""))
        review = status_map.get(lid, {})
        status = review.get("status", "")
        note = review.get("note", "")
        
        created = log.get("created_at", "")[:10]
        ftype = log.get("failure_type", "") or ""
        host = log.get("host", "") or ""
        msg = log.get("message", "") or ""
        
        status_label = f"[{status}]" if status else ""
        
        print(f"{lid:>5} {created:>12} {status_label:>14} {ftype:>12} {host:>20} {msg[:50]}")
        if note:
            print(f"      └─ {note[:90]}")

if __name__ == "__main__":
    main()
