"""Offline task-scoped derived context. Original evidence is never rewritten."""
from __future__ import annotations

import argparse
from contextlib import redirect_stderr
from datetime import datetime, timezone, timedelta
import hashlib
import heapq
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

import awx_paths
from log_redact import redact_text

JOURNAL_LIMIT = 4 * 1024 * 1024
EVENT_LIMIT = 2000
RECEIPT_LIMIT = 200
JSON_LIMIT = 64 * 1024
MD_LIMIT = 12 * 1024
TASK_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\Z")
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")


class ContextError(Exception):
    def __init__(self, reason, code=2):
        super().__init__(reason)
        self.code = code


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def clean(value):
    text = str(value or "")
    text = re.sub(r"(?is)\b(prompt|response|conversation|messages|raw_body)\s*[:=].*", r"\1=[OMITTED]", text)
    text = redact_text(text)[0]
    text = re.sub(r"\bsk-[A-Za-z0-9_-]+", "[REDACTED]", text)
    text = re.sub(r"(?i)[A-Z]:[\\/][^\s|<>\"']*|\\\\[^\s|<>\"']+|/(?:Users|home)/[^\s|<>\"']*", "[PATH]", text)
    return re.sub(r"[\x00-\x1f\x7f|]", " ", text)[:240]


def safe_path(base, name):
    """No traversal, credential paths, or links escaping the selected root."""
    value = str(name).replace("\\", "/")
    parts = value.split("/")
    if not value or value.startswith("/") or ":" in value or any(p in ("", ".", "..") for p in parts):
        raise ContextError("unsafe-relative-path")
    if any(p.casefold().startswith((".env", ".secrets")) for p in parts):
        raise ContextError("protected-path")
    if Path(value).suffix.lower() in (".pem", ".key", ".p12", ".pfx", ".jks", ".db"):
        raise ContextError("protected-path")
    path = base.joinpath(*parts)
    if not path.resolve().is_relative_to(base.resolve()):
        raise ContextError("path-outside-root")
    return path


def locations(root, task=None):
    root = Path(root).resolve()
    if task is not None and (not TASK_RE.fullmatch(task) or task in (".", "..")):
        raise ContextError("invalid-task-id")
    registry_file = root / "configs/agent-paths.yaml"
    registry = awx_paths.load_registry(registry_file if registry_file.exists() else None)
    selected = {}
    for key in ("journal.base", "handoff.root"):
        entry = dict(registry[key])
        for field in ("path",):
            if entry.get(field) and not Path(entry[field]).is_absolute():
                entry[field] = str(root / entry[field])
        entry["old_paths"] = [str(root / p) if not Path(p).is_absolute() else p for p in entry.get("old_paths", [])]
        with redirect_stderr(io.StringIO()):
            path = awx_paths.resolve(key, registry={key: entry})
        selected[key] = (root / path).resolve() if not path.is_absolute() else path.resolve()
    directory = safe_path(selected["journal.base"], task) if task else None
    return root, selected["journal.base"], selected["handoff.root"], directory


def read_doc(path, label, sources, counts):
    try:
        with path.open("rb") as stream:
            raw = stream.read(JOURNAL_LIMIT + 1)
        if len(raw) > JOURNAL_LIMIT:
            counts["oversize"] += 1
            counts["unreadable"] += 1
            sources.append({"path": clean(label), "state": "unreadable", "reason": "size-cap"})
            return None
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError()
        sources.append({"path": clean(label), "sha256": sha(raw), "bytes": len(raw), "state": "read"})
        return value
    except FileNotFoundError:
        state = "missing"
    except (OSError, ValueError, UnicodeError):
        state = "unreadable"
    counts[state] += 1
    sources.append({"path": clean(label), "state": state})
    return None


