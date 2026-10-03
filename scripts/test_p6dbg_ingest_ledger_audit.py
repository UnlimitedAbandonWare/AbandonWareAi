#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Self-test for p6dbg_ingest_ledger_audit + fixture manifest sanity."""
import json, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FX = ROOT / "data/agent-handoff/devin-p6/fixtures/jsonl"
AUDIT = ROOT / "scripts/p6dbg_ingest_ledger_audit.py"

def run_audit(jsonl, state=None):
    cmd = [sys.executable, "-B", str(AUDIT), "--root", str(ROOT),
           "--jsonl", str(jsonl), "--no-copy"]
    if state:
        cmd += ["--state", str(state)]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, json.loads(p.stdout)

def main():
    fails = []
    man = json.loads((FX / "fixtures-manifest.json").read_text(encoding="utf-8"))
    assert man["count"] >= 8, "need >=8 fixtures"
    for e in man["entries"]:
        p = FX / e["file"]
        assert p.exists() and p.stat().st_size == e["bytes"], f"{e['file']} size drift"

    # 1) clean file → PASS, no violations
    rc, r = run_audit(FX / "valid-3.jsonl")
    if not (rc == 0 and r["status"] == "PASS" and r["complete_lines"] == 3):
        fails.append(("valid", r))

    # 2) unterminated tail → PASS (tail is legal, flagged not violated) but durable_end < size
    rc, r = run_audit(FX / "no-trailing-newline.jsonl")
    if not (rc == 0 and r["durable_end"] < r["jsonl_bytes"]):
        fails.append(("tail", r))

    # 3) checkpoint ahead → FAIL with CKPT-01
    with tempfile.TemporaryDirectory() as td:
        st = Path(td) / "state.json"
        st.write_text(json.dumps({"offset": 10**6, "fileHash": "x", "fileLength": 10**6}))
        rc, r = run_audit(FX / "valid-3.jsonl", st)
        if not (rc == 1 and any(v["id"] == "CKPT-01" for v in r["violations"])):
            fails.append(("ckpt_ahead", r))
        # 4) checkpoint inside durable region → PASS on CKPT-01
        st.write_text(json.dumps({"offset": 20}))
        rc, r = run_audit(FX / "valid-3.jsonl", st)
        if not (rc == 0 and any(c["id"] == "CKPT-01" and c["ok"] for c in r["checks"])):
            fails.append(("ckpt_ok", r))

    # 5) duplicate ids → FAIL DUP-01
    rc, r = run_audit(FX / "duplicate-ids.jsonl")
    if not (rc == 1 and any(v["id"] == "DUP-01" for v in r["violations"])):
        fails.append(("dup", r))

    # 6) mid-utf8 tail → noted, UTF8-01 ok flag
    rc, r = run_audit(FX / "utf8-mid-char-cut.jsonl")
    if not (r["checks"][1]["id"] == "UTF8-01"):
        fails.append(("utf8", r))

    # 7) missing dataset → NOT_RUN exit 2
    rc, r = run_audit(FX / "nope.jsonl")
    if not (rc == 2 and r["status"] == "NOT_RUN"):
        fails.append(("missing", r))

    if fails:
        print(json.dumps({"status": "FAIL", "fails": len(fails)}, ensure_ascii=False))
        for n, r in fails:
            print("CASE", n, json.dumps(r, ensure_ascii=False)[:300])
        return 1
    print(json.dumps({"status": "PASS", "cases": 7, "fixtures": man["count"]}))
    return 0

if __name__ == "__main__":
    sys.exit(main())
