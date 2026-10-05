"""Apply the AM-01 reconcile edits to scripts/agent_change_plane.py on disk.

The Devin edit tool's writes to this file never reached the filesystem
(verified: sentinel absent, mtime unchanged), so this script applies the same
replacements directly. Each replacement must match exactly once.
"""
from pathlib import Path
import sys

PATH = Path("scripts/agent_change_plane.py")
text = PATH.read_text(encoding="utf-8")
text = text.replace("\n# exec-sentinel", "")

PAIRS = []

# R1 docstring
PAIRS.append((
"""Blocked intents keep state=blocked; `plan` re-evaluates them live and emits at
most one release_request event per conflict fingerprint - requests never
delete or force-release foreign locks.
""",
"""Blocked intents keep state=blocked; `plan` re-evaluates them live and emits at
most one release_request event per conflict fingerprint — requests never
delete or force-release foreign locks. `reconcile` (also run inside
status/admit/plan) retires ghost blocks: with no live overlapping lease row
or surface holder, a blocked intent returns to `proposed` when its owner
journal is still in_progress, else moves to terminal `superseded` — history
kept, nothing deleted, no lease ever ended here.
"""))

# R2 helpers before surface_holders
PAIRS.append((
"""    task_hash = str(lease_row.get("taskIdHash") or "")
    if task_hash and task_hash in hash_map:
        return hash_map[task_hash], "task-id-hash"
    return None, "unresolved"


def surface_holders(state):""",
'''    task_hash = str(lease_row.get("taskIdHash") or "")
    if task_hash and task_hash in hash_map:
        return hash_map[task_hash], "task-id-hash"
    return None, "unresolved"


def journal_states(root):
    """taskId -> raw journal status (\'in_progress\' = open). A taskId absent
    from the map means no journal evidence either way."""
    states = {}
    for doc in scope.iter_task_docs(root, "journal.json"):
        if isinstance(doc, dict) and isinstance(doc.get("taskId"), str):
            states[doc["taskId"]] = str(doc.get("status") or "unknown")
    return states


def unblocked_target(intent, journals):
    return ("proposed" if journals.get(intent.get("taskId")) == "in_progress"
            else "superseded")


def clear_stale_block(store, state, intent, journals, via, extra=None):
    """Clear a `blocked` intent whose recorded conflict no longer has live
    overlap. Owner journal in_progress -> `proposed` (a live task may retry
    admit); closed/missing -> `superseded` (terminal, re-proposable by the
    same owner). The intent row, its conflict record and every event stay as
    history; nothing is deleted and no lease is touched."""
    conflict = intent.get("conflict") or {}
    detail = {"via": via,
              "previousFingerprint": conflict.get("fingerprint"),
              "blockingOwners": sorted(str(r.get("ownerTaskId"))
                                       for r in conflict.get("blockingLeases")
                                       or [] if r.get("ownerTaskId"))}
    if extra:
        detail.update(extra)
    if unblocked_target(intent, journals) == "proposed":
        intent["state"] = "proposed"
        intent["conflict"] = None
        intent["_lastBlockedFp"] = None
        store.append_event(state, "intent.unblocked", intent, detail)
        return "proposed"
    intent["state"] = "superseded"
    intent["reconciledAtUtc"] = iso(utcnow())
    if isinstance(intent.get("conflict"), dict):
        intent["conflict"]["resolved"] = "superseded"
    intent["_lastBlockedFp"] = None
    store.append_event(state, "intent.superseded", intent, detail)
    return "superseded"


def reconcile_ghosts(store, root, state, dry_run=False, summary=None,
                     journals=None, exclude=None):
    """AM-01 ghost sweep over `blocked` intents.

    A blocked intent is stale when nothing live still blocks it: no
    source-edit lease row (any status — expiry never unlocks) overlapping its
    targets and no live surface holder. Those clear via clear_stale_block; an
    intent with any live overlap stays blocked. Recorded blocker journal
    states ride along as evidence only — the PS1 lease rows are the
    authority, not journal bookkeeping. Never ends a lease and never deletes
    intents or events."""
    if summary is None:
        summary = scope.lease_summary(root)
    if journals is None:
        journals = journal_states(root)
    rows = (summary or {}).get("sourceLeases") or []
    claims = hash_map = None
    result = {"superseded": [], "revived": [], "stillBlocked": []}
    for intent in state["intents"]:
        if intent.get("state") != "blocked":
            continue
        if exclude and intent.get("intentId") in exclude:
            continue
        conflict = intent.get("conflict") or {}
        holder, _shared = surface_conflict(state, intent)
        hits, live_rows = blocking_leases(intent_paths(intent), rows)
        if hits or holder is not None:
            result["stillBlocked"].append({
                "intentId": intent.get("intentId"),
                "reason": "live-overlap",
                "conflictingPaths": hits,
                "liveBlockerTopics": sorted(str(r.get("topic"))
                                            for r in live_rows),
                "surfaceHeldBy": (holder or {}).get("intentId")})
            continue
        blockers = []
        for rec in conflict.get("blockingLeases") or []:
            owner = rec.get("ownerTaskId")
            basis = "recorded"
            if not owner and rec.get("topic") != "change-plane-surface":
                if claims is None:
                    claims, hash_map = owner_task_map(root)
                owner, basis = resolve_owner_task(rec, claims, hash_map)
            blockers.append({"topic": rec.get("topic"),
                             "ownerTaskId": owner,
                             "ownerTaskIdBasis": basis if owner
                             else "unresolved",
                             "journal": journals.get(owner) if owner
                             else "missing"})
        target = unblocked_target(intent, journals)
        entry = {"intentId": intent.get("intentId"), "to": target,
                 "previousFingerprint": conflict.get("fingerprint"),
                 "blockers": blockers}
        if not dry_run:
            clear_stale_block(store, state, intent, journals, "reconcile",
                              extra={"blockers": blockers})
        result["superseded" if target == "superseded" else "revived"].append(
            entry)
    return result


def surface_holders(state):'''))

