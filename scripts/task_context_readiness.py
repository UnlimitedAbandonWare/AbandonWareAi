#!/usr/bin/env python3
"""GO/WAIT readiness card for the next Codex task-context lane.

DEMO1-DEVIN-SESSION-CONTEXT-E2E-20261004 (D3). Read-only: never writes into
the repo, never prints file contents — paths, counts and sha12 only.

Checks (R1-R7):
  R1 astra ledger has a final report (report|final|closing file) OR its
     journal is closed (endedAtUtc set)
  R2 scripts/work_journal.py is git-clean and covered by no foreign lease
  R3 scripts/task_context.py still absent ("already started" if present)
  R4 journal size stats inside the task-context budget (4 MiB, 2000 events)
  R5 one smoke taskId candidate (folder name only, >=10 events)
  R6 checkpoint storage readable (paths exist; contents never printed)
  R7 sol/Grok assistant output dirs listed when present (paths only)

Usage:
  task_context_readiness.py            markdown card on stdout (GO/WAIT first line)
  task_context_readiness.py --json     same verdict as JSON
  task_context_readiness.py --self-test fixture evaluation, exit 0 on pass

Exit codes: 0 GO | 1 WAIT | 2 usage | 4 self-test failed
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

SCHEMA = "awx.task-context-readiness.v1"
ASTRA_LEDGER = Path("data/agent-handoff/codex-session-jsonl-28bf623e")
ASTRA_JOURNAL = Path("data/agent-handoff/codex-autonomy/codex-session-jsonl-94c579f3/journal.json")
JOURNAL_ROOT = Path("data/agent-handoff/codex-autonomy")
LOCK_ROOT = Path("__patch_drop__/source-edit-locks")
WORK_JOURNAL_PY = Path("scripts/work_journal.py")
TASK_CONTEXT_PY = Path("scripts/task_context.py")
CHECKPOINT_PY = Path("scripts/codex_work_checkpoint.py")
SOL_OUT = Path("var/codex-assist-session-context")
GROK_OUT = Path("var/codex-assist-grok-session-context")
BUDGET_BYTES = 4 * 1024 * 1024
BUDGET_EVENTS = 2000
SMOKE_MIN_EVENTS = 10
REPORT_HINTS = ("report", "final", "closing", "accept")


def _git_exe() -> str | None:
    found = shutil.which("git")
    if found:
        return found
    candidate = Path(r"F:\git\cmd\git.exe")
    return str(candidate) if candidate.is_file() else None


def git_porcelain(root: Path, rel: str) -> str | None:
    """Porcelain status for one path; None when git is unavailable/failed."""
    exe = _git_exe()
    if not exe:
        return None
    try:
        proc = subprocess.run([exe, "-C", str(root), "status", "--porcelain", "--", rel],
                              capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return proc.stdout if proc.returncode == 0 else None


def journal_stats(root: Path) -> dict:
    """Count/size/events stats over journal.json files (contents aggregated only)."""
    base = root / JOURNAL_ROOT
    sizes, events, count = [], [], 0
    for jf in base.glob("*/journal.json") if base.is_dir() else []:
        try:
            sizes.append(jf.stat().st_size)
            doc = json.loads(jf.read_text(encoding="utf-8", errors="replace"))
            events.append(len(doc.get("events") or []))
            count += 1
        except (OSError, ValueError):
            continue
    return {
        "journals": count,
        "maxBytes": max(sizes, default=0),
        "avgBytes": round(sum(sizes) / len(sizes)) if sizes else 0,
        "maxEvents": max(events, default=0),
        "withinBytes": (max(sizes, default=0) <= BUDGET_BYTES),
        "withinEvents": (max(events, default=0) <= BUDGET_EVENTS),
    }


def smoke_candidate(root: Path) -> str | None:
    """Newest journal dir whose journal has >= SMOKE_MIN_EVENTS (folder name only)."""
    base = root / JOURNAL_ROOT
    best = None
    for jf in base.glob("*/journal.json") if base.is_dir() else []:
        try:
            doc = json.loads(jf.read_text(encoding="utf-8", errors="replace"))
            if len(doc.get("events") or []) >= SMOKE_MIN_EVENTS:
                mtime = jf.stat().st_mtime
                if best is None or mtime > best[1]:
                    best = (jf.parent.name, mtime)
        except (OSError, ValueError):
            continue
    return best[0] if best else None


def astra_report_present(root: Path) -> dict:
    ledger = root / ASTRA_LEDGER
    files = [p.name for p in ledger.iterdir()
             if p.is_file() and any(h in p.name.lower() for h in REPORT_HINTS)] \
        if ledger.is_dir() else []
    journal_closed = False
    jf = root / ASTRA_JOURNAL
    if jf.is_file():
        try:
            doc = json.loads(jf.read_text(encoding="utf-8", errors="replace"))
            journal_closed = bool(doc.get("endedAtUtc")) or \
                doc.get("status") not in (None, "in_progress")
        except (OSError, ValueError):
            pass
    return {"reportFiles": files, "journalClosed": journal_closed,
            "ok": bool(files) or journal_closed}


def foreign_lease_on(root: Path, rel_path: str) -> list:
    """Lease topics (not ours) whose targetPaths cover rel_path. Names only."""
    locks = root / LOCK_ROOT
    hits = []
    rel = rel_path.replace("\\", "/").casefold()
    for lf in locks.glob("*.lock/lease.json") if locks.is_dir() else []:
        try:
            doc = json.loads(lf.read_text(encoding="utf-8", errors="replace"))
        except (OSError, ValueError):
            continue
        targets = [str(p).replace("\\", "/").casefold()
                   for p in doc.get("targetPaths", [])]
        if any(t == rel or rel.startswith(t + "/") or t.startswith(rel + "/")
               for t in targets):
            hits.append(lf.parent.name)
    return hits


def dir_listing(root: Path, rel: Path) -> dict:
    d = root / rel
    if not d.is_dir():
        return {"present": False, "path": str(rel), "files": []}
    files = sorted(str(p.relative_to(root)).replace("\\", "/")
                   for p in d.rglob("*") if p.is_file())
    return {"present": True, "path": str(rel), "files": files[:50],
            "fileCount": len(files)}


def evaluate(root: Path, porcelain=git_porcelain) -> dict:
    rows = []
    r1 = astra_report_present(root)
    rows.append({"id": "R1", "ok": r1["ok"],
                 "evidence": {"ledgerReportFiles": len(r1["reportFiles"]),
                              "journalClosed": r1["journalClosed"]}})

    porc = porcelain(root, str(WORK_JOURNAL_PY).replace("\\", "/"))
    git_clean = (porc is not None and porc.strip() == "")
    leases = foreign_lease_on(root, "scripts/work_journal.py")
    r2_ok = git_clean and not leases
    rows.append({"id": "R2", "ok": r2_ok,
                 "evidence": {"gitPorcelain": ("clean" if git_clean else
                              ("dirty" if porc is not None else "not_observed")),
                              "foreignLeases": leases}})

    r3_absent = not (root / TASK_CONTEXT_PY).is_file()
    rows.append({"id": "R3", "ok": r3_absent,
                 "evidence": {"taskContextPy": "absent" if r3_absent
                              else "present(already-started)"}})

    stats = journal_stats(root)
    r4_ok = stats["withinBytes"] and stats["withinEvents"]
    rows.append({"id": "R4", "ok": r4_ok, "evidence": stats})

    cand = smoke_candidate(root)
    rows.append({"id": "R5", "ok": cand is not None,
                 "evidence": {"taskIdCandidate": cand}})

    r6_ok = (root / JOURNAL_ROOT).is_dir() and (root / CHECKPOINT_PY).is_file()
    rows.append({"id": "R6", "ok": r6_ok,
                 "evidence": {"journalRoot": (root / JOURNAL_ROOT).is_dir(),
                              "checkpointScript": (root / CHECKPOINT_PY).is_file()}})

    sol, grok = dir_listing(root, SOL_OUT), dir_listing(root, GROK_OUT)
    r7_ok = sol["present"] or grok["present"]
    rows.append({"id": "R7", "ok": r7_ok,
                 "evidence": {"sol": sol["path"] if sol["present"] else None,
                              "solFiles": sol.get("fileCount", 0),
                              "grok": grok["path"] if grok["present"] else None,
                              "grokFiles": grok.get("fileCount", 0)}})

    first_block = next((r for r in rows if not r["ok"]), None)
    verdict = "GO" if first_block is None else "WAIT"
    reason = "all R1-R7 ready" if first_block is None else \
        f"{first_block['id']} not-ready: {json.dumps(first_block['evidence'], ensure_ascii=True)}"
    return {"schema": SCHEMA, "verdict": verdict, "reason": reason, "rows": rows}


def render_markdown(result: dict) -> str:
    line = ("GO" if result["verdict"] == "GO"
            else f"WAIT - {result['reason']}")
    out = [f"# task-context readiness - {line}", "",
           "| item | status | evidence |", "|---|---|---|"]
    for r in result["rows"]:
        ev = json.dumps(r["evidence"], ensure_ascii=True)
        out.append(f"| {r['id']} | {'ok' if r['ok'] else 'NOT_READY'} | {ev} |")
    out += ["",
            "GO requires R1-R7 all ok. Run: "
            "`python -B scripts/task_context_readiness.py`"]
    return "\n".join(out) + "\n"


def self_test() -> int:
    import tempfile
    failures = []

    def check(name, cond):
        if not cond:
            failures.append(name)

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        # Fixture tree: astra report + closed journal, journal stats, locks.
        (root / ASTRA_LEDGER).mkdir(parents=True)
        (root / ASTRA_LEDGER / "report.md").write_text("x", encoding="utf-8")
        aj = root / ASTRA_JOURNAL
        aj.parent.mkdir(parents=True)
        aj.write_text(json.dumps({"status": "closed", "endedAtUtc": "t"}),
                      encoding="utf-8")
        (root / WORK_JOURNAL_PY).parent.mkdir(parents=True, exist_ok=True)
        (root / WORK_JOURNAL_PY).write_text("x", encoding="utf-8")
        (root / CHECKPOINT_PY).write_text("x", encoding="utf-8")
        (root / SOL_OUT).mkdir(parents=True)
        (root / SOL_OUT / "a.txt").write_text("x", encoding="utf-8")
        busy = root / JOURNAL_ROOT / "task-busy-1"
        busy.mkdir(parents=True)
        busy.joinpath("journal.json").write_text(
            json.dumps({"events": [{"a": i} for i in range(12)]}), encoding="utf-8")
        fake_git = lambda r, rel: ""
        res = evaluate(root, porcelain=fake_git)
        check("go", res["verdict"] == "GO")
        check("r5.cand", any(row["id"] == "R5" and
                             row["evidence"]["taskIdCandidate"] == "task-busy-1"
                             for row in res["rows"]))
        # Remove astra report + reopen journal -> WAIT on R1.
        (root / ASTRA_LEDGER / "report.md").unlink()
        aj.write_text(json.dumps({"status": "in_progress"}), encoding="utf-8")
        res2 = evaluate(root, porcelain=fake_git)
        check("wait", res2["verdict"] == "WAIT" and res2["reason"].startswith("R1"))
        # Present task_context.py -> R3 already-started.
        (root / ASTRA_LEDGER / "report.md").write_text("x", encoding="utf-8")
        aj.write_text(json.dumps({"status": "closed", "endedAtUtc": "t"}),
                      encoding="utf-8")
        (root / TASK_CONTEXT_PY).parent.mkdir(parents=True, exist_ok=True)
        (root / TASK_CONTEXT_PY).write_text("x", encoding="utf-8")
        res3 = evaluate(root, porcelain=fake_git)
        check("r3.started", res3["verdict"] == "WAIT" and "R3" in res3["reason"])
        (root / TASK_CONTEXT_PY).unlink()
        # Dirty git -> R2 fails.
        res4 = evaluate(root, porcelain=lambda r, rel: " M scripts/work_journal.py")
        check("r2.dirty", res4["verdict"] == "WAIT" and "R2" in res4["reason"])
        # Foreign lease on work_journal.py -> R2 fails.
        lock = root / LOCK_ROOT / "foreign.lock"
        lock.mkdir(parents=True)
        lock.joinpath("lease.json").write_text(
            json.dumps({"targetPaths": ["scripts/work_journal.py"]}), encoding="utf-8")
        res5 = evaluate(root, porcelain=fake_git)
        check("r2.lease", res5["verdict"] == "WAIT" and "R2" in res5["reason"])
        lock.joinpath("lease.json").unlink()
        lock.rmdir()
        # Markdown first line contract.
        md = render_markdown(evaluate(root, porcelain=fake_git))
        check("md.firstline", md.startswith("# task-context readiness - GO"))

    ok = not failures
    print(json.dumps({"schema": SCHEMA, "mode": "self-test",
                      "verdict": "PASS" if ok else "FAIL",
                      "failures": failures}, indent=2))
    return 0 if ok else 4


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="task_context_readiness.py",
        description="GO/WAIT card for the next Codex task-context lane (read-only).")
    parser.add_argument("--root", default=None, help="repo root (default: auto)")
    parser.add_argument("--json", action="store_true", help="JSON verdict output")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    root = Path(args.root).resolve() if args.root \
        else Path(__file__).resolve().parents[1]
    result = evaluate(root)
    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        print(render_markdown(result), end="")
    return 0 if result["verdict"] == "GO" else 1


if __name__ == "__main__":
    raise SystemExit(main())
