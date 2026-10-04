#!/usr/bin/env python3
"""coop_verify.py — cooperative deferred-verification rails for shared-worktree agents.

Contract DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928. One shared checkout is
edited by several agents; a tree that another session is mid-editing is not a
stable verification input. This tool adds writer edit_batch markers plus durable
verification tickets on top of the existing journal/lease layer — it never
replaces them (file ownership stays with __patch_drop__/source_edit_session.ps1
leases and work_journal.py; coop writer records only *reference* a leaseId).

Core rule: DEFERRED / QUIESCING / WAITING_FOR_RUNNER / BLOCKED_UNKNOWN_OWNER are
never VERIFIED_PASS. Exit 0 on status/request means transport success only; the
JSON `state` field is the verdict. run-once exits 0 only when a real verify
command passed on a fixed input and wrote a receipt.

Store (derived records, no secrets): data/agent-handoff/coop-verify/
  state.json      writers + tickets + runner + lastSourceChangeAtUtc
  .coop.lock      atomic state-transition mutex (xb create, dead-pid reclaim)
  .verify.lock    single heavy-verifier lease (max_heavy_verifiers = 1)
  runner.json     watch heartbeat
  receipts/       per-ticket verification receipts

run-once exit codes (project-local contract — map at hook boundary, never raw):
  0 VERIFIED_PASS · 10 DEFERRED/QUIESCING · 11 INVALIDATED · 20 FAILED ·
  21 ENVIRONMENT_ERROR/TIMEOUT · 30 BLOCKED_UNKNOWN_OWNER · 44 NO_PENDING_TICKET
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_work_checkpoint as ck

SCHEMA = "awx.coop-verify.v1"
DEFAULT_STORE = "data/agent-handoff/coop-verify"

DEFAULTS = {
    "cheap_state_poll_seconds": 2,
    "heartbeat_seconds": 10,
    "stale_suspect_seconds": 90,
    "source_quiet_seconds": 15,
    "validation_priority_after_seconds": 120,
    "max_heavy_verifiers": 1,
    "build_timeout_seconds": 900,
}

WRITER_OPEN = {"EDITING", "CHECKPOINT"}
TICKET_OPEN = {"DEFERRED", "QUIESCING", "WAITING_FOR_RUNNER"}
TICKET_INFLIGHT = {"VERIFYING"}
TICKET_CLOSED = {"VERIFIED_PASS", "FAILED", "INVALIDATED", "ENVIRONMENT_ERROR",
                 "TIMEOUT", "SUPERSEDED", "BLOCKED_UNKNOWN_OWNER"}

EXIT_PASS = 0
EXIT_DEFERRED = 10
EXIT_INVALIDATED = 11
EXIT_FAILED = 20
EXIT_ENV = 21
EXIT_BLOCKED = 30
EXIT_NO_TICKET = 44

MAX_WRITERS = 64
MAX_TICKETS = 256
MAX_COVERED = 512
MAX_SCOPE_FILES = 512
MAX_HASH_BYTES = 8 * 1024 * 1024
RECEIPT_LOG_TAIL = 8192
SAFE_NAME = __import__("re").compile(r"[A-Za-z0-9_.:-]{1,120}")


class CoopError(ValueError):
    pass


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_ts(value: str):
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None


def age_seconds(ts: str, now: datetime):
    parsed = parse_ts(ts)
    if parsed is None or parsed.tzinfo is None:
        return None
    return (now - parsed).total_seconds()


def clean_name(value, field: str) -> str:
    text = str(value or "").strip()
    ck.require(bool(SAFE_NAME.fullmatch(text)), field + "-invalid")
    ck.secret_free(text.encode("utf-8"))
    return text


def norm_rel(root: Path, value: str) -> str:
    path = ck.relative_path(root, value)
    return str(path.relative_to(root)).replace("\\", "/")


def store_dir(root: Path, store: str) -> Path:
    candidate = Path(store)
    if not candidate.is_absolute():
        candidate = ck.relative_path(root, store)
    return candidate


def empty_state() -> dict:
    return {"schemaVersion": SCHEMA, "writers": {}, "tickets": {}, "runners": {},
            "lastSourceChangeAtUtc": None, "validationIntentAtUtc": None}


def load_state(directory: Path) -> dict:
    raw = ck.contents(directory / "state.json")
    if raw is None:
        return empty_state()
    try:
        state = json.loads(raw)
    except ValueError as exc:
        raise CoopError("state-corrupt") from exc
    if state.get("schemaVersion") != SCHEMA:
        raise CoopError("state-schema-mismatch")
    return state


def save_state(directory: Path, state: dict):
    directory.mkdir(parents=True, exist_ok=True)
    ck.write_json(directory / "state.json", state)


def _holder_pid(lock: Path):
    try:
        holder = json.loads(lock.read_bytes() or b"{}")
        pid = holder.get("pid")
        return pid if isinstance(pid, int) else None
    except (OSError, ValueError):
        return None


@contextmanager
def exclusive_lock(directory: Path, name: str, busy_error: str):
    """xb-create lock file holding this pid; reclaim only provably-dead holders."""
    directory.mkdir(parents=True, exist_ok=True)
    lock = directory / name
    handle = None
    for attempt in range(2):
        try:
            handle = lock.open("xb")
            handle.write(json.dumps(
                {"pid": os.getpid(), "at": utcnow()}).encode("utf-8"))
            handle.flush()
            break
        except FileExistsError:
            pid = _holder_pid(lock)
            if attempt == 0 and isinstance(pid, int) and not ck.pid_alive(pid):
                try:
                    lock.unlink()
                    continue
                except OSError:
                    pass
            raise CoopError(busy_error) from None
    try:
        yield handle
    finally:
        if handle is not None:
            handle.close()
        try:
            lock.unlink()
        except OSError:
            pass


def classify_writers(state: dict, now: datetime, cfg: dict):
    active, suspect, blocked = [], [], []
    for writer in state["writers"].values():
        if writer.get("state") == "RELEASED":
            continue
        if writer.get("state") == "BLOCKED_UNKNOWN_OWNER":
            blocked.append(writer)
            continue
        hb_age = age_seconds(writer.get("heartbeatAtUtc", ""), now)
        if hb_age is None or hb_age > cfg["stale_suspect_seconds"]:
            suspect.append(writer)
        else:
            active.append(writer)
    return active, suspect, blocked


def runner_alive(state: dict, now: datetime, cfg: dict) -> bool:
    for runner in state["runners"].values():
        age = age_seconds(runner.get("heartbeatAtUtc", ""), now)
        if age is not None and age <= cfg["stale_suspect_seconds"]:
            pid = runner.get("pid")
            # ck.pid_alive reports False for our own pid; this process is alive.
            if isinstance(pid, int) and (pid == os.getpid() or ck.pid_alive(pid)):
                return True
    return False


def quiet_satisfied(state: dict, now: datetime, cfg: dict) -> bool:
    last = state.get("lastSourceChangeAtUtc")
    if not last:
        return True
    age = age_seconds(last, now)
    return age is None or age >= cfg["source_quiet_seconds"]


def paths_overlap(a: str, b: str) -> bool:
    a, b = a.casefold().rstrip("/"), b.casefold().rstrip("/")
    return a == b or a.startswith(b + "/") or b.startswith(a + "/")


def scope_overlaps(writer_paths, scope) -> bool:
    return any(paths_overlap(w, s) for w in writer_paths or [] for s in scope or [])


def refresh_ticket_states(state: dict, now: datetime, cfg: dict):
    """Recompute every open ticket's derived state. Never emits PASS."""
    active, suspect, blocked = classify_writers(state, now, cfg)
    quiet_ok = quiet_satisfied(state, now, cfg)
    has_runner = runner_alive(state, now, cfg)
    for ticket in state["tickets"].values():
        if ticket.get("state") not in TICKET_OPEN:
            continue
        if active or suspect:
            ticket["state"] = "DEFERRED"
            ticket["deferReason"] = "writers-active"
            ticket["waitingOn"] = sorted(
                w["editBatchId"] for w in active + suspect)
        elif blocked:
            ticket["state"] = "DEFERRED"
            ticket["deferReason"] = "blocked-unknown-owner"
            ticket["waitingOn"] = sorted(w["editBatchId"] for w in blocked)
        elif not quiet_ok:
            ticket["state"] = "QUIESCING"
            ticket["deferReason"] = "source-quiet-window"
            ticket["waitingOn"] = []
        elif not has_runner:
            ticket["state"] = "WAITING_FOR_RUNNER"
            ticket["deferReason"] = "no-runner"
            ticket["waitingOn"] = []
        else:
            ticket["state"] = "DEFERRED"
            ticket["deferReason"] = "eligible-queued"
            ticket["waitingOn"] = []
        ticket["updatedAtUtc"] = now.isoformat()


