#!/usr/bin/env python3
"""agent_recovery_status.py — one-call blocker/recovery summary.

Replaces the repeated 5+ command recovery drill
(work_journal list + lease status + reclaim dry-run + docs lookup +
checkpoint-begin failure interpretation) with a single JSON answer.
Directive: .devin/PROMPTS/codex-weekly-skill-directive-20260924.md P2.

Actions:
  status [--targets targets.json] [--skip-lease-scan]
      leases   : begin allowed? conflict owners (hashed) + quarantine candidates
      journals : in_progress journals with age + cleanup suggestion
      nextActions : ordered guidance for the current state
  guide --reason <firstBlockingRule>
      static map from a failed checkpoint/lease reason to the next action

Read-only: never releases, reclaims, or edits foreign journals/leases.
Exit codes: 0 ok (including "holds found"), 2 usage/subprocess error.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.agent-recovery-status.v1"
SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
STALE_JOURNAL_HOURS = 24

GUIDE = {
    "source-lease-scope": [
        "Declare every target in the lease TargetManifest (docs/*.md, "
        "agent-prompts/, .agents/skills/**.md are artifacts — split them "
        "into a lease-free cycle).",
        "Or re-begin the lease with the missing paths added.",
    ],
    "source-owner-lease-required": [
        "Acquire a target-scoped lease: source_edit_session.ps1 -Action "
        "begin -TargetManifest <targets.json>.",
        "Or restrict targets to artifact paths (docs/, agent-prompts/, "
        ".agents/skills/**.md, root Markdown).",
    ],
    "source-lease-expired": [
        "Renew: source_edit_session.ps1 -Action heartbeat -LeaseFingerprint <f>.",
        "If the lease is foreign and stale: lease_conflict_autoflow.py "
        "reclaim --dry-run, then reclaim (never force-release a live lease).",
    ],
    "source-lease-drift": [
        "Re-read the lease.json bytes; a renewal rewrote it. Re-run begin "
        "with the current fingerprint.",
    ],
    "checkpoint-already-exists": [
        "Use a fresh <taskId>/cycle-NN directory, or inspect/restore the "
        "existing run first (codex_work_checkpoint.py status|restore).",
    ],
    "credential-path-protected": [
        "Remove the credential path from targets; .env/.pem/.key/.p12/.pfx/"
        ".jks are never checkpoint targets.",
    ],
    "invalid-or-unavailable-local-evidence": [
        "Write a decision packet JSON with goalId/reasonCode/risk(5 factors "
        "0-4)/gates(8 bools), then assess+begin.",
    ],
    "backup-preserve-failed": [
        "Do NOT start the change — preimage could not be preserved. Check "
        "disk/path permissions, then retry begin.",
    ],
}


def _run(cmd, timeout=60):
    try:
        proc = subprocess.run(cmd, cwd=str(ROOT), capture_output=True,
                              text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, str(error)
    try:
        return json.loads(proc.stdout.strip()), None
    except ValueError:
        return None, f"non-json-output(exit={proc.returncode})"


def _journal_summary(root):
    data, err = _run([sys.executable, "-B", str(SCRIPTS / "work_journal.py"),
                      "list", "--active", "--root", str(root)])
    if err:
        return {"status": "unavailable", "error": err}
    now = datetime.now(timezone.utc)
    rows = []
    for task in (data or {}).get("tasks", []):
        age_h = None
        try:
            stamp = datetime.fromisoformat(
                str(task.get("updatedAtUtc", "")).replace("Z", "+00:00"))
            age_h = round((now - stamp).total_seconds() / 3600, 1)
        except ValueError:
            pass
        rows.append({
            "taskId": task.get("taskId"),
            "agent": task.get("agent"),
            "status": task.get("status"),
            "ageHours": age_h,
            "purpose": str(task.get("purpose") or "")[:120],
            "suggestion": ("stale-candidate: 진행 여부 미확인 — 소유자 확인 또는 "
                           "정리 제안" if age_h is not None
                           and age_h > STALE_JOURNAL_HOURS
                           else "recent"),
        })
    return {"status": "ok", "inProgressCount": len(rows), "journals": rows}


def _lease_summary(root, targets_manifest):
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
           str(ROOT / "__patch_drop__" / "source_edit_session.ps1"),
           "-Action", "status", "-Root", str(root), "-Json"]
    if targets_manifest:
        cmd += ["-TargetManifest", targets_manifest]
    data, err = _run(cmd, timeout=90)
    if err:
        return {"status": "unavailable", "error": err}
    out = {
        "status": "ok",
        "blockingCount": data.get("sourceLeaseBlockingCount"),
        "activeCount": data.get("sourceLeaseActiveCount"),
        "corruptCount": data.get("sourceLeaseCorruptCount"),
        "expiredCount": data.get("sourceLeaseExpiredCount"),
        "topics": data.get("sourceLeaseActiveTopics") or [],
    }
    conflict = data.get("targetConflict")
    if isinstance(conflict, dict):
        out["beginAllowed"] = conflict.get("allowed")
        out["conflictingPaths"] = conflict.get("conflictingPaths")
    return out


def _reclaim_candidates(root):
    data, err = _run([sys.executable, "-B",
                      str(SCRIPTS / "lease_conflict_autoflow.py"), "reclaim",
                      "--dry-run", "--root", str(root)])
    if err:
        return {"status": "unavailable", "error": err}
    return {"status": "ok",
            "candidates": (data or {}).get("reclaimed")
            or (data or {}).get("candidates") or [],
            "skipped": (data or {}).get("skipped") or []}


def _next_actions(leases, journals, reclaim):
    actions = []
    if leases.get("status") == "ok":
        if leases.get("beginAllowed") is False:
            actions.append("target-conflict: proceed non-overlapping paths "
                           "only; never force-release a live lease")
        if (leases.get("expiredCount") or 0) > 0 or reclaim.get("candidates"):
            actions.append("stale lease candidates exist: "
                           "lease_conflict_autoflow.py reclaim (dry-run "
                           "already shown) with your --task")
    stale_journals = [j for j in journals.get("journals", [])
                      if j.get("suggestion", "").startswith("stale")]
    if stale_journals:
        actions.append(f"{len(stale_journals)} in_progress journal(s) idle "
                       f">{STALE_JOURNAL_HOURS}h — 진행 여부 미확인; check "
                       "owner before cleanup, never mark done on their behalf")
    if not actions:
        actions.append("no blockers observed — begin/checkpoint may proceed")
    return actions


def cmd_status(args) -> dict:
    root = Path(args.root).resolve()
    journals = _journal_summary(root)
    leases = {"status": "skipped"} if args.skip_lease_scan \
        else _lease_summary(root, args.targets)
    reclaim = _reclaim_candidates(root)
    return {
        "schemaVersion": SCHEMA,
        "leases": leases,
        "quarantineCandidates": reclaim,
        "journals": journals,
        "nextActions": _next_actions(leases, journals, reclaim),
    }


def cmd_guide(args) -> dict:
    reason = str(args.reason or "").strip()
    steps = GUIDE.get(reason)
    return {
        "schemaVersion": SCHEMA,
        "reason": reason,
        "known": reason in GUIDE,
        "nextActions": steps or [
            "unmapped reason — read the blockingEvidence field, keep the "
            "hold, and check docs/codex-autonomous-work.md",
        ],
        "knownReasons": sorted(GUIDE),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("status")
    p.add_argument("--root", default=str(ROOT))
    p.add_argument("--targets", help="TargetManifest JSON for scoped begin check")
    p.add_argument("--skip-lease-scan", action="store_true",
                   help="skip the PowerShell lease inventory (journals+reclaim only)")
    p = sub.add_parser("guide")
    p.add_argument("--reason", required=True)
    args = parser.parse_args(argv)

    result = cmd_status(args) if args.action == "status" else cmd_guide(args)
    print(json.dumps(result, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
