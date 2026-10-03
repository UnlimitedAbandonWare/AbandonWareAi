#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""p6r2dbg_verify_codex_r2.py — post-verifier for the Codex P6-R2 round.

Pattern after p6dbg_verify_codex_p6.py (hash compare + claim-vs-JUnit check);
reads devin-p6-r2/pre-r2-snapshot.json captured before Codex R2 started.

Actions:
  snapshot  capture R5.1-protected / HOLD / demo-interview / sql file hashes
            -> data/agent-handoff/devin-p6-r2/pre-r2-snapshot.json
  verify    if data/agent-handoff/codex-p6-r2/REPORT.md is absent -> PENDING
            (exit 2). Never waits, never loops. Pure stdlib, read-only on the
            repo (writes only the verify JSON under devin-p6-r2/).

Checks (verify):
  r_claims          R1..R6 verdicts + claimed test counts vs JUnit XML totals
  t01_trace         T01_TRACE.md exists; each allowed route has a file:method
                    reference nearby (R1 acceptance)
  verify_triage_132 VERIFY_TRIAGE.md bucket counts sum to 132 (R4)
  protected_hashes  r51_protected + protected_no_edit hash unchanged (R6)
  hold_untouched    hold_files hash unchanged (HOLD list)
  demo_interview    demo.interview.enabled lines unchanged in properties files
  lease_residual    no codex p6-r2 lease left under __patch_drop__/source-edit-locks
  git_diff_check    git diff HEAD --check on the report's changed-file list

Exit: 0 VERIFIED · 1 MISMATCH/FAIL · 2 PENDING · 3 usage/input error.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "devin-p6-r2.verify-codex-r2.v1"

DEVIN_R2 = Path("data/agent-handoff/devin-p6-r2")
CODEX_R2 = Path("data/agent-handoff/codex-p6-r2")
SNAPSHOT = DEVIN_R2 / "pre-r2-snapshot.json"
CODEX_REPORT = CODEX_R2 / "REPORT.md"
T01_TRACE = CODEX_R2 / "T01_TRACE.md"
VERIFY_TRIAGE = CODEX_R2 / "VERIFY_TRIAGE.md"
LOCKS_DIR = Path("__patch_drop__/source-edit-locks")
VERIFY_OUT = DEVIN_R2 / "codex-r2-verify.json"

# R5.1 protected set (identical to devin-p6/pre-codex-snapshot.json
# `r51_uncommitted_preserve`; kept explicit so this file is self-contained).
R51_PROTECTED = [
    "main/java/com/example/lms/llm/ChatGptOAuthRegistration.java",
    "main/java/com/example/lms/assist/ConversateApiCueService.java",
    "main/java/com/example/lms/assist/ConversateCueRoutingPolicy.java",
    "main/java/com/example/lms/debug/PromptMasker.java",
]
# R2 HOLD list: chat.js 전체, ChatWorkflow, TraceStore, SubagentFlowRunner,
# Responses tool guard, plus the protected chat-stream test contract.
HOLD_FILES = [
    "main/resources/static/js/chat.js",
    "src/test/js/chat-stream-boundaries.test.cjs",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/search/TraceStore.java",
    "main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentFlowRunner.java",
    "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java",
]
ALLOWED_ROUTES = [
    "/api/chat/stream",
    "/api/chat/cancel",
    "/api/chat/state",
    "/api/chat/sessions",
    "/api/chat/sessions/{id}",
]
PRODUCT_PATH_RE = re.compile(
    r"(?:main[/\\](?:java|resources)|src[/\\]test|frontend[/\\])"
    r"[\w./\\-]*\.(?:java|properties|yml|yaml|xml|js|ts|tsx|sql|cjs)")
R_CLAIM_RE = re.compile(r"\bR([1-6])\b[^\n|]*?\b(PASS|FAIL|NOT_RUN|PENDING|PARTIAL|VERIFIED|MISMATCH)\b", re.I)
TESTS_RE = re.compile(r"tests?[:\s]+(\d+)", re.I)
FAIL_RE = re.compile(r"failures?[:\s]+(\d+)", re.I)
ERR_RE = re.compile(r"errors?[:\s]+(\d+)", re.I)
JAVA_REF_RE = re.compile(r"[\w./\\]*[A-Za-z_]\w*\.java(?::\d+|#\w+|\.\w+\()")
LEASE_TOPIC_RE = re.compile(r"(?i)(p6.*r2|r2.*p6)")
TRIAGE_BUCKET_RE = re.compile(r"(무해|조사 ?필요|실제 ?결함|harmless|investigate|defect|suspect)", re.I)