def validation_intent(state: dict, now: datetime, cfg: dict) -> bool:
    oldest = None
    for ticket in state["tickets"].values():
        if ticket.get("state") in TICKET_OPEN:
            requested = parse_ts(ticket.get("requestedAtUtc", ""))
            if requested and (oldest is None or requested < oldest):
                oldest = requested
    if oldest is None:
        return False
    return (now - oldest).total_seconds() > cfg["validation_priority_after_seconds"]


def scope_manifest(root: Path, scope) -> dict:
    """Hash the actual verify inputs (bounded). Missing paths stay visible."""
    entries = []
    files = 0
    for item in scope or []:
        full = (root / item.replace("/", os.sep)).resolve()
        if full.is_dir():
            for child in sorted(full.rglob("*")):
                if files >= MAX_SCOPE_FILES:
                    entries.append({"path": item, "truncated": True})
                    break
                if not child.is_file():
                    continue
                files += 1
                entries.append(_file_entry(root, child))
        else:
            entries.append(_file_entry(root, full))
            files += 1
    digest = hashlib.sha256(
        json.dumps(entries, sort_keys=True, ensure_ascii=True).encode("utf-8")
    ).hexdigest()
    return {"entries": entries, "fileCount": files, "manifestSha256": digest}


def _file_entry(root: Path, path: Path) -> dict:
    try:
        rel = str(path.relative_to(root)).replace("\\", "/")
    except ValueError:
        rel = str(path)
    if not path.is_file():
        return {"path": rel, "missing": True}
    size = path.stat().st_size
    entry = {"path": rel, "bytes": size}
    entry["sha256"] = ck.digest(path.read_bytes()) if size <= MAX_HASH_BYTES else None
    return entry


