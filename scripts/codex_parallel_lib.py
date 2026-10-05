#!/usr/bin/env python3
"""Shared read-only detection helpers for the Codex parallel-lanes kit.

Contract DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002. One module holds the
goal-key / overlap / liveness rules so codex_parallel_preflight.py,
codex_lane_board.py, codex_lane_integrate.py and the lane plan tool all judge
the same evidence the same way. Everything here is read-only: journals, scope
claims, source-edit lease files, coop_verify state and file mtimes are
observed, never mutated. Reclaim stays with the existing tools.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import sys
from datetime import datetime, timezone

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    import codex_work_checkpoint as ck
except ImportError:  # pragma: no cover - direct module run fallback
    ck = None

SCHEMA = "awx.parallel-lanes.v1"
JOURNAL_BASE = "data/agent-handoff/codex-autonomy"
COOP_STATE = "data/agent-handoff/coop-verify/state.json"
PLAN_BASE = "data/agent-handoff/parallel-lanes"
LEASE_DIR = "__patch_drop__/source-edit-locks"
JOURNAL_SCHEMA = "awx.work_journal.v1"
CLAIM_SCHEMA = "awx.agent-scope-claim.v1"
PLAN_SCHEMA = "awx.parallel-lane-plan.v1"

CONTRACT_RE = re.compile(r"\[Contract:\s*([A-Za-z0-9_.:-]+)\]", re.IGNORECASE)
GOAL_RE = re.compile(r"(DEMO1-[A-Z0-9][A-Z0-9-]+)", re.IGNORECASE)
HEX_SUFFIX_RE = re.compile(r"-[0-9a-fA-F]{8}$")
LANE_TAG_RE = re.compile(r"\[LANE:\s*([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)", re.IGNORECASE)

# Scan roots for "recently changed tracked files". data/, var/, build output
# and agent scratch stay out - they churn on every tool run.
EDIT_SCAN_ROOTS = ("main", "src", "scripts", "docs", "configs", ".agents",
                   ".windsurf", ".clinerules", ".grok", ".devin", "agent-prompts",
                   "tools", "app/src")
EDIT_SCAN_FILES = ("AGENTS.md", "agents.md", "settings.gradle.kts",
                   "build.gradle.kts")
EDIT_SKIP_DIRS = {"data", "var", "build", ".git", "node_modules", "__pycache__",
                  "__patch_drop__", ".gradle", "bin", "out", "target"}
WRITER_OPEN = {"EDITING", "CHECKPOINT"}
LIVE_MINUTES_DEFAULT = 20
EDIT_MINUTES_DEFAULT = 30
STALE_CLAIM_HOURS = 24


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_iso(text):
    try:
        return datetime.fromisoformat(str(text).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def age_seconds(text, now=None):
    stamp = parse_iso(text)
    if stamp is None:
        return None
    return ((now or datetime.now(timezone.utc)) - stamp).total_seconds()


def canon(path_text):
    """repo-relative lowercase '/'-joined key; None when unusable."""
    text = str(path_text or "").strip().replace("\\", "/")
    if not text or os.path.isabs(text):
        return None
    parts = [p for p in text.split("/") if p not in ("", ".")]
    if not parts or any(p in ("..", ".git") for p in parts):
        return None
    return "/".join(parts).lower()


def paths_overlap(a, b):
    return bool(a and b) and (a == b or a.startswith(b + "/") or b.startswith(a + "/"))


def overlapping(scope_a, scope_b):
    return sorted({a for a in scope_a or [] for b in scope_b or []
                   if paths_overlap(a, b)})


def goal_key(task_id, purpose=""):
    """Same-goal key: [Contract: X] wins, else DEMO1-* token, else taskId
    minus its trailing -8hex instance suffix."""
    match = CONTRACT_RE.search(str(purpose or ""))
    if match:
        return "contract:" + match.group(1).strip().upper()
    match = GOAL_RE.search(str(purpose or ""))
    if match:
        return "goal:" + match.group(1).strip().upper()
    return "task:" + HEX_SUFFIX_RE.sub("", str(task_id or "")).lower()


def normalize_key(text):
    """CLI --goal-key -> the same form goal_key() emits."""
    raw = str(text or "").strip()
    if raw.lower().startswith(("contract:", "goal:", "task:", "key:")):
        return raw
    match = CONTRACT_RE.search(raw)
    if match:
        return "contract:" + match.group(1).strip().upper()
    match = GOAL_RE.search(raw)
    if match:
        return "goal:" + match.group(1).strip().upper()
    return "goal:" + raw.upper() if raw else ""


def _read_json(path):
    try:
        doc = json.loads(Path(path).read_bytes())
    except (OSError, ValueError):
        return None
    return doc if isinstance(doc, dict) else None


def iter_journals(root):
    base = Path(root) / JOURNAL_BASE
    if not base.is_dir():
        return
    for path in sorted(base.glob("*/journal.json"))[:512]:
        doc = _read_json(path)
        if doc is not None:
            doc["_dir"] = path.parent
            yield doc


def iter_claims(root):
    base = Path(root) / JOURNAL_BASE
    if not base.is_dir():
        return
    for path in sorted(base.glob("*/scope-claim-*.json"))[:1024]:
        doc = _read_json(path)
        if isinstance(doc, dict) and doc.get("schemaVersion") == CLAIM_SCHEMA:
            yield doc


def load_coop_state(root):
    doc = _read_json(Path(root) / COOP_STATE)
    return doc if isinstance(doc, dict) else {"writers": {}, "tickets": {}}


def iter_source_leases(root):
    base = Path(root) / LEASE_DIR
    if not base.is_dir():
        return
    for path in sorted(base.glob("*.lock/lease.json"))[:256]:
        doc = _read_json(path)
        if isinstance(doc, dict):
            doc["_topic"] = path.parent.name[:-5]
            yield doc


def lease_lifecycle(lease, now=None):
    """live|stale|unknown from a raw lease.json row (no ps1 needed)."""
    if not isinstance(lease, dict):
        return "unknown"
    now = now or datetime.now(timezone.utc)
    expiry = parse_iso(lease.get("expiresAtUtc") or lease.get("expiresAt"))
    pid = lease.get("ownerProcessId")
    if isinstance(pid, int) and pid > 0 and ck is not None and ck.pid_alive(pid):
        return "live"
    if expiry is not None and expiry > now:
        return "live"
    if expiry is not None and expiry <= now:
        return "stale"
    return "unknown"


def lease_owned_by_task(lease, task_id):
    """True when lease.taskIdHash is sha256(task_id). An empty task matches nothing."""
    if not task_id or not isinstance(lease, dict):
        return False
    task_hash = hashlib.sha256(str(task_id).encode("utf-8")).hexdigest()
    return lease.get("taskIdHash") == task_hash


def journal_last_activity(root, journal):
    """Most recent of updatedAtUtc, task-dir mtime, newest scope file mtime."""
    stamps = [parse_iso(journal.get("updatedAtUtc"))]
    task_dir = journal.get("_dir")
    if task_dir and Path(task_dir).is_dir():
        try:
            stamps.append(datetime.fromtimestamp(
                max(f.stat().st_mtime for f in Path(task_dir).rglob("*") if f.is_file()),
                tz=timezone.utc))
        except (OSError, ValueError):
            pass
    root = Path(root)
    for entry in journal.get("plannedScope") or []:
        c = canon(entry)
        target = root / c if c else None
        try:
            if target is not None and target.is_file():
                stamps.append(datetime.fromtimestamp(target.stat().st_mtime, tz=timezone.utc))
        except OSError:
            continue
    stamps = [s for s in stamps if s is not None]
    return max(stamps) if stamps else None


def has_handoff(root, task_id):
    """handoff.json file, or a journal event that records a handoff
    (kind=handoff or handoff/handed-off/인계 wording)."""
    task_dir = Path(root) / JOURNAL_BASE / str(task_id)
    if (task_dir / "handoff.json").is_file():
        return True
    doc = _read_json(task_dir / "journal.json") or {}
    for event in doc.get("events") or []:
        blob = (str(event.get("kind") or "") + " " +
                str(event.get("text") or "")).lower()
        if "handoff" in blob or "handed off" in blob or "인계" in blob:
            return True
    return False


def lane_tag(journal):
    """[LANE: planId/laneId ...] marker a lane journal carries, if any."""
    text = str(journal.get("purpose") or "")
    for event in journal.get("events") or []:
        text += " " + str(event.get("text") or "")
    match = LANE_TAG_RE.search(text)
    return (match.group(1), match.group(2)) if match else (None, None)


def load_plan(root, plan_id):
    doc = _read_json(Path(root) / PLAN_BASE / str(plan_id) / "plan.json")
    if isinstance(doc, dict) and doc.get("schemaVersion") == PLAN_SCHEMA:
        return doc
    return None


def iter_plans(root):
    base = Path(root) / PLAN_BASE
    if not base.is_dir():
        return
    for path in sorted(base.glob("*/plan.json"))[:128]:
        doc = _read_json(path)
        if isinstance(doc, dict) and doc.get("schemaVersion") == PLAN_SCHEMA:
            yield doc


def plan_lane(plan, lane_id):
    for lane in (plan or {}).get("lanes") or []:
        if str(lane.get("laneId")) == str(lane_id):
            return lane
    return None


def latest_checkpoint_postimages(root, task_id):
    """canon path -> sha256 merged from a task's cycle-*/checkpoint.json
    `postimages` maps (later cycles override earlier ones)."""
    task_dir = Path(root) / JOURNAL_BASE / str(task_id)
    posts = {}
    if not task_dir.is_dir():
        return posts
    for checkpoint in sorted(task_dir.glob("cycle-*/checkpoint.json")):
        doc = _read_json(checkpoint)
        if not isinstance(doc, dict):
            continue
        for path, sha in (doc.get("postimages") or {}).items():
            c = canon(path)
            if c and sha:
                posts[c] = sha
    return posts


def checkpoint_changed_paths(root, task_id):
    """canon paths a task actually wrote: checkpoint postimages != manifest
    preimageSha256 (covers create/edit/delete), per cycle."""
    task_dir = Path(root) / JOURNAL_BASE / str(task_id)
    changed = []
    if not task_dir.is_dir():
        return changed
    for checkpoint in sorted(task_dir.glob("cycle-*/checkpoint.json")):
        doc = _read_json(checkpoint)
        if not isinstance(doc, dict):
            continue
        manifest = _read_json(checkpoint.parent / "manifest.json") or {}
        pre = {canon(t.get("path")): t.get("preimageSha256")
               for t in manifest.get("targets") or []}
        for path, sha in (doc.get("postimages") or {}).items():
            c = canon(path)
            if c and sha != pre.get(c):
                changed.append(c)
    return sorted(set(changed))


def detect_duplicates(root, key, scope, exclude_task=None, live_minutes=LIVE_MINUTES_DEFAULT,
                      plan_id=None, lane_id=None):
    """Same-goal in-progress journals whose plannedScope overlaps `scope`.
    A peer registered as another lane of the same plan is not a duplicate."""
    now = datetime.now(timezone.utc)
    mine = [canon(p) for p in scope or []]
    out = []
    for journal in iter_journals(root):
        if journal.get("status") != "in_progress":
            continue
        if journal.get("taskId") == exclude_task:
            continue
        if goal_key(journal.get("taskId"), journal.get("purpose")) != key:
            continue
        peer_plan, peer_lane = lane_tag(journal)
        if plan_id and peer_plan == plan_id and peer_lane and peer_lane != lane_id:
            continue  # same plan, different lane = by design
        peer_scope = [canon(s) for s in journal.get("plannedScope") or []]
        hits = overlapping(mine, [s for s in peer_scope if s])
        if not hits:
            continue
        last = journal_last_activity(root, journal)
        quiet_for = (now - last).total_seconds() / 60.0 if last else None
        out.append({
            "taskId": journal.get("taskId"), "agent": journal.get("agent"),
            "overlappingScope": hits,
            "lastActivityUtc": last.isoformat() if last else None,
            "quietMinutes": round(quiet_for, 1) if quiet_for is not None else None,
            "handoff": has_handoff(root, journal.get("taskId")),
            "plan": peer_plan, "lane": peer_lane,
            "live": quiet_for is not None and quiet_for <= live_minutes,
        })
    return out


def role_decision(root, key, scope, exclude_task=None, live_minutes=LIVE_MINUTES_DEFAULT,
                  plan=None, lane=None):
    """OWNER | VERIFIER | TAKEOVER | WAIT per the duplicate/liveness rules."""
    plan_id = plan.get("planId") if plan else None
    dups = detect_duplicates(root, key, scope, exclude_task=exclude_task,
                             live_minutes=live_minutes,
                             plan_id=plan_id, lane_id=lane)
    handed = [d for d in dups if d["handoff"]]
    live_dups = [d for d in dups if d["live"] and not d["handoff"]]
    if handed:
        peer = handed[0]
        return {"role": "TAKEOVER", "verdict": "DUPLICATE_GOAL_HANDOFF",
                "duplicates": dups,
                "baseline": {"taskId": peer["taskId"],
                             "postimages": latest_checkpoint_postimages(root, peer["taskId"])},
                "reason": "peer recorded a handoff - take over from its checkpoint"}
    if live_dups:
        return {"role": "VERIFIER", "verdict": "DUPLICATE_GOAL_LIVE",
                "duplicates": dups,
                "reason": "same goal key + overlapping scope + live peer"}
    quiet = [d for d in dups if not d["live"]]
    if quiet:
        peer = quiet[0]
        return {"role": "TAKEOVER", "verdict": "DUPLICATE_GOAL_QUIET",
                "duplicates": dups,
                "baseline": {"taskId": peer["taskId"],
                             "postimages": latest_checkpoint_postimages(root, peer["taskId"])},
                "reason": "peer quiet past window or handoff recorded"}
    # WAIT: a different goal holds a live claim/lease on part of the scope.
    mine = [canon(p) for p in scope or []]
    blocking, partial_free = [], set(mine)
    for claim in iter_claims(root):
        if claim.get("released") or claim.get("taskId") == exclude_task:
            continue
        if goal_key(claim.get("taskId"), claim.get("purpose")) == key:
            continue
        hits = overlapping(mine, [canon(t.get("path")) for t in claim.get("targets") or []])
        if hits:
            blocking.append({"taskId": claim.get("taskId"), "agent": claim.get("agent"),
                             "topic": claim.get("topic"), "paths": hits})
            partial_free -= set(hits)
    for lease in iter_source_leases(root):
        if lease_lifecycle(lease) != "live":
            continue
        if lease_owned_by_task(lease, exclude_task):
            continue
        hits = overlapping(mine, [canon(t) for t in lease.get("targetPaths") or []])
        if hits:
            blocking.append({"lease": lease.get("_topic"), "paths": hits,
                             "taskId": lease.get("taskIdHash", "")[:8]})
            partial_free -= set(hits)
    if blocking:
        return {"role": "WAIT", "verdict": "FOREIGN_CLAIM_OVERLAP",
                "duplicates": dups, "blockingClaims": blocking,
                "freeScope": sorted(partial_free),
                "reason": "live claim/lease of another goal overlaps part of scope"}
    return {"role": "OWNER", "verdict": "CLEAR",
            "duplicates": dups, "reason": "no same-goal peer or overlap"}


def covered_by_live_writer(root, path):
    state = load_coop_state(root)
    for writer in (state.get("writers") or {}).values():
        if writer.get("state") not in WRITER_OPEN:
            continue
        if age_seconds(writer.get("heartbeatAtUtc")) is not None and \
                age_seconds(writer.get("heartbeatAtUtc")) > 300:
            continue
        if any(paths_overlap(canon(c), path) for c in writer.get("changedPaths") or []):
            return writer.get("editBatchId")
    return None


def covered_by_claim_or_lease(root, path):
    for claim in iter_claims(root):
        if claim.get("released"):
            continue
        if any(paths_overlap(canon(t.get("path")), path)
               for t in claim.get("targets") or []) or \
           any(paths_overlap(canon(r), path) for r in claim.get("reservePaths") or []):
            return {"kind": "claim", "taskId": claim.get("taskId")}
    for lease in iter_source_leases(root):
        if lease_lifecycle(lease) != "live":
            continue
        if any(paths_overlap(canon(t), path) for t in lease.get("targetPaths") or []):
            return {"kind": "lease", "topic": lease.get("_topic")}
    batch = covered_by_live_writer(root, path)
    if batch:
        return {"kind": "writer", "editBatchId": batch}
    return None


def suspect_owner(root, path):
    """Best-effort: which task most likely wrote this file (plannedScope or
    checkpoint postimage sha matching current bytes)."""
    current_sha = None
    try:
        current_sha = hashlib.sha256((Path(root) / path).read_bytes()).hexdigest()
    except OSError:
        pass
    best = None
    for journal in iter_journals(root):
        scope = [canon(s) for s in journal.get("plannedScope") or []]
        if any(paths_overlap(s, path) for s in scope if s):
            best = {"taskId": journal.get("taskId"), "via": "plannedScope"}
            posts = latest_checkpoint_postimages(root, journal.get("taskId"))
            if current_sha and posts.get(path) == current_sha:
                return {"taskId": journal.get("taskId"), "via": "postimage-sha"}
    return best


def unclaimed_edits(root, minutes=EDIT_MINUTES_DEFAULT, scope=None, now=None):
    """Tracked files changed inside `minutes` with no live claim/writer/lease."""
    now = now or datetime.now(timezone.utc)
    root = Path(root)
    cutoff = now.timestamp() - minutes * 60
    scope_filter = [canon(s) for s in scope or []]
    found = []
    candidates = []
    for top in EDIT_SCAN_ROOTS:
        base = root / top
        if base.is_dir():
            for dirpath, dirnames, filenames in os.walk(base):
                dirnames[:] = [d for d in dirnames if d not in EDIT_SKIP_DIRS]
                for name in filenames:
                    candidates.append(Path(dirpath) / name)
    for name in EDIT_SCAN_FILES:
        path = root / name
        if path.is_file():
            candidates.append(path)
    for path in candidates:
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if mtime < cutoff:
            continue
        rel = canon(path.relative_to(root))
        if rel is None:
            continue
        if scope_filter and not any(paths_overlap(rel, s) for s in scope_filter):
            continue
        cover = covered_by_claim_or_lease(root, rel)
        if cover is not None:
            continue
        found.append({"path": rel,
                      "mtimeUtc": datetime.fromtimestamp(mtime, tz=timezone.utc).isoformat(),
                      "suspectedBy": suspect_owner(root, rel)})
    return sorted(found, key=lambda r: r["mtimeUtc"], reverse=True)


def stale_claims(root, hours=STALE_CLAIM_HOURS, now=None):
    """Dry-run candidates: released=false, no live lease, journal closed or
    quiet past `hours`. Report only - reclaim stays with agent_scope_lease."""
    now = now or datetime.now(timezone.utc)
    leases = {l.get("_topic"): l for l in iter_source_leases(root)}
    journals = {j.get("taskId"): j for j in iter_journals(root)}
    out = []
    for claim in iter_claims(root):
        if claim.get("released"):
            continue
        lease = leases.get(claim.get("topic"))
        if lease is not None and lease_lifecycle(lease, now) == "live":
            continue
        journal = journals.get(claim.get("taskId"))
        last = journal_last_activity(root, journal) if journal else None
        quiet_hours = (now - last).total_seconds() / 3600.0 if last else None
        closed = journal is not None and journal.get("status") != "in_progress"
        if not closed and (quiet_hours is None or quiet_hours < hours):
            continue
        out.append({"taskId": claim.get("taskId"), "agent": claim.get("agent"),
                    "topic": claim.get("topic"),
                    "claimedAtUtc": claim.get("claimedAtUtc"),
                    "journalStatus": (journal or {}).get("status") or "missing",
                    "quietHours": round(quiet_hours, 1) if quiet_hours is not None else None,
                    "leaseLifecycle": lease_lifecycle(lease, now) if lease else "absent",
                    "targets": [t.get("path") for t in claim.get("targets") or []]})
    return out


def file_sha(root, rel):
    try:
        return hashlib.sha256((Path(root) / canon(rel)).read_bytes()).hexdigest()
    except (OSError, TypeError):
        return None
