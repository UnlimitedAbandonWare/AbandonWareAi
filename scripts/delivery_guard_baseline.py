#!/usr/bin/env python3
"""delivery_guard_baseline.py — snapshot the pre-patch surface of the
TASK-CONTINUITY-DELIVERY-20261008 work and diff it after Codex's patch.

Purpose: make Acceptance A4 ("기존 checkpoint/ledger 호환·다른작업 무변경")
checkable. The snapshot records sha256 plus extracted surface markers (CLI
options, headings, AGENTS block names) so a post-patch --diff distinguishes
*added* markers from *removed* ones — removed CLI options or headings are a
compat risk; added ones are the intended extension.

    python -B scripts/delivery_guard_baseline.py --snapshot --out <baseline.json>
    python -B scripts/delivery_guard_baseline.py --diff <baseline.json>
    python -B scripts/delivery_guard_baseline.py --self-test

Exit: snapshot 0; diff 0 when every tracked file is same, 6 on change/missing;
2 usage/io error. Read-only except for the --out file.
"""
import argparse
import hashlib
import json
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

WATCH = [
    "AGENTS.md",
    "scripts/checkpoint_doctor.py",
    "scripts/codex_work_checkpoint.py",
    "scripts/demo1_goal_switch_barrier.py",
    "scripts/work_journal.py",
    "scripts/deliver_to_downloads.py",
    "scripts/task_context.py",
    ".agents/skills/demo1-session-state-checkpoint/SKILL.md",
    ".agents/skills/demo1-devin-directive-loop/SKILL.md",
    ".agents/skills/demo1-goal-complete-stop/SKILL.md",
    "docs/agents-rules/DEMO1-OUTPUT-BUDGET.md",
    "docs/agents-rules/DEMO1-DELIVERY-DOWNLOADS.md",
    "docs/agents-rules/DEMO1-WORK-LEDGER.md",
]

RX_OPTION = re.compile(r"--[a-zA-Z][a-zA-Z0-9\-]+")
RX_HEADING = re.compile(r"^#{1,4}\s+.+$", re.M)
RX_AGENTS_BLOCK = re.compile(r"<!--\s*(BEGIN|END)\s+([A-Z0-9\-]+)\s*-->")
RX_STATE_MD = re.compile(r"state\.md")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(262144), b""):
            h.update(chunk)
    return h.hexdigest()


def markers_for(rel, text):
    marks = {}
    if rel.endswith(".py"):
        marks["cliOptions"] = sorted(set(RX_OPTION.findall(text)))
    if rel.endswith(".md") or rel.endswith(".skill"):
        marks["headings"] = RX_HEADING.findall(text)
    if rel == "AGENTS.md":
        marks["agentsBlocks"] = sorted({f"{m[0]}:{m[1]}" for m in RX_AGENTS_BLOCK.findall(text)})
        marks["stateMdMentionLines"] = [
            i + 1 for i, line in enumerate(text.splitlines()) if RX_STATE_MD.search(line)]
    return marks


def capture(paths):
    snap = {}
    for rel in paths:
        p = ROOT / rel
        if not p.is_file():
            snap[rel] = {"exists": False}
            continue
        try:
            text = p.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            text = None
        entry = {"exists": True, "sha256": sha256_of(p), "bytes": p.stat().st_size,
                 "markers": markers_for(rel, text) if text is not None else {}}
        snap[rel] = entry
    return snap


def do_snapshot(out_path):
    snap = {"schema": "awx.delivery-guard-baseline.v1",
            "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
            "root": str(ROOT), "files": capture(WATCH)}
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    Path(out_path).write_text(json.dumps(snap, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
    tracked = sum(1 for v in snap["files"].values() if v.get("exists"))
    print(f"snapshot -> {out_path}  tracked={tracked}/{len(WATCH)}")
    return 0


def do_diff(baseline_path):
    try:
        base = json.loads(Path(baseline_path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"baseline unreadable: {exc}")
        return 2
    old = base.get("files", {})
    now = capture(list(old.keys()) or WATCH)
    changed = []
    for rel, prev in old.items():
        cur = now.get(rel, {"exists": False})
        if not prev.get("exists") and not cur.get("exists"):
            continue
        status = "same"
        detail = ""
        if prev.get("exists") != cur.get("exists"):
            status = "missing" if not cur.get("exists") else "created"
        elif prev.get("sha256") != cur.get("sha256"):
            status = "changed"
            adds, removes = [], []
            for key in set((prev.get("markers") or {})) | set((cur.get("markers") or {})):
                before = set((prev.get("markers") or {}).get(key) or [])
                after = set((cur.get("markers") or {}).get(key) or [])
                adds += [f"{key}+{x}" for x in sorted(after - before)]
                removes += [f"{key}-{x}" for x in sorted(before - after)]
            parts = []
            if adds:
                parts.append("added:" + ",".join(adds[:12]))
            if removes:
                parts.append("REMOVED:" + ",".join(removes[:12]))
            detail = "  ".join(parts)
        if status != "same":
            changed.append({"path": rel, "status": status, "detail": detail})
        print(f"{status:<8} {rel}" + (f"  {detail}" if detail else ""))
    print(json.dumps({"changed": len(changed),
                      "removedMarkers": sum(d["detail"].count("REMOVED:") for d in changed),
                      "baselineCapturedAtUtc": base.get("capturedAtUtc")},
                     ensure_ascii=False))
    return 6 if changed else 0


def self_test():
    with tempfile.TemporaryDirectory(prefix="dg-baseline-") as tmp:
        tmp = Path(tmp)
        global ROOT
        orig = ROOT
        try:
            ROOT = tmp
            f = tmp / "scripts" / "checkpoint_doctor.py"
            f.parent.mkdir(parents=True)
            f.write_text("# v1 --run --latest --warn-seconds\n", encoding="utf-8")
            snap_path = tmp / "snap.json"
            assert do_snapshot(snap_path) == 0
            f.write_text("# v2 --run --latest\n", encoding="utf-8")
            assert do_diff(snap_path) == 6
            f.write_text("# v1 --run --latest --warn-seconds\n", encoding="utf-8")
            assert do_diff(snap_path) == 0
        finally:
            ROOT = orig
    print("SELFTEST snapshot->diff change-detection: PASS")
    print("SELFTEST snapshot->diff same-detection: PASS")
    return 0


def main():
    try:  # Windows consoles may be cp949; help/docstrings carry non-ASCII
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--snapshot", action="store_true")
    ap.add_argument("--diff", default=None, metavar="BASELINE_JSON")
    ap.add_argument("--out", default="var/codex-assist-task-continuity-20261008/baseline.json")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.snapshot:
        return do_snapshot(args.out)
    if args.diff:
        return do_diff(args.diff)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