def emit(payload) -> int:
    print(json.dumps(payload, ensure_ascii=True))
    return 0


def mask_token(value) -> str:
    """stdout은 스캐너에 잡히는 표면이라 토큰은 앞뒤 4자리만 남긴다.
    실제 값은 <store>/.coop-token-<batchId> 에만 기록한다."""
    text = str(value or "")
    if len(text) <= 8:
        return text
    return text[:4] + "..." + text[-4:]


def token_file(directory: Path, batch_id) -> Path:
    return directory / (".coop-token-" + clean_name(batch_id, "batch-id"))


def read_token_file(directory: Path, batch_id):
    try:
        doc = json.loads(token_file(directory, batch_id).read_bytes() or b"{}")
    except (OSError, ValueError):
        return None
    if isinstance(doc, dict) and doc.get("editBatchId") == batch_id:
        return doc.get("writerToken")
    return None


def cmd_status(root, directory, args, cfg) -> int:
    now = datetime.now(timezone.utc)
    state = load_state(directory)
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        refresh_ticket_states(state, now, cfg)
        save_state(directory, state)
    active, suspect, blocked = classify_writers(state, now, cfg)
    writers = {k: {**w, "heartbeatAgeSeconds": age_seconds(w.get("heartbeatAtUtc", ""), now)}
               for k, w in state["writers"].items()}
    tickets = state["tickets"]
    if args.ticket:
        tickets = {k: v for k, v in tickets.items() if k == args.ticket}
    payload = {
        "schemaVersion": SCHEMA, "action": "status", "root": str(root),
        "generatedAtUtc": now.isoformat(),
        "writers": {"active": [w["editBatchId"] for w in active],
                    "staleSuspect": [w["editBatchId"] for w in suspect],
                    "blockedUnknownOwner": [w["editBatchId"] for w in blocked],
                    "all": writers},
        "tickets": tickets,
        "runnerAlive": runner_alive(state, now, cfg),
        "runners": state["runners"],
        "quietSatisfied": quiet_satisfied(state, now, cfg),
        "lastSourceChangeAtUtc": state.get("lastSourceChangeAtUtc"),
        "validationIntent": validation_intent(state, now, cfg),
        "note": "exit 0 = read ok; verdict lives in state fields, not this code",
    }
    if args.agent:
        mine_open = [w for w in active + suspect if w.get("agent") == args.agent]
        mine_pending = [t for t in state["tickets"].values()
                        if t.get("state") in TICKET_OPEN and t.get("agent") == args.agent]
        verified = [t for t in state["tickets"].values()
                    if t.get("state") == "VERIFIED_PASS" and t.get("agent") == args.agent]
        payload["turnEnd"] = {
            "agent": args.agent,
            "openWriters": [w["editBatchId"] for w in mine_open],
            "pendingTickets": [t["ticketId"] for t in mine_pending],
            "verifiedTickets": [t["ticketId"] for t in verified],
            "allowedToEnd": not mine_open,
            "reportState": ("APPLIED_PENDING_VERIFICATION" if mine_pending
                            else ("VERIFIED_PASS" if verified else "NOT_VERIFIED")),
            "rule": "DEFERRED/WAITING_FOR_RUNNER tickets persist with a resumable "
                    "owner; Stop must not convert them to VERIFIED_PASS",
            "reportTemplate": {
                "applied": "APPLIED|NOT_APPLIED",
                "verificationStatus": "<ticket state>",
                "target": "source identity + profile + scope",
                "waitingOn": "session/task/batch or none",
                "ticket": "ticketId + first requestedAtUtc",
                "resumeOwner": "runner identity or WAITING_FOR_RUNNER",
                "ranStages": "commands + exit codes + receipt paths",
                "skippedStages": "stage + reason",
                "sourceBuiltRunningOnGlasses": "separate evidence each",
            },
        }
    return emit(payload)


