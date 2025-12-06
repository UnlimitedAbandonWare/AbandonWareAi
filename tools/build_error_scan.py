
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re, sys, json, os, datetime, pathlib

PATTERN_FILE = os.path.join(os.path.dirname(__file__), "build_error_patterns.json")
HISTORY_FILE = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".build", "error_history.json")

def load_patterns():
    with open(PATTERN_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def scan_log(log_text, patterns):
    hits = []
    for p in patterns:
        for m in re.finditer(p["regex"], log_text, re.IGNORECASE|re.MULTILINE):
            group = m.group(1) if m.lastindex else None
            hits.append({
                "id": p["id"],
                "pos": m.start(),
                "group": group,
                "severity": p.get("severity","info"),
                "msg": p["explain"],
                "hint": p["fix_hint"]
            })
    return hits

def update_history(hits):
    os.makedirs(os.path.dirname(HISTORY_FILE), exist_ok=True)
    now = datetime.datetime.utcnow().isoformat() + "Z"
    hist = {"runs": []}
    if os.path.exists(HISTORY_FILE):
        with open(HISTORY_FILE, "r", encoding="utf-8") as f:
            try:
                hist = json.load(f)
            except Exception:
                hist = {"runs": []}
    hist["runs"].append({"ts": now, "hits": hits})
    with open(HISTORY_FILE, "w", encoding="utf-8") as f:
        json.dump(hist, f, ensure_ascii=False, indent=2)

def main():
    if len(sys.argv) < 2:
        print("Usage: build_error_scan.py <build_log_file>", file=sys.stderr)
        sys.exit(2)
    log_path = sys.argv[1]
    with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
        log_text = f.read()
    patterns = load_patterns()
    hits = scan_log(log_text, patterns)
    update_history(hits)
    print(json.dumps({"hit_count": len(hits), "hits": hits}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
