"""Failure report archive registry for multi-agent failures on this checkout.

Classifies agent failure/error reports with a Genshin-artifact-style taxonomy
(SSOT: docs/agent-tooling/failure-archive-taxonomy.md): slot = reporting agent,
set = subsystem domain, main stat = failure category, sub stats =
severity/reproducibility/recoverability/evidence_tier, status = preservation
state (LOCKED_ACTIVE / ENHANCED_RESOLVED / FODDER_SUPERSEDED /
ARCHIVED_MUSEUM). Storage is append-friendly JSONL at
data/agent-handoff/failure-archive/failures.jsonl plus one pretty record per
entry under records/. TASK_FAILURE (goal missed, verification failed, false
completion claim) is kept distinct from RUNTIME_ERROR (crash, build abort,
unhandled exception) so a mission failure is never silently relabeled a system
error. Secret-looking values are refused; env names only.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
import tempfile
import uuid

BASE = "data/agent-handoff/failure-archive"
REGISTRY = BASE + "/failures.jsonl"
SCHEMA = "awx.failure_archive.v1"

SLOTS = ("CODEX", "DEVIN", "GROK", "CLEAN", "AGY", "GPTPRO")
SETS = ("rag-core", "display-meta", "oauth-auth", "lms-runtime",
        "ops-tooling", "api-provider")
MAIN_STATS = ("TASK_FAILURE", "RUNTIME_ERROR", "TOOL_DEFECT", "LOCK_COLLISION",
              "SPEC_API_DRIFT", "GUARD_BREACH")
SEVERITIES = ("CRITICAL", "HIGH", "MEDIUM", "LOW")
REPROS = ("DETERMINISTIC", "INTERMITTENT", "ENV_DEPENDENT")
RECOVERABILITIES = ("AUTO_RETRYABLE", "PATCH_REQUIRED", "USER_DECISION")
EVIDENCE_TIERS = ("live_success", "degraded_honest", "provider_direct_only",
                  "auth_blocked", "not_observed")
STATUSES = ("LOCKED_ACTIVE", "ENHANCED_RESOLVED", "FODDER_SUPERSEDED",
            "ARCHIVED_MUSEUM")

MAIN_STAT_LABELS = {
    "TASK_FAILURE": "임무 실패",
    "RUNTIME_ERROR": "시스템 에러",
    "TOOL_DEFECT": "도구 결함",
    "LOCK_COLLISION": "선점 충돌",
    "SPEC_API_DRIFT": "API/스펙 드리프트",
    "GUARD_BREACH": "가드 위반",
}

MAX_FIELD = 2000
MAX_REGISTRY_BYTES = 8 * 1024 * 1024
SECRET_HINTS = re.compile(
    r"(sk-[A-Za-z0-9]{20,}|xox[baprs]-|ghp_[A-Za-z0-9]{20,}|"
    r"AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|"
    r"api[_-]?key\s*[:=]\s*\S{12,}|password\s*[:=]\s*\S+|"
    r"Bearer\s+[A-Za-z0-9._-]{20,})", re.IGNORECASE)
RECORD_ID = re.compile(r"^FA-\d{8}-[0-9A-F]{4}$")


class ArchiveError(ValueError):
    pass


def fail(reason):
    raise ArchiveError(reason)


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def root_path(value):
    root = Path(value).resolve()
    if not root.is_dir():
        fail("root-not-directory")
    return root


def registry_path(root):
    return root / REGISTRY


def check_text(name, value, required=True):
    if value is None:
        if required:
            fail(name + "-required")
        return None
    text = str(value).strip()
    if required and not text:
        fail(name + "-required")
    if len(text) > MAX_FIELD:
        fail(name + "-too-long")
    if SECRET_HINTS.search(text):
        fail(name + "-secret-like")
    return text


def read_records(root):
    path = registry_path(root)
    if not path.exists():
        return []
    if path.stat().st_size > MAX_REGISTRY_BYTES:
        fail("registry-oversize")
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except ValueError:
            fail("registry-corrupt-line")
        if not isinstance(row, dict) or row.get("schemaVersion") != SCHEMA:
            fail("registry-schema-mismatch")
        records.append(row)
    return records


def write_records(root, records):
    path = registry_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    (path.parent / "records").mkdir(exist_ok=True)
    payload = "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n"
                      for r in records)
    data = payload.encode("utf-8")
    if len(data) > MAX_REGISTRY_BYTES:
        fail("registry-oversize")
    with tempfile.NamedTemporaryFile("wb", dir=str(path.parent), delete=False,
                                     prefix="failures-", suffix=".tmp") as tmp:
        tmp.write(data)
        tmp_name = tmp.name
    Path(tmp_name).replace(path)
    for record in records:
        (path.parent / "records" / (record["id"] + ".json")).write_text(
            json.dumps(record, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")


def find(records, record_id):
    ident = str(record_id).strip().upper()
    if not RECORD_ID.match(ident):
        fail("invalid-record-id")
    matches = [r for r in records if r.get("id") == ident]
    if not matches:
        fail("record-not-found:" + ident)
    return matches[0]


def new_id(records):
    today = datetime.now(timezone.utc).strftime("%Y%m%d")
    existing = {r.get("id") for r in records}
    for _ in range(64):
        ident = "FA-%s-%s" % (today, uuid.uuid4().hex[:4].upper())
        if ident not in existing:
            return ident
    fail("id-exhausted")


def cmd_record(root, args):
    records = read_records(root)
    record = {
        "schemaVersion": SCHEMA,
        "id": new_id(records),
        "recordedAtUtc": utcnow(),
        "slot": args.agent,
        "set": args.domain,
        "mainStat": args.category,
        "mainStatLabel": MAIN_STAT_LABELS[args.category],
        "title": check_text("title", args.title),
        "evidence": check_text("evidence", args.evidence),
        "subStats": {
            "severity": args.severity,
            "reproducibility": args.repro,
            "recoverability": args.recoverability,
            "evidenceTier": args.evidence_tier,
        },
        "status": "LOCKED_ACTIVE",
        "statusHistory": [{
            "to": "LOCKED_ACTIVE", "at": utcnow(), "note": "recorded",
        }],
    }
    records.append(record)
    write_records(root, records)
    return {"action": "record", "record": record}


def card(record):
    sub = record.get("subStats", {})
    return ("[%s] %s | slot=%s set=%s | %s(%s) | sev=%s repro=%s rec=%s ev=%s | %s"
            % (record.get("status"), record.get("id"), record.get("slot"),
               record.get("set"), record.get("mainStat"),
               record.get("mainStatLabel", ""),
               sub.get("severity"), sub.get("reproducibility"),
               sub.get("recoverability"), sub.get("evidenceTier"),
               record.get("title")))


def cmd_list(root, args):
    records = read_records(root)
    def keep(r):
        if args.status != "ALL" and r.get("status") != args.status:
            return False
        if args.agent and r.get("slot") != args.agent:
            return False
        if args.domain and r.get("set") != args.domain:
            return False
        if args.category and r.get("mainStat") != args.category:
            return False
        return True
    selected = [r for r in records if keep(r)]
    if args.json:
        return {"action": "list", "count": len(selected), "records": selected}
    if not selected:
        return {"action": "list", "count": 0,
                "text": "(no records match)"}
    lines = ["Failure archive — %d record(s)" % len(selected)]
    lines += [card(r) for r in selected]
    return {"action": "list", "count": len(selected), "text": "\n".join(lines)}


def cmd_show(root, args):
    record = find(read_records(root), args.id)
    if args.json:
        return {"action": "show", "record": record}
    sub = record.get("subStats", {})
    text = [
        "id: %s" % record.get("id"),
        "status: %s" % record.get("status"),
        "slot(agent): %s" % record.get("slot"),
        "set(domain): %s" % record.get("set"),
        "mainStat(category): %s (%s)" % (record.get("mainStat"),
                                         record.get("mainStatLabel", "")),
        "title: %s" % record.get("title"),
        "evidence: %s" % record.get("evidence"),
        "subStats: severity=%s reproducibility=%s recoverability=%s evidenceTier=%s"
        % (sub.get("severity"), sub.get("reproducibility"),
           sub.get("recoverability"), sub.get("evidenceTier")),
        "recordedAtUtc: %s" % record.get("recordedAtUtc"),
    ]
    for entry in record.get("statusHistory", []):
        extra = entry.get("by") or entry.get("reason") or entry.get("note") or ""
        text.append("history: %s -> %s at %s %s"
                    % (entry.get("from", "-"), entry.get("to"),
                       entry.get("at"), extra))
    return {"action": "show", "text": "\n".join(text)}


def transition(root, args, to_status, extra_fields):
    records = read_records(root)
    record = find(records, args.id)
    if record.get("status") in ("ENHANCED_RESOLVED", "FODDER_SUPERSEDED",
                                "ARCHIVED_MUSEUM"):
        fail("record-terminal-status")
    entry = {"from": record.get("status"), "to": to_status, "at": utcnow()}
    entry.update(extra_fields)
    record["status"] = to_status
    record.setdefault("statusHistory", []).append(entry)
    write_records(root, records)
    return {"action": args.command, "record": record}


def cmd_resolve(root, args):
    return transition(root, args, "ENHANCED_RESOLVED",
                      {"by": check_text("by", args.by),
                       "note": check_text("note", args.note)})


def cmd_supersede(root, args):
    return transition(root, args, "FODDER_SUPERSEDED",
                      {"reason": check_text("reason", args.reason)})


def build_parser():
    parser = argparse.ArgumentParser(
        description="Artifact-style failure report archive registry.")
    parser.add_argument("--root", default=".", help="repository root")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("record", help="register a new failure record")
    p.add_argument("--agent", required=True, choices=SLOTS)
    p.add_argument("--category", required=True, choices=MAIN_STATS)
    p.add_argument("--domain", required=True, choices=SETS)
    p.add_argument("--title", required=True)
    p.add_argument("--evidence", required=True,
                   help="evidence ref/path or short description (no secrets)")
    p.add_argument("--repro", required=True, choices=REPROS)
    p.add_argument("--severity", required=True, choices=SEVERITIES)
    p.add_argument("--recoverability", default="PATCH_REQUIRED",
                   choices=RECOVERABILITIES)
    p.add_argument("--evidence-tier", default="not_observed",
                   choices=EVIDENCE_TIERS)
    p.set_defaults(func=cmd_record)

    p = sub.add_parser("list", help="list records as artifact cards")
    p.add_argument("--category", choices=MAIN_STATS)
    p.add_argument("--agent", choices=SLOTS)
    p.add_argument("--domain", choices=SETS)
    p.add_argument("--status", default="LOCKED_ACTIVE",
                   choices=list(STATUSES) + ["ALL"])
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("show", help="show one record")
    p.add_argument("id", help="FA-YYYYMMDD-XXXX")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_show)

    p = sub.add_parser("resolve", help="mark a record ENHANCED_RESOLVED")
    p.add_argument("id")
    p.add_argument("--by", required=True,
                   help="resolving task/commit ref, e.g. taskId or sha")
    p.add_argument("--note", required=True)
    p.set_defaults(func=cmd_resolve)

    p = sub.add_parser("supersede", help="mark a record FODDER_SUPERSEDED")
    p.add_argument("id")
    p.add_argument("--reason", required=True)
    p.set_defaults(func=cmd_supersede)
    return parser


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    args = build_parser().parse_args(argv)
    try:
        root = root_path(args.root)
        result = args.func(root, args)
    except ArchiveError as err:
        print(json.dumps({"status": "error", "reason": str(err)},
                         ensure_ascii=False))
        return 2
    if "text" in result:
        print(result["text"])
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