# R3 evaluate signature
PAIRS.append((
'''def evaluate_open_intents(store, root, state):
    """Live re-evaluation of proposed/blocked intents against the lease
    summary (authoritative overlap) and surface holders. Returns
    (proceed, blocked_rows) and mutates intent state/conflict fields."""
    summary = scope.lease_summary(root)
    rows = summary.get("sourceLeases") or []
    proceed, blocked = [], []''',
'''def evaluate_open_intents(store, root, state, summary=None, journals=None):
    """Live re-evaluation of proposed/blocked intents against the lease
    summary (authoritative overlap) and surface holders. Returns
    (proceed, blocked_rows, superseded_rows, summary) and mutates intent
    state/conflict fields."""
    if summary is None:
        summary = scope.lease_summary(root)
    if journals is None:
        journals = journal_states(root)
    rows = summary.get("sourceLeases") or []
    proceed, blocked, superseded = [], [], []'''))

# R4 evaluate else-branch
PAIRS.append((
"""        else:
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
    return proceed, blocked, summary""",
"""        else:
            if intent.get("state") == "blocked":
                if clear_stale_block(store, state, intent, journals,
                                     "evaluate") == "superseded":
                    superseded.append({"intentId": intent["intentId"],
                                       "agent": intent.get("agent"),
                                       "taskId": intent.get("taskId"),
                                       "targets": intent_paths(intent)})
                    continue
            else:
                intent["state"] = "proposed"
                intent["conflict"] = None
            proceed.append({"intentId": intent["intentId"],
                            "agent": intent.get("agent"),
                            "taskId": intent.get("taskId"),
                            "targets": intent_paths(intent)})
    return proceed, blocked, superseded, summary"""))

# R5 cmd_plan
PAIRS.append((
"""def cmd_plan(root, args):
    store = Store(root)
    with store.lock():
        state = store.load()
        proceed, blocked, summary = evaluate_open_intents(store, root, state)
        store.save(state)
    owners = surface_holders(state)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "plan",
                      "generatedAtUtc": iso(utcnow()),
                      "proceed": proceed, "blocked": blocked,
                      "owners": owners,""",
"""def cmd_plan(root, args):
    store = Store(root)
    with store.lock():
        state = store.load()
        summary = scope.lease_summary(root)
        journals = journal_states(root)
        reconcile = reconcile_ghosts(store, root, state, summary=summary,
                                     journals=journals)
        proceed, blocked, superseded, _ = evaluate_open_intents(
            store, root, state, summary=summary, journals=journals)
        store.save(state)
    owners = surface_holders(state)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "plan",
                      "generatedAtUtc": iso(utcnow()),
                      "proceed": proceed, "blocked": blocked,
                      "superseded": superseded,
                      "reconcile": reconcile,
                      "owners": owners,"""))

