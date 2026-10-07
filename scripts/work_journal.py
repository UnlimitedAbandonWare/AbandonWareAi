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
import subprocess
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_work_checkpoint as ck

BASE = "data/agent-handoff/codex-autonomy"
SCHEMA = "awx.work_journal.v1"
STATUS_DOC = "docs/PROJECT_STATUS.md"
STATUS_TABLE_KEY = "| 시각(UTC) | taskId |"
KINDS = {"plan", "preserve", "change", "verify", "hold", "external-change",
         "recovery", "report", "info", "lease_conflict", "lease_reclaim"}
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


def _status_cell(text, limit):
    # One table cell: strip pipes/newlines/control chars so the row stays a
    # single `| ... |` line for status_doc.check_row.
    text = re.sub(r"[\x00-\x1f\x7f]", " ", str(text))
    return (text.replace("|", "/").strip() or "-")[:limit].strip() or "-"


def touch_status_doc(root, journal):
    """Append one §4 row to docs/PROJECT_STATUS.md for a closed task.

    Never raises and never blocks the close: the doc write reuses
    status_doc.py read->append-row semantics (fresh sha256 + expect-sha), and
    any miss returns a `warning` dict with a retry hint — an unrecorded close
    is a visible statusDoc warning, not a silent drop.
    """
    try:
        import status_doc as sd
    except ImportError:
        return {"status": "warning", "reason": "status-doc-module-missing",
                "retry": "python -B scripts/status_doc.py read --file docs/PROJECT_STATUS.md --key \"| taskId |\""}
    try:
        path = ck.relative_path(root, STATUS_DOC)
        if not path.is_file():
            return {"status": "skipped", "reason": "status-doc-absent"}
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        scope_list = [str(p) for p in journal.get("plannedScope", [])]
        scope = ", ".join(scope_list[:3])
        if len(scope_list) > 3:
            scope += " +" + str(len(scope_list) - 3)
        summary = ""
        for event in reversed(journal.get("events", [])):
            if event.get("kind") == "report":
                summary = str(event.get("text") or "")
                break
        line = "| {} | {} | {} | {} | {} |".format(
            stamp, journal.get("taskId"), _status_cell(scope, 90),
            _status_cell(summary, 220), journal.get("result"))
        probe = sd.read(str(path), STATUS_TABLE_KEY)
        if probe.get("matches") != 1:
            return {"status": "warning",
                    "reason": "status-table-key-" + ("missing" if not probe.get("matches") else "ambiguous"),
                    "retry": "python -B scripts/status_doc.py append-row --file docs/PROJECT_STATUS.md --after-key \"<§4 header row>\" --expect-sha256 <fresh-sha256> --line-file <row-file>"}
        applied = sd.append_row(str(path), STATUS_TABLE_KEY, line, probe["sha256"])
        if applied.get("status") == "applied":
            return {"status": "applied", "afterSha256": applied.get("afterSha256"),
                    "insertedAfterLine": applied.get("insertedAfterLine")}
        return {"status": "warning", "reason": str(applied.get("reason", "conflict")),
                "retry": "python -B scripts/status_doc.py read --file docs/PROJECT_STATUS.md --key \"| taskId |\" then append-row with the fresh sha256"}
    except (OSError, ValueError, KeyError, TypeError) as error:
        return {"status": "warning", "reason": "status-doc-io:" + str(error)[:160],
                "retry": "python -B scripts/status_doc.py append-row --file docs/PROJECT_STATUS.md --after-key \"| 시각(UTC) | taskId |\" --expect-sha256 <fresh-sha256> --line-file <row-file>"}


def close_journal(root, task_id, result, summary, touch_status=True,
                  check_evidence=False, idempotent=False):
    root = ck.root_path(root)
    directory, journal = load(root, task_id)
    ck.require(result in RESULTS, "invalid-close-result allowed:" + ",".join(sorted(RESULTS)))
    if journal["status"] == "closed" and idempotent:
        ck.require(journal.get("result") == result, "journal-close-result-mismatch")
        if check_evidence and result == "verified":
            check_close_evidence(root, task_id)
        return {**journal, "alreadyClosed": True}
    ck.require(journal["status"] == "in_progress", "journal-already-closed")
    if check_evidence and result == "verified":
        check_close_evidence(root, task_id)
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
    if touch_status:
        # Report-only field: appended after the journal file is saved so a doc
        # write failure can never reopen or corrupt the closed journal.
        journal["statusDoc"] = touch_status_doc(root, journal)
    return journal


