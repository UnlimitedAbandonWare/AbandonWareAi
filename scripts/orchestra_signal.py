"""orchestra_signal.py — awx.orchestra-signal.v1 signal store for the orchestra layer.

Signals carry one work item between user / Grok Bot / agy / GPT Pro / Devin /
Codex / Clean / Grok CLI. The schema embeds every awx.parallel-handoff.v1
field and adds routing fields (lane, kind, evidenceTier, budget, parentId).

Store layout (relative to --store, default data/agent-handoff/orchestra):
  inbox/<agent>/<id>.json   — waiting for that agent
  outbox/<agent>/<id>.json  — produced by that agent, ready to hand on
  archive/<id>.json         — done/dropped stems

Commands: new | validate | link | move | list. Read/list are read-only.
This tool never calls the network and never posts anything to another agent;
the user pastes generated lines by hand. Secret-looking content is refused.
"""
import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

SCHEMA = "awx.orchestra-signal.v1"
HANDOFF_SCHEMA = "awx.parallel-handoff.v1"

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (OSError, ValueError):
        pass

AGENTS = ("user", "grokbot", "agy", "gptpro", "devin", "codex", "clean", "grokcli")
KINDS = ("idea", "amplified", "research-question", "web-evidence", "gptpro-brief",
         "codex-brief", "devin-signal", "patch-report", "verify-finding", "question")
LANES = ("DEVIN", "CODEX_DIRECT", "GPTPRO_THEN_CODEX", "AGY_RESEARCH",
         "GROKBOT_AMPLIFY", "AGY_AS_GROKBOT", "ASK_USER")
PRIORITIES = ("P0", "P1", "P2")
EVIDENCE_TIERS = ("확인됨", "보고됨", "공식", "추론", "evidence_needed")
STATUSES = ("new", "routed", "in-progress", "done", "hold", "dropped")
STORE_PARTS = ("inbox", "outbox", "archive")

# Fields inherited verbatim from awx.parallel-handoff.v1 (fixtures/
# parallel_lanes/handoff-packet-example.json). Kept so a signal can be
# transcribed into a lane handoff packet without losing data.
HANDOFF_FIELDS = ("fromTask", "toTask", "fromChat", "lastCheckpoint",
                  "ownedChanges", "smokeUsed", "restartUsed", "openItems",
                  "askOnceAnswers", "findings", "atUtc")

SECRET_PATTERNS = (
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"\bBearer\s+[A-Za-z0-9._~+/=-]{12,}",
    r"\b(?:sk|pk)_(?:live|test)_[A-Za-z0-9]{8,}",
    r"\bsk-[A-Za-z0-9]{20,}",
    r"\bAIza[0-9A-Za-z_-]{20,}",
    r"\bgh[pousr]_[A-Za-z0-9]{16,}",
    r"\bxox[baprs]-[A-Za-z0-9-]{10,}",
    r"\brt_[A-Za-z0-9]{20,}",
    r"(?i)\b(?:api[_-]?key|secret[_-]?key|access[_-]?token|refresh[_-]?token|"
    r"password|passwd)\s*[:=]\s*['\"]?[^\s'\"]{8,}",
)
SECRET_RES = tuple(re.compile(p) for p in SECRET_PATTERNS)


class SignalError(ValueError):
    pass


def now_utc():
    return datetime.now(timezone.utc)


