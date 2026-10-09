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


REQUEST_SCHEMA = "awx.request-contract.v1"
REQUEST_FIELDS = {"schemaVersion", "taskId", "revision", "instructionRef", "goal", "knowledge", "stages"}
STAGE_FIELDS = {"id", "dependsOn", "requiredKnowledge", "inputs", "outputs", "api", "errors",
                "successTests", "steps", "sourceFiles", "testFiles"}


def check_request_contract(doc, root=None, latest_ref=None, expected_revision=None):
    """Check an agent-reconciled contract, not natural language or semantic fact truth.

    Facts require provenance; assumptions/unknowns hold affected stages. This
    read-only check neither grants authority nor changes continuity/policy state.
    """
    out = {"schemaVersion": REQUEST_SCHEMA, "status": "REJECTED", "taskId": None,
           "revision": None, "contractHash": None, "stages": [], "errors": []}

    def require(condition, code):
        if not condition:
            raise ValueError(code)

    def identifier(value):
        return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}", value)

    def text(value):
        return isinstance(value, str) and bool(value.strip()) and len(value) <= 2048

    def sequence(value, code, limit=100):
        require(isinstance(value, list) and len(value) <= limit, code)
        return value

    def canonical_path(value):
        require(isinstance(value, str) and text(value) and "\\" not in value and ":" not in value,
                "request-path-invalid")
        parts = value.split("/")
        require(all(p and p not in {".", ".."} and p == p.strip() for p in parts), "request-path-invalid")
        protected = {".secrets", ".auth", ".codex", ".git", "sessions", "rollouts"}
        require(not any(p.lower() in protected or p.lower().startswith(".env") for p in parts)
                and Path(value).suffix.lower() not in {".pem", ".key", ".pfx", ".p12", ".jks"},
                "request-path-protected")
        if root is not None:
            base = Path(root).resolve()
            candidate = base.joinpath(*parts)
            for ancestor in (candidate, *candidate.parents):
                if ancestor.is_symlink():
                    raise ValueError("request-path-protected")
                if ancestor.exists() and (getattr(ancestor.lstat(), "st_file_attributes", 0) & 0x400):
                    raise ValueError("request-path-protected")
                if ancestor == base:
                    break
            require(candidate.resolve().is_relative_to(base), "request-path-boundary")
        return value.casefold()

    try:
        raw = json.dumps(doc, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                         allow_nan=False).encode("utf-8")
        require(len(raw) <= MAX_STATE_BYTES, "request-contract-too-large")
        scanner_path = Path(__file__).resolve().with_name("codex_work_checkpoint.py")
        scanner = sys.modules.get("codex_work_checkpoint")
        if scanner is None:
            import importlib.util
            spec = importlib.util.spec_from_file_location("codex_work_checkpoint", scanner_path)
            require(spec is not None and spec.loader is not None, "request-guard-unavailable")
            scanner = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(scanner)
            sys.modules["codex_work_checkpoint"] = scanner
        require(getattr(scanner, "__file__", None) and Path(scanner.__file__).resolve() == scanner_path,
                "request-guard-identity-invalid")
        scanner.secret_free(raw, "request-contract.json")
        out["contractHash"] = hashlib.sha256(raw).hexdigest()
        require(isinstance(doc, dict) and set(doc) == REQUEST_FIELDS and
                doc.get("schemaVersion") == REQUEST_SCHEMA, "request-schema-invalid")
        require(identifier(doc["taskId"]) and type(doc["revision"]) is int and doc["revision"] >= 1
                and text(doc["instructionRef"]) and text(doc["goal"]), "request-identity-invalid")
        out.update(taskId=doc["taskId"], revision=doc["revision"])
        require(latest_ref is None or latest_ref == doc["instructionRef"], "latest-instruction-mismatch")
        require(expected_revision is None or expected_revision == doc["revision"], "state-revision-conflict")
        knowledge = {}
        for item in sequence(doc["knowledge"], "request-knowledge-invalid"):
            require(isinstance(item, dict) and set(item) == {"id", "kind", "summary", "sourceRef"}
                    and identifier(item.get("id")) and item.get("kind") in {"fact", "assumption", "unknown"}
                    and text(item.get("summary")) and (item.get("sourceRef") is None or text(item["sourceRef"])),
                    "request-knowledge-invalid")
            require(item["id"] not in knowledge, "request-knowledge-duplicate")
            require(item["kind"] != "fact" or text(item["sourceRef"]), "request-fact-provenance-required")
            knowledge[item["id"]] = item
        stages = sequence(doc["stages"], "request-stages-invalid", 25)
        require(bool(stages), "request-stages-empty")
        rows, dependencies = {}, {}

        for stage in stages:
            require(isinstance(stage, dict) and not set(stage) - STAGE_FIELDS and identifier(stage.get("id")),
                    "request-stage-invalid")
            sid = stage["id"]
            require(sid not in rows, "request-stage-duplicate")
            row = {"id": sid, "status": "READY", "blockers": []}
            rows[sid] = row

            def hold(code):
                row["blockers"].append(code)

            def fields(item, allowed, label, string_fields=()):
                require(isinstance(item, dict) and not set(item) - allowed, "request-slot-invalid")
                for field in allowed:
                    if field not in item or item[field] is None or item[field] == "":
                        hold("missing-slot:" + label + "." + field)
                    elif field in string_fields:
                        require(isinstance(item[field], str) and len(item[field]) <= 2048, "request-slot-invalid")
                        if not item[field].strip():
                            hold("missing-slot:" + label + "." + field)
                ref = item.get("knowledgeRef")
                if ref is not None and ref != "":
                    require(isinstance(ref, str) and ref in knowledge, "request-knowledge-ref-invalid")
                    if knowledge[ref]["kind"] != "fact":
                        hold("unconfirmed-knowledge:" + ref)

            for field in STAGE_FIELDS - {"id", "api"}:
                if field not in stage:
                    hold("missing-slot:" + field)
                else:
                    sequence(stage[field], "request-slot-invalid")
                    if field != "dependsOn" and field != "errors" and not stage[field]:
                        hold("missing-slot:" + field)
            deps = stage.get("dependsOn", [])
            require(all(identifier(d) for d in deps) and len(set(deps)) == len(deps), "request-dependency-invalid")
            dependencies[sid] = deps
            refs = stage.get("requiredKnowledge", [])
            require(all(isinstance(r, str) and r in knowledge for r in refs) and len(set(refs)) == len(refs),
                    "request-knowledge-ref-invalid")
            for ref in refs:
                if knowledge[ref]["kind"] != "fact":
                    hold("unconfirmed-knowledge:" + ref)
            for field in ("inputs", "outputs"):
                names = set()
                for item in stage.get(field, []):
                    fields(item, {"name", "type", "knowledgeRef"}, field, ("name", "type"))
                    if text(item.get("name")):
                        require(item["name"] not in names, "request-slot-duplicate")
                        names.add(item["name"])
            api = stage.get("api")
            if api is None:
                hold("missing-slot:api")
            elif isinstance(api, dict) and "applicable" in api:
                require(api.get("applicable") is False, "request-api-invalid")
                fields(api, {"applicable", "reason", "knowledgeRef"}, "api", ("reason",))
            else:
                fields(api, {"method", "path", "knowledgeRef"}, "api", ("method", "path"))
                if text(api.get("method")):
                    require(api["method"] in {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"},
                            "request-api-invalid")
                if text(api.get("path")):
                    require(api["path"].startswith("/") and "://" not in api["path"], "request-api-invalid")
                if not stage.get("errors"):
                    hold("missing-slot:errors")
            for item in stage.get("errors", []):
                fields(item, {"status", "condition", "knowledgeRef"}, "errors", ("condition",))
                if item.get("status") is not None:
                    require(type(item["status"]) is int and 100 <= item["status"] <= 599, "request-error-invalid")
            tests = set()
            for item in stage.get("successTests", []):
                fields(item, {"id", "commandId", "expectation", "knowledgeRef", "argvSha256", "expectedSuites"},
                       "successTests", ("id", "commandId", "expectation", "argvSha256"))
                if item.get("argvSha256") not in (None, ""):
                    require(isinstance(item["argvSha256"], str) and
                            re.fullmatch(r"[0-9a-f]{64}", item["argvSha256"]), "request-planned-argv-invalid")
                if item.get("expectedSuites") is not None:
                    suites = sequence(item["expectedSuites"], "request-planned-suites-invalid")
                    require(all(isinstance(suite, str) and
                                re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.$-]{0,199}", suite) for suite in suites),
                            "request-planned-suites-invalid")
                    require(len(suites) == len(set(suites)), "request-planned-suites-duplicate")
                    if not suites:
                        hold("missing-slot:successTests.expectedSuites")
                if text(item.get("id")):
                    require(identifier(item["id"]) and item["id"] not in tests, "request-test-duplicate")
                    tests.add(item["id"])
            for step in stage.get("steps", []):
                require(isinstance(step, str) and len(step) <= 2048, "request-step-invalid")
                if not step.strip():
                    hold("missing-slot:steps")
            for field in ("sourceFiles", "testFiles"):
                paths = [canonical_path(p) for p in stage.get(field, [])]
                require(len(paths) == len(set(paths)), "request-path-duplicate")
        visiting, visited = set(), set()

        def visit(sid):
            require(sid in rows, "request-dependency-invalid")
            require(sid not in visiting, "request-dependency-cycle")
            if sid in visited:
                return
            visiting.add(sid)
            for dep in dependencies[sid]:
                visit(dep)
                if rows[dep]["status"] == "HOLD":
                    rows[sid]["blockers"].append("dependency-held:" + dep)
            visiting.remove(sid)
            visited.add(sid)
            rows[sid]["blockers"] = sorted(set(rows[sid]["blockers"]))
            rows[sid]["status"] = "HOLD" if rows[sid]["blockers"] else "READY"

        for sid in rows:
            visit(sid)
        out["stages"] = list(rows.values())
        out["status"] = "HOLD" if any(row["status"] == "HOLD" for row in out["stages"]) else "READY"
    except (OSError, ValueError, TypeError, KeyError, RecursionError, ImportError) as error:
        code = str(error)
        out["errors"] = [code if re.fullmatch(r"[a-z][a-z-]+", code) else "request-contract-input-refused"]
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
    ap.add_argument("--request-contract", help="Read-only check of a local agent-reconciled request contract")
    ap.add_argument("--check-complete", action="store_true", help="Reject missing current delivery proof (exit 5)")
    ap.add_argument("--write-contract", help="Local non-sensitive JSON input; updates the same state.md with CAS")
    ap.add_argument("--expected-revision", type=int)
    ap.add_argument("--latest-instruction-ref")
    ap.add_argument("--environment", help="Caller-verified execution environment identity")
    ap.add_argument("--preference-scope", help="Read-only shadow candidates from the same contract")
    ap.add_argument("--artifact-type", choices=("report", "directive"), default="report")
    args = ap.parse_args()
    root = Path(args.root)

    if args.request_contract:
        if args.state or args.write_contract or args.check_complete or args.run or args.latest:
            ap.error("--request-contract cannot be combined with continuity or checkpoint actions")
        try:
            path = Path(args.request_contract).absolute()
            file_digest(path)  # Existing privacy/link gate before reading input.
            if path.stat().st_size > MAX_STATE_BYTES:
                raise ValueError("request-contract-too-large")
            def unique_fields(pairs):
                value = {}
                for key, item in pairs:
                    if key in value:
                        raise ValueError("request-json-duplicate-field")
                    value[key] = item
                return value
            doc = json.loads(path.read_bytes(), object_pairs_hook=unique_fields)
            out = check_request_contract(doc, root=root, latest_ref=args.latest_instruction_ref,
                                         expected_revision=args.expected_revision)
        except (OSError, ValueError, TypeError):
            out = {"schemaVersion": REQUEST_SCHEMA, "status": "REJECTED", "taskId": None,
                   "revision": None, "contractHash": None, "stages": [],
                   "errors": ["request-contract-input-refused"]}
        print(json.dumps(out, ensure_ascii=True, allow_nan=False))
        return {"READY": 0, "HOLD": 5, "REJECTED": 2}[out["status"]]

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
