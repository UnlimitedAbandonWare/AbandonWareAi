"""Per-task work journal for agent handoff continuity on this checkout.

Records who claimed which scope, what was planned/applied/verified, and where
preimage bytes live. A journal is not a lock and not a backup: target-scoped
source leases stay with __patch_drop__/source_edit_session.ps1 and per-change
preimage/postimage bytes stay with scripts/codex_work_checkpoint.py cycle
directories in the same task directory. A journal entry never authorizes an
edit and never proves verification; it records what the calling agent reported.

Storage: data/agent-handoff/codex-autonomy/<taskId>/journal.json. The
"codex-autonomy" directory name is historical; records are agent-neutral and
the agent identity is a recorded field. Per-task files keep concurrent writers
from sharing one mutable record; never append to another task's journal.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_work_checkpoint as ck

BASE = "data/agent-handoff/codex-autonomy"
SCHEMA = "awx.work_journal.v1"
KINDS = {"plan", "preserve", "change", "verify", "hold", "external-change",
         "recovery", "report", "info", "lease_conflict"}
RESULTS = {"verified", "partial", "blocked", "abandoned", "superseded"}
SECRET_SUFFIXES = {".key", ".pem", ".pfx", ".p12", ".jks"}
PROTECTED_PARTS = {".git", ".codex", ".secrets"}
TASK_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")
SLUG = re.compile(r"[a-z0-9][a-z0-9-]{0,55}")
MAX_EVENTS = 512
MAX_TEXT = 2000
MAX_PURPOSE = 500
MAX_NAME = 120
MAX_REFS = 32
MAX_SCOPE = 64
MAX_JOURNALS = 256


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def journal_task_id(value):
    ck.require(isinstance(value, str) and TASK_ID.fullmatch(value), "invalid-task-id")
    return value


def ref_path(root, value):
    path = ck.relative_path(root, value)
    parts = [p.casefold() for p in Path(value).parts]
    ck.require(not (set(parts) & PROTECTED_PARTS) and not Path(value).name.startswith(".env")
               and Path(value).suffix.lower() not in SECRET_SUFFIXES, "protected-ref-path")
    return path


def task_dir(root, task_id):
    journal_task_id(task_id)
    return ck.relative_path(root, BASE + "/" + task_id)


def load(root, task_id):
    directory = task_dir(root, task_id)
    data = ck.contents(directory / "journal.json")
    ck.require(data is not None and len(data) <= 256 * 1024, "journal-missing-or-oversize")
    journal = json.loads(data)
    ck.require(journal.get("schemaVersion") == SCHEMA and journal.get("taskId") == task_id,
               "journal-identity-changed")
    return directory, journal


def save(directory, journal):
    journal["updatedAtUtc"] = utcnow()
    ck.write_json(directory / "journal.json", journal)


def text_field(value, limit, reason):
    ck.require(isinstance(value, str) and 0 < len(value.strip()) and len(value) <= limit, reason)
    ck.secret_free(value.encode("utf-8"))
    return value.strip()


def ref_list(root, values, limit):
    values = values or []
    ck.require(isinstance(values, list) and len(values) <= limit, "ref-list-bounds")
    for value in values:
        ref_path(root, value)
    return [str(v).replace("\\", "/") for v in values]


def open_journal(root, task_id, agent, purpose, scope):
    root = ck.root_path(root)
    directory = task_dir(root, task_id)
    ck.require(not directory.exists(), "task-dir-already-exists")
    journal = {
        "schemaVersion": SCHEMA,
        "taskId": task_id,
        "agent": text_field(agent, MAX_NAME, "agent-required"),
        "purpose": text_field(purpose, MAX_PURPOSE, "purpose-required"),
        "plannedScope": ref_list(root, scope, MAX_SCOPE),
        "status": "in_progress",
        "result": None,
        "startedAtUtc": utcnow(),
        "updatedAtUtc": utcnow(),
        "endedAtUtc": None,
        "events": [],
    }
    directory.mkdir(parents=True, exist_ok=False)
    save(directory, journal)
    return journal


def add_note(root, task_id, kind, text, refs):
    root = ck.root_path(root)
    directory, journal = load(root, task_id)
    ck.require(journal["status"] == "in_progress", "journal-closed")
    ck.require(kind in KINDS, "invalid-event-kind allowed:" + ",".join(sorted(KINDS)))
    ck.require(len(journal["events"]) < MAX_EVENTS, "journal-event-cap")
    journal["events"].append({
        "at": utcnow(),
        "kind": kind,
        "text": text_field(text, MAX_TEXT, "note-text-required"),
        "refs": ref_list(root, refs, MAX_REFS),
    })
    save(directory, journal)
    return journal


def close_journal(root, task_id, result, summary):
    root = ck.root_path(root)
    directory, journal = load(root, task_id)
    ck.require(journal["status"] == "in_progress", "journal-already-closed")
    ck.require(result in RESULTS, "invalid-close-result allowed:" + ",".join(sorted(RESULTS)))
    journal["events"].append({
        "at": utcnow(),
        "kind": "report",
        "text": text_field(summary, MAX_TEXT, "summary-required"),
        "refs": [],
    })
    journal["status"] = "closed"
    journal["result"] = result
    journal["endedAtUtc"] = utcnow()
    save(directory, journal)
    return journal


def handoff(root, task_id, out=None):
    """Compact handoff packet (awx.handoff.v1): goal, approved scope, files with
    current hashes, verification runs, unresolved holds, recovery locations.
    A receiving agent re-verifies current bytes before acting — the packet is a
    map, not proof. Never carries raw conversation or secret values."""
    root = ck.root_path(root)
    directory, journal = load(root, task_id)
    files, runs, unresolved = [], [], []
    for cycle in sorted(directory.glob("*/checkpoint.json")):
        try:
            state = json.loads(cycle.read_bytes())
            manifest = json.loads((cycle.parent / "manifest.json").read_bytes())
        except (OSError, ValueError):
            continue
        rel = str(cycle.parent.relative_to(directory)).replace("\\", "/")
        for target in manifest.get("targets", []):
            path = ck.relative_path(root, target["path"])
            files.append({
                "path": target["path"], "cycle": rel,
                "preimageSha256": target.get("preimageSha256"),
                "postimageSha256": (state.get("postimages") or {}).get(target["path"]),
                "currentSha256": ck.digest(ck.contents(path)),
                "cycleStatus": state.get("status"),
            })
        if "verificationExitCode" in state:
            runs.append({"cycle": rel, "commandId": state.get("commandId"),
                         "exitCode": state.get("verificationExitCode"),
                         "failureClass": state.get("failureClass")})
        if state.get("status") in ("hold", "restoring", "sealed"):
            unresolved.append({"cycle": rel, "status": state.get("status"),
                               "blocking": state.get("firstBlockingRule"),
                               "targets": [t["path"] for t in manifest.get("targets", [])]})
    packet = {
        "schemaVersion": "awx.handoff.v1",
        "taskId": journal["taskId"], "agent": journal["agent"],
        "purpose": journal["purpose"], "status": journal["status"],
        "result": journal.get("result"), "plannedScope": journal["plannedScope"],
        "generatedAtUtc": utcnow(),
        "files": files, "verificationRuns": runs,
        "unresolved": unresolved,
        "events": [{"at": e["at"], "kind": e["kind"],
                    "text": e["text"][:500]} for e in journal.get("events", [])[-40:]],
        "recovery": {"taskDir": str(directory.relative_to(root)).replace("\\", "/"),
                     "restore": "codex_work_checkpoint.py restore --run <cycle>"},
        "receiverMust": "re-read files and compare currentSha256 before editing",
    }
    out_path = ref_path(root, out) if out else directory / "handoff.json"
    ck.write_json(out_path, packet)
    return packet


def list_journals(root, active_only):
    root = ck.root_path(root)
    base = root / BASE
    rows = []
    if base.is_dir():
        for path in sorted(base.glob("*/journal.json"))[:MAX_JOURNALS]:
            try:
                journal = json.loads(path.read_bytes())
            except (OSError, ValueError):
                continue
            if not isinstance(journal, dict) or journal.get("schemaVersion") != SCHEMA:
                continue
            if active_only and journal.get("status") != "in_progress":
                continue
            rows.append({
                "taskId": journal.get("taskId"),
                "agent": journal.get("agent"),
                "status": journal.get("status"),
                "result": journal.get("result"),
                "startedAtUtc": journal.get("startedAtUtc"),
                "updatedAtUtc": journal.get("updatedAtUtc"),
                "eventCount": len(journal.get("events", [])),
                "scopeCount": len(journal.get("plannedScope", [])),
                "purpose": str(journal.get("purpose", ""))[:120],
            })
    rows.sort(key=lambda row: str(row.get("updatedAtUtc") or ""), reverse=True)
    return {"schemaVersion": "awx.work_journal.list.v1", "activeOnly": active_only,
            "journalRoot": BASE, "tasks": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("open", "note", "close", "status", "list", "handoff"))
    parser.add_argument("--root", default=".")
    parser.add_argument("--task", help="Slug for open (auto-suffixed) or exact taskId otherwise")
    parser.add_argument("--task-id", help="Exact taskId for open (no auto-suffix)")
    parser.add_argument("--agent", help="Worker/agent name recorded at open")
    parser.add_argument("--purpose", help="One-line task purpose recorded at open")
    parser.add_argument("--scope", action="append", default=[], help="Planned repo-relative path")
    parser.add_argument("--kind", help="Event kind: " + ",".join(sorted(KINDS)))
    parser.add_argument("--text", help="Event or summary text; env names only, never values")
    parser.add_argument("--ref", action="append", default=[], help="Repo-relative evidence path")
    parser.add_argument("--result", help="Close result: " + ",".join(sorted(RESULTS)))
    parser.add_argument("--summary", help="Closing summary")
    parser.add_argument("--active", action="store_true", help="list: only in_progress tasks")
    parser.add_argument("--out", help="handoff: optional repo-relative output path")
    args = parser.parse_args()
    try:
        if args.action == "open":
            if args.task_id:
                task_id = journal_task_id(args.task_id)
            else:
                ck.require(isinstance(args.task, str) and SLUG.fullmatch(args.task),
                           "invalid-task-slug")
                task_id = args.task + "-" + uuid.uuid4().hex[:8]
            ck.require(args.agent and args.purpose, "agent-and-purpose-required")
            result = open_journal(args.root, task_id, args.agent, args.purpose, args.scope)
        elif args.action == "note":
            ck.require(args.task and args.kind and args.text, "task-kind-text-required")
            result = add_note(args.root, args.task, args.kind, args.text, args.ref)
        elif args.action == "close":
            ck.require(args.task and args.result and args.summary, "task-result-summary-required")
            result = close_journal(args.root, args.task, args.result, args.summary)
        elif args.action == "status":
            ck.require(args.task, "task-required")
            _, result = load(ck.root_path(args.root), args.task)
        elif args.action == "handoff":
            ck.require(args.task, "task-required")
            result = handoff(args.root, args.task, args.out)
        else:
            result = list_journals(args.root, args.active)
        print(json.dumps(result, ensure_ascii=True))
        return 0
    except (ck.CheckpointError, OSError, ValueError, KeyError, TypeError) as error:
        reason = str(error) if isinstance(error, ck.CheckpointError) else "journal-io-or-evidence-error"
        print(json.dumps({"status": "error", "reason": reason}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