def binding(root, name, expected, absent=False):
    expected = expected if isinstance(expected, str) and SHA_RE.fullmatch(expected) else None
    result = {"path": clean(name), "sha256": expected, "absent": absent, "currentApplicable": None}
    try:
        path = safe_path(root, name)
        if result["path"] != name:
            raise ContextError("redacted-path")
        if absent:
            result["currentApplicable"] = not path.exists()
        elif isinstance(expected, str) and SHA_RE.fullmatch(expected):
            result["currentApplicable"] = path.is_file() and sha(path.read_bytes()) == expected
    except (OSError, ContextError):
        pass
    return result


def evidence_row(root, name, receipt, code, bindings, valid=True, tier=None):
    applicable = (all(b["currentApplicable"] is True for b in bindings)
                  if bindings and all(b["currentApplicable"] is not None for b in bindings) and valid else None)
    if valid and any(b["currentApplicable"] is False for b in bindings):
        applicable = False
    code = code if type(code) is int else None
    passed = valid and code == 0 and receipt in ("VERIFIED_PASS", "verified")
    state = ("STALE_PASS" if applicable is False else "VERIFIED_PASS" if applicable is True else "UNKNOWN") if passed else (
        "NOT_RUN" if code is None else "FAILED" if code != 0 else "UNKNOWN")
    return {"item": clean(name), "reported": None, "exitCode": code if type(code) is int else None,
            "receipt": {"status": clean(receipt), "source": tier or "coop", "valid": valid},
            "currentApplicable": applicable, "state": state, "bindings": bindings}


