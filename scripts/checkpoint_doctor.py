#!/usr/bin/env python3
"""Checkpoint state doctor (read-only).

Answers "where is this checkpoint run stuck and what is the next command".

    python -B scripts/checkpoint_doctor.py --run <cycle-dir> [--root .]
    python -B scripts/checkpoint_doctor.py --latest        # newest run dir

Prints: status, firstBlockingRule, lease path + seconds-to-expiry,
target count, and ONE next-command hint. Emits a warning when the run
is sealed-but-not-finished or when the bound lease is near expiry —
"seal/finish must run before the lease expires".

Exit 0 always for a readable run; 2 for usage/IO errors.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

RUN_PREFIX = "data/agent-handoff/"
CONTINUITY_SCHEMA = "awx.task-continuity.v1"
CONTRACT_PREFIX = "continuity: "
MAX_STATE_BYTES = 128 * 1024
FIELDS = {"schemaVersion", "taskId", "revision", "instructionRef", "observedAt", "goal",
          "nonGoals", "taskStatus", "supersedes", "deliverables", "remaining", "blockers",
          "nextAction", "accessFailures", "preferenceEvents"}
DELIVERY_STATUSES = {"NOT_STARTED", "READY", "BLOCKED", "VERIFIED", "FOUND_VERIFIED",
                     "CANCELLED", "SUPERSEDED", "PARTIAL", "NOT_RUN", "UNKNOWN", "NOT_VERIFIED"}
EVENT_FIELDS = {"eventId", "sourceRef", "actorVerified", "taskId", "actionCategory", "artifactType",
                "destinationClass", "intentEvidence", "outcome", "observedAt", "supersedes", "scope"}


def instruction_slots(text, source_type):
    """Tagged fixture/intake helper only; arbitrary language must be reconciled by the caller."""
    if source_type != "user":
        return {}
    slots = {}
    for line in text.splitlines():
        key, separator, value = line.partition(":")
        if separator and key in {"goal", "destination", "status", "stop"}:
            if key in slots or not value.strip():
                raise ValueError("instruction-slot-ambiguous")
            slots[key] = value.strip()
    return slots


def preference_default(events, scope, artifact_type, explicit_destination=None, cancelled=False, minimum=3):
    """Shadow evaluation of current-scope choices. This never authorizes or executes an action."""
    out = {"status": "insufficient-evidence", "default": None, "support": 0,
           "opportunities": 0, "actionAuthorized": False}
    if cancelled:
        out["status"] = "cancelled"
        return out
    if explicit_destination is not None:
        out.update(status="explicit-current-choice", default=explicit_destination)
        return out
    if not scope or artifact_type not in {"report", "directive"} or not 3 <= minimum <= 5:
        return out
    choices = {}
    conflicts = False
    for event in events:
        if (not isinstance(event, dict) or set(event) != EVENT_FIELDS or
                event.get("actorVerified") is not True or
                event.get("intentEvidence") != "explicit-user-choice" or
                not str(event.get("sourceRef", "")).startswith("user:") or
                event.get("actionCategory") != "deliver" or
                event.get("scope") != scope or event.get("artifactType") != artifact_type or
                event.get("destinationClass") not in {"downloads", "attachment"} or
                not event.get("taskId") or not timestamp(event.get("observedAt")) or
                event.get("outcome") not in {"chosen", "rejected", "cancelled"}):
            continue
        task = event["taskId"]
        choice = event["destinationClass"]
        if event["outcome"] != "chosen" or event.get("supersedes"):
            conflicts = True
        if task in choices and choices[task] != choice:
            conflicts = True
        choices[task] = choice
    counts = {choice: list(choices.values()).count(choice) for choice in set(choices.values())}
    out["opportunities"] = len(choices)
    out["support"] = max(counts.values(), default=0)
    if conflicts or len(counts) > 1:
        out["status"] = "suspended-conflict"
    elif out["support"] >= minimum:
        out.update(status="shadow-candidate", default=next(iter(counts)))
    return out


def timestamp(value):
    try:
        stamp = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return bool(stamp.tzinfo and stamp <= datetime.now(timezone.utc))
    except (ValueError, TypeError):
        return False


def validate_contract(doc):
    """Strict local contract, never a stored authorization or a transcript."""
    if (not isinstance(doc, dict) or set(doc) != FIELDS or
            doc.get("schemaVersion") != CONTINUITY_SCHEMA or
            not isinstance(doc.get("taskId"), str) or
            not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}", doc["taskId"]) or
            type(doc.get("revision")) is not int or doc["revision"] < 1 or
            not timestamp(doc.get("observedAt")) or
            doc.get("taskStatus") not in {"active", "paused", "cancelled", "completed"}):
        raise ValueError("continuity-schema-invalid")
    for field in ("instructionRef", "goal", "nextAction"):
        if not isinstance(doc[field], str) or not doc[field].strip():
            raise ValueError("continuity-schema-invalid")
    for field in ("nonGoals", "deliverables", "remaining", "blockers", "accessFailures", "preferenceEvents"):
        if not isinstance(doc[field], list) or len(doc[field]) > 100:
            raise ValueError("continuity-schema-invalid")
    for failure in doc["accessFailures"]:
        if (not isinstance(failure, dict) or set(failure) !=
                {"action", "target", "environment", "observedAt", "errorCode"} or
                failure["action"] not in {"read", "write", "attach"} or
                not all(isinstance(failure[k], str) and failure[k] for k in failure) or
                not timestamp(failure["observedAt"])):
            raise ValueError("continuity-schema-invalid")
    for event in doc["preferenceEvents"]:
        if (not isinstance(event, dict) or set(event) != EVENT_FIELDS or
                type(event.get("actorVerified")) is not bool or not timestamp(event.get("observedAt")) or
                not all(isinstance(event.get(k), str) for k in EVENT_FIELDS - {"actorVerified", "supersedes"})):
            raise ValueError("continuity-schema-invalid")
    ids = set()
    for artifact in doc["deliverables"]:
        if (not isinstance(artifact, dict) or set(artifact) !=
                {"id", "version", "source", "sha256", "deliveries"} or
                not all(isinstance(artifact.get(k), str) and artifact[k] for k in
                        ("id", "version", "source", "sha256")) or
                not re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]) or
                artifact["id"] in ids or not isinstance(artifact["deliveries"], list) or
                not artifact["deliveries"]):
            raise ValueError("continuity-schema-invalid")
        ids.add(artifact["id"])
        for delivery in artifact["deliveries"]:
            if (not isinstance(delivery, dict) or
                    set(delivery) - {"method", "required", "destination", "environment", "status", "evidence", "supersededBy"} or
                    delivery.get("method") not in {"file", "attachment"} or
                    type(delivery.get("required")) is not bool or
                    delivery.get("status") not in DELIVERY_STATUSES or
                    not all(isinstance(delivery.get(k), str) and delivery[k] for k in
                            ("destination", "environment")) or
                    not isinstance(delivery.get("evidence"), dict)):
                raise ValueError("continuity-schema-invalid")
    # Reuse the existing scanner; no sensitive values become durable state.
    from codex_work_checkpoint import secret_free
    secret_free(json.dumps(doc, ensure_ascii=False).encode("utf-8"), "state.md")
    return doc


def read_contract(path):
    raw = Path(path).read_bytes()
    if len(raw) > MAX_STATE_BYTES:
        raise ValueError("continuity-state-too-large")
    lines = raw.decode("utf-8-sig").splitlines()
    contracts = [line[len(CONTRACT_PREFIX):] for line in lines if line.startswith(CONTRACT_PREFIX)]
    if len(contracts) != 1:
        raise ValueError("continuity-contract-required")
    return validate_contract(json.loads(contracts[0]))


@contextmanager
def state_write_lock(path):
    lock = Path(path).with_name("state.md.write-lock")
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise ValueError("state-writer-conflict") from None
    try:
        os.close(fd)
        yield
    finally:
        lock.unlink()


def write_contract(path, doc, expected_revision):
    """Explicit CAS update of the same state.md; a transient lock is not state."""
    doc = validate_contract(doc)
    path = Path(path)
    if path.name != "state.md" or path.is_symlink():
        raise ValueError("continuity-state-path-invalid")
    path.parent.mkdir(parents=True, exist_ok=True)
    staged = None
    with state_write_lock(path):
        try:
            raw = path.read_bytes() if path.exists() else b""
            lines = raw.decode("utf-8-sig").splitlines()
            old = read_contract(path) if any(line.startswith(CONTRACT_PREFIX) for line in lines) else None
            revision = old["revision"] if old else 0
            if revision != expected_revision or doc["revision"] != revision + 1:
                raise ValueError("state-revision-conflict")
            if old and old["taskId"] != doc["taskId"]:
                raise ValueError("state-task-mismatch")
            if old and old["instructionRef"] != doc["instructionRef"] and doc["supersedes"] != old["instructionRef"]:
                raise ValueError("latest-instruction-supersession-required")
            if old and old["instructionRef"] == doc["instructionRef"]:
                def obligations(contract):
                    return {(a["id"], d["method"], d["destination"], d["environment"]): d["status"]
                            for a in contract["deliverables"] for d in a["deliveries"] if d["required"]}
                previous, current = obligations(old), obligations(doc)
                if (old["goal"] != doc["goal"] or old["nonGoals"] != doc["nonGoals"] or
                        not previous.keys() <= current.keys() or
                        any(current[key] in {"CANCELLED", "SUPERSEDED"} and current[key] != status
                            for key, status in previous.items())):
                    raise ValueError("instruction-change-required")
                if old["taskStatus"] in {"paused", "cancelled"} and doc["taskStatus"] == "active":
                    raise ValueError("instruction-change-required")
            # Preserve legacy human summary lines without truncation or replacing obligations.
            lines = [line for line in lines if not line.startswith(CONTRACT_PREFIX)]
            lines.append(CONTRACT_PREFIX + json.dumps(doc, ensure_ascii=False, separators=(",", ":")))
            output = ("\n".join(lines) + "\n").encode("utf-8")
            if len(lines) > 20 or len(output) > MAX_STATE_BYTES:
                raise ValueError("continuity-state-capacity-exceeded")
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
                staged = Path(stream.name)
                stream.write(output)
                stream.flush()
                os.fsync(stream.fileno())
            if (path.read_bytes() if path.exists() else b"") != raw:
                raise ValueError("state-preimage-conflict")
            os.replace(staged, path)
        finally:
            if staged and staged.exists():
                staged.unlink()
    return {"status": "updated", "taskId": doc["taskId"], "revision": doc["revision"]}


def file_digest(path):
    path = Path(path)
    if not path.is_absolute() or path.is_symlink():
        raise ValueError("delivery-path-invalid")
    # Reject sensitive material before any read. Files are read once; no retry or bypass.
    protected = {".secrets", ".auth", ".codex", ".git", "sessions", "rollouts"}
    if any(p.lower() in protected or p.lower().startswith(".env") for p in path.parts) or path.suffix.lower() in {".pem", ".key", ".pfx", ".p12", ".jks"}:
        raise ValueError("delivery-path-protected")
    for ancestor in (path, *path.parents):
        info = ancestor.lstat()
        if ancestor.is_symlink() or (getattr(info, "st_file_attributes", 0) & 0x400):
            raise ValueError("delivery-path-protected")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def check_continuity(path, expected_task=None, latest_ref=None, expected_revision=None,
                     complete=False, environment=None):
    """Read-only structural and actual delivery proof; no compaction hook/network."""
    report = {"schemaVersion": CONTINUITY_SCHEMA, "allowed": False, "errors": [], "deliveries": []}
    errors = report["errors"]
    try:
        doc = read_contract(path)
    except (OSError, ValueError, TypeError, KeyError, UnicodeError):
        errors.append("continuity-schema-invalid")
        return report
    report.update(taskId=doc["taskId"], revision=doc["revision"], taskStatus=doc["taskStatus"])
    if expected_task is not None and doc["taskId"] != expected_task:
        errors.append("state-task-mismatch")
    if latest_ref is not None and doc["instructionRef"] != latest_ref:
        errors.append("latest-instruction-mismatch")
    if expected_revision is not None and doc["revision"] != expected_revision:
        errors.append("state-revision-conflict")
    if not complete:
        report["allowed"] = not errors
        return report
    if latest_ref is None or expected_revision is None:
        errors.append("latest-instruction-evidence-required")
    if doc["taskStatus"] not in {"active", "completed"}:
        errors.append("task-not-active")
    if doc["remaining"] or doc["blockers"]:
        errors.append("task-work-incomplete")
    def denied_read(target):
        return any(f["action"] == "read" and f["environment"] == environment and
                   os.path.normcase(f["target"]) == os.path.normcase(str(target))
                   for f in doc["accessFailures"])
    for artifact in doc["deliverables"]:
        required = [d for d in artifact["deliveries"] if d["required"]]
        active = [d for d in required if d["status"] not in {"CANCELLED", "SUPERSEDED"}]
        if active:
            try:
                if denied_read(artifact["source"]):
                    errors.append("delivery-access-failure-recorded")
                elif file_digest(artifact["source"]) != artifact["sha256"]:
                    errors.append("artifact-content-mismatch")
            except (OSError, ValueError):
                errors.append("artifact-not-readable")
        for delivery in required:
            report["deliveries"].append({"artifactId": artifact["id"], "method": delivery["method"], "status": delivery["status"]})
            status = delivery["status"]
            if status in {"CANCELLED", "SUPERSEDED"}:
                if delivery.get("supersededBy") != doc["instructionRef"]:
                    errors.append("delivery-supersession-unbound")
                continue
            if status not in {"VERIFIED", "FOUND_VERIFIED"}:
                errors.append("delivery-not-verified")
                continue
            evidence = delivery["evidence"]
            if (evidence.get("sha256") != artifact["sha256"] or evidence.get("version") != artifact["version"] or
                    evidence.get("taskRevision") != doc["revision"] or not timestamp(evidence.get("observedAt"))):
                errors.append("delivery-evidence-stale")
            if not environment or delivery["environment"] != environment or evidence.get("environment") != environment:
                errors.append("delivery-environment-unverified")
                continue
            if delivery["method"] == "file":
                try:
                    if denied_read(delivery["destination"]):
                        errors.append("delivery-access-failure-recorded")
                    elif file_digest(delivery["destination"]) != artifact["sha256"]:
                        errors.append("delivery-content-mismatch")
                except ValueError as exc:
                    errors.append(str(exc))
                except OSError:
                    errors.append("delivery-not-readable")
            else:
                # This proves a recorded delivery-tool observation, not independent remote availability.
                try:
                    receipt_path = Path(evidence.get("receipt", ""))
                    if denied_read(receipt_path):
                        raise ValueError("receipt-access-failure-recorded")
                    file_digest(receipt_path)  # same path/privacy checks before the receipt read
                    if receipt_path.stat().st_size > MAX_STATE_BYTES:
                        raise ValueError("receipt-too-large")
                    receipt = json.loads(receipt_path.read_bytes())
                    binding = {"artifactId": artifact["id"], "method": "attachment", "destination": delivery["destination"],
                               "sha256": artifact["sha256"], "version": artifact["version"], "taskRevision": doc["revision"],
                               "environment": environment, "status": "delivered"}
                    if (not isinstance(receipt, dict) or any(receipt.get(k) != v for k, v in binding.items()) or
                            not str(receipt.get("sourceRef", "")).startswith("tool:") or
                            not timestamp(receipt.get("observedAt"))):
                        raise ValueError("receipt-unbound")
                except (OSError, ValueError, TypeError):
                    errors.append("attachment-receipt-unbound")
    report["errors"] = sorted(set(errors))
    report["allowed"] = not errors
    return report


def load_lease(root, ref):
    if not isinstance(ref, dict):
        return None
    rel = ref.get("path", "")
    p = root / rel
    if not p.is_file():
        return {"path": rel, "present": False}
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"path": rel, "present": True, "unreadable": True}
    exp = None
    try:
        exp = datetime.fromisoformat(str(data.get("expiresAtUtc", "")).replace("Z", "+00:00"))
    except ValueError:
        pass
    return {"path": rel, "present": True,
            "leaseId": data.get("leaseId"), "ownerId": data.get("ownerId"),
            "expiresAtUtc": data.get("expiresAtUtc"),
            "secondsToExpiry": ((exp - datetime.now(timezone.utc)).total_seconds()
                                if exp and exp.tzinfo else None)}


def next_hint(state, lease):
    status = state.get("status")
    rule = state.get("firstBlockingRule") or ""
    hints = {
        "prepared": "apply the declared edits, then: codex_work_checkpoint.py seal --run <run>",
        "begun": "apply the declared edits, then: codex_work_checkpoint.py seal --run <run>",
        "sealed": "run the real verification, then: codex_work_checkpoint.py finish --run <run> --exit-code <n>",
        "hold": "read firstBlockingRule; fix the named blocker, do NOT retry seal blindly",
        "verified": "cycle complete; record note in work_journal",
        "rolled_back": "targets restored; reconcile before re-attempting",
        "restore_staged": "verify staged copies, then restore without --staging",
    }
    hint = hints.get(status, "inspect checkpoint.json")
    if rule == "source-lease-drift":
        hint = ("lease file changed mid-cycle (heartbeat renews expiresAtUtc); "
                "post-patch identity check passes same-lease renewals — "
                "re-run the action once")
    if rule == "secret-pattern":
        hint = "locate the flagged file:line in firstBlockingRule detail; mask or env-ize the literal"
    if lease and lease.get("secondsToExpiry") is not None and status in ("prepared", "begun", "sealed"):
        secs = lease["secondsToExpiry"]
        hint += " | seal+finish BEFORE lease expiry (in %ds)" % secs
    return hint


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--run", default=None)
    ap.add_argument("--latest", action="store_true")
    ap.add_argument("--warn-seconds", type=int, default=600)
    ap.add_argument("--state", help="Exact existing task state.md; no newest-task selection")
    ap.add_argument("--check-complete", action="store_true", help="Reject missing current delivery proof (exit 5)")
    ap.add_argument("--write-contract", help="Local non-sensitive JSON input; updates the same state.md with CAS")
    ap.add_argument("--expected-revision", type=int)
    ap.add_argument("--latest-instruction-ref")
    ap.add_argument("--environment", help="Caller-verified execution environment identity")
    ap.add_argument("--preference-scope", help="Read-only shadow candidates from the same contract")
    ap.add_argument("--artifact-type", choices=("report", "directive"), default="report")
    args = ap.parse_args()
    root = Path(args.root)

    if args.state:
        path = Path(args.state)
        if not path.is_absolute():
            path = root / path
        if args.write_contract:
            try:
                doc = json.loads(Path(args.write_contract).read_bytes())
                out = write_contract(path, doc, args.expected_revision)
            except (OSError, ValueError, TypeError, KeyError):
                print(json.dumps({"status": "error", "reason": "continuity-update-refused"}))
                return 5
        else:
            out = check_continuity(path, latest_ref=args.latest_instruction_ref,
                                   expected_revision=args.expected_revision,
                                   complete=args.check_complete, environment=args.environment)
            if args.preference_scope and out["allowed"]:
                doc = read_contract(path)
                out["preferenceCandidate"] = preference_default(doc["preferenceEvents"],
                    args.preference_scope, args.artifact_type, cancelled=doc["taskStatus"] in {"paused", "cancelled"})
        print(json.dumps(out, ensure_ascii=True))
        return 0 if out.get("allowed", out.get("status") == "updated") else 5
    if args.check_complete or args.write_contract:
        ap.error("--state is required for continuity actions")

    run = None
    if args.latest:
        cands = sorted(root.glob("data/agent-handoff/**/checkpoint.json"),
                       key=lambda p: p.stat().st_mtime, reverse=True)
        run = cands[0].parent if cands else None
    elif args.run:
        run = Path(args.run)
    if run is None or not (run / "checkpoint.json").is_file():
        print(json.dumps({"status": "error", "reason": "checkpoint.json not found",
                          "run": str(run)}))
        return 2

    try:
        state = json.loads((run / "checkpoint.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(json.dumps({"status": "error", "reason": "unreadable", "detail": str(exc)[:160]}))
        return 2

    lease = load_lease(root, (state.get("manifest") or {}).get("lease")
                       or state.get("lease"))
    report = {"run": str(run), "status": state.get("status"),
              "firstBlockingRule": state.get("firstBlockingRule"),
              "nextAction": state.get("nextAction"),
              "targetCount": len((state.get("manifest") or {}).get("targets") or []),
              "lease": lease,
              "hint": next_hint(state, lease)}
    if lease and lease.get("secondsToExpiry") is not None:
        report["leaseExpiryWarning"] = (
            "seal/finish before lease expiry" if 0 < lease["secondsToExpiry"] < args.warn_seconds
            else ("lease-EXPIRED" if lease["secondsToExpiry"] <= 0 else "ok"))
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
