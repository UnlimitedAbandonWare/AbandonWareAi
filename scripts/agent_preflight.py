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
  signals      peer-signal packet: bus inbox refs, LEASE_RELEASE_REQUEST.md
               locations, active peer journals, and (with --agent) the
               goal-switch barrier check summary. Metadata/counts only —
               no document bodies, secrets, or conversation text.

Read-only: probes never mutate leases/journals. Exit 0 always unless the report
itself cannot be produced; degraded surfaces are fields, not exit codes.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
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
            "recovery": "-Action recover quarantines proven same-host dead owners; stale reclaim (below) covers TTL/heartbeat-expired leases whose owner is not proven alive; do not delete or steal lock dirs",
            "ownerStateUnknown": "owner-evidence-needed + expired TTL/heartbeat = stale, reclaimable via the tool; a live lease (recent heartbeat or alive owner) is never force-released",
        },
        "staleReclaim": {
            "lifecycle": "live (valid TTL / recent heartbeat / alive owner) | stale (expired, owner not proven alive) | orphan (unreadable lock)",
            "tool": "python -B scripts/lease_conflict_autoflow.py reclaim [--targets <paths>] [--task <myTaskId>] [--dry-run] [--include-orphan]",
            "alias": "python -B scripts/agent_scope_lease.py reclaim",
            "policy": "stale leases are quarantined to source-edit-quarantine with receipt + stale-reclaim event + AUTO:lease-reclaimed journal; never ask the user to relay 'please end your lease' to another session",
        },
        "statusWithoutManifest": {
            "exit7IfAnyBlockingLease": True,
            "doesNotMeanRepositoryWideHold": True,
        },
        "conflictAutoflow": {
            "tool": "python -B scripts/lease_conflict_autoflow.py plan --goal-files <paths> --task <myTaskId> --execute",
            "skill": "$demo1-lease-conflict-autoflow",
            "policy": "stale overlap auto-reclaimed on --execute; live lease never forced; proceed non-overlapping targets; one informational prompt per conflict fingerprint",
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


JOURNAL_BASE = Path("data") / "agent-handoff" / "codex-autonomy"
RELEASE_DOC_NAME = "LEASE_RELEASE_REQUEST.md"
RELEASE_FALLBACK = Path("data") / "agent-handoff" / "change-plane" / "release-requests"
SAFE_TASK = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
SIGNAL_KINDS = ("lease_conflict", "lease_reclaim", "recovery", "hold")


def utc_iso(epoch):
    try:
        return datetime.fromtimestamp(epoch, timezone.utc).isoformat()
    except (OSError, ValueError, OverflowError):
        return None


def read_json(path, cap=524288):
    try:
        if path.stat().st_size > cap:
            return None
        return json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except (OSError, ValueError):
        return None


def release_requests(root, agent=None, limit=32):
    """LEASE_RELEASE_REQUEST.md locations under task dirs + the change-plane
    fallback dir. Paths/metadata only — request bodies are never copied."""
    entries = []
    base = root / JOURNAL_BASE
    if base.is_dir():
        for task_dir in sorted(base.iterdir()):
            doc = task_dir / RELEASE_DOC_NAME
            if not doc.is_file():
                continue
            journal = read_json(task_dir / "journal.json") or {}
            try:
                stat = doc.stat()
            except OSError:
                continue
            entries.append({
                "path": str(doc.relative_to(root)).replace("\\", "/"),
                "ownerTaskId": task_dir.name,
                "ownerAgent": journal.get("agent"),
                "bytes": stat.st_size,
                "mtimeUtc": utc_iso(stat.st_mtime),
                "fallback": False})
    fallback_dir = root / RELEASE_FALLBACK
    if fallback_dir.is_dir():
        for path in sorted(fallback_dir.glob("*.md")):
            try:
                stat = path.stat()
            except OSError:
                continue
            # Name shape is <fingerprint>-<ownerTaskId>; owner resolution is
            # best-effort via that task's journal.
            stem = path.stem
            owner_task = stem.split("-", 2)[2] if stem.count("-") >= 2 else None
            owner_agent = None
            if owner_task and SAFE_TASK.fullmatch(owner_task):
                doc = read_json(base / owner_task / "journal.json")
                owner_agent = (doc or {}).get("agent")
            entries.append({
                "path": str(path.relative_to(root)).replace("\\", "/"),
                "ownerTaskId": owner_task,
                "ownerAgent": owner_agent,
                "bytes": stat.st_size,
                "mtimeUtc": utc_iso(stat.st_mtime),
                "fallback": True})
    entries = entries[:limit]
    field = {"count": len(entries), "requests": entries}
    if agent:
        field["agent"] = agent
        field["addressedToAgent"] = sum(
            1 for e in entries if e.get("ownerAgent") == agent)
    return field


def peer_journals(root, journals_field, agent=None, tail=8):
    """Active-journal summary + recent signal-kind event lines.

    Purpose strings are already truncated at journal open; event text is cut
    to 160 chars here. No file bodies, secrets, or conversation text.
    """
    tasks = (((journals_field or {}).get("result") or {}).get("tasks") or [])
    rows = []
    for task in tasks[:32]:
        if not isinstance(task, dict):
            continue
        rows.append({"taskId": task.get("taskId"),
                     "agent": task.get("agent"),
                     "updatedAtUtc": task.get("updatedAtUtc"),
                     "eventCount": task.get("eventCount"),
                     "scopeCount": task.get("scopeCount"),
                     "purpose": str(task.get("purpose") or "")[:120]})
    recent = []
    base = root / JOURNAL_BASE
    for row in rows[:16]:
        task_id = str(row.get("taskId") or "")
        if not SAFE_TASK.fullmatch(task_id):
            continue
        doc = read_json(base / task_id / "journal.json") or {}
        for event in (doc.get("events") or [])[-40:]:
            if not isinstance(event, dict):
                continue
            kind = str(event.get("kind") or "")
            text = str(event.get("text") or "")
            if kind in SIGNAL_KINDS or text.startswith("AUTO:"):
                recent.append({"taskId": task_id, "at": event.get("at"),
                               "kind": kind, "text": text[:160]})
    field = {"activeCount": len(rows), "journals": rows,
             "recentSignals": recent[-tail:]}
    if agent:
        field["agent"] = agent
        field["ownActiveCount"] = sum(
            1 for r in rows if r.get("agent") == agent)
        field["peerActiveCount"] = len(rows) - field["ownActiveCount"]
    return field


def inbox_field(root, py):
    """awx_device_bus.py inbox as a compact signal — refs only, last 5."""
    res = run_json([py, "-B", "scripts/awx_device_bus.py", "inbox"], root)
    result = res.get("result") if isinstance(res.get("result"), dict) else {}
    refs = result.get("eventRefs") or []
    return {"status": res.get("status"), "exitCode": res.get("exitCode"),
            "observedStatus": result.get("status"),
            "eventCount": result.get("eventCount"),
            "latestEventRefs": [str(r) for r in refs[-5:]]}


def goal_switch_field(root, py, agent):
    """Optional barrier-check summary; needs an owner name to be meaningful."""
    if not agent:
        return {"status": "skipped",
                "reason": "agent not provided; run demo1_goal_switch_barrier.py check --agent <name>"}
    res = run_json([py, "-B", "scripts/demo1_goal_switch_barrier.py", "check",
                    "--root", str(root), "--agent", agent], root, timeout=30)
    result = res.get("result") if isinstance(res.get("result"), dict) else {}
    keep = ("ownedInProgressCount", "foreignInProgressCount",
            "switchRequired", "allowedOpen", "recommendedNext", "leaseScan")
    check = {k: result.get(k) for k in keep}
    check["ownedLeaseCount"] = len(result.get("ownedLeases") or [])
    return {"status": res.get("status"), "exitCode": res.get("exitCode"),
            "check": check}


def signals_field(root, py, agent, journals_field):
    return {"schemaVersion": "awx.agent-preflight.signals.v1",
            "agent": agent,
            "inbox": inbox_field(root, py),
            "releaseRequests": release_requests(root, agent),
            "peerJournals": peer_journals(root, journals_field, agent),
            "goalSwitch": goal_switch_field(root, py, agent)}


def collect(root, agent=None):
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
    report["signals"] = signals_field(root, py, agent, report.get("journals"))

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
    parser.add_argument("--agent", default=None,
                        help="caller agent name; scopes signals "
                             "(releaseRequests for-me count, own vs peer "
                             "journals, goal-switch barrier check)")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(collect(args.root, agent=args.agent), ensure_ascii=True))
        return 0
    except Exception as failure:  # report must always be printable
        print(json.dumps({"schemaVersion": "awx.agent-preflight.v1",
                          "status": "error", "reason": type(failure).__name__}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