def collect(root, task, checkpoint_root=None, coop_store=None):
    root, base, handoff, directory = locations(root, task)
    sources, rows = [], []
    counts = dict.fromkeys(("missing", "unreadable", "unknown", "oversize", "eventsOmitted", "receiptsOmitted"), 0)
    journal = read_doc(directory / "journal.json", "journal/journal.json", sources, counts)
    if journal and (journal.get("taskId") != task or journal.get("schemaVersion") != "awx.work_journal.v1"):
        counts["unknown"] += 1
        journal = None
    journal = journal or {}
    events = journal.get("events", [])
    if not isinstance(events, list):
        counts["unknown"] += 1
        events = []
    counts["eventsOmitted"] = max(0, len(events) - EVENT_LIMIT)
    selected_events = events[-EVENT_LIMIT:]
    event_views = []
    for event in reversed(selected_events):
        if not isinstance(event, dict):
            counts["unknown"] += 1
            continue
        text = clean(event.get("text"))
        event_views.append({"at": clean(event.get("at")), "kind": clean(event.get("kind")), "text": text})
        if event.get("kind") == "verify" or re.search(r"\b(PASS|FAIL|NOT_RUN)\b", text):
            reported = "PASS" if re.search(r"\bPASS\b", text) else "FAIL" if re.search(r"\bFAIL\b", text) else text
            rows.append({"item": text, "reported": reported, "exitCode": None, "receipt": None,
                         "currentApplicable": None, "state": "NOT_RUN", "bindings": []})

    store = Path(coop_store) if coop_store else handoff / "coop-verify"
    state = read_doc(store / "state.json", "coop/state.json", sources, counts) or {}
    tickets = state.get("tickets", {})
    if not isinstance(tickets, dict):
        tickets = {}
        counts["unknown"] += 1
    matching = [(key, t) for key, t in tickets.items()
                if isinstance(t, dict) and t.get("taskId") == task and t.get("ticketId") == key]
    counts["receiptsOmitted"] = max(0, len(matching) - RECEIPT_LIMIT)
    for key, ticket in sorted(matching, key=lambda pair: str(pair[1].get("updatedAtUtc", "")), reverse=True)[:RECEIPT_LIMIT]:
        try:
            receipt_path = safe_path(store, ticket.get("receiptPath", ""))
        except ContextError:
            counts["unknown"] += 1
            continue
        receipt = read_doc(receipt_path, "coop/" + ticket["receiptPath"], sources, counts)
        if not receipt:
            continue
        before, after = receipt.get("inputManifestBefore", {}), receipt.get("inputManifestAfter", {})
        entries = after.get("entries", []) if isinstance(after, dict) else []
        valid = (receipt.get("ticketId") == key and receipt.get("cwd") == str(root)
                 and receipt.get("schemaVersion") == "awx.coop-verify.v1.receipt"
                 and isinstance(before, dict) and isinstance(after, dict) and isinstance(entries, list)
                 and isinstance(ticket.get("verifyCommand"), list) and bool(ticket["verifyCommand"])
                 and receipt.get("verifyCommand") == ticket["verifyCommand"])
        if valid:
            for manifest in (before, after):
                valid = valid and manifest.get("manifestSha256") == sha(json.dumps(manifest.get("entries"), sort_keys=True, ensure_ascii=True).encode())
            valid = valid and before.get("manifestSha256") == after.get("manifestSha256") and not receipt.get("interferingWriters")
        bindings = [binding(root, e.get("path", ""), e.get("sha256"), e.get("missing") is True)
                    for e in entries if isinstance(e, dict)] if isinstance(entries, list) else []
        valid = valid and len(bindings) == len(entries) and not any(e.get("truncated") for e in entries if isinstance(e, dict))
        scope = ticket.get("scope", [])
        scope = [s.replace("\\", "/").rstrip("/").casefold() for s in scope if isinstance(s, str)] if isinstance(scope, list) else []
        valid = bool(valid and scope and all(any(
            b["path"].casefold() == s or b["path"].casefold().startswith(s + "/") or s == "."
            for s in scope) for b in bindings))
        if not valid:
            counts["unknown"] += 1
        rows.append(evidence_row(root, key, receipt.get("verdict"), receipt.get("exitCode"), bindings, valid))

    checkpoint_directory = Path(checkpoint_root or os.environ.get("AWX_TASK_CONTEXT_CHECKPOINT_ROOT", directory))
    decision = read_doc(directory / "decision.json", "journal/decision.json", sources, counts) or {}
    expected_goal = decision.get("goalId", task)
    if not checkpoint_directory.is_dir():
        counts["unreadable"] += 1
        sources.append({"path": "checkpoints/", "state": "unreadable"})
    else:
        # Only the selected task subtree; skip derived context and byte backups.
        checkpoint_limit = max(0, RECEIPT_LIMIT - min(len(matching), RECEIPT_LIMIT))
        found = []
        for parent, dirs, files in os.walk(checkpoint_directory, followlinks=False):
            dirs[:] = [d for d in dirs if d not in ("context", "before", "preimage") and not Path(parent, d).is_symlink()]
            if "checkpoint.json" in files:
                found.append(Path(parent) / "checkpoint.json")
                if len(found) > checkpoint_limit:
                    counts["receiptsOmitted"] += 1
                    break
        for path in found[:checkpoint_limit]:
            label = "checkpoints/" + path.relative_to(checkpoint_directory).as_posix()
            checkpoint = read_doc(path, label, sources, counts)
            manifest = read_doc(path.parent / "manifest.json", label.replace("checkpoint.json", "manifest.json"), sources, counts)
            if not checkpoint or not manifest:
                continue
            manifest_decision = manifest.get("decision")
            valid = (isinstance(manifest_decision, dict) and checkpoint.get("goalId") == expected_goal
                     and manifest_decision.get("goalId") == expected_goal
                     and manifest.get("root") == str(root) and manifest.get("version") == 1
                     and checkpoint.get("manifestSha256") == sources[-1].get("sha256"))
            if not valid:
                counts["unknown"] += 1
                continue
            postimages = checkpoint.get("postimages", {})
            targets = manifest.get("targets", [])
            valid = isinstance(postimages, dict) and isinstance(targets, list)
            if not valid:
                counts["unknown"] += 1
                continue
            valid = set(postimages) == {t.get("path") for t in targets if isinstance(t, dict)}
            bindings = [binding(root, name, value, value is None) for name, value in postimages.items()]
            rows.append(evidence_row(root, label, checkpoint.get("status"), checkpoint.get("verificationExitCode"),
                                     bindings, valid, checkpoint.get("verificationEvidenceMode", "checkpoint")))

    stale = sorted({b["path"] for r in rows for b in r["bindings"] if b["currentApplicable"] is False})
    result = {"schemaVersion": "awx.task-context.v1", "taskId": task,
              "projectRoot": {"alias": "project", "sha256": sha(str(root).encode())},
              "goal": clean(journal.get("purpose")), "taskStatus": clean(journal.get("status", "unknown")),
              "builtAtUtc": datetime.now(timezone.utc).isoformat(), "events": event_views,
              "state": "STALE_PASS" if any(r["state"] == "STALE_PASS" for r in rows) else "EVIDENCE_ONLY",
              "verification": rows, "sources": sources, "counts": counts,
              "truncated": any(counts[k] for k in ("oversize", "eventsOmitted", "receiptsOmitted")),
              "nextCheck": stale or ["Run the task's focused verification; reported text alone is not proof."],
              "warnings": ["Dirty/foreign lease state: not_observed; inspect ownership before editing."]}
    return result


