"""Shared task-entry preflight for Codex/Grok/Devin/Cline (awx.agent-preflight.v1).

One command, one report. Prints JSON only. Every agent that touches files runs
this first; every field is an observation (or a bounded probe), never a guess.
Observed:
  deviceBus   shared-resource registry probe (start is idempotent observation)
  journals    active work journals (any agent's unfinished task stays visible)
  leases      source-edit lease status incl. expired leases pending owner evidence
  changePlane ChangeIntent board summary (read-only intents.json projection)
  protections which agent adapters are observably installed (presence only;
              hook presence != enforcement — apply-time checks still bind)
  statusDoc    PROJECT_STATUS.md sha256 (the value update-row expects)
  tools        which common-guard entry points are present
  projectRoot  whether --root looks like DEMO1-PROJECT-ROOT (markers + canonical)

Read-only: probes never mutate leases/journals. Exit 0 always unless the report
itself cannot be produced; degraded surfaces are fields, not exit codes.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import hashlib


def sha(path):
    try:
        return hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError:
        return None


def run_json(argv, cwd, timeout=30):
    try:
        out = subprocess.run(argv, cwd=cwd, capture_output=True, text=True,
                             timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as failure:
        return {"status": "unavailable", "reason": type(failure).__name__}
    try:
        data = json.loads(out.stdout.strip().splitlines()[-1]) if out.stdout.strip() else None
    except (ValueError, IndexError):
        data = None
    return {"status": "ok" if out.returncode == 0 else "error",
            "exitCode": out.returncode, "result": data}


LEASE_ACTIONS = (
    "begin", "end", "status", "verify", "bind-scope", "heartbeat", "recover",
)


def lease_guidance(leases_field):
    """Explain lease inventory vs overlap. Does not mutate leases."""
    result = {}
    if isinstance(leases_field, dict) and isinstance(leases_field.get("result"), dict):
        result = leases_field["result"]
    return {
        "schemaVersion": "awx.agent-preflight.lease-guidance.v1",
        "actions": list(LEASE_ACTIONS),
        "unsupportedActions": ["open"],
        "targetManifest": {
            "param": "TargetManifest",
            "kind": "json-file-path",
            "shape": {
                "targets": [{"path": "repo-relative", "sha256": "64-hex or null for a still-absent new file"}],
            },
            "inlineJsonNotAccepted": True,
            "unsupportedParams": ["TargetPath"],
        },
        "blockingCount": {
            "formula": "active+corrupt+expired",
            "value": result.get("sourceLeaseBlockingCount"),
            "repositoryWideHold": False,
            "meaning": "global inventory of reservations that still protect their declared targets; not proof that this session cannot proceed",
        },
        "overlap": {
            "how": "powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action status -Root . -Json -TargetManifest <json-file>",
            "fields": [
                "targetConflict.allowed",
                "targetConflict.conflictingLeaseCount",
                "targetConflict.unrelatedLeaseCount",
                "targetConflict.conflictingPaths",
            ],
            "proceedWhen": "targetConflict.allowed is true even if sourceLeaseBlockingCount > 0",
        },
        "expiredUnknown": {
            "stillBlocksOverlap": True,
            "recovery": "only -Action recover after a proven same-host dead owner; TTL or folder mtime is not death proof; do not delete or steal",
            "ownerStateUnknown": "owner-evidence-needed is not permission to take the lock",
        },
        "statusWithoutManifest": {
            "exit7IfAnyBlockingLease": True,
            "doesNotMeanRepositoryWideHold": True,
        },
        "conflictAutoflow": {
            "tool": "python -B scripts/lease_conflict_autoflow.py plan --goal-files <paths> --task <myTaskId> --execute",
            "skill": "$demo1-lease-conflict-autoflow",
            "policy": "no force release; proceed non-overlapping targets; one user prompt per conflict fingerprint",
        },
        "changePlane": {
            "tool": "python -B scripts/agent_change_plane.py propose|admit|renew|status|events|plan|request-release",
            "skill": "$demo1-agent-change-plane",
            "policy": "intent board over the same lease; admit delegates to begin; fence-gated renew/seal/end",
        },
    }


def change_plane_field(root):
    """Read-only projection of data/agent-handoff/change-plane/.

    Parses intents.json directly (never runs the tool, never mutates): the
    board may be absent on a fresh checkout and that is a field, not a failure.
    """
    field = {"schemaVersion": "awx.agent-preflight.change-plane.v1",
             "present": False, "activeIntents": 0, "blockedCount": 0,
             "runtimeOwner": None, "buildOwner": None, "lastEventSeq": 0}
    base = root / "data" / "agent-handoff" / "change-plane"
    intents_path = base / "intents.json"
    if not intents_path.is_file():
        return field
    field["present"] = True
    try:
        doc = json.loads(intents_path.read_bytes())
    except (OSError, ValueError):
        field["error"] = "intents-unreadable"
        return field
    live = ("admitted", "sealed")
    open_states = live + ("proposed",)
    for intent in (doc or {}).get("intents") or []:
        if not isinstance(intent, dict):
            continue
        state = intent.get("state")
        if state in open_states:
            field["activeIntents"] += 1
        elif state == "blocked":
            field["blockedCount"] += 1
        if state in live:
            for surface in intent.get("surfaces") or []:
                key = str(surface) + "Owner"
                if key in field and field[key] is None:
                    field[key] = {"intentId": intent.get("intentId"),
                                  "agent": intent.get("agent"),
                                  "taskId": intent.get("taskId")}
    nxt = (doc or {}).get("nextEventSeq")
    if isinstance(nxt, int) and nxt > 0:
        field["lastEventSeq"] = nxt - 1
    return field


def collect(root):
    root = Path(root).resolve()
    py = sys.executable
    report = {"schemaVersion": "awx.agent-preflight.v1", "root": str(root)}

    report["deviceBus"] = run_json(
        [py, "-B", "scripts/awx_device_bus.py", "start"], root, timeout=45)

    report["journals"] = run_json(
        [py, "-B", "scripts/work_journal.py", "list", "--active"], root)

    leases_script = root / "__patch_drop__" / "source_edit_session.ps1"
    if leases_script.is_file():
        report["leases"] = run_json(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             str(leases_script), "-Action", "status", "-Root", str(root),
             "-Json"], root, timeout=45)
    else:
        report["leases"] = {"status": "unavailable", "reason": "session-script-missing"}
    report["leaseGuidance"] = lease_guidance(report.get("leases"))
    report["changePlane"] = change_plane_field(root)

    def rel(*parts):
        p = root.joinpath(*parts)
        return {"present": p.exists(), "sha256": sha(p) if p.is_file() else None}

    report["protections"] = {
        "codexHook": rel(".codex", "hooks.json"),
        "devinHook": rel(".devin", "hooks.v1.json"),
        "grokDir": rel(".grok"),
        "clineRules": rel(".clinerules"),
        "agentsMd": rel("AGENTS.md"),
        "workLedgerSkill": rel(".agents", "skills", "demo1-work-ledger", "SKILL.md"),
    }
    status = root / "docs" / "PROJECT_STATUS.md"
    report["statusDoc"] = {"path": str(status), "present": status.is_file(),
                           "sha256": sha(status)}
    report["tools"] = {name: rel("scripts", name)["present"] for name in (
        "codex_work_checkpoint.py", "run_verified_command.py", "status_doc.py",
        "work_journal.py", "codex_home_quarantine.py", "awx_session_evidence.py",
        "agent_preflight.py", "agent_session_watch.py", "agent_work_guard.py")}
    guard = root / "scripts" / "agent_work_guard.py"
    if guard.is_file():
        report["projectRoot"] = run_json(
            [py, "-B", str(guard), "root", "--root", str(root)], root, timeout=20)
    else:
        report["projectRoot"] = {"status": "unavailable",
                                 "reason": "work-guard-missing"}
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(collect(args.root), ensure_ascii=True))
        return 0
    except Exception as failure:  # report must always be printable
        print(json.dumps({"schemaVersion": "awx.agent-preflight.v1",
                          "status": "error", "reason": type(failure).__name__}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