def sha256(p: Path) -> str:
    try:
        return hashlib.sha256(p.read_bytes()).hexdigest()
    except OSError:
        return "MISSING"


def _git(root: Path, *args: str, timeout: int = 60) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), *args],
            capture_output=True, text=True, timeout=timeout)
        return proc.returncode, proc.stdout + proc.stderr
    except (OSError, subprocess.TimeoutExpired):
        return -1, ""


def _demo_lines(p: Path) -> list[str]:
    try:
        return [ln.strip() for ln in
                p.read_text(encoding="utf-8", errors="replace").splitlines()
                if ln.strip().startswith("demo.interview.enabled")]
    except OSError:
        return []


def capture_snapshot(root: Path) -> dict:
    groups: dict[str, dict] = {"r51_protected": {}, "hold_files": {},
                               "properties_demo_interview": {}, "sql_schema": {}}
    for rel in R51_PROTECTED:
        groups["r51_protected"][rel] = sha256(root / rel)
    for rel in HOLD_FILES:
        groups["hold_files"][rel] = sha256(root / rel)
    res = root / "main/resources"
    if res.is_dir():
        for p in sorted(res.glob("application*.properties")):
            rel = str(p.relative_to(root)).replace("\\", "/")
            groups["properties_demo_interview"][rel] = {
                "sha256": sha256(p), "demo_interview_lines": _demo_lines(p)}
        for p in sorted(res.glob("db/**/*.sql")):
            rel = str(p.relative_to(root)).replace("\\", "/")
            groups["sql_schema"][rel] = sha256(p)
    head_rc, head_out = _git(root, "rev-parse", "HEAD")
    _, status_out = _git(root, "status", "--porcelain", timeout=180)
    dirty = sum(1 for ln in status_out.splitlines() if ln.strip())
    return {
        "schema": "devin-p6-r2.pre-r2-snapshot.v1",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "git_head": head_out.strip() if head_rc == 0 else None,
        "git_dirty_count": dirty,
        "groups": groups,
    }


def junit_totals(root: Path) -> dict:
    tests = failures = errors = skipped = 0
    files = 0
    for p in root.glob("build/test-results/test/TEST-*.xml"):
        try:
            s = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        files += 1
        tests += int(s.get("tests", 0))
        failures += int(s.get("failures", 0))
        errors += int(s.get("errors", 0))
        skipped += int(s.get("skipped", 0))
    return {"files": files, "tests": tests, "failures": failures,
            "errors": errors, "skipped": skipped}


def parse_report(md: str) -> dict:
    claims = {}
    for m in R_CLAIM_RE.finditer(md):
        claims[f"R{m.group(1)}"] = m.group(2).upper()
    nums = {}
    for rx, key in ((TESTS_RE, "tests"), (FAIL_RE, "failures"), (ERR_RE, "errors")):
        m = rx.search(md)
        if m:
            nums[key] = int(m.group(1))
    changed = sorted({m.group(0).replace("\\", "/")
                      for m in PRODUCT_PATH_RE.finditer(md)})
    return {"r_claims": claims, "claimed": nums, "changed_files": changed}


def check_t01_trace(root: Path) -> dict:
    p = root / T01_TRACE
    if not p.is_file():
        return {"verdict": "MISMATCH", "reason": f"{T01_TRACE} absent"}
    lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
    missing = []
    for route in ALLOWED_ROUTES:
        needle = route.replace("/{id}", "") if route.endswith("/{id}") else route
        hits = [i for i, ln in enumerate(lines) if needle in ln]
        ok = any(JAVA_REF_RE.search("\n".join(lines[max(0, i - 6): i + 7]))
                 for i in hits)
        if not hits or not ok:
            missing.append(route)
    return {"verdict": "VERIFIED" if not missing else "MISMATCH",
            "routes_checked": len(ALLOWED_ROUTES),
            "missing_file_method_ref": missing}