def cmd_writer_begin(root, directory, args, cfg) -> int:
    agent = clean_name(args.agent, "agent")
    task_id = clean_name(args.task or "unknown", "task-id")
    session_id = clean_name(args.session or "", "session-id") if args.session else None
    batch_id = clean_name(args.batch_id or uuid.uuid4().hex[:12], "batch-id")
    paths = [norm_rel(root, p) for p in args.path or []]
    now = datetime.now(timezone.utc)
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        ck.require(batch_id not in state["writers"] and not
                   (directory / "released-writers" / (batch_id + ".json")).exists(),
                   "edit-batch-id-taken")
        # Closed metadata must not consume a live writer slot forever. Archive
        # before removal; an archive failure leaves the saved state untouched.
        released = sorted(
            (w for w in state["writers"].values() if w.get("state") == "RELEASED"),
            key=lambda w: (w.get("endedAtUtc") or "", w["editBatchId"]))
        needed = max(0, len(state["writers"]) - MAX_WRITERS + 1)
        ck.require(len(released) >= needed, "writer-cap")
        for old in released[:needed]:
            old_id = clean_name(old["editBatchId"], "batch-id")
            (directory / "released-writers").mkdir(exist_ok=True)
            ck.write_json(directory / "released-writers" / (old_id + ".json"), old)
            state["writers"].pop(old_id)
        ck.require(len(state["writers"]) < MAX_WRITERS, "writer-cap")
        writer = {
            "editBatchId": batch_id, "agent": agent, "sessionId": session_id,
            "taskId": task_id, "leaseId": args.lease_id or None,
            "state": "EDITING", "writerToken": uuid.uuid4().hex,
            "begunAtUtc": now.isoformat(), "heartbeatAtUtc": now.isoformat(),
            "lastSourceChangeAtUtc": None, "endedAtUtc": None,
            "ownerPid": args.pid if isinstance(args.pid, int) else os.getpid(),
            "changedPaths": paths,
        }
        state["writers"][batch_id] = writer
        inflight = [t["ticketId"] for t in state["tickets"].values()
                    if t.get("state") in TICKET_INFLIGHT
                    and scope_overlaps(paths, t.get("scope") or [])]
        intent = validation_intent(state, now, cfg)
        if intent:
            state["validationIntentAtUtc"] = now.isoformat()
        save_state(directory, state)
        ck.write_json(token_file(directory, batch_id), {
            "schemaVersion": SCHEMA, "editBatchId": batch_id,
            "writerToken": writer["writerToken"], "writtenAtUtc": now.isoformat()})
    return emit({"schemaVersion": SCHEMA, "action": "writer-begin",
                 "editBatchId": batch_id,
                 "writerToken": mask_token(writer["writerToken"]),
                 "writerTokenMasked": True,
                 "tokenFile": token_file(directory, batch_id).name,
                 "state": "EDITING", "changedPaths": paths,
                 "inflightVerifyOverlap": inflight,
                 "validationIntent": intent})


def _owned_writer(state, batch_id: str, batch_token: str, directory=None) -> dict:
    writer = state["writers"].get(batch_id)
    ck.require(writer is not None, "writer-unknown")
    if not batch_token and directory is not None:
        # 마스킹된 stdout을 본 세션은 .coop-token-<batchId> 파일에서 토큰을 읽는다.
        batch_token = read_token_file(directory, batch_id)
    ck.require(batch_token and writer.get("writerToken") == batch_token,
               "writer-token-mismatch")
    return writer


def cmd_writer_heartbeat(root, directory, args, cfg) -> int:
    now = datetime.now(timezone.utc)
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        writer = _owned_writer(state, args.batch_id, args.token, directory)
        ck.require(writer.get("state") in WRITER_OPEN, "writer-closed")
        writer["heartbeatAtUtc"] = now.isoformat()
        if args.source_changed:
            writer["lastSourceChangeAtUtc"] = now.isoformat()
            state["lastSourceChangeAtUtc"] = now.isoformat()
        save_state(directory, state)
    return emit({"schemaVersion": SCHEMA, "action": "writer-heartbeat",
                 "editBatchId": args.batch_id, "state": writer["state"],
                 "heartbeatAtUtc": writer["heartbeatAtUtc"]})


def cmd_writer_checkpoint(root, directory, args, cfg) -> int:
    now = datetime.now(timezone.utc)
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        writer = _owned_writer(state, args.batch_id, args.token, directory)
        ck.require(writer.get("state") in WRITER_OPEN, "writer-closed")
        writer["state"] = "CHECKPOINT"
        writer["heartbeatAtUtc"] = now.isoformat()
        writer["lastSourceChangeAtUtc"] = now.isoformat()
        state["lastSourceChangeAtUtc"] = now.isoformat()
        if args.path:
            writer["changedPaths"] = sorted(set(writer.get("changedPaths") or [])
                                            | {norm_rel(root, p) for p in args.path})
        save_state(directory, state)
    return emit({"schemaVersion": SCHEMA, "action": "writer-checkpoint",
                 "editBatchId": args.batch_id, "state": "CHECKPOINT"})


def cmd_writer_end(root, directory, args, cfg) -> int:
    now = datetime.now(timezone.utc)
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        writer = _owned_writer(state, args.batch_id, args.token, directory)
        ck.require(writer.get("state") != "RELEASED", "writer-already-released")
        writer["state"] = "RELEASED"
        writer["endedAtUtc"] = now.isoformat()
        writer["heartbeatAtUtc"] = now.isoformat()
        writer["lastSourceChangeAtUtc"] = now.isoformat()
        state["lastSourceChangeAtUtc"] = now.isoformat()
        refresh_ticket_states(state, now, cfg)
        save_state(directory, state)
        try:
            token_file(directory, args.batch_id).unlink()
        except OSError:
            pass
    return emit({"schemaVersion": SCHEMA, "action": "writer-end",
                 "editBatchId": args.batch_id, "state": "RELEASED",
                 "note": "release is not a success verdict"})


