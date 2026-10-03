#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6-D10: Post-Codex verification (runs only when codex-p6/REPORT.md exists).

Reused: pattern after verify_codex_r51.py (hash compare + expected-status check);
reads devin-p6/pre-codex-snapshot.json captured at D1 baseline.
Added: A1-A11 claim vs JUnit XML cross-check, R5.1/hold-file hash diff,
test-expectation classification, D1 re-run before/after.

If data/agent-handoff/codex-p6/REPORT.md is absent -> PENDING (exit 2).
Never waits/loops. Pure stdlib, read-only.
"""
from __future__ import annotations
import argparse, hashlib, json, re, sys
import xml.etree.ElementTree as ET
from pathlib import Path

SNAPSHOT = Path("data/agent-handoff/devin-p6/pre-codex-snapshot.json")
CODEX_REPORT = Path("data/agent-handoff/codex-p6/REPORT.md")
DEVIN_P6 = Path("data/agent-handoff/devin-p6")
HOLD_PATTERNS = [
    "ChatWorkflow", "TraceStore", "SubagentFlowRunner",  # HOLD items per brief
]
PROTECTED = [
    "main/resources/static/js/chat.js",
]

def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest().upper() if p.exists() else "MISSING"

def junit_totals(root: Path) -> dict:
    tests = failures = skipped = 0
    for p in root.glob("build/test-results/test/TEST-*.xml"):
        try:
            s = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        tests += int(s.get("tests", 0)); failures += int(s.get("failures", 0))
        failures += int(s.get("errors", 0)); skipped += int(s.get("skipped", 0))
    return {"tests": tests, "failures": failures, "skipped": skipped}

def parse_report(md: str) -> dict:
    """Extract A1..A11 verdicts + claimed test counts from Codex REPORT.md."""
    claims = {}
    for m in re.finditer(r"\bA(\d{1,2})\b[^\n]*?((?:PASS|FAIL|PENDING|NOT_RUN|PARTIAL))",
                         md, re.IGNORECASE):
        claims[f"A{m.group(1)}"] = m.group(2).upper()
    nums = {}
    for m in re.finditer(r"tests[:\s]+(\d+)", md, re.I):
        nums.setdefault("tests", int(m.group(1)))
    for m in re.finditer(r"failures?[:\s]+(\d+)", md, re.I):
        nums.setdefault("failures", int(m.group(1)))
    return {"acceptance": claims, "claimed": nums}

def main() -> int:
    ap = argparse.ArgumentParser(description="Codex P6 post-verification")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    report_path = root / CODEX_REPORT
    out = {"status": "PENDING", "checks": {}}

    if not report_path.exists():
        out["reason"] = f"{CODEX_REPORT} absent — Codex still running; rerun later"
        print(json.dumps(out, indent=2)); return 2

    checks = {}
    # 1) A1-A11 vs JUnit XML
    md = report_path.read_text(encoding="utf-8", errors="replace")
    rep = parse_report(md)
    totals = junit_totals(root)
    claimed = rep["claimed"]
    verdict = "VERIFIED"
    if "tests" in claimed and claimed["tests"] != totals["tests"]:
        verdict = "MISMATCH"
    if totals["failures"] and not rep["acceptance"]:
        verdict = "MISMATCH"
    checks["acceptance_vs_junit"] = {
        "verdict": verdict, "claimed": claimed, "junit": totals,
        "a_items": rep["acceptance"]}

    # 2) R5.1 + protected + hold hashes vs snapshot (groups{r51,hold,protected,sql})
    snap = json.loads((root / SNAPSHOT).read_text(encoding="utf-8"))
    files_map: dict[str, str] = {}
    demo_lines_map: dict[str, list] = {}
    for grp in snap.get("groups", {}).values():
        if not isinstance(grp, dict):
            continue
        for rel, val in grp.items():
            if isinstance(val, str):
                files_map[rel] = val
            elif isinstance(val, dict) and "sha256" in val:
                files_map[rel] = val["sha256"]
                if "demo_interview_lines" in val:
                    demo_lines_map[rel] = val["demo_interview_lines"]
    changed = []
    for rel, old in files_map.items():
        now = sha256(root / rel).lower()
        if now != old.lower():
            changed.append({"path": rel, "old": old[:12], "now": now[:12]})
    demo_diff = []
    for rel, old_lines in demo_lines_map.items():
        p = root / rel
        if not p.exists():
            demo_diff.append({"path": rel, "reason": "missing"})
            continue
        now_lines = [l.strip() for l in p.read_text(
            encoding="utf-8", errors="replace").splitlines()
            if l.strip().startswith("demo.interview.enabled")]
        if now_lines != old_lines:
            demo_diff.append({"path": rel, "old": old_lines, "now": now_lines})
    checks["demo_interview_lines"] = {"compared": len(demo_lines_map),
                                      "diffs": demo_diff,
                                      "verdict": "VERIFIED" if not demo_diff else "MISMATCH"}
    checks["pre_codex_hashes"] = {"compared": len(files_map), "changed": changed,
                                  "verdict": "VERIFIED" if not changed else "MISMATCH"}

    # 3) HOLD items untouched — snapshot's hold_files group is the reference
    hold_hits = []
    hold_grp = snap.get("groups", {}).get("hold_files", {})
    for rel, old in hold_grp.items():
        now = sha256(root / rel).lower()
        if now != old.lower():
            hold_hits.append(rel)
    checks["hold_untouched"] = {"compared": len(hold_grp), "hits": hold_hits,
                                "verdict": "VERIFIED" if not hold_hits else "REVIEW"}

    # 4) D1 re-run before/after
    try:
        import subprocess
        p = subprocess.run([sys.executable, "-B", str(root / "scripts/p6dbg_claim_drift.py"),
                            "--root", str(root)], capture_output=True, text=True, timeout=300)
        after = json.loads(p.stdout) if p.returncode == 0 else {}
        before = json.loads((root / DEVIN_P6 / "claim-drift-baseline.json").read_text(encoding="utf-8"))
        resolved = sum(1 for a in after.get("anchors", []) if a.get("verdict") == "STILL_PRESENT")
        checks["drift_before_after"] = {
            "before_still_present": before.get("verdicts", {}).get("STILL_PRESENT"),
            "after_still_present": resolved,
            "verdict": "INFO"}
    except Exception as e:
        checks["drift_before_after"] = {"verdict": "NOT_RUN", "reason": str(e)}

    # 5) protected (chat.js etc.)
    prot = {p: sha256(root / p) for p in PROTECTED}
    checks["protected_hashes"] = prot

    out["checks"] = checks
    out["status"] = "MISMATCH" if any(
        c.get("verdict") == "MISMATCH" for c in checks.values()) else "VERIFIED"
    dest = root / DEVIN_P6 / "codex-p6-verify.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": out["status"], "out": str(dest),
                      "summary": {k: v.get("verdict") for k, v in checks.items()}}))
    return 0 if out["status"] == "VERIFIED" else 1

if __name__ == "__main__":
    sys.exit(main())