def check_triage_132(root: Path) -> dict:
    p = root / VERIFY_TRIAGE
    if not p.is_file():
        return {"verdict": "MISMATCH", "reason": f"{VERIFY_TRIAGE} absent"}
    nums: list[int] = []
    for ln in p.read_text(encoding="utf-8", errors="replace").splitlines():
        if TRIAGE_BUCKET_RE.search(ln):
            nums += [int(n) for n in re.findall(r"\d+", ln)]
    total = sum(nums)
    return {"verdict": "VERIFIED" if total == 132 else "MISMATCH",
            "bucket_numbers": nums, "sum": total, "expected": 132}


def _compare_group(root: Path, snap: dict, group: str) -> dict:
    grp = (snap.get("groups") or {}).get(group) or {}
    changed = []
    for rel, old in grp.items():
        want = old.get("sha256") if isinstance(old, dict) else old
        now = sha256(root / rel)
        if str(now).lower() != str(want).lower():
            changed.append({"path": rel, "old": str(want)[:12],
                            "now": str(now)[:12]})
    return {"compared": len(grp), "changed": changed,
            "verdict": "VERIFIED" if not changed else "MISMATCH"}


def check_demo_lines(root: Path, snap: dict) -> dict:
    grp = (snap.get("groups") or {}).get("properties_demo_interview") or {}
    diffs = []
    for rel, old in grp.items():
        if not isinstance(old, dict):
            continue
        p = root / rel
        if not p.is_file():
            diffs.append({"path": rel, "reason": "missing"})
            continue
        now = _demo_lines(p)
        if now != old.get("demo_interview_lines", []):
            diffs.append({"path": rel, "old": old.get("demo_interview_lines"),
                          "now": now})
    return {"compared": len(grp), "diffs": diffs,
            "verdict": "VERIFIED" if not diffs else "MISMATCH"}


def check_lease_residual(root: Path, self_topic: str = "devin-p6-r2-kit") -> dict:
    base = root / LOCKS_DIR
    seen, residuals = [], []
    if base.is_dir():
        for lock in sorted(base.glob("*.lock")):
            lease = lock / "lease.json"
            topic = lock.name[:-5]
            owner = ""
            if lease.is_file():
                try:
                    meta = json.loads(lease.read_text(encoding="utf-8"))
                    topic = meta.get("topic", topic)
                    owner = meta.get("ownerId", "")
                except (json.JSONDecodeError, OSError):
                    owner = "unreadable"
            seen.append(topic)
            if topic == self_topic:
                continue
            if LEASE_TOPIC_RE.search(topic) or LEASE_TOPIC_RE.search(owner):
                residuals.append({"topic": topic, "ownerId": owner})
    return {"leases_seen": seen, "codex_r2_residuals": residuals,
            "verdict": "VERIFIED" if not residuals else "MISMATCH"}


def check_git_diff(root: Path, changed_files: list[str]) -> dict:
    if not (root / ".git").exists():
        return {"verdict": "NOT_RUN", "reason": "not-a-git-root"}
    if not changed_files:
        return {"verdict": "NOT_RUN",
                "reason": "no product changed-file list parsed from REPORT.md"}
    bad = []
    for rel in changed_files:
        rc, out = _git(root, "diff", "--check", "HEAD", "--", rel, timeout=120)
        if rc not in (0,):
            bad.append({"path": rel, "rc": rc, "out": out.strip()[:200]})
    return {"files_checked": len(changed_files), "whitespace_errors": bad,
            "verdict": "VERIFIED" if not bad else "MISMATCH"}