def _parse_command(args):
    if args.command_json:
        argv = json.loads(args.command_json)
        ck.require(isinstance(argv, list) and argv and
                   all(isinstance(x, str) and x for x in argv), "command-json-invalid")
        return argv
    if args.command:
        argv = [x for x in shlex.split(args.command, posix=False) if x]
        ck.require(bool(argv), "command-empty")
        return argv
    raise CoopError("command-required")


def _same_intent(a, b):
    """Only complete, typed verification contracts can cover one another."""
    fields = {"taskId": (str,), "profile": (str,), "targetMode": (str,),
              "targetIdentity": (str, type(None)), "scope": (list,),
              "verifyCommand": (list,), "requiredStages": (list,),
              "timeoutSeconds": (int, float)}
    if not isinstance(a, dict) or not isinstance(b, dict):
        return False
    for key, types in fields.items():
        if key not in a or key not in b or type(a[key]) not in types \
                or type(a[key]) is not type(b[key]):
            return False
        if isinstance(a[key], list) and any(type(v) is not str for v in a[key] + b[key]):
            return False
    if a["taskId"] in ("", "unknown") or b["taskId"] in ("", "unknown"):
        return False
    return (sorted(a["scope"]) == sorted(b["scope"])
            and all(a[k] == b[k] for k in fields if k != "scope"))


def cmd_request(root, directory, args, cfg) -> int:
    agent = clean_name(args.agent, "agent")
    task_id = clean_name(args.task or "unknown", "task-id")
    profile = clean_name(args.profile or "default", "profile")
    scope = sorted({norm_rel(root, s) for s in args.scope or []})
    argv_cmd = _parse_command(args)
    mode = args.target_mode
    identity = args.target_identity or None
    intent = {"taskId": task_id, "profile": profile, "scope": scope,
              "targetMode": mode, "targetIdentity": identity,
              "verifyCommand": argv_cmd,
              "requiredStages": args.stage or ["verifyCommand"],
              "timeoutSeconds": args.timeout_seconds or cfg["build_timeout_seconds"]}
    now = datetime.now(timezone.utc)
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        refresh_ticket_states(state, now, cfg)
        if mode == "latest":
            for ticket in state["tickets"].values():
                if (ticket.get("state") in TICKET_OPEN
                        and _same_intent(intent, ticket)):
                    covered = ticket.setdefault("coveredRequests", [])
                    ck.require(len(covered) < MAX_COVERED, "covered-cap")
                    covered.append({"requester": agent, "taskId": task_id,
                                    "requestedAtUtc": now.isoformat(),
                                    "verifyCommand": argv_cmd})
                    ticket["updatedAtUtc"] = now.isoformat()
                    save_state(directory, state)
                    return emit({"schemaVersion": SCHEMA, "action": "request",
                                 "merged": True, "ticketId": ticket["ticketId"],
                                 "state": ticket["state"],
                                 "requestedAtUtc": ticket["requestedAtUtc"],
                                 "note": "exit 0 = ticket stored, not verified"})
        ck.require(len(state["tickets"]) < MAX_TICKETS, "ticket-cap")
        ticket_id = "cv-" + uuid.uuid4().hex[:16]
        ticket = {
            "ticketId": ticket_id, "requester": agent, "agent": agent,
            **intent,
            "requestedAtUtc": now.isoformat(), "updatedAtUtc": now.isoformat(),
            "state": "DEFERRED", "deferReason": "new", "waitingOn": [],
            "coveredRequests": [], "receiptPath": None, "supersededBy": None,
        }
        state["tickets"][ticket_id] = ticket
        refresh_ticket_states(state, now, cfg)
        save_state(directory, state)
    return emit({"schemaVersion": SCHEMA, "action": "request", "merged": False,
                 "ticketId": ticket_id, "state": ticket["state"],
                 "deferReason": ticket.get("deferReason"),
                 "waitingOn": ticket.get("waitingOn"),
                 "note": "exit 0 = ticket stored, not verified; "
                         "read `state` for the verdict"})


def _supersede_stale_open(state, done_ticket, now):
    if done_ticket.get("state") != "VERIFIED_PASS":
        return
    for ticket in state["tickets"].values():
        if ticket["ticketId"] == done_ticket["ticketId"]:
            continue
        if ticket.get("state") in TICKET_OPEN and ticket.get("targetMode") == "latest" \
                and _same_intent(ticket, done_ticket):
            ticket["state"] = "SUPERSEDED"
            ticket["supersededBy"] = done_ticket["ticketId"]
            ticket["updatedAtUtc"] = now.isoformat()