def markdown(result):
    events = result["events"]
    last = events[0]["at"] if events else ""
    try:
        last = datetime.fromisoformat(last.replace("Z", "+00:00")).astimezone(timezone(timedelta(hours=9))).isoformat()
    except ValueError:
        last = "unknown"
    lines = ["# Current goal", result["goal"] or "unknown", "", "## Status",
             result["taskStatus"] + "; last event KST: " + last,
             "Evidence state: " + result["state"], "", "## Verification",
             "| Item | reported | exitCode | receipt | currentApplicable |",
             "|---|---|---|---|---|"]
    for row in result["verification"]:
        receipt = row["receipt"]
        lines.append("| " + " | ".join(str(v) for v in (
            clean(row["item"]), row["reported"], row["exitCode"],
            receipt["status"] if receipt else None, row["currentApplicable"])) + " |")
    lines += ["", "## STALE and failure history"]
    repeated = {}
    for row in result["verification"]:
        if row["state"] in ("STALE_PASS", "FAILED") or row["reported"] == "FAIL":
            key = (row["item"], row["state"])
            repeated[key] = repeated.get(key, 0) + 1
    lines += [f"- {clean(key[0])}: {key[1]}; attempts={count}" for key, count in repeated.items()]
    lines += ["", "## Next check"] + ["- " + clean(s) for s in result["nextCheck"]]
    lines += ["", "## Warnings"] + ["- " + clean(s) for s in result["warnings"]]
    lines += ["- truncated=" + str(result["truncated"]).lower() + "; counts=" + json.dumps(result["counts"], sort_keys=True)]
    lines += ["", "## Sources"]
    lines += ["- " + s["path"] + " " + s.get("sha256", s["state"])[:12] for s in result["sources"]]
    return "\n".join(lines) + "\n"


def bounded(result):
    unique = {}
    for row in result["verification"]:
        for item in row.pop("bindings"):
            item["id"] = sha(encoded([item["path"], item["sha256"], item["absent"]]))
            row.setdefault("bindingIds", []).append(item["id"])
            unique[(item["path"], item["sha256"], item["absent"])] = item
    bindings = list(unique.values())
    result["counts"]["bindingsOmitted"] = max(0, len(bindings) - 128)
    result["bindings"] = bindings[:128]
    result["truncated"] |= bool(result["counts"]["bindingsOmitted"])
    for key in ("events", "verification", "sources", "nextCheck"):
        result["counts"].setdefault(key + "Omitted", 0)
    # Remove oldest projections until BOTH serialized limits fit. Originals stay untouched.
    while len(encoded(result)) > JSON_LIMIT or len(markdown(result).encode("utf-8")) > MD_LIMIT:
        key = next((k for k in ("events", "verification", "sources", "nextCheck")
                    if len(result[k]) > (1 if k == "nextCheck" else 0)), None)
        if key is None:
            result["counts"]["bindingsOmitted"] += len(result["bindings"])
            result["bindings"] = []
            break
        result[key].pop()
        result["counts"][key + "Omitted"] += 1
        result["truncated"] = True
    return result


