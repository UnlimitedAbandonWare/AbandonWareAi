"""ChangeIntent control plane layered over the existing source-edit lease.

Cross-agent (Grok/Codex/Devin/Cline) board for parallel write sessions on this
checkout. It adds intent declaration, admission fencing, an event log, and a
once-per-conflict-fingerprint release request on top of machinery that already
exists - it never reimplements it:

  - __patch_drop__/source_edit_session.ps1 stays the lock authority: `admit`
    runs the status precheck, then calls `-Action begin` itself; this tool never
    writes lease/lock files directly. `renew`/`seal`/`end` delegate to
    `heartbeat`/`verify`/`end` with the stored owner + lease fingerprint.
  - scripts/work_journal.py journals stay per-task visibility, not a lock.
  - scripts/agent_port_lease.py port leases are read for the board only.

Storage (all under data/agent-handoff/change-plane/):
  intents.json    declared intents + monotonic counters (nextEventSeq, nextFence)
  events.jsonl    append-only {seq, atUtc, type, intentId, agent, taskId, detail}
  LATEST.md       regenerated board snapshot (derivable; delete and run status)
  manifests/      per-intent TargetManifest files passed to the PS1
  release-requests/  fallback doc home when the owner task dir is unresolvable

Flow: propose (declare targets pinned by sha256) -> admit (atomic check ->
PS1 begin -> monotonic fence f-N) -> renew -> seal -> end. seal/end/renew
require the same fence admit issued (meta validation; not a PS1 replacement).
Blocked intents keep state=blocked; `plan` re-evaluates them live and emits at
most one release_request event per conflict fingerprint - requests never
delete or force-release foreign locks.

Output is always one JSON line. Exit codes: 0 ok, 2 usage, 3 owner/fence/
identity error, 6 evidence or session error, 7 conflict-blocked.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_scope_lease as scope  # canon/overlap/run_ps/lease_summary/journal reads

SCHEMA = "awx.agent-change-plane.v1"
INTENT_SCHEMA = "awx.change-intent.v1"
REQUEST_SCHEMA = "awx.change-plane.release-request.v1"
PREFLIGHT_SCHEMA = "awx.change-plane.preflight.v1"
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPT_DIR.parent
STORE_REL = "data/agent-handoff/change-plane"
JOURNAL_BASE = "data/agent-handoff/codex-autonomy"
LOCKS_REL = "__patch_drop__/source-edit-locks"
HEARTBEAT_REL = "__patch_drop__/source-edit-heartbeats"
PORT_LEASES_REL = "var/agent-port-lease/leases"
RELEASE_DOC = "LEASE_RELEASE_REQUEST.md"
SURFACES = ("runtime", "build")
LIVE_STATES = ("admitted", "sealed")
OPEN_STATES = ("proposed", "admitted", "sealed", "blocked")
MAX_TARGETS = 64
MAX_INTENTS = 512
EVENT_TAIL_BYTES = 4_000_000
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
SHA256_RE = re.compile(r"^[a-fA-F0-9]{64}$")
PORT_ACTIVE_STATES = frozenset({"reserved", "starting", "running", "unhealthy"})

USER_PROMPT_TEMPLATE = (
    "작업 {owner}가 {files}를 예약 중입니다. 끝났으면 그 작업에서 "
    "source-edit lease를 정상 종료해 주세요. 강제 해제는 하지 않습니다. "
    "현재 작업은 겹치지 않는 파일만 계속합니다."
)


class PlaneError(ValueError):
    pass


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat()


def parse_time(value):
    try:
        moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment


def sha_text(text: str) -> str:
    return hashlib.sha256(str(text).encode("utf-8")).hexdigest()


def sha_file(path: Path):
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def new_id(prefix: str) -> str:
    return prefix + hashlib.sha256(os.urandom(16)).hexdigest()[:12]


def safe_name(value, label) -> str:
    if not isinstance(value, str) or not SAFE_NAME.fullmatch(value):
        raise PlaneError(f"invalid-{label}")
    return value


def atomic_write(path: Path, text: str) -> bool:
    data = (text + "\n").encode("utf-8")
    try:
        if path.is_file() and path.read_bytes() == data:
            return False
    except OSError:
        pass
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with tmp.open("xb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()
    return True


def rel(root: Path, path: Path) -> str:
    return str(path.relative_to(root)).replace("\\", "/")


class Store:
    """intents.json + events.jsonl under a byte-range coordination lock."""

    def __init__(self, root):
        self.root = Path(root).resolve()
        self.dir = self.root / STORE_REL
        self.intents_path = self.dir / "intents.json"
        self.events_path = self.dir / "events.jsonl"
        self.latest_path = self.dir / "LATEST.md"
        self.lock_path = self.dir / ".lock"
        self.manifests_dir = self.dir / "manifests"
        self.requests_dir = self.dir / "release-requests"

    @contextmanager
    def lock(self):
        self.dir.mkdir(parents=True, exist_ok=True)
        handle = open(self.lock_path, "a+b")
        locked = False
        try:
            handle.seek(0, os.SEEK_END)
            if handle.tell() < 1:
                handle.write(b"\0")
                handle.flush()
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                deadline = time.monotonic() + 8
                while True:
                    try:
                        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                        locked = True
                        break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise PlaneError("change-plane-busy")
                        time.sleep(0.02)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
                locked = True
            yield
        finally:
            if locked:
                try:
                    if os.name == "nt":
                        import msvcrt
                        handle.seek(0)
                        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
                except OSError:
                    pass
            handle.close()

    def load(self):
        if not self.intents_path.is_file():
            return {"schemaVersion": SCHEMA, "nextEventSeq": 1, "nextFence": 1,
                    "updatedAtUtc": None, "intents": []}
        try:
            state = json.loads(self.intents_path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            raise PlaneError("change-plane-state-unreadable")
        if not isinstance(state, dict) or state.get("schemaVersion") != SCHEMA \
                or not isinstance(state.get("intents"), list):
            raise PlaneError("change-plane-state-invalid")
        state.setdefault("nextEventSeq", 1)
        state.setdefault("nextFence", 1)
        return state

    def save(self, state):
        state["updatedAtUtc"] = iso(utcnow())
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.intents_path.with_name(
            "intents.json." + uuid.uuid4().hex + ".tmp")
        try:
            tmp.write_text(json.dumps(state, ensure_ascii=True, indent=1) + "\n",
                           encoding="utf-8")
            os.replace(tmp, self.intents_path)
        finally:
            if tmp.exists():
                tmp.unlink()

    def append_event(self, state, event_type, intent=None, detail=None):
        event = {"seq": int(state.get("nextEventSeq", 1)),
                 "atUtc": iso(utcnow()), "type": str(event_type),
                 "intentId": intent.get("intentId") if intent else None,
                 "agent": intent.get("agent") if intent else None,
                 "taskId": intent.get("taskId") if intent else None,
                 "detail": detail or {}}
        state["nextEventSeq"] = event["seq"] + 1
        self.dir.mkdir(parents=True, exist_ok=True)
        with open(self.events_path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, ensure_ascii=True) + "\n")
        return event

    def read_events(self):
        if not self.events_path.is_file():
            return []
        data = self.events_path.read_bytes()
        if len(data) > EVENT_TAIL_BYTES:
            data = data[-EVENT_TAIL_BYTES:]
            data = data.split(b"\n", 1)[-1]
        rows = []
        for line in data.decode("utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except ValueError:
                continue
        return rows

    def requested_fingerprints(self):
        seen = set()
        for event in self.read_events():
            if event.get("type") == "release_request":
                fp = (event.get("detail") or {}).get("fingerprint")
                if fp:
                    seen.add(fp)
        return seen


def find_intent(state, intent_id):
    for intent in state["intents"]:
        if intent.get("intentId") == intent_id:
            return intent
    raise PlaneError("intent-unknown")


def intent_paths(intent):
    return [t["path"] for t in intent.get("targets", [])]


def blocking_leases(paths, lease_rows):
    """Lease rows whose declared targetPaths overlap any of `paths`.

    The PS1 summary is authoritative: every listed lease (active, expired, or
    corrupt) still protects its declared scope until recover proves a dead
    owner - expiry never unlocks anything here."""
    hits, rows = set(), []
    for row in lease_rows or []:
        owned = [str(p).replace("\\", "/").lower()
                 for p in (row.get("targetPaths") or []) if isinstance(p, str)]
        if not owned:
            # unresolved scope cannot be excluded -> counts as blocking all
            hits.update(paths)
            rows.append(row)
            continue
        overlap_paths = sorted({t for t in paths for o in owned
                                if scope.overlap(t, o)})
        if overlap_paths:
            hits.update(overlap_paths)
            rows.append({**row, "overlappingTargets": overlap_paths})
    return sorted(hits), rows


def conflict_fingerprint(reason, paths, lease_rows, surfaces=None):
    payload = json.dumps(
        {"reason": reason,
         "paths": sorted(paths),
         "leases": sorted(str(r.get("leaseId") or r.get("topic") or "")
                          for r in lease_rows or []),
         "owners": sorted(str(r.get("taskIdHash") or r.get("ownerHash") or "")
                          for r in lease_rows or []),
         "surfaces": sorted(surfaces or [])},
        sort_keys=True)
    return "cf-" + sha_text(payload)[:16]


def owner_task_map(root):
    claims = list(scope.iter_claims(root))
    task_ids = set()
    for doc in scope.iter_task_docs(root, "journal.json"):
        if isinstance(doc, dict) and isinstance(doc.get("taskId"), str):
            task_ids.add(doc["taskId"])
    for claim in claims:
        if isinstance(claim.get("taskId"), str):
            task_ids.add(claim["taskId"])
    return claims, {sha_text(t): t for t in task_ids}


def resolve_owner_task(lease_row, claims, hash_map):
    """Best-effort lease -> owner taskId. Basis is always recorded."""
    topic = str(lease_row.get("topic") or "")
    for claim in claims:
        if claim.get("released"):
            continue
        lease_name = str(claim.get("leaseName") or "")
        if lease_name and (lease_name == topic + ".lock"
                           or lease_name.startswith(topic + ".")):
            return claim.get("taskId"), "claim-leaseName"
    for claim in claims:
        if not claim.get("released") and claim.get("topic") == topic:
            return claim.get("taskId"), "claim-topic"
    task_hash = str(lease_row.get("taskIdHash") or "")
    if task_hash and task_hash in hash_map:
        return hash_map[task_hash], "task-id-hash"
    return None, "unresolved"


def surface_holders(state):
    """First live-state intent holding each surface. The board reports the
    holder even when its lease lapsed (leaseStatus says so) - liveness is
    evidence, not an unlock."""
    owners = {"runtime": None, "build": None}
    for intent in state["intents"]:
        if intent.get("state") not in LIVE_STATES:
            continue
        for surface in intent.get("surfaces") or []:
            if surface in owners and owners[surface] is None:
                owners[surface] = {
                    "intentId": intent.get("intentId"),
                    "agent": intent.get("agent"),
                    "taskId": intent.get("taskId"),
                    "fence": intent.get("fence"),
                    "admittedAtUtc": intent.get("admittedAtUtc"),
                }
    return owners


def surface_conflict(state, intent):
    wanted = set(intent.get("surfaces") or [])
    if not wanted:
        return None, set()
    for other in state["intents"]:
        if other is intent or other.get("state") not in LIVE_STATES:
            continue
        shared = wanted & set(other.get("surfaces") or [])
        if shared:
            return other, shared
    return None, set()


def annotate_owners(root, lease_rows):
    claims, hash_map = owner_task_map(root)
    for row in lease_rows or []:
        owner, basis = resolve_owner_task(row, claims, hash_map)
        row["ownerTaskId"] = owner
        row["ownerTaskIdBasis"] = basis
    return lease_rows


def mark_blocked(store, state, intent, reason, fingerprint, paths, leases,
                 surfaces=None, emit_request=True):
    intent["state"] = "blocked"
    intent["conflict"] = {
        "reason": reason, "fingerprint": fingerprint,
        "conflictingPaths": sorted(paths),
        "blockingLeases": [{"topic": r.get("topic"), "leaseId": r.get("leaseId"),
                            "status": r.get("status"),
                            "ownerTaskId": r.get("ownerTaskId")}
                           for r in (leases or [])],
        "surfaces": sorted(surfaces or []),
        "atUtc": iso(utcnow()),
    }
    previous = (intent.get("conflictHistory") or [])
    intent["conflictHistory"] = (previous + [intent["conflict"]["fingerprint"]])[-8:]
    detail = {"reason": reason, "fingerprint": fingerprint,
              "conflictingPaths": sorted(paths),
              "surfaces": sorted(surfaces or [])}
    if intent.get("_lastBlockedFp") != fingerprint:
        store.append_event(state, "intent.blocked", intent, detail)
    intent["_lastBlockedFp"] = fingerprint
    requested = None
    if emit_request:
        requested = emit_release_request(store, state, root_of(store), intent,
                                         paths, leases, fingerprint, reason)
    return requested


def root_of(store):
    return store.root


def release_doc_text(owner_task, paths, requester, fingerprint, leases, reason):
    lease_lines = []
    for row in leases or []:
        lease_lines.append(
            f"- topic `{row.get('topic')}` leaseId `{row.get('leaseId')}` "
            f"status `{row.get('status')}` expiresAtUtc `{row.get('expiresAtUtc')}`\n"
            f"  정상 종료: `python -B scripts/agent_scope_lease.py done --task "
            f"{owner_task}` 또는 `source_edit_session.ps1 -Action end -Topic "
            f"{row.get('topic')} -OwnerId <ownerId> -LeaseFingerprint <sha256>`")
    return (
        "# LEASE_RELEASE_REQUEST\n\n"
        f"- schemaVersion: `{REQUEST_SCHEMA}`\n"
        f"- requestedAtUtc: `{iso(utcnow())}`\n"
        f"- fingerprint: `{fingerprint}`\n"
        f"- requestedBy: `{requester or 'unknown'}`\n"
        f"- reason: `{reason}`\n"
        "- forceRelease: `false` (강제 해제/lock 삭제 금지)\n\n"
        + USER_PROMPT_TEMPLATE.format(owner=owner_task or "unresolved-owner",
                                    files=", ".join(sorted(paths)))
        + "\n\n## 예약 중인 lease\n\n"
        + ("\n".join(lease_lines) if lease_lines else "- (scope 미해석 lease)") + "\n")


def write_release_doc(store, owner_task, paths, requester, fingerprint,
                      leases, reason):
    owner_dir = store.root / JOURNAL_BASE / owner_task if owner_task else None
    if owner_dir is not None and owner_dir.is_dir():
        doc_path = owner_dir / RELEASE_DOC
        fallback = False
    else:
        safe_owner = re.sub(r"[^A-Za-z0-9_.-]", "-",
                            str(owner_task or "unresolved")).strip("-")
        doc_path = store.requests_dir / (fingerprint + "-" + safe_owner + ".md")
        fallback = True
    written = atomic_write(doc_path, release_doc_text(
        owner_task, paths, requester, fingerprint, leases, reason))
    return {"path": rel(store.root, doc_path), "written": written,
            "fallback": fallback, "ownerTaskId": owner_task}


def emit_release_request(store, state, root, intent, paths, lease_rows,
                         fingerprint, reason):
    """One release_request event per conflict fingerprint, ever."""
    if fingerprint in store.requested_fingerprints():
        return {"emitted": False, "suppressed": "already-requested",
                "fingerprint": fingerprint}
    if lease_rows and "ownerTaskIdBasis" not in (lease_rows[0] or {}):
        annotate_owners(root, lease_rows)
    by_owner = {}
    for row in lease_rows or []:
        by_owner.setdefault(row.get("ownerTaskId") or "unresolved",
                            []).append(row)
    if not by_owner:
        by_owner["unresolved"] = []
    docs = []
    for owner, rows in sorted(by_owner.items(), key=lambda kv: kv[0]):
        owner_paths = sorted({p for r in rows
                          for p in (r.get("overlappingTargets") or paths)})
        docs.append(write_release_doc(
            store, None if owner == "unresolved" else owner, owner_paths,
            intent.get("taskId") if intent else None, fingerprint, rows, reason))
    event = store.append_event(state, "release_request", intent, {
        "fingerprint": fingerprint, "reason": reason,
        "owners": sorted(by_owner), "paths": sorted(paths),
        "docs": [d["path"] for d in docs]})
    return {"emitted": True, "fingerprint": fingerprint, "docs": docs,
            "eventSeq": event["seq"]}


def lease_rows_by_topic(summary):
    rows = {}
    for row in (summary or {}).get("sourceLeases") or []:
        topic = str(row.get("topic") or "")
        if topic:
            rows[topic] = row
    return rows


def effective_state(intent, lease_by_topic):
    state = intent.get("state")
    if state not in LIVE_STATES:
        return state
    lease = intent.get("lease") or {}
    topic = lease.get("topic") or intent.get("intentId")
    row = lease_by_topic.get(topic)
    if row is None:
        return "lease_absent"
    status = row.get("status")
    if status == "active":
        return state
    if status in ("expired", "corrupt"):
        return status + "_lease"
    return "lease_" + str(status or "unknown")


def local_lease_state(root, lease_name):
    """Read-only liveness for preflight: no PS1 spawn, no mutation."""
    lock_dir = root / LOCKS_REL / str(lease_name or "")
    lease_path = lock_dir / "lease.json"
    if not lease_path.is_file():
        return "absent", None
    try:
        raw = lease_path.read_bytes()
        lease = json.loads(raw)
        expires = parse_time(lease.get("expiresAtUtc") or lease.get("expiresAt"))
    except (OSError, ValueError, AttributeError):
        return "corrupt", None
    if expires is None:
        return "corrupt", None
    lease_id = str(lease.get("leaseId") or "")
    if re.fullmatch(r"[a-f0-9]{32}", lease_id):
        hb_path = root / HEARTBEAT_REL / (lease_id + ".json")
        try:
            hb = json.loads(hb_path.read_bytes())
            renewed = parse_time(hb.get("renewedAtUtc"))
            until = parse_time(hb.get("expiresAtUtc"))
            now = utcnow()
            if (hb.get("leaseId") == lease_id and renewed and until
                    and str(hb.get("leaseFingerprint", "")).lower()
                    == hashlib.sha256(raw).hexdigest()
                    and renewed <= now + timedelta(seconds=30)
                    and renewed < until <= renewed + timedelta(minutes=540)
                    and until > expires):
                expires = until
        except (OSError, ValueError, AttributeError):
            pass
    if expires <= utcnow():
        return "expired", iso(expires)
    return "active", iso(expires)


def write_manifest(store, intent):
    store.manifests_dir.mkdir(parents=True, exist_ok=True)
    path = store.manifests_dir / (intent["intentId"] + ".json")
    doc = {"targets": [{"path": t["path"], "sha256": t.get("sha256")}
                       for t in intent["targets"]]}
    atomic_write(path, json.dumps(doc, ensure_ascii=True))
    return path


def journal_note_best_effort(root, task_id, text):
    try:
        return scope.journal_note(root, task_id, "plan", text[:400],
                                  [STORE_REL + "/events.jsonl"])
    except Exception:
        return False


def cmd_propose(root, args):
    store = Store(root)
    agent = safe_name(args.agent, "agent")
    task_id = safe_name(args.task, "task")
    ttl = int(args.ttl)
    if not 1 <= ttl <= 540:
        raise PlaneError("ttl-out-of-range")
    surfaces = sorted({s for s in (args.surface or [])})
    bad = [s for s in surfaces if s not in SURFACES]
    if bad:
        raise PlaneError("surface-unknown:" + ",".join(bad))

    targets = []
    if args.manifest:
        try:
            doc = json.loads(Path(args.manifest).read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            raise PlaneError("manifest-unreadable")
        for entry in doc.get("targets") or []:
            path = scope.canon(entry.get("path"))
            sha = entry.get("sha256")
            if sha is not None and not SHA256_RE.match(str(sha)):
                raise PlaneError("target-sha-invalid")
            targets.append({"path": path,
                            "sha256": str(sha).lower() if sha else None})
    else:
        for raw in args.path or []:
            path = scope.canon(raw)
            full = root / path
            if full.is_dir():
                raise PlaneError("target-is-directory-declare-files")
            sha = sha_file(full) if full.is_file() else None
            targets.append({"path": path, "sha256": sha})
    seen = set()
    for t in targets:
        if t["path"] in seen:
            raise PlaneError("target-duplicate")
        seen.add(t["path"])
    if not targets or len(targets) > MAX_TARGETS:
        raise PlaneError("targets-required-1..%d" % MAX_TARGETS)

    with store.lock():
        state = store.load()
        if args.intent:
            intent = find_intent(state, args.intent)
            if intent.get("state") not in ("proposed", "blocked"):
                raise PlaneError("intent-not-reproposable")
            if agent != intent.get("agent") or task_id != intent.get("taskId"):
                raise PlaneError("intent-owner-mismatch")
            intent.update({"targets": targets, "surfaces": surfaces,
                           "base": args.base or intent.get("base") or "worktree",
                           "ttlMinutes": ttl, "state": "proposed",
                           "conflict": None, "_lastBlockedFp": None,
                           "proposedAtUtc": iso(utcnow())})
            if args.purpose:
                intent["purpose"] = args.purpose[:400]
            store.append_event(state, "intent.reproposed", intent,
                               {"targets": [t["path"] for t in targets]})
        else:
            intent = {
                "schemaVersion": INTENT_SCHEMA,
                "intentId": new_id("ci-"),
                "agent": agent, "taskId": task_id,
                "purpose": (args.purpose or "")[:400],
                "base": args.base or "worktree",
                "targets": targets, "surfaces": surfaces,
                "state": "proposed", "fence": None, "lease": None,
                "conflict": None, "_lastBlockedFp": None,
                "ttlMinutes": ttl,
                "proposedAtUtc": iso(utcnow()),
                "admittedAtUtc": None, "sealedAtUtc": None,
                "endedAtUtc": None,
            }
            state["intents"].append(intent)
            if len(state["intents"]) > MAX_INTENTS:
                released = [i for i in state["intents"]
                            if i.get("state") == "released"]
                for old in released[:len(state["intents"]) - MAX_INTENTS]:
                    state["intents"].remove(old)
            store.append_event(state, "intent.proposed", intent,
                               {"targets": [t["path"] for t in targets],
                                "surfaces": surfaces})
        store.save(state)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "propose",
                      "intent": {k: v for k, v in intent.items()
                                 if not k.startswith("_")}},
                     ensure_ascii=True))
    return 0


def cmd_admit(root, args):
    store = Store(root)
    with store.lock():
        state = store.load()
        intent = find_intent(state, args.intent)
        if intent.get("state") not in ("proposed", "blocked"):
            raise PlaneError("intent-not-admissible:" +
                             str(intent.get("state")))
        owner_id = scope_name(intent)
        topic = intent["intentId"]
        manifest_path = write_manifest(store, intent)

        holder, shared = surface_conflict(state, intent)
        if holder is not None:
            fp = conflict_fingerprint("surface-conflict", [], [],
                                      surfaces=sorted(shared))
            mark_blocked(store, state, intent, "surface-conflict", fp, [], [],
                         surfaces=sorted(shared), emit_request=False)
            store.save(state)
            print(json.dumps({"schemaVersion": SCHEMA, "action": "admit",
                              "admitted": False, "reason": "surface-conflict",
                              "surfaces": sorted(shared),
                              "heldBy": holder.get("intentId"),
                              "conflictFingerprint": fp}, ensure_ascii=True))
            return 7

        proc = scope.run_ps(root, "status", manifest=str(manifest_path),
                            want_json=True)
        summary = scope.parse_json_lines(proc.stdout)
        if proc.returncode == 6 or summary is None:
            reason = (summary or {}).get("reason") or "target-evidence-unavailable"
            mark_blocked(store, state, intent, reason,
                         conflict_fingerprint(reason, intent_paths(intent), []),
                         intent_paths(intent), [], emit_request=False)
            store.save(state)
            print(json.dumps({"schemaVersion": SCHEMA, "action": "admit",
                              "admitted": False, "reason": reason,
                              "exitCode": proc.returncode}, ensure_ascii=True))
            return 6
        conflict = summary.get("targetConflict") or {}
        if proc.returncode == 7 or conflict.get("allowed") is False:
            rows = summary.get("sourceLeases") or []
            hits, leases = blocking_leases(intent_paths(intent), rows)
            annotate_owners(root, leases)
            reason = conflict.get("reason") or "source-target-overlap"
            fp = conflict_fingerprint(reason, hits or intent_paths(intent),
                                      leases)
            mark_blocked(store, state, intent, reason, fp,
                         hits or intent_paths(intent), leases)
            store.save(state)
            print(json.dumps({"schemaVersion": SCHEMA, "action": "admit",
                              "admitted": False, "reason": reason,
                              "conflictFingerprint": fp,
                              "conflictingPaths": hits or intent_paths(intent),
                              "blockingLeases": [
                                  {"topic": r.get("topic"),
                                   "leaseId": r.get("leaseId"),
                                   "status": r.get("status"),
                                   "ownerTaskId": r.get("ownerTaskId")}
                                  for r in leases]}, ensure_ascii=True))
            return 7

        proc = scope.run_ps(root, "begin", topic=topic, owner=owner_id,
                            manifest=str(manifest_path), want_json=True,
                            ttl=intent.get("ttlMinutes") or 180,
                            task_id=intent.get("taskId"))
        receipt = scope.parse_json_lines(proc.stdout) or {}
        if proc.returncode == 7 or receipt.get("acquired") is not True:
            # Lost the precheck->begin race: re-scan and mark blocked.
            rows = []
            if proc.returncode == 7:
                try:
                    rows = scope.lease_summary(root).get("sourceLeases") or []
                except scope.ScopeError:
                    rows = []
            hits, leases = blocking_leases(intent_paths(intent), rows)
            annotate_owners(root, leases)
            reason = "source-target-overlap" if proc.returncode == 7 \
                else "begin-failed"
            fp = conflict_fingerprint(reason, hits or intent_paths(intent),
                                      leases)
            if proc.returncode == 7:
                mark_blocked(store, state, intent, reason, fp,
                             hits or intent_paths(intent), leases)
            else:
                intent["_lastBlockedFp"] = None
                store.append_event(state, "intent.admit_failed", intent,
                                   {"exitCode": proc.returncode,
                                    "detail": (proc.stdout + proc.stderr)
                                    .strip()[-300:]})
            store.save(state)
            print(json.dumps({"schemaVersion": SCHEMA, "action": "admit",
                              "admitted": False, "reason": reason,
                              "exitCode": proc.returncode,
                              "conflictFingerprint": fp}, ensure_ascii=True))
            return proc.returncode if proc.returncode else 6

        fence = "f-%d" % int(state.get("nextFence", 1))
        state["nextFence"] = int(state.get("nextFence", 1)) + 1
        intent["state"] = "admitted"
        intent["fence"] = fence
        intent["conflict"] = None
        intent["_lastBlockedFp"] = None
        intent["admittedAtUtc"] = iso(utcnow())
        intent["lease"] = {
            "topic": receipt.get("topic") or topic,
            "ownerId": owner_id,
            "leaseName": receipt.get("leaseName"),
            "fingerprint": receipt.get("fingerprint"),
            "manifestHash": receipt.get("manifestHash"),
            "manifestPath": rel(root, manifest_path),
            "ttlMinutes": intent.get("ttlMinutes") or 180,
        }
        store.append_event(state, "intent.admitted", intent, {
            "fence": fence, "leaseName": intent["lease"]["leaseName"],
            "fingerprint": intent["lease"]["fingerprint"],
            "targets": intent_paths(intent)})
        store.save(state)
    journal_note_best_effort(
        root, intent["taskId"],
        "change-plane admit {0} fence {1} targets={2}".format(
            intent["intentId"], fence, ",".join(intent_paths(intent))))
    print(json.dumps({"schemaVersion": SCHEMA, "action": "admit",
                      "admitted": True, "intentId": intent["intentId"],
                      "fence": fence, "lease": intent["lease"],
                      "targets": intent["targets"]}, ensure_ascii=True))
    return 0


def scope_name(intent):
    owner = "%s-%s" % (intent.get("agent"), intent.get("taskId"))
    return re.sub(r"[^A-Za-z0-9_.-]", "-", owner)[:150]


def load_live_intent(store, args):
    state = store.load()
    intent = find_intent(state, args.intent)
    if intent.get("state") not in LIVE_STATES:
        raise PlaneError("intent-not-live:" + str(intent.get("state")))
    if not args.fence or args.fence != intent.get("fence"):
        raise PlaneError("fence-mismatch")
    if not intent.get("lease"):
        raise PlaneError("lease-record-missing")
    return state, intent


def cmd_renew(root, args):
    store = Store(root)
    with store.lock():
        state, intent = load_live_intent(store, args)
        lease = intent["lease"]
        proc = scope.run_ps(root, "heartbeat", topic=lease["topic"],
                            owner=lease["ownerId"],
                            fingerprint=lease.get("fingerprint"),
                            ttl=int(args.ttl or lease.get("ttlMinutes") or 180))
        row = scope.parse_json_lines(proc.stdout)
        ok = proc.returncode == 0
        store.append_event(state,
                           "intent.renewed" if ok else "intent.renew_failed",
                           intent, {"exitCode": proc.returncode,
                                    "heartbeat": row})
        store.save(state)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "renew",
                      "intentId": intent["intentId"], "renewed": ok,
                      "exitCode": proc.returncode, "heartbeat": row},
                     ensure_ascii=True))
    return proc.returncode


def cmd_seal(root, args):
    """Fence-gated completion marker. The PS1 `verify` action re-checks the
    preimage manifest and is meant for immediately-before-apply, not for
    after-the-edit - so seal instead proves the lease is still ours and
    active via the read-only summary, then records the seal."""
    store = Store(root)
    with store.lock():
        state, intent = load_live_intent(store, args)
        lease = intent["lease"]
        rows = scope.lease_summary(root).get("sourceLeases") or []
        mine = [r for r in rows if r.get("topic") == lease.get("topic")]
        row = mine[0] if mine else None
        status = (row or {}).get("status") or "absent"
        ok = status == "active"
        if ok:
            intent["state"] = "sealed"
            intent["sealedAtUtc"] = iso(utcnow())
        store.append_event(state,
                           "intent.sealed" if ok else "intent.seal_failed",
                           intent, {"leaseStatus": status,
                                    "note": (args.note or "")[:400]})
        store.save(state)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "seal",
                      "intentId": intent["intentId"], "sealed": ok,
                      "leaseStatus": status}, ensure_ascii=True))
    return 0 if ok else 6


def cmd_end(root, args):
    store = Store(root)
    with store.lock():
        state, intent = load_live_intent(store, args)
        lease = intent["lease"]
        proc = scope.run_ps(root, "end", topic=lease["topic"],
                            owner=lease["ownerId"],
                            fingerprint=lease.get("fingerprint"))
        ok = proc.returncode == 0
        if ok:
            intent["state"] = "released"
            intent["endedAtUtc"] = iso(utcnow())
        store.append_event(state,
                           "intent.released" if ok else "intent.end_failed",
                           intent, {"exitCode": proc.returncode,
                                    "detail": (proc.stdout + proc.stderr)
                                    .strip()[-300:]})
        store.save(state)
    if ok:
        journal_note_best_effort(
            root, intent["taskId"],
            "change-plane end {0} fence {1} released".format(
                intent["intentId"], args.fence))
    print(json.dumps({"schemaVersion": SCHEMA, "action": "end",
                      "intentId": intent["intentId"], "released": ok,
                      "exitCode": proc.returncode}, ensure_ascii=True))
    return proc.returncode


def evaluate_open_intents(store, root, state):
    """Live re-evaluation of proposed/blocked intents against the lease
    summary (authoritative overlap) and surface holders. Returns
    (proceed, blocked_rows) and mutates intent state/conflict fields."""
    summary = scope.lease_summary(root)
    rows = summary.get("sourceLeases") or []
    proceed, blocked = [], []
    for intent in state["intents"]:
        if intent.get("state") not in ("proposed", "blocked"):
            continue
        holder, shared = surface_conflict(state, intent)
        if holder is not None:
            reason = "surface-conflict"
            fp = conflict_fingerprint(reason, [], [], surfaces=sorted(shared))
            hits = []
            leases = [{"topic": "change-plane-surface",
                       "leaseId": None, "status": "held",
                       "ownerTaskId": holder.get("taskId"),
                       "overlappingTargets": []}]
            emit = False  # a surface hold is not a lease; no release doc
        else:
            hits, leases = blocking_leases(intent_paths(intent), rows)
            annotate_owners(root, leases)
            reason = "source-target-overlap" if hits else ""
            fp = conflict_fingerprint(reason or "clear", hits, leases) \
                if hits else None
            emit = True
        if hits or holder is not None:
            mark_blocked(store, state, intent, reason, fp, hits, leases,
                         surfaces=sorted(shared), emit_request=emit)
            blocked.append({"intentId": intent["intentId"],
                            "agent": intent.get("agent"),
                            "taskId": intent.get("taskId"),
                            "reason": reason,
                            "conflictFingerprint": fp,
                            "conflictingPaths": hits,
                            "blocking": [{"topic": r.get("topic"),
                                          "leaseId": r.get("leaseId"),
                                          "status": r.get("status"),
                                          "ownerTaskId": r.get("ownerTaskId")}
                                         for r in leases]})
        else:
            if intent.get("state") == "blocked":
                store.append_event(state, "intent.unblocked", intent,
                                   {"previousFingerprint":
                                    (intent.get("conflict") or {})
                                    .get("fingerprint")})
            intent["state"] = "proposed"
            intent["conflict"] = None
            intent["_lastBlockedFp"] = None
            proceed.append({"intentId": intent["intentId"],
                            "agent": intent.get("agent"),
                            "taskId": intent.get("taskId"),
                            "targets": intent_paths(intent)})
    return proceed, blocked, summary


def cmd_plan(root, args):
    store = Store(root)
    with store.lock():
        state = store.load()
        proceed, blocked, summary = evaluate_open_intents(store, root, state)
        store.save(state)
    owners = surface_holders(state)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "plan",
                      "generatedAtUtc": iso(utcnow()),
                      "proceed": proceed, "blocked": blocked,
                      "owners": owners,
                      "leaseCounts": {
                          "active": summary.get("sourceLeaseActiveCount"),
                          "expired": summary.get("sourceLeaseExpiredCount"),
                          "corrupt": summary.get("sourceLeaseCorruptCount"),
                          "blocking": summary.get("sourceLeaseBlockingCount")},
                      "policy": {"forceRelease": False,
                                 "expiredStillBlocks": True,
                                 "releaseRequestOncePerFingerprint": True}},
                     ensure_ascii=True))
    return 7 if blocked and not proceed else 0


def cmd_request_release(root, args):
    store = Store(root)
    paths = []
    if args.intent:
        state = store.load()
        intent = find_intent(state, args.intent)
        paths = intent_paths(intent)
    for raw in args.path or []:
        paths.append(scope.canon(raw))
    paths = sorted(set(paths))
    if not paths:
        raise PlaneError("targets-required")
    with store.lock():
        state = store.load()
        intent = None
        if args.intent:
            intent = find_intent(state, args.intent)
        rows = (scope.lease_summary(root).get("sourceLeases") or [])
        hits, leases = blocking_leases(paths, rows)
        if not hits:
            print(json.dumps({"schemaVersion": SCHEMA, "action":
                              "request-release", "emitted": False,
                              "reason": "no-blocking-lease",
                              "paths": paths}, ensure_ascii=True))
            return 0
        reason = "source-target-overlap"
        fp = conflict_fingerprint(reason, hits, leases)
        outcome = emit_release_request(store, state, root, intent, hits,
                                       leases, fp, reason)
        store.save(state)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "request-release",
                      "conflictFingerprint": fp, "conflictingPaths": hits,
                      **outcome}, ensure_ascii=True))
    return 0


def port_lease_rows(root):
    rows = []
    folder = root / PORT_LEASES_REL
    if not folder.is_dir():
        return rows
    for path in sorted(folder.glob("apl-*.json"))[:512]:
        try:
            lease = json.loads(path.read_bytes())
        except (OSError, ValueError):
            continue
        if not isinstance(lease, dict):
            continue
        state = lease.get("state")
        expires = parse_time(lease.get("expiresAt"))
        if state in PORT_ACTIVE_STATES and expires and expires <= utcnow():
            state = "expired"
        rows.append({"leaseId": lease.get("leaseId"),
                     "owner": lease.get("owner"),
                     "session": lease.get("session"),
                     "service": lease.get("service"),
                     "port": lease.get("port"),
                     "state": state,
                     "expiresAt": lease.get("expiresAt")})
    return rows


def board(root, store):
    state = store.load()
    try:
        summary = scope.lease_summary(root)
    except scope.ScopeError:
        summary = None
    rows = (summary or {}).get("sourceLeases") or []
    by_topic = lease_rows_by_topic(summary)
    intents = []
    for intent in state["intents"]:
        lease = intent.get("lease") or {}
        row = by_topic.get(lease.get("topic") or intent.get("intentId"))
        intents.append({
            "intentId": intent.get("intentId"),
            "agent": intent.get("agent"),
            "taskId": intent.get("taskId"),
            "state": intent.get("state"),
            "effectiveState": effective_state(intent, by_topic),
            "fence": intent.get("fence"),
            "targets": intent_paths(intent),
            "surfaces": intent.get("surfaces") or [],
            "lease": {"topic": lease.get("topic"),
                      "leaseName": lease.get("leaseName"),
                      "status": (row or {}).get("status"),
                      "expiresAtUtc": (row or {}).get("expiresAtUtc")},
            "conflict": intent.get("conflict"),
            "admittedAtUtc": intent.get("admittedAtUtc"),
            "endedAtUtc": intent.get("endedAtUtc"),
        })
    owners = surface_holders(state)
    for surface, owner in owners.items():
        if owner:
            intent = find_intent(state, owner["intentId"])
            owner["effectiveState"] = effective_state(intent, by_topic)
    journals = [{"taskId": j.get("taskId"), "agent": j.get("agent"),
                 "purpose": str(j.get("purpose"))[:120],
                 "updatedAtUtc": j.get("updatedAtUtc")}
                for j in scope.iter_journals(root)][:64]
    ports = port_lease_rows(root)
    last_seq = int(state.get("nextEventSeq", 1)) - 1
    report = {
        "schemaVersion": SCHEMA, "action": "status",
        "generatedAtUtc": iso(utcnow()),
        "owners": owners,
        "intents": {"total": len(intents), "items": intents},
        "leases": {"available": summary is not None,
                   "activeCount": (summary or {}).get("sourceLeaseActiveCount"),
                   "expiredCount": (summary or {}).get("sourceLeaseExpiredCount"),
                   "corruptCount": (summary or {}).get("sourceLeaseCorruptCount"),
                   "blockingCount": (summary or {}).get("sourceLeaseBlockingCount"),
                   "rows": rows},
        "journals": {"activeCount": len(journals), "active": journals},
        "portLeases": {"count": len(ports), "rows": ports},
        "lastEventSeq": last_seq,
        "board": STORE_REL + "/LATEST.md",
        "derivedFrom": ["intents.json", "events.jsonl"],
    }
    return state, report


def latest_md(report):
    def owner_line(name, owner):
        if not owner:
            return f"- {name}: (none)"
        return (f"- {name}: `{owner['agent']}` task `{owner['taskId']}` "
                f"intent `{owner['intentId']}` fence `{owner['fence']}` "
                f"effective `{owner.get('effectiveState')}`")
    lines = [
        "# Change Plane - LATEST",
        "",
        f"- schemaVersion: `{SCHEMA}`",
        f"- generatedAtUtc: `{report['generatedAtUtc']}`",
        f"- lastEventSeq: `{report['lastEventSeq']}`",
        "- truth: `__patch_drop__/source-edit-locks` leases are authoritative;"
        " this board is derived state, never an unlock.",
        "",
        "## Surface owners",
        owner_line("runtimeOwner", report["owners"].get("runtime")),
        owner_line("buildOwner", report["owners"].get("build")),
        "",
        "## Leases",
        f"- active `{report['leases']['activeCount']}` expired "
        f"`{report['leases']['expiredCount']}` corrupt "
        f"`{report['leases']['corruptCount']}` blocking "
        f"`{report['leases']['blockingCount']}`",
        "",
        "## Intents",
    ]
    if not report["intents"]["items"]:
        lines.append("- (none)")
    for item in report["intents"]["items"]:
        conflict = item.get("conflict") or {}
        suffix = (f" conflict `{conflict.get('reason')}` "
                  f"fp `{conflict.get('fingerprint')}`") if conflict else ""
        lines.append(
            f"- `{item['intentId']}` {item['agent']}/{item['taskId']} "
            f"state `{item['state']}` effective `{item['effectiveState']}` "
            f"fence `{item['fence']}` surfaces {item['surfaces'] or '-'} "
            f"targets {item['targets']}{suffix}")
    lines += [
        "",
        "## Active journals",
    ]
    if not report["journals"]["active"]:
        lines.append("- (none)")
    for j in report["journals"]["active"][:20]:
        lines.append(f"- `{j['taskId']}` {j['agent']} - {j['purpose']}")
    lines += [
        "",
        "## Port leases",
    ]
    if not report["portLeases"]["rows"]:
        lines.append("- (none)")
    for p in report["portLeases"]["rows"][:20]:
        lines.append(f"- `{p['leaseId']}` {p['owner']}/{p['session']} "
                     f"port {p['port']} state `{p['state']}`")
    lines += [
        "",
        f"Rebuilt from `{STORE_REL}/intents.json` + `events.jsonl` "
        "(delete this file and run `status` to regenerate).",
    ]
    return "\n".join(lines)


def cmd_status(root, args):
    store = Store(root)
    state, report = board(root, store)
    atomic_write(store.latest_path, latest_md(report))
    print(json.dumps(report, ensure_ascii=True))
    return 0


def cmd_events(root, args):
    store = Store(root)
    rows = store.read_events()
    if args.intent:
        rows = [r for r in rows if r.get("intentId") == args.intent]
    tail = rows[-int(args.tail):] if args.tail else rows
    state = store.load()
    print(json.dumps({"schemaVersion": SCHEMA, "action": "events",
                      "count": len(rows), "returned": len(tail),
                      "lastEventSeq": int(state.get("nextEventSeq", 1)) - 1,
                      "events": tail}, ensure_ascii=True))
    return 0


def cmd_preflight(root, args):
    """Read-only projection for agent_preflight.py's `changePlane` field.
    No PS1 spawn, no mutation; lease liveness is read from lock files."""
    store = Store(root)
    state = store.load()
    intents = state.get("intents") or []
    active = 0
    blocked = 0
    owners = {"runtime": None, "build": None}
    for intent in intents:
        istate = intent.get("state")
        eff = istate
        if istate in LIVE_STATES:
            lease = intent.get("lease") or {}
            ls, _ = local_lease_state(root, lease.get("leaseName") or "")
            eff = {"active": istate, "expired": "expired_lease",
                   "corrupt": "corrupt_lease"}.get(ls, "lease_" + ls)
        if istate == "blocked":
            blocked += 1
        elif eff in LIVE_STATES or istate == "proposed":
            active += 1
        for surface in intent.get("surfaces") or []:
            if surface in owners and owners[surface] is None \
                    and eff in LIVE_STATES:
                owners[surface] = {"agent": intent.get("agent"),
                                   "taskId": intent.get("taskId"),
                                   "intentId": intent.get("intentId"),
                                   "effectiveState": eff}
    last_seq = int(state.get("nextEventSeq", 1)) - 1
    print(json.dumps({"schemaVersion": PREFLIGHT_SCHEMA,
                      "changePlane": {
                          "present": store.intents_path.is_file(),
                          "activeIntents": active,
                          "blockedCount": blocked,
                          "runtimeOwner": owners["runtime"],
                          "buildOwner": owners["build"],
                          "lastEventSeq": last_seq}},
                     ensure_ascii=True))
    return 0


def build_parser():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(DEFAULT_ROOT),
                        help="repo root (default: this checkout)")
    sub = parser.add_subparsers(dest="action", required=True)

    p = sub.add_parser("propose", help="declare a ChangeIntent (targets pinned "
                                       "by sha256; read-phase record only)")
    p.add_argument("--agent", required=True)
    p.add_argument("--task", required=True, help="taskId recorded on the intent")
    p.add_argument("--purpose", default="")
    p.add_argument("--base", default="worktree",
                   help="base label recorded on the intent (e.g. worktree, branch)")
    p.add_argument("--path", action="append", default=[],
                   help="repo-relative target path; sha256 pinned from live bytes")
    p.add_argument("--manifest", help="JSON file with explicit "
                                     "{targets:[{path,sha256}]} pins")
    p.add_argument("--surface", action="append", default=[],
                   help="singleton surface to hold: runtime|build")
    p.add_argument("--ttl", type=int, default=180, help="lease TTL minutes (1-540)")
    p.add_argument("--intent", help="re-pin an existing proposed/blocked intent")
    p.set_defaults(func=cmd_propose)

    p = sub.add_parser("admit", help="atomic check -> PS1 begin -> monotonic fence")
    p.add_argument("--intent", required=True)
    p.set_defaults(func=cmd_admit)

    p = sub.add_parser("renew", help="delegate to PS1 heartbeat (fence required)")
    p.add_argument("--intent", required=True)
    p.add_argument("--fence", required=True)
    p.add_argument("--ttl", type=int)
    p.set_defaults(func=cmd_renew)

    p = sub.add_parser("seal", help="fence-gated lease-liveness check -> state sealed")
    p.add_argument("--intent", required=True)
    p.add_argument("--fence", required=True)
    p.add_argument("--note", default="", help="verification note recorded in the seal event")
    p.set_defaults(func=cmd_seal)

    p = sub.add_parser("end", help="fence-gated PS1 end -> release the lease")
    p.add_argument("--intent", required=True)
    p.add_argument("--fence", required=True)
    p.set_defaults(func=cmd_end)

    p = sub.add_parser("status", help="board: intents+leases+journals+ports -> "
                                      "LATEST.md + JSON")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("events", help="replay events.jsonl")
    p.add_argument("--tail", type=int, default=50)
    p.add_argument("--intent")
    p.set_defaults(func=cmd_events)

    p = sub.add_parser("plan", help="proceed[]/blocked[] for open intents; "
                                    "release_request once per fingerprint")
    p.set_defaults(func=cmd_plan)

    p = sub.add_parser("request-release", help="write LEASE_RELEASE_REQUEST.md "
                                               "for blockers + event (never unlocks)")
    p.add_argument("--intent")
    p.add_argument("--path", action="append", default=[])
    p.set_defaults(func=cmd_request_release)

    p = sub.add_parser("preflight", help="read-only changePlane field for "
                                         "agent_preflight.py")
    p.set_defaults(func=cmd_preflight)
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(getattr(args, "root", None) or DEFAULT_ROOT).resolve()
    try:
        return args.func(root, args)
    except (PlaneError, scope.ScopeError) as error:
        reason = str(error)
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": reason}, ensure_ascii=True))
        if any(k in reason for k in ("fence", "owner", "not-live",
                                     "not-admissible", "not-reproposable",
                                     "identity")):
            return 3
        return 7 if "conflict" in reason or "overlap" in reason else 6
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": f"change-plane-io-or-evidence-error:{error}"},
                         ensure_ascii=True))
        return 6


if __name__ == "__main__":
    sys.exit(main())