def check_close_evidence(root, task_id):
    """Opt-in final source check; it neither releases leases nor changes receipts."""
    packet = handoff(root, task_id, write=False)
    evidence = packet["sourceCloseEvidence"]
    ck.require(evidence["status"] == "PASS", "journal-close-evidence-" + evidence["status"])
    paths = sorted(row["path"] for row in evidence["files"])
    ck.require(0 < len(paths) <= MAX_SCOPE, "journal-close-target-set")
    command = [sys.executable, "-B", str(Path(__file__).with_name("agent_scope_lease.py")),
               "--root", str(root), "check", "--strict", "--task", task_id]
    for path in paths:
        command += ["--path", path]
    try:
        checked = subprocess.run(command, capture_output=True, text=True,
                                 encoding="utf-8", errors="replace", timeout=90)
    except (OSError, subprocess.TimeoutExpired):
        raise ck.CheckpointError("journal-close-guard-unavailable") from None
    rows = []
    for line in checked.stdout.splitlines():
        try:
            value = json.loads(line)
            if isinstance(value, dict):
                rows.append(value)
        except ValueError:
            pass
    ck.require(checked.returncode == 0 and rows and rows[-1].get("allowed") is True,
               "journal-close-guard-rejected")
    current = handoff(root, task_id, write=False)["sourceCloseEvidence"]
    ck.require(current == evidence, "journal-close-evidence-changed")


def _current_evidence(state, manifest, manifest_bytes, root, target):
    name = target["path"]
    posts = state.get("postimages")
    posts = posts if isinstance(posts, dict) else {}
    post_recorded = name in posts
    expected = posts[name] if post_recorded else target.get("preimageSha256")
    current = ck.digest(ck.contents(ck.relative_path(root, name)))
    manifest_matches = (state.get("manifestSha256") == ck.digest(manifest_bytes)
                        and manifest.get("version") == 1 and manifest.get("root") == str(root))
    binding_matches = manifest_matches and current == expected
    status = state.get("status")
    exit_code = state.get("verificationExitCode")
    if status in ("hold", "restoring") or not manifest_matches:
        verdict = "HOLD"
    elif type(exit_code) is int and exit_code != 0:
        verdict = "FAIL"
    elif not binding_matches:
        verdict = "INVALIDATED"
    elif (status == "verified" and post_recorded and type(exit_code) is int
          and exit_code == 0 and isinstance(state.get("commandId"), str)
          and state["commandId"].strip()
          and state.get("verificationEvidenceMode") == "caller-observed"):
        verdict = "PASS"
    else:
        verdict = "NOT_RUN"
    return {"postimageRecorded": post_recorded, "postimageSha256": posts.get(name),
            "currentSha256": current, "sourceBindingBasis": "postimage" if post_recorded else "preimage",
            "sourceBindingMatches": binding_matches, "manifestBindingMatches": manifest_matches,
            "verificationStatus": verdict}