def _write_file(path, data):
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def validate_revision(directory, manifest):
    if not isinstance(manifest, dict) or set(manifest.get("files", {})) != {"context.json", "context.md", "sources.json"}:
        raise ContextError("CORRUPT", 20)
    for name, item in manifest["files"].items():
        path = safe_path(directory, name)
        limit = MD_LIMIT if name == "context.md" else JSON_LIMIT if name == "context.json" else JOURNAL_LIMIT
        if path.stat().st_size > limit:
            raise ContextError("CORRUPT", 20)
        raw = path.read_bytes()
        if len(raw) != item.get("bytes") or sha(raw) != item.get("sha256"):
            raise ContextError("CORRUPT", 20)


def publish(root, task, collect_result):
    directory = locations(root, task)[3]
    context = safe_path(directory, "context")
    context.mkdir(parents=True, exist_ok=True)
    lock = context / ".build.lock"
    try:
        fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        # Unknown or dead owners are never removed blindly; no age-based stealing.
        raise ContextError("BUSY: inspect the lock owner PID before recovery", 3)
    lock_owner = encoded({"pid": os.getpid(), "nonce": uuid.uuid4().hex})
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(lock_owner)
        result = collect_result()
        numbers = [int(p.name[1:]) for p in context.iterdir() if re.fullmatch(r"r[0-9]{6}", p.name)]
        number = max(numbers, default=0) + 1
        if number > 999999:
            raise ContextError("revision-limit")
        revision = f"r{number:06d}"
        temporary = context / (".tmp-" + uuid.uuid4().hex)
        temporary.mkdir()
        artifacts = {"context.json": encoded(result), "context.md": markdown(result).encode("utf-8"),
                     "sources.json": encoded({"sources": result["sources"], "bindings": result["bindings"],
                                              "bindingCoverageIncomplete": bool(result["counts"]["bindingsOmitted"])})}
        manifest = {"schemaVersion": "awx.task-context.manifest.v1", "taskId": task,
                    "files": {name: {"bytes": len(raw), "sha256": sha(raw)} for name, raw in artifacts.items()}}
        for name, raw in artifacts.items():
            _write_file(temporary / name, raw)
        manifest_bytes = encoded(manifest)
        _write_file(temporary / "manifest.json", manifest_bytes)
        validate_revision(temporary, manifest)
        temporary.rename(context / revision)
        pointer = context / (".current-" + uuid.uuid4().hex + ".tmp")
        _write_file(pointer, encoded({"revision": revision, "manifestSha256": sha(manifest_bytes)}))
        os.replace(pointer, context / "current.json")
        return result
    finally:
        if lock.exists() and lock.read_bytes() == lock_owner:
            lock.unlink()


def build(root, task, dry_run=False, **kwargs):
    collect_result = lambda: bounded(collect(root, task, **kwargs))
    return collect_result() if dry_run else publish(root, task, collect_result)

def load_current(root, task):
    directory = locations(root, task)[3] / "context"
    pointer_path = directory / "current.json"
    if not pointer_path.exists():
        raise ContextError("No context; run build --task " + task, 4)
    try:
        if pointer_path.stat().st_size > JSON_LIMIT:
            raise ContextError("CORRUPT", 20)
        pointer = json.loads(pointer_path.read_bytes())
        revision = pointer["revision"]
        if not isinstance(revision, str) or not re.fullmatch(r"r[0-9]{6}", revision):
            raise ContextError("CORRUPT", 20)
        path = safe_path(directory, revision)
        manifest_path = safe_path(path, "manifest.json")
        if manifest_path.stat().st_size > JSON_LIMIT:
            raise ContextError("CORRUPT", 20)
        raw = manifest_path.read_bytes()
        manifest = json.loads(raw)
        if sha(raw) != pointer["manifestSha256"] or manifest.get("taskId") != task:
            raise ContextError("CORRUPT", 20)
        validate_revision(path, manifest)
        document = json.loads((path / "context.json").read_bytes())
        if document["taskId"] != task or document["projectRoot"]["sha256"] != sha(str(Path(root).resolve()).encode()):
            raise ContextError("CORRUPT", 20)
        return path, document
    except (OSError, ValueError, KeyError, TypeError, AttributeError, ContextError) as exc:
        if isinstance(exc, ContextError) and exc.code == 4:
            raise
        raise ContextError("CORRUPT", 20) from None