def run_once(root, directory, args, cfg):
    """One verification pass. Returns (exit_code, payload)."""
    now = datetime.now(timezone.utc)
    lock_name = ".verify.lock"
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        # A manual run-once IS the executor for this pass: register a transient
        # runner marker so WAITING_FOR_RUNNER tickets are pickable here.
        if not runner_alive(state, now, cfg):
            state["runners"]["runonce-" + str(os.getpid())] = {
                "runnerId": "runonce-" + str(os.getpid()), "pid": os.getpid(),
                "heartbeatAtUtc": now.isoformat(), "manual": True}
        refresh_ticket_states(state, now, cfg)
        open_tickets = [t for t in state["tickets"].values()
                        if t.get("state") in TICKET_OPEN | TICKET_INFLIGHT]
        if not open_tickets:
            save_state(directory, state)
            return EXIT_NO_TICKET, {"schemaVersion": SCHEMA, "action": "run-once",
                                    "state": "NO_PENDING_TICKET"}
        ticket = None
        if args.ticket:
            ticket = next((t for t in open_tickets
                           if t["ticketId"] == args.ticket), None)
            if ticket is None:
                closed = state["tickets"].get(args.ticket)
                ck.require(closed is not None, "ticket-unknown")
                closed_exit = {"VERIFIED_PASS": EXIT_PASS, "FAILED": EXIT_FAILED,
                               "INVALIDATED": EXIT_INVALIDATED,
                               "ENVIRONMENT_ERROR": EXIT_ENV, "TIMEOUT": EXIT_ENV,
                               "BLOCKED_UNKNOWN_OWNER": EXIT_BLOCKED
                               }.get(closed.get("state"), EXIT_DEFERRED)
                save_state(directory, state)
                return closed_exit, {
                    "schemaVersion": SCHEMA, "action": "run-once",
                    "ticketId": closed["ticketId"], "state": closed.get("state"),
                    "alreadyClosed": True,
                    "receiptPath": closed.get("receiptPath"),
                    "note": "recorded verdict replayed; not a new verification"}
        else:
            eligible = [t for t in open_tickets
                        if t.get("deferReason") == "eligible-queued"]
            pool = eligible or sorted(
                (t for t in open_tickets if t["state"] in TICKET_OPEN),
                key=lambda t: t.get("requestedAtUtc", ""))
            ticket = pool[0] if pool else None
        if ticket is None or ticket.get("state") != "DEFERRED" \
                or ticket.get("deferReason") != "eligible-queued":
            save_state(directory, state)
            head = ticket or sorted(
                state["tickets"].values(),
                key=lambda t: t.get("requestedAtUtc", ""))[0]
            return EXIT_DEFERRED, {
                "schemaVersion": SCHEMA, "action": "run-once",
                "state": head.get("state"), "deferReason": head.get("deferReason"),
                "waitingOn": head.get("waitingOn"), "ticketId": head.get("ticketId")}
        # Decision + verify-lease acquisition in one atomic transition.
        handle = None
        try:
            handle = (directory / lock_name).open("xb")
        except FileExistsError:
            pid = _holder_pid(directory / lock_name)
            if isinstance(pid, int) and pid != os.getpid() and not ck.pid_alive(pid):
                try:
                    (directory / lock_name).unlink()
                    handle = (directory / lock_name).open("xb")
                except OSError:
                    handle = None
            if handle is None:
                ticket["state"] = "DEFERRED"
                ticket["deferReason"] = "verifier-busy"
                ticket["updatedAtUtc"] = now.isoformat()
                save_state(directory, state)
                return EXIT_DEFERRED, {
                    "schemaVersion": SCHEMA, "action": "run-once",
                    "state": "DEFERRED", "deferReason": "verifier-busy",
                    "ticketId": ticket["ticketId"]}
        handle.write(json.dumps({"pid": os.getpid(),
                                 "ticketId": ticket["ticketId"],
                                 "at": now.isoformat()}).encode("utf-8"))
        handle.flush()
        ticket["state"] = "VERIFYING"
        ticket["verifyStartedAtUtc"] = now.isoformat()
        ticket["verifierPid"] = os.getpid()
        save_state(directory, state)
    try:
        return _execute_verify(root, directory, ticket, cfg)
    finally:
        try:
            handle.close()
        except OSError:
            pass
        try:
            (directory / lock_name).unlink()
        except OSError:
            pass