def kst(ts):
    return ts.astimezone(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M KST")


def secret_free(text, where):
    if text and any(rx.search(text) for rx in SECRET_RES):
        raise SignalError("secret-like-content:" + where)


def signal_id(payload):
    """Content hash (12 hex): same logical signal -> same id -> dedupe."""
    canon = json.dumps({
        "from": payload.get("from"),
        "kind": payload.get("kind"),
        "summary": payload.get("summary", ""),
        "files": sorted(payload.get("files") or []),
        "parentId": payload.get("parentId"),
    }, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()[:12]


def validate_signal(sig):
    errs = []
    if sig.get("schemaVersion") != SCHEMA:
        errs.append("schemaVersion!=" + SCHEMA)
    if sig.get("from") not in AGENTS:
        errs.append("from-not-in-agents")
    if sig.get("kind") not in KINDS:
        errs.append("kind-unknown")
    if sig.get("lane") is not None and sig.get("lane") not in LANES:
        errs.append("lane-unknown")
    if sig.get("priority") not in PRIORITIES:
        errs.append("priority-unknown")
    if sig.get("evidenceTier") not in EVIDENCE_TIERS:
        errs.append("evidenceTier-unknown")
    if sig.get("status") not in STATUSES:
        errs.append("status-unknown")
    if not isinstance(sig.get("files"), list):
        errs.append("files-not-list")
    budget = sig.get("budget")
    if not (isinstance(budget, dict) and isinstance(budget.get("liveCalls"), int)
            and isinstance(budget.get("restarts"), int)):
        errs.append("budget-missing")
    if sig.get("parentId") is not None and not re.fullmatch(r"[0-9a-f]{12}", sig["parentId"]):
        errs.append("parentId-not-12hex")
    if not re.fullmatch(r"[0-9a-f]{12}", sig.get("id", "")):
        errs.append("id-not-12hex")
    elif sig["id"] != signal_id(sig):
        errs.append("id-content-hash-mismatch")
    for field in HANDOFF_FIELDS:
        if field not in sig:
            errs.append("handoff-field-missing:" + field)
    for text_field in ("summary", "notes"):
        try:
            secret_free(sig.get(text_field), text_field)
        except SignalError as exc:
            errs.append(str(exc))
    return errs


def blank_signal(args):
    ts = now_utc()
    sig = {
        "schemaVersion": SCHEMA,
        "fromTask": args.from_task, "toTask": None, "fromChat": args.from_chat,
        "lastCheckpoint": None, "ownedChanges": [], "smokeUsed": 0,
        "restartUsed": 0, "openItems": [], "askOnceAnswers": [], "findings": [],
        "atUtc": ts.isoformat(),
        "id": None, "parentId": args.parent,
        "from": args.sender, "kind": args.kind or "idea", "lane": args.lane,
        "priority": args.priority, "evidenceTier": args.evidence_tier,
        "summary": args.summary, "notes": args.notes or "",
        "files": args.files or [],
        "budget": {"liveCalls": args.budget_live, "restarts": args.budget_restarts},
        "budgetUsed": {"liveCalls": 0, "restarts": 0},
        "status": "new", "pasteFile": None, "atKst": kst(ts),
        "children": [], "roundtrips": 0,
    }
    sig["id"] = signal_id(sig)
    return sig


def store_dir(root, store):
    return Path(root) / store


def signal_path(store, part, agent, sid):
    base = Path(store) / part
    if part in ("inbox", "outbox"):
        base = base / (agent or "orchestra")
    return base / (sid + ".json")


def find_signal(store, sid):
    base = Path(store)
    for part in ("inbox", "outbox"):
        part_dir = base / part
        if part_dir.is_dir():
            for path in sorted(part_dir.glob("*/" + sid + ".json")):
                return path
    hit = base / "archive" / (sid + ".json")
    return hit if hit.is_file() else None


def load_signal(store, sid_or_path):
    path = Path(sid_or_path)
    if not path.is_file():
        path = find_signal(store, sid_or_path)
    if not path or not path.is_file():
        raise SignalError("signal-not-found:" + str(sid_or_path))
    return json.loads(path.read_text(encoding="utf-8")), path


def iter_signals(store):
    base = Path(store)
    for part in STORE_PARTS:
        part_dir = base / part
        if not part_dir.is_dir():
            continue
        for path in sorted(part_dir.rglob("*.json")):
            try:
                yield json.loads(path.read_text(encoding="utf-8")), path
            except (OSError, ValueError):
                continue


def write_signal(store, part, agent, sig):
    path = signal_path(store, part, agent, sig["id"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(sig, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def cmd_new(args):
    sig = blank_signal(args)
    for field in ("summary", "notes"):
        secret_free(sig.get(field), field)
    errs = validate_signal(sig)
    if errs:
        raise SignalError("invalid:" + ",".join(errs))
    # Dedupe: identical content already stored anywhere in the store.
    for existing, path in iter_signals(args.store):
        if existing.get("id") == sig["id"]:
            return {"schemaVersion": SCHEMA, "action": "new", "stored": False,
                    "duplicateOf": str(path), "id": sig["id"]}
    path = write_signal(args.store, "inbox", args.to, sig)
    return {"schemaVersion": SCHEMA, "action": "new", "stored": True,
            "path": str(path), "id": sig["id"], "signal": sig}


def cmd_validate(args):
    sig, path = load_signal(args.store, args.id or args.file)
    errs = validate_signal(sig)
    return {"schemaVersion": SCHEMA, "action": "validate", "path": str(path),
            "id": sig.get("id"), "valid": not errs, "errors": errs}


def cmd_link(args):
    parent, ppath = load_signal(args.store, args.parent)
    child, cpath = load_signal(args.store, args.child)
    if child["id"] == parent["id"]:
        raise SignalError("self-link")
    child["parentId"] = parent["id"]
    child["roundtrips"] = int(parent.get("roundtrips", 0)) + (
        1 if child.get("from") != parent.get("from") else 0)
    if child["id"] not in parent.setdefault("children", []):
        parent["children"].append(child["id"])
    cpath.write_text(json.dumps(child, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    ppath.write_text(json.dumps(parent, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"schemaVersion": SCHEMA, "action": "link", "parent": parent["id"],
            "child": child["id"], "childRoundtrips": child["roundtrips"]}


def cmd_move(args):
    sig, path = load_signal(args.store, args.id)
    part, _, agent = args.to.partition("/")
    if part not in STORE_PARTS or (part in ("inbox", "outbox") and not agent):
        raise SignalError("bad-destination:" + args.to)
    if args.status:
        if args.status not in STATUSES:
            raise SignalError("bad-status:" + args.status)
        sig["status"] = args.status
    if args.lane:
        if args.lane not in LANES:
            raise SignalError("bad-lane:" + args.lane)
        sig["lane"] = args.lane
    new_path = signal_path(args.store, part, agent, sig["id"])
    new_path.parent.mkdir(parents=True, exist_ok=True)
    new_path.write_text(json.dumps(sig, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if new_path != path:
        path.unlink()
    return {"schemaVersion": SCHEMA, "action": "move", "id": sig["id"],
            "from": str(path), "to": str(new_path), "status": sig["status"]}


def cmd_list(args):
    out = []
    for sig, path in iter_signals(args.store):
        if args.kind and sig.get("kind") != args.kind:
            continue
        if args.status and sig.get("status") != args.status:
            continue
        if args.agent and args.agent not in str(path).replace("\\", "/"):
            continue
        out.append({"id": sig.get("id"), "from": sig.get("from"),
                    "kind": sig.get("kind"), "lane": sig.get("lane"),
                    "status": sig.get("status"), "priority": sig.get("priority"),
                    "summary": (sig.get("summary") or "")[:80],
                    "path": str(path), "atKst": sig.get("atKst")})
    return {"schemaVersion": SCHEMA, "action": "list", "count": len(out),
            "signals": out}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("new", "validate", "link", "move", "list"))
    parser.add_argument("--store", default="data/agent-handoff/orchestra")
    parser.add_argument("--root", default=".")
    parser.add_argument("--id", default=None)
    parser.add_argument("--file", default=None, help="validate: signal json path")
    parser.add_argument("--text-file", default=None,
                        help="new: read summary/notes from a text or PASTE file")
    parser.add_argument("--from", dest="sender", default="user", choices=AGENTS)
    parser.add_argument("--from-task", dest="from_task", default=None)
    parser.add_argument("--from-chat", dest="from_chat", default=None)
    parser.add_argument("--to", default="orchestra",
                        help="new: inbox agent; move: inbox/<agent>|outbox/<agent>|archive")
    parser.add_argument("--agent", default=None,
                        help="list: filter to paths containing this agent name")
    parser.add_argument("--kind", default=None, choices=KINDS,
                        help="new: signal kind (default idea); list: filter")
    parser.add_argument("--lane", default=None, choices=LANES)
    parser.add_argument("--priority", default="P1", choices=PRIORITIES)
    parser.add_argument("--evidence-tier", dest="evidence_tier",
                        default="evidence_needed", choices=EVIDENCE_TIERS)
    parser.add_argument("--summary", default="")
    parser.add_argument("--notes", default=None)
    parser.add_argument("--files", nargs="*", default=None)
    parser.add_argument("--parent", default=None)
    parser.add_argument("--child", default=None)
    parser.add_argument("--status", default=None)
    parser.add_argument("--budget-live", dest="budget_live", type=int, default=0)
    parser.add_argument("--budget-restart", dest="budget_restarts", type=int, default=0)
    args = parser.parse_args()

    if args.text_file:
        body = Path(args.text_file).read_text(encoding="utf-8-sig")
        if not args.summary:
            args.summary = body.splitlines()[0][:200] if body.splitlines() else ""
        if args.notes is None:
            args.notes = body
    try:
        handler = {"new": cmd_new, "validate": cmd_validate, "link": cmd_link,
                   "move": cmd_move, "list": cmd_list}[args.action]
        result = handler(args)
    except (SignalError, OSError, ValueError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "action": args.action,
                          "status": "error", "reason": str(exc)},
                         ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