def verify(root, task):
    try:
        path, document = load_current(root, task)
        sources = json.loads((path / "sources.json").read_bytes())
        bindings = sources["bindings"]
        if not isinstance(bindings, list):
            raise ValueError()
        checks = [binding(Path(root).resolve(), b["path"], b["sha256"], b["absent"]) for b in bindings]
        stale = sorted({b["path"] for b in checks if b["currentApplicable"] is not True})
        state = "STALE" if stale or sources.get("bindingCoverageIncomplete") else "FRESH"
        return {"state": state, "exitCode": 10 if state == "STALE" else 0, "changed": stale,
                "scope": "revision-integrity-and-recorded-target-hashes; not task completion"}
    except (ContextError, OSError, ValueError, KeyError, TypeError):
        return {"state": "CORRUPT", "exitCode": 20, "changed": []}


def list_contexts(root, since_hours=None, limit=20):
    if limit <= 0:
        return []
    limit = min(limit, 200)
    base = locations(root)[1]
    cutoff = (datetime.now(timezone.utc).timestamp() - since_hours * 3600
              if since_hours is not None else float("-inf"))
    def hints():
        if not base.is_dir():
            return
        with os.scandir(base) as entries:
            for item in entries:
                if not item.is_dir(follow_symlinks=False) or not TASK_RE.fullmatch(item.name):
                    continue
                pointer = Path(item.path) / "context/current.json"
                try:
                    timestamp = pointer.stat().st_mtime
                    if timestamp >= cutoff and pointer.stat().st_size <= JSON_LIMIT:
                        yield timestamp, item.name, pointer
                except OSError:
                    continue
    rows = []
    for stamp, task, path in heapq.nlargest(limit, hints()):
        try:
            pointer = json.loads(path.read_bytes())
            revision = pointer.get("revision")
            if isinstance(revision, str) and re.fullmatch(r"r[0-9]{6}", revision):
                rows.append({"taskId": task, "revision": revision, "modifiedAt": stamp})
        except (OSError, ValueError, AttributeError):
            continue
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=os.environ.get("AWX_ROOT", str(Path(__file__).resolve().parents[1])))
    commands = parser.add_subparsers(dest="command", required=True)
    for action in ("build", "show", "verify", "list"):
        sub = commands.add_parser(action)
        sub.add_argument("--root", default=argparse.SUPPRESS)
        sub.add_argument("--json", action="store_true")
        if action != "list":
            sub.add_argument("--task", required=True)
        if action == "build":
            sub.add_argument("--dry-run", action="store_true")
            sub.add_argument("--checkpoint-root", help="Explicit selected task checkpoint directory (AWX_TASK_CONTEXT_CHECKPOINT_ROOT)")
            sub.add_argument("--coop-store")
        if action == "list":
            sub.add_argument("--since-hours", type=float)
            sub.add_argument("--limit", type=int, default=20)
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            result = build(args.root, args.task, dry_run=args.dry_run,
                           checkpoint_root=args.checkpoint_root, coop_store=args.coop_store)
            print(encoded(result).decode("utf-8").rstrip() if args.json else markdown(result), end="\n" if args.json else "")
        elif args.command == "show":
            directory, document = load_current(args.root, args.task)
            sys.stdout.write((directory / ("context.json" if args.json else "context.md")).read_text(encoding="utf-8"))
        elif args.command == "verify":
            result = verify(args.root, args.task)
            print(json.dumps(result, ensure_ascii=True) if args.json else result["state"])
            return result["exitCode"]
        else:
            print(json.dumps(list_contexts(args.root, args.since_hours, args.limit), ensure_ascii=True))
        return 0
    except ContextError as exc:
        print(str(exc), file=sys.stderr)
        return exc.code
    except (OSError, ValueError, TypeError, KeyError, RuntimeError):
        print("task-context: unreadable input or invalid metadata", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