def handoff(root, task_id, out=None, write=True):
    """Compact handoff packet (awx.handoff.v1): goal, approved scope, files with
    current hashes, verification runs, unresolved holds, recovery locations.
    A receiving agent re-verifies current bytes before acting — the packet is a
    map, not proof. Never carries raw conversation or secret values."""
    root = ck.root_path(root)
    directory, journal = load(root, task_id)
    files, runs, unresolved, latest = [], [], [], {}
    for cycle in sorted(directory.glob("*/checkpoint.json")):
        try:
            checkpoint_bytes = cycle.read_bytes()
            state = json.loads(checkpoint_bytes)
            manifest_bytes = (cycle.parent / "manifest.json").read_bytes()
            manifest = json.loads(manifest_bytes)
        except (OSError, ValueError):
            unresolved.append({"cycle": cycle.parent.name, "status": "hold",
                               "blocking": "checkpoint-unreadable", "targets": []})
            continue
        rel = str(cycle.parent.relative_to(directory)).replace("\\", "/")
        for target in manifest.get("targets", []):
            evidence = _current_evidence(state, manifest, manifest_bytes, root, target)
            row = {
                "path": target["path"], "cycle": rel,
                "preimageSha256": target.get("preimageSha256"),
                "cycleStatus": state.get("status"),
                "checkpointSha256": ck.digest(checkpoint_bytes),
                **evidence,
            }
            files.append(row)
            key = str(target["path"]).casefold()
            order = (str(state.get("updatedAtUtc") or ""), rel)
            if key not in latest or order > latest[key][0]:
                latest[key] = (order, row)
        if "verificationExitCode" in state:
            runs.append({"cycle": rel, "commandId": state.get("commandId"),
                         "exitCode": state.get("verificationExitCode"),
                         "failureClass": state.get("failureClass")})
        if state.get("status") in ("prepared", "hold", "restoring", "sealed"):
            unresolved.append({"cycle": rel, "status": state.get("status"),
                               "blocking": state.get("firstBlockingRule"),
                               "targets": [t["path"] for t in manifest.get("targets", [])]})
    latest_files = [entry[1] for _, entry in sorted(latest.items())]
    statuses = {row["verificationStatus"] for row in latest_files}
    if unresolved:
        statuses.add("HOLD" if any(row["status"] in ("hold", "restoring")
                                  for row in unresolved) else "NOT_RUN")
    close_status = next((status for status in ("HOLD", "INVALIDATED", "FAIL", "NOT_RUN")
                         if status in statuses), "PASS" if latest_files else "NOT_RUN")
    packet = {
        "schemaVersion": "awx.handoff.v1",
        "taskId": journal["taskId"], "agent": journal["agent"],
        "purpose": journal["purpose"], "status": journal["status"],
        "result": journal.get("result"), "plannedScope": journal["plannedScope"],
        "generatedAtUtc": utcnow(),
        "files": files, "verificationRuns": runs,
        "unresolved": unresolved,
        "sourceCloseEvidence": {"status": close_status, "files": latest_files},
        "events": [{"at": e["at"], "kind": e["kind"],
                    "text": e["text"][:500]} for e in journal.get("events", [])[-40:]],
        "recovery": {"taskDir": str(directory.relative_to(root)).replace("\\", "/"),
                     "restore": "codex_work_checkpoint.py restore --run <cycle>"},
        "receiverMust": "re-read files and compare currentSha256 before editing",
    }
    if write:
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
    parser.add_argument("--no-status-doc", action="store_true",
                        help="close: skip the PROJECT_STATUS §4 row append")
    parser.add_argument("--out", help="handoff: optional repo-relative output path")
    parser.add_argument("--check-evidence", action="store_true",
                        help="close: require current verified checkpoint targets and strict scope check")
    parser.add_argument("--idempotent", action="store_true",
                        help="close: accept an already closed journal with the same result without writing")
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
            try:
                from task_context import locations
                context_root, _, _, context_task = locations(args.root, task_id)
                pointer = context_task / "context/current.json"
                if pointer.is_file():
                    relative = (pointer.relative_to(context_root).as_posix()
                                if pointer.is_relative_to(context_root) else "journal/" + task_id + "/context/current.json")
                    print("[task-context] 이전 문맥: " + relative + " (verify로 신선도 확인)", file=sys.stderr)
            except Exception:
                pass
        elif args.action == "note":
            ck.require(args.task and args.kind and args.text, "task-kind-text-required")
            result = add_note(args.root, args.task, args.kind, args.text, args.ref)
        elif args.action == "close":
            ck.require(args.task and args.result and args.summary, "task-result-summary-required")
            result = close_journal(args.root, args.task, args.result, args.summary,
                                   touch_status=not args.no_status_doc,
                                   check_evidence=args.check_evidence, idempotent=args.idempotent)
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