def verify(root: Path, report_path: Path | None = None,
           snapshot_path: Path | None = None,
           self_topic: str = "devin-p6-r2-kit") -> tuple[int, dict]:
    report_path = report_path or (root / CODEX_REPORT)
    out: dict = {"schemaVersion": SCHEMA, "status": "PENDING", "checks": {}}
    if not report_path.is_file():
        out["reason"] = (f"{report_path} absent — Codex R2 still running; "
                         "rerun later (never waits)")
        return 2, out

    snap_p = snapshot_path or (root / SNAPSHOT)
    snap = {}
    if snap_p.is_file():
        try:
            snap = json.loads(snap_p.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as exc:
            out["checks"]["snapshot"] = {"verdict": "MISMATCH",
                                         "reason": f"snapshot unreadable: {exc}"}
    else:
        out["checks"]["snapshot"] = {"verdict": "MISMATCH",
                                     "reason": f"{snap_p} absent — run `snapshot` first"}

    md = report_path.read_text(encoding="utf-8", errors="replace")
    rep = parse_report(md)
    totals = junit_totals(root)

    claimed = rep["claimed"]
    verdict = "VERIFIED"
    detail = []
    if "tests" in claimed and claimed["tests"] != totals["tests"]:
        verdict = "MISMATCH"
        detail.append(f"claimed tests={claimed['tests']} vs junit={totals['tests']}")
    if totals["failures"] or totals["errors"]:
        if rep["r_claims"].get("R5") == "PASS":
            verdict = "MISMATCH"
            detail.append(f"R5=PASS but junit failures={totals['failures']} errors={totals['errors']}")
    if not rep["r_claims"]:
        verdict = "MISMATCH"
        detail.append("no R1..R6 verdict lines parsed")
    out["checks"]["r_claims_vs_junit"] = {
        "verdict": verdict, "detail": detail,
        "r_claims": rep["r_claims"], "claimed": claimed, "junit": totals}

    out["checks"]["t01_trace"] = check_t01_trace(root)
    out["checks"]["verify_triage_132"] = check_triage_132(root)
    if snap:
        g1 = _compare_group(root, snap, "r51_protected")
        g2 = _compare_group(root, snap, "protected_no_edit")
        g2["compared"] = g2.get("compared", 0)
        out["checks"]["protected_hashes"] = {
            "verdict": "MISMATCH" if (g1["changed"] or g2["changed"]) else "VERIFIED",
            "r51_protected": g1, "protected_no_edit": g2}
        out["checks"]["hold_untouched"] = _compare_group(root, snap, "hold_files")
        out["checks"]["demo_interview_lines"] = check_demo_lines(root, snap)
        out["checks"]["sql_schema"] = _compare_group(root, snap, "sql_schema")
    out["checks"]["lease_residual"] = check_lease_residual(root, self_topic)
    out["checks"]["git_diff_check"] = check_git_diff(root, rep["changed_files"])

    out["status"] = "MISMATCH" if any(
        c.get("verdict") == "MISMATCH" for c in out["checks"].values()) else "VERIFIED"
    return (0 if out["status"] == "VERIFIED" else 1), out


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("action", nargs="?", default="verify",
                    choices=("verify", "snapshot"))
    ap.add_argument("--root", default=".")
    ap.add_argument("--report", default=None, help="override codex REPORT.md path")
    ap.add_argument("--snapshot-file", default=None, help="override snapshot path")
    ap.add_argument("--self-topic", default="devin-p6-r2-kit",
                    help="lease topic to exclude from the residual check")
    ap.add_argument("--out", default=None, help="verify JSON destination")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()

    if args.action == "snapshot":
        snap = capture_snapshot(root)
        dest = Path(args.snapshot_file) if args.snapshot_file else root / SNAPSHOT
        if not dest.is_absolute():
            dest = root / dest
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(snap, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
        print(json.dumps({"status": "PASS", "out": str(dest),
                          "groups": {k: len(v) for k, v in snap["groups"].items()},
                          "git_head": snap["git_head"],
                          "git_dirty_count": snap["git_dirty_count"]},
                         ensure_ascii=False))
        return 0

    code, out = verify(root,
                       Path(args.report) if args.report else None,
                       Path(args.snapshot_file) if args.snapshot_file else None,
                       args.self_topic)
    if out["status"] != "PENDING":
        dest = Path(args.out) if args.out else root / VERIFY_OUT
        if not dest.is_absolute():
            dest = root / dest
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
        out["written"] = str(dest)
    print(json.dumps({"status": out["status"],
                      "summary": {k: v.get("verdict") for k, v in
                                  out.get("checks", {}).items()},
                      "reason": out.get("reason")}, ensure_ascii=False))
    return code


if __name__ == "__main__":
    sys.exit(main())