def _execute_verify(root, directory, ticket, cfg):
    before = scope_manifest(root, ticket.get("scope") or [])
    started = datetime.now(timezone.utc)
    argv = ticket.get("verifyCommand") or []
    exit_code, out_tail, err_tail, env_error = None, "", "", None
    try:
        proc = subprocess.run(argv, cwd=str(root), capture_output=True,
                              timeout=ticket.get("timeoutSeconds")
                              or cfg["build_timeout_seconds"])
        exit_code = proc.returncode
        out_tail = proc.stdout.decode("utf-8", "replace")[-RECEIPT_LOG_TAIL:]
        err_tail = proc.stderr.decode("utf-8", "replace")[-RECEIPT_LOG_TAIL:]
    except subprocess.TimeoutExpired:
        exit_code, env_error = None, "TIMEOUT"
    except OSError as exc:
        exit_code, env_error = None, "ENVIRONMENT_ERROR:" + exc.__class__.__name__
    ended = datetime.now(timezone.utc)
    after = scope_manifest(root, ticket.get("scope") or [])
    now = ended
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        # Any writer whose batch began or touched scoped paths inside the verify
        # window invalidates the result — even if hashes match (A→B→A drift).
        start = ticket["verifyStartedAtUtc"]
        interfering = [
            w["editBatchId"] for w in state["writers"].values()
            if (w.get("begunAtUtc", "") > start
                or (w.get("lastSourceChangeAtUtc") or "") > start)
            and scope_overlaps(w.get("changedPaths") or [],
                               ticket.get("scope") or [])]
        drifted = before["manifestSha256"] != after["manifestSha256"]
        if interfering or drifted:
            verdict, code = "INVALIDATED", EXIT_INVALIDATED
        elif env_error and env_error.startswith("TIMEOUT"):
            verdict, code = "TIMEOUT", EXIT_ENV
        elif env_error:
            verdict, code = "ENVIRONMENT_ERROR", EXIT_ENV
        elif exit_code == 0:
            verdict, code = "VERIFIED_PASS", EXIT_PASS
        else:
            verdict, code = "FAILED", EXIT_FAILED
        receipt_rel = None
        receipt = {
            "schemaVersion": SCHEMA + ".receipt", "ticketId": ticket["ticketId"],
            "profile": ticket.get("profile"),
            "requiredStages": ticket.get("requiredStages"),
            "verifyCommand": argv, "cwd": str(root),
            "exitCode": exit_code, "verdict": verdict,
            "startedAtUtc": started.isoformat(), "endedAtUtc": ended.isoformat(),
            "durationMs": int((ended - started).total_seconds() * 1000),
            "inputManifestBefore": before, "inputManifestAfter": after,
            "interferingWriters": interfering,
            "stdoutTail": out_tail, "stderrTail": err_tail,
        }
        receipt_rel = "receipts/" + ticket["ticketId"] + ".json"
        (directory / "receipts").mkdir(parents=True, exist_ok=True)
        ck.write_json(directory / receipt_rel.replace("/", os.sep), receipt)
        current = state["tickets"].get(ticket["ticketId"])
        if current is not None:
            current["state"] = verdict
            current["exitCode"] = exit_code
            current["receiptPath"] = receipt_rel
            current["updatedAtUtc"] = now.isoformat()
            current.pop("verifyStartedAtUtc", None)
            current.pop("verifierPid", None)
            _supersede_stale_open(state, current, now)
        refresh_ticket_states(state, now, cfg)
        save_state(directory, state)
    return code, {"schemaVersion": SCHEMA, "action": "run-once",
                  "ticketId": ticket["ticketId"], "state": verdict,
                  "exitCode": exit_code, "receiptPath": receipt_rel,
                  "interferingWriters": interfering, "inputDrift": drifted}


def cmd_run_once(root, directory, args, cfg) -> int:
    code, payload = run_once(root, directory, args, cfg)
    print(json.dumps(payload, ensure_ascii=True))
    return code


def cmd_watch(root, directory, args, cfg) -> int:
    runner_id = clean_name(args.runner_id or ("runner-" + str(os.getpid())), "runner-id")
    iterations = args.iterations
    poll = args.poll or cfg["cheap_state_poll_seconds"]
    ran = 0
    last = {"state": "IDLE"}
    try:
        while iterations is None or ran < iterations:
            now = datetime.now(timezone.utc)
            with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
                state = load_state(directory)
                state["runners"][runner_id] = {
                    "runnerId": runner_id, "pid": os.getpid(),
                    "heartbeatAtUtc": now.isoformat()}
                save_state(directory, state)
            code, last = run_once(root, directory, args, cfg)
            ran += 1
            if iterations is None:
                time.sleep(poll)
    except KeyboardInterrupt:
        pass
    finally:
        with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
            state = load_state(directory)
            state["runners"].pop(runner_id, None)
            save_state(directory, state)
    return emit({"schemaVersion": SCHEMA, "action": "watch",
                 "runnerId": runner_id, "iterations": ran,
                 "lastResult": last})