# R6 cmd_reconcile before cmd_request_release
PAIRS.append((
"""def cmd_request_release(root, args):""",
'''def cmd_reconcile(root, args):
    """Explicit AM-01 ghost sweep. --dry-run reports the same decisions
    without mutating intents.json/events.jsonl; either mode writes a receipt
    under change-plane/. status/admit/plan already run this silently."""
    store = Store(root)
    with store.lock():
        state = store.load()
        result = reconcile_ghosts(store, root, state,
                                  dry_run=bool(args.dry_run))
        if not args.dry_run:
            store.save(state)
    receipt = {"schemaVersion": SCHEMA, "action": "reconcile",
               "generatedAtUtc": iso(utcnow()),
               "dryRun": bool(args.dry_run),
               "blockedBefore": (len(result["superseded"])
                                 + len(result["revived"])
                                 + len(result["stillBlocked"])),
               "blockedAfter": len(result["stillBlocked"]),
               **result}
    name = ("reconcile-receipt-"
            + utcnow().strftime("%Y%m%dT%H%M%S-%f") + "Z.json")
    path = store.dir / name
    atomic_write(path, json.dumps(receipt, ensure_ascii=True, indent=1))
    receipt["receipt"] = rel(store.root, path)
    print(json.dumps(receipt, ensure_ascii=True))
    return 0


def cmd_request_release(root, args):'''))

# R7 cmd_status
PAIRS.append((
"""def cmd_status(root, args):
    store = Store(root)
    state, report = board(root, store)
    atomic_write(store.latest_path, latest_md(report))
    print(json.dumps(report, ensure_ascii=True))
    return 0""",
"""def cmd_status(root, args):
    store = Store(root)
    try:
        with store.lock():
            state = store.load()
            reconcile = reconcile_ghosts(store, root, state)
            store.save(state)
    except (PlaneError, scope.ScopeError) as error:
        reconcile = {"skipped": str(error)}
    state, report = board(root, store)
    report["reconcile"] = reconcile
    atomic_write(store.latest_path, latest_md(report))
    print(json.dumps(report, ensure_ascii=True))
    return 0"""))

# R8 cmd_admit
PAIRS.append((
"""    store = Store(root)
    with store.lock():
        state = store.load()
        intent = find_intent(state, args.intent)
        if intent.get("state") not in ("proposed", "blocked"):
            raise PlaneError("intent-not-admissible:" +
                             str(intent.get("state")))""",
"""    store = Store(root)
    with store.lock():
        state = store.load()
        try:
            reconcile_ghosts(store, root, state, exclude={args.intent})
        except scope.ScopeError:
            pass  # admit's own PS1 precheck below stays authoritative
        intent = find_intent(state, args.intent)
        if intent.get("state") not in ("proposed", "blocked"):
            raise PlaneError("intent-not-admissible:" +
                             str(intent.get("state")))"""))

# R9 propose re-pin
PAIRS.append((
"""        if args.intent:
            intent = find_intent(state, args.intent)
            if intent.get("state") not in ("proposed", "blocked"):
                raise PlaneError("intent-not-reproposable")""",
"""        if args.intent:
            intent = find_intent(state, args.intent)
            if intent.get("state") not in ("proposed", "blocked",
                                           "superseded"):
                raise PlaneError("intent-not-reproposable")"""))

# R10 MAX_INTENTS prune
PAIRS.append((
"""            if len(state["intents"]) > MAX_INTENTS:
                released = [i for i in state["intents"]
                            if i.get("state") == "released"]
                for old in released[:len(state["intents"]) - MAX_INTENTS]:
                    state["intents"].remove(old)""",
"""            if len(state["intents"]) > MAX_INTENTS:
                retired = [i for i in state["intents"]
                           if i.get("state") in ("released", "superseded")]
                for old in retired[:len(state["intents"]) - MAX_INTENTS]:
                    state["intents"].remove(old)"""))

# R11 parser
PAIRS.append((
'''    p = sub.add_parser("preflight", help="read-only changePlane field for "
                                         "agent_preflight.py")
    p.set_defaults(func=cmd_preflight)
    return parser''',
'''    p = sub.add_parser("reconcile", help="retire ghost blocked intents: no live "
                                         "overlapping lease/surface -> proposed "
                                         "(owner journal open) or superseded; "
                                         "writes a receipt")
    p.add_argument("--dry-run", action="store_true",
                   help="report decisions without mutating intents/events; "
                        "receipt is still written")
    p.set_defaults(func=cmd_reconcile)

    p = sub.add_parser("preflight", help="read-only changePlane field for "
                                         "agent_preflight.py")
    p.set_defaults(func=cmd_preflight)
    return parser'''))

for i, (old, new) in enumerate(PAIRS):
    n = text.count(old)
    if n != 1:
        print("PAIR %d matched %d times - ABORT" % (i, n))
        sys.exit(2)
    text = text.replace(old, new, 1)

import ast
ast.parse(text)
PATH.write_text(text, encoding="utf-8")
print("patched ok, bytes", len(text.encode("utf-8")))