def cmd_recover(root, directory, args, cfg) -> int:
    now = datetime.now(timezone.utc)
    report = {"blockedUnknown": [], "releasedDead": [], "keptAlive": [],
              "verifyLockReclaimed": False}
    with exclusive_lock(directory, ".coop.lock", "coordination-locked"):
        state = load_state(directory)
        for writer in state["writers"].values():
            if writer.get("state") not in WRITER_OPEN | {"BLOCKED_UNKNOWN_OWNER"}:
                continue
            hb_age = age_seconds(writer.get("heartbeatAtUtc", ""), now)
            stale = hb_age is None or hb_age > cfg["stale_suspect_seconds"]
            if not stale:
                report["keptAlive"].append(writer["editBatchId"])
                continue
            pid = writer.get("ownerPid")
            dead = isinstance(pid, int) and not ck.pid_alive(pid)
            if not dead:
                report["keptAlive"].append(writer["editBatchId"])
                continue
            if args.release:
                writer["state"] = "RELEASED"
                writer["endedAtUtc"] = now.isoformat()
                writer["recoveryReason"] = "dead-owner-evidence"
                report["releasedDead"].append(writer["editBatchId"])
            elif writer.get("state") != "BLOCKED_UNKNOWN_OWNER":
                writer["state"] = "BLOCKED_UNKNOWN_OWNER"
                writer["recoveryReason"] = "stale-heartbeat-dead-owner"
                report["blockedUnknown"].append(writer["editBatchId"])
        # Initial absence is required; reclaiming a dead lock is not absence proof.
        if not os.path.lexists(directory / ".verify.lock"):
            for ticket in state["tickets"].values():
                pid = ticket.get("verifierPid")
                if ticket.get("state") == "VERIFYING" and type(pid) is int \
                        and pid > 0 and ck.pid_alive(pid) is False:
                    ticket["state"] = "INVALIDATED"
        lock = directory / ".verify.lock"
        if lock.is_file():
            pid = _holder_pid(lock)
            if not (isinstance(pid, int) and ck.pid_alive(pid)):
                try:
                    lock.unlink()
                    report["verifyLockReclaimed"] = True
                except OSError:
                    pass
        refresh_ticket_states(state, now, cfg)
        save_state(directory, state)
    report.update({"schemaVersion": SCHEMA, "action": "recover",
                   "releaseMode": bool(args.release)})
    return emit(report)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--root", default=".")
    p.add_argument("--store", default=DEFAULT_STORE,
                   help="ticket/receipt store (default data/agent-handoff/coop-verify)")
    p.add_argument("--config", help="JSON file overriding timing knobs")
    p.add_argument("--set", dest="overrides", action="append", default=[],
                   metavar="name=seconds", help="timing knob override (tests)")
    sub = p.add_subparsers(dest="action", required=True)

    s = sub.add_parser("status", help="world view + per-agent turnEnd block")
    s.add_argument("--agent")
    s.add_argument("--ticket")
    s.set_defaults(func=cmd_status)

    s = sub.add_parser("writer-begin", help="register an edit_batch before first write")
    s.add_argument("--agent", required=True)
    s.add_argument("--task")
    s.add_argument("--session")
    s.add_argument("--batch-id")
    s.add_argument("--lease-id")
    s.add_argument("--pid", type=int)
    s.add_argument("--path", action="append", default=[])
    s.set_defaults(func=cmd_writer_begin)

    s = sub.add_parser("writer-heartbeat", help="liveness only; never resets quiet")
    s.add_argument("--batch-id", required=True)
    s.add_argument("--token", help="생략 시 <store>/.coop-token-<batchId>를 읽는다")
    s.add_argument("--source-changed", action="store_true")
    s.set_defaults(func=cmd_writer_heartbeat)

    s = sub.add_parser("writer-checkpoint", help="close a logical batch; yields verify slot")
    s.add_argument("--batch-id", required=True)
    s.add_argument("--token", help="생략 시 <store>/.coop-token-<batchId>를 읽는다")
    s.add_argument("--path", action="append", default=[])
    s.set_defaults(func=cmd_writer_checkpoint)

    s = sub.add_parser("writer-end", help="release the batch (not a success verdict)")
    s.add_argument("--batch-id", required=True)
    s.add_argument("--token", help="생략 시 <store>/.coop-token-<batchId>를 읽는다")
    s.set_defaults(func=cmd_writer_end)

    s = sub.add_parser("request", help="store/merge a durable verify ticket")
    s.add_argument("--agent", required=True)
    s.add_argument("--task")
    s.add_argument("--profile")
    s.add_argument("--scope", action="append", default=[])
    s.add_argument("--command")
    s.add_argument("--command-json")
    s.add_argument("--stage", action="append", default=[])
    s.add_argument("--target-mode", choices=("latest", "exact"), default="latest")
    s.add_argument("--target-identity")
    s.add_argument("--timeout-seconds", type=int)
    s.set_defaults(func=cmd_request)

    s = sub.add_parser("run-once", help="verify one eligible ticket; 0==VERIFIED_PASS")
    s.add_argument("--ticket")
    s.set_defaults(func=cmd_run_once)

    s = sub.add_parser("watch", help="single-runner ticket consumer loop")
    s.add_argument("--runner-id")
    s.add_argument("--iterations", type=int)
    s.add_argument("--poll", type=float)
    s.add_argument("--ticket")
    s.set_defaults(func=cmd_watch)

    s = sub.add_parser("recover", help="bounded reclaim of dead writers/verify locks")
    s.add_argument("--release", action="store_true",
                   help="release writers only when ownerPid is provably dead")
    s.set_defaults(func=cmd_recover)
    return p


def load_config(args) -> dict:
    cfg = dict(DEFAULTS)
    if args.config:
        data = json.loads(Path(args.config).read_text(encoding="utf-8"))
        ck.require(isinstance(data, dict), "config-invalid")
        cfg.update({k: v for k, v in data.items() if k in DEFAULTS})
    for item in args.overrides or []:
        key, _, value = item.partition("=")
        ck.require(key in DEFAULTS and value, "config-key-unknown:" + key)
        cfg[key] = float(value) if "." in value else int(value)
    return cfg


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    root = Path(args.root).resolve()
    ck.require(root.is_dir() and not str(root).startswith("\\\\"), "local-root-required")
    cfg = load_config(args)
    directory = store_dir(root, args.store)
    try:
        return args.func(root, directory, args, cfg)
    except CoopError as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": str(exc)}, ensure_ascii=True))
        return EXIT_BLOCKED if "locked" in str(exc) else 2
    except (OSError, ValueError, KeyError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": str(exc)}, ensure_ascii=True))
        return 2


if __name__ == "__main__":
    sys.exit(main())
