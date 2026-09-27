"""Bounded opt-in traces and conservative metrics. No target execution or repair."""
from __future__ import annotations

from collections import Counter, defaultdict
from contextlib import contextmanager
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import threading
import time
import uuid

SCHEMA = "demo1.skill-diagnostics.event.v1"
CONTRACT = "1"
MAX_LINE = 65536
MAX_TRACE = 32 * 1024 * 1024
STATUSES = {"succeeded", "failed", "timed_out", "cancelled", "skipped", "blocked"}
STAGES = {"input", "decision", "tool_start", "tool_result", "exception", "run_result"}
IDENTITY = ("skillId", "targetSkillId", "skillHash", "sourceFingerprint", "caseId", "inputRef", "cohortId",
            "environmentHash", "oracleHash", "policyHash", "caseRef")
HASHES = {"skillHash", "sourceFingerprint", "environmentHash", "oracleHash", "policyHash"}
FIELDS = set(IDENTITY) | {"schemaVersion", "contractVersion", "eventId", "runId", "traceId",
    "spanId", "parentSpanId", "seq", "stage", "status", "reasonCode", "durationMs",
    "toolCallId", "toolId", "attemptIndex", "retryOf", "action", "evidenceRefs",
    "evidenceRequired", "exceptionType", "location", "observationSource", "synthetic",
    "telemetryComplete"}
SECRET = re.compile(r"(?i)(sk-[a-z0-9_-]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN .*PRIVATE KEY)")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=True, allow_nan=False).encode()).hexdigest()


def file_hash(path, limit=MAX_TRACE):
    with Path(path).open("rb") as stream:
        content = stream.read(limit + 1)
    if len(content) > limit:
        raise ValueError("artifact_too_large")
    return hashlib.sha256(content).hexdigest()


def label(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,96}", value) or SECRET.search(value):
        raise ValueError("invalid_identifier")
    return value


def relative_ref(value):
    if (not isinstance(value, str) or not value or len(value) > 400 or
            any(c in value for c in (":", "\\", "\n", "\r", "\x00")) or
            value.startswith("/") or ".." in value.split("/") or SECRET.search(value)):
        raise ValueError("invalid_relative_ref")
    return value


def safe_path(root, value, *, exists=True):
    root = Path(root).absolute()
    value = Path(value)
    if ".." in value.parts:
        raise ValueError("path_escape")
    path = value if value.is_absolute() else root / value
    try:
        relative = path.relative_to(root)
    except ValueError:
        raise ValueError("path_escape") from None
    current = root
    for part in ("", *relative.parts):
        current = current / part
        if current.is_symlink():
            raise ValueError("reparse_path")
        if current.exists() and getattr(current.stat(), "st_file_attributes", 0) & 0x400:
            raise ValueError("reparse_path")
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("path_escape")
    if exists and not path.is_file():
        raise ValueError("artifact_unavailable")
    return path


def read_json(root, path, limit=MAX_TRACE):
    path = safe_path(root, path)
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("artifact_too_large")
    return json.loads(data.decode("utf-8-sig"), parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite_json")))


def write_json(root, path, value):
    path = safe_path(root, path, exists=False)
    encoded = json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    if SECRET.search(encoded):
        raise ValueError("secret_pattern")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        stream.write(encoded)


def validate_event(event):
    if not isinstance(event, dict) or set(event) - FIELDS:
        raise ValueError("unknown_event_field")
    if "parentSpanId" not in event:
        raise ValueError("missing_parent_field")
    if event.get("schemaVersion") != SCHEMA or event.get("contractVersion") != CONTRACT:
        raise ValueError("unsupported_schema")
    for key in IDENTITY:
        value = event.get(key)
        if key in HASHES:
            if not isinstance(value, str) or not re.fullmatch("[0-9a-f]{64}", value):
                raise ValueError("invalid_hash")
        else:
            label(value)
    for key in ("eventId", "runId", "traceId", "spanId"):
        label(event.get(key))
    if event.get("parentSpanId") is not None:
        label(event["parentSpanId"])
    if type(event.get("seq")) is not int or event["seq"] < 1:
        raise ValueError("invalid_sequence")
    if event.get("stage") not in STAGES:
        raise ValueError("invalid_stage")
    if event.get("observationSource") not in {"wrapper", "imported", "declared"}:
        raise ValueError("invalid_observation_source")
    if type(event.get("synthetic")) is not bool:
        raise ValueError("invalid_synthetic_flag")
    if "durationMs" in event and (type(event["durationMs"]) not in (int, float) or
            not math.isfinite(event["durationMs"]) or event["durationMs"] < 0):
        raise ValueError("invalid_duration")
    for key in ("toolCallId", "toolId", "retryOf", "action", "exceptionType", "reasonCode"):
        if event.get(key) is not None:
            label(event[key])
    if event["stage"] in {"tool_start", "tool_result"}:
        label(event.get("toolCallId")); label(event.get("toolId"))
        if "retryOf" not in event:
            raise ValueError("missing_retry_field")
        if type(event.get("attemptIndex")) is not int or not 1 <= event["attemptIndex"] <= 10000:
            raise ValueError("invalid_attempt")
    if event["stage"] in {"run_result", "tool_result"}:
        if event.get("status") not in STATUSES or "durationMs" not in event:
            raise ValueError("invalid_terminal")
    if event["stage"] == "run_result" and type(event.get("telemetryComplete")) is not bool:
        raise ValueError("missing_telemetry_status")
    if event["stage"] == "decision":
        label(event.get("action"))
        if type(event.get("evidenceRequired")) is not bool:
            raise ValueError("invalid_evidence_requirement")
        refs = event.get("evidenceRefs")
        if not isinstance(refs, list) or len(refs) > 8:
            raise ValueError("invalid_evidence_refs")
        for ref in refs:
            if not isinstance(ref, dict) or set(ref) != {"path", "sha256"}:
                raise ValueError("invalid_evidence_ref")
            relative_ref(ref["path"])
            if not re.fullmatch("[0-9a-f]{64}", str(ref["sha256"])):
                raise ValueError("invalid_evidence_hash")
    if "location" in event:
        loc = event["location"]
        if not isinstance(loc, dict) or set(loc) != {"path", "line"}:
            raise ValueError("invalid_location")
        if loc["path"] is not None:
            relative_ref(loc["path"])
        if loc["line"] is not None and (type(loc["line"]) is not int or loc["line"] < 1):
            raise ValueError("invalid_location")
    return event


class TraceRecorder:
    """Explicit per-run sink. Body results/exceptions are preserved if recording fails."""
    def __init__(self, root, path, context, *, synthetic=False, max_bytes=MAX_TRACE):
        self.root = Path(root).absolute()
        self.path = safe_path(self.root, path, exists=False)
        context = dict(context)
        context.setdefault("targetSkillId", context["skillId"])
        self.base = {key: context[key] for key in IDENTITY}
        self.base.update(schemaVersion=SCHEMA, contractVersion=CONTRACT,
                         runId=uuid.uuid4().hex, traceId=uuid.uuid4().hex,
                         observationSource="wrapper", synthetic=synthetic)
        self.span = uuid.uuid4().hex
        self.owner_thread = threading.get_ident()
        validate_event(dict(self.base, eventId=uuid.uuid4().hex, spanId=self.span,
                            parentSpanId=None, seq=1, stage="input"))
        if type(max_bytes) is not int or not 0 < max_bytes <= MAX_TRACE:
            raise ValueError("invalid_trace_budget")
        self.max_bytes = max_bytes
        self.bytes_written = 0
        self.seq = 0
        self.telemetry_complete = True
        self.status = "succeeded"
        # Hold identity until this bounded run ends: Python can recycle id()
        # after a caught exception is freed, which must not suppress a new one.
        self.seen_exceptions = {}
        self.started = time.perf_counter_ns()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.stream = self.path.open("x", encoding="utf-8", newline="\n")

    def _emit(self, stage, *, span=None, **data):
        if threading.get_ident() != self.owner_thread:
            self.telemetry_complete = False
            return
        self.seq += 1
        event = dict(self.base, eventId=uuid.uuid4().hex, seq=self.seq, stage=stage,
                     spanId=span or self.span, parentSpanId=self.span if span else None, **data)
        try:
            validate_event(event)
            encoded = json.dumps(event, separators=(",", ":"), allow_nan=False) + "\n"
            size = len(encoded.encode("utf-8"))
            if size > MAX_LINE or self.bytes_written + size > self.max_bytes:
                raise ValueError("trace_budget_exceeded")
            self.stream.write(encoded)
            self.stream.flush()
            self.bytes_written += size
        except (OSError, ValueError, TypeError):
            self.telemetry_complete = False

    def __enter__(self):
        self._emit("input")
        return self

    @staticmethod
    def exception_status(error):
        if isinstance(error, TimeoutError):
            return "timed_out"
        if isinstance(error, (KeyboardInterrupt, SystemExit)) or type(error).__name__ == "CancelledError":
            return "cancelled"
        return "failed"

    def _exception(self, error, span=None):
        if id(error) in self.seen_exceptions:
            return
        self.seen_exceptions[id(error)] = error
        location = {"path": None, "line": None}
        tb = error.__traceback__
        while tb:
            path = Path(tb.tb_frame.f_code.co_filename).absolute()
            if path.is_relative_to(self.root):
                location = {"path": path.relative_to(self.root).as_posix(), "line": tb.tb_lineno}
            tb = tb.tb_next
        kind = type(error).__name__
        try:
            label(kind)
        except ValueError:
            kind = "Exception"
        self._emit("exception", span=span, exceptionType=kind, location=location)

    def decision(self, action, *, evidence_refs=(), evidence_required=True):
        self._emit("decision", action=action, evidenceRefs=list(evidence_refs),
                   evidenceRequired=evidence_required)

    def set_result(self, status):
        if status not in STATUSES:
            raise ValueError("invalid_status")
        self.status = status

    @contextmanager
    def tool(self, tool_id, *, call_id=None, attempt=1):
        span = uuid.uuid4().hex
        call_id = call_id or uuid.uuid4().hex
        fields = dict(toolId=tool_id, toolCallId=call_id, attemptIndex=attempt,
                      retryOf=call_id if attempt > 1 else None)
        self._emit("tool_start", span=span, **fields)
        began = time.perf_counter_ns()
        result = {"status": "succeeded"}
        try:
            yield result
        except BaseException as error:
            result["status"] = self.exception_status(error)
            self._exception(error, span)
            raise
        finally:
            self._emit("tool_result", span=span, **fields, status=result["status"],
                       durationMs=(time.perf_counter_ns() - began) / 1e6)

    def __exit__(self, kind, error, tb):
        if error is not None:
            self.status = self.exception_status(error)
            self._exception(error)
        try:
            self._emit("run_result", status=self.status,
                       durationMs=(time.perf_counter_ns() - self.started) / 1e6,
                       telemetryComplete=self.telemetry_complete)
        finally:
            try:
                self.stream.close()
            except OSError:
                self.telemetry_complete = False
            self.seen_exceptions.clear()
        return False


def rate(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator,
            "value": numerator / denominator if denominator else None}


def latency(values):
    values = sorted(values)
    return {"n": len(values), "p50": statistics.median(values) if values else None,
            "p95": values[math.ceil(.95 * len(values)) - 1] if values else None}


def evidence_state(root, event):
    refs = event["evidenceRefs"]
    if not refs:
        return "missing" if event["evidenceRequired"] else "not_required"
    contradiction = False
    for ref in refs:
        try:
            path = safe_path(root, ref["path"])
            if file_hash(path) != ref["sha256"]:
                return "hash_mismatch"
            # A linked artifact is not a semantic oracle. Only the explicit bound
            # verification schema may establish contradiction or validation.
            if path.suffix.lower() == ".json":
                artifact = read_json(root, ref["path"])
                if isinstance(artifact, dict) and artifact.get("schemaVersion") == "demo1.skill-diagnostics.verification.v1":
                    if any(artifact.get(key) != event[key] for key in
                           ("caseId", "inputRef", "sourceFingerprint", "oracleHash")):
                        return "binding_mismatch"
                    label(artifact.get("validatorId"))
                    if artifact.get("outcome") not in {"pass", "contradiction"}:
                        return "invalid_verification"
                    contradiction |= artifact["outcome"] == "contradiction"
        except (OSError, ValueError, UnicodeError):
            return "unavailable"
    return "contradiction" if contradiction else "linked"


def load_events(root, paths):
    events = {}
    issues = Counter()
    duplicate = total = 0
    hashes = []
    for name in paths:
        try:
            path = safe_path(root, name)
            size = path.stat().st_size
            if total + size > MAX_TRACE:
                issues["trace_budget_exceeded"] += 1
                continue
            hasher = hashlib.sha256()
            with path.open("rb") as stream:
                while raw := stream.readline(MAX_LINE + 1):
                    total += len(raw)
                    if total > MAX_TRACE:
                        issues["trace_budget_exceeded"] += 1
                        break
                    hasher.update(raw)
                    if len(raw) > MAX_LINE:
                        issues["event_too_large"] += 1
                        break
                    try:
                        event = validate_event(json.loads(raw.decode("utf-8")))
                    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
                        issues["invalid_json" if not raw.rstrip().endswith(b"}") else "invalid_event"] += 1
                        continue
                    event_id = event["eventId"]
                    if event_id in events:
                        if events[event_id] == event:
                            duplicate += 1
                        else:
                            issues["conflicting_event"] += 1
                        continue
                    events[event_id] = event
            if path.stat().st_size != size:
                issues["trace_changed_during_read"] += 1
            hashes.append(dict(path=path.relative_to(Path(root).absolute()).as_posix(), sha256=hasher.hexdigest()))
        except (OSError, ValueError):
            issues["trace_unavailable"] += 1
    return list(events.values()), issues, duplicate, hashes


def aggregate(root, paths, *, expected_skills=()):
    events, issues, duplicates, hashes = load_events(root, paths)
    # Invalid ingestion cannot be assigned reliably to a run. Quarantine this
    # supplied batch from reliability denominators; retain observed counts.
    ingestion_tainted = bool(issues)
    runs = defaultdict(list)
    for event in events:
        runs[event["runId"]].append(event)
    groups = {}
    run_rows = []
    for run_id, rows in sorted(runs.items()):
        rows.sort(key=lambda row: row["seq"])
        first = rows[0]
        provenance = IDENTITY + ("contractVersion", "synthetic", "observationSource", "traceId")
        before_issues = sum(issues.values())
        if any(any(row[key] != first[key] for key in provenance) for row in rows):
            issues["run_identity_conflict"] += 1
        if [r["seq"] for r in rows] != list(range(1, len(rows) + 1)):
            issues["sequence_gap_or_duplicate"] += 1
        stages = Counter(row["stage"] for row in rows)
        if stages["input"] != 1 or stages["run_result"] != 1 or not stages["decision"]:
            issues["missing_stage"] += 1
        if rows[0]["stage"] != "input" or rows[-1]["stage"] != "run_result":
            issues["invalid_run_order"] += 1
        root_span = first["spanId"]
        if any(r["spanId"] != root_span or r["parentSpanId"] is not None
               for r in rows if r["stage"] in {"input", "decision", "run_result"}):
            issues["root_span_mismatch"] += 1
        known_spans = {row["spanId"] for row in rows}
        if any(row["parentSpanId"] is not None and row["parentSpanId"] not in known_spans for row in rows):
            issues["missing_parent"] += 1
        terminal = next((r for r in reversed(rows) if r["stage"] == "run_result"), None)
        if terminal and not terminal["telemetryComplete"]:
            issues["recorder_incomplete"] += 1
        attempts = defaultdict(list)
        for row in rows:
            if row["stage"] in {"tool_start", "tool_result"}:
                attempts[(row["toolCallId"], row["attemptIndex"])].append(row)
        if not attempts:
            issues["missing_stage"] += 1
        tool_statuses = Counter()
        tool_times = []
        call_results = defaultdict(list)
        call_starts = defaultdict(list)
        used_spans = set()
        call_tools = defaultdict(set)
        for (call_id, attempt), pair in attempts.items():
            start = [r for r in pair if r["stage"] == "tool_start"]
            end = [r for r in pair if r["stage"] == "tool_result"]
            if len(start) != 1 or len(end) != 1:
                issues["incomplete_attempt"] += 1
            elif start[0]["seq"] >= end[0]["seq"] or any(start[0][key] != end[0][key]
                     for key in ("spanId", "toolId", "parentSpanId", "retryOf")):
                issues["attempt_identity_conflict"] += 1
            if start:
                call_starts[call_id].append(attempt)
                call_tools[call_id].add(start[0]["toolId"])
                if start[0]["spanId"] == root_span or start[0]["spanId"] in used_spans:
                    issues["attempt_span_collision"] += 1
                used_spans.add(start[0]["spanId"])
                if start[0]["retryOf"] != (call_id if attempt > 1 else None):
                    issues["invalid_retry_link"] += 1
                if start[0]["parentSpanId"] != root_span:
                    issues["invalid_attempt_parent"] += 1
            if len(end) == 1:
                tool_statuses[end[0]["status"]] += 1
                tool_times.append(end[0]["durationMs"])
                call_results[call_id].append((attempt, end[0]["status"]))
        for indices in call_starts.values():
            if sorted(indices) != list(range(1, len(indices) + 1)):
                issues["attempt_gap"] += 1
        if any(len(values) != 1 for values in call_tools.values()):
            issues["logical_tool_identity_changed"] += 1
        unsupported = contradicted = 0
        reasons = Counter()
        for row in rows:
            if row["stage"] == "decision":
                state = evidence_state(root, row)
                reasons[state] += 1
                unsupported += state not in {"not_required", "linked", "contradiction"}
                contradicted += state == "contradiction"
        key = tuple(first[k] for k in ("skillId", "targetSkillId", "skillHash", "sourceFingerprint", "environmentHash", "oracleHash", "policyHash", "cohortId", "synthetic", "observationSource"))
        group = groups.setdefault(key, dict({k:first[k] for k in
                ("skillId", "targetSkillId", "skillHash", "sourceFingerprint", "environmentHash", "oracleHash", "policyHash", "cohortId", "synthetic", "observationSource")}, runCount=0,
                runStatuses=Counter(), toolStatuses=Counter(), runTimes=[], toolTimes=[],
                retryCount=0, logicalSuccess=0, logicalCount=0,
                firstAttemptFailed=0, firstAttemptCount=0, retryRecoveredCallCount=0,
                unsupportedDecisionCount=0, contradictedDecisionCount=0,
                evidenceReasons=Counter(), exceptionLocations=Counter(), exceptionCount=0,
                completeRuns=0))
        group["runCount"] += 1
        status = terminal["status"] if terminal else "incomplete"
        complete = not ingestion_tainted and sum(issues.values()) == before_issues
        group["runStatuses"][status if complete else "incomplete"] += 1
        first_results = [min(values)[1] for values in call_results.values()]
        first_failed = sum(s in {"failed", "timed_out"} for s in first_results)
        first_count = sum(s in {"succeeded", "failed", "timed_out"} for s in first_results)
        recovered = sum(min(v)[1] in {"failed", "timed_out"} and max(v)[1] == "succeeded"
                        for v in call_results.values())
        if terminal and complete:
            group["runTimes"].append(terminal["durationMs"])
        if complete:
            group["toolStatuses"].update(tool_statuses)
            group["toolTimes"].extend(tool_times)
            group["logicalCount"] += len(call_results)
            group["logicalSuccess"] += sum(max(values)[1] == "succeeded" for values in call_results.values())
            group["firstAttemptFailed"] += first_failed
            group["firstAttemptCount"] += first_count
            group["retryRecoveredCallCount"] += recovered
        group["retryCount"] += sum(max(0, len(indices)-1) for indices in call_starts.values())
        group["unsupportedDecisionCount"] += unsupported
        group["contradictedDecisionCount"] += contradicted
        group["evidenceReasons"].update(reasons)
        for row in rows:
            if row["stage"] == "exception":
                loc = row.get("location", {"path": None, "line": None})
                group["exceptionLocations"][(loc["path"], loc["line"], row.get("exceptionType", "unknown"))] += 1
                group["exceptionCount"] += 1
        group["completeRuns"] += complete
        run_rows.append(dict({k: first[k] for k in IDENTITY}, contractVersion=CONTRACT,
                             synthetic=first["synthetic"], observationSource=first["observationSource"],
                             runId=run_id, status=status, complete=complete,
                             firstAttemptFailed=first_failed, firstAttemptCount=first_count,
                             retryCount=sum(max(0,len(v)-1) for v in call_starts.values()),
                             unsupportedDecisionCount=unsupported, contradictedDecisionCount=contradicted))
    skills = []
    for group in groups.values():
        counts = group["runStatuses"]
        group["failureRate"] = rate(counts["failed"] + counts["timed_out"],
                                    sum(counts[s] for s in ("succeeded", "failed", "timed_out")))
        group["toolSuccessRate"] = rate(group["toolStatuses"]["succeeded"], sum(group["toolStatuses"].values()))
        group["logicalToolSuccessRate"] = rate(group.pop("logicalSuccess"), group.pop("logicalCount"))
        group["firstAttemptFailureRate"] = rate(group.pop("firstAttemptFailed"), group.pop("firstAttemptCount"))
        group["quarantinedRunCount"] = group["runCount"] - group["completeRuns"]
        group["runLatencyMs"] = latency(group.pop("runTimes"))
        group["toolLatencyMs"] = latency(group.pop("toolTimes"))
        group["traceCoverage"] = rate(group["completeRuns"], group["runCount"])
        group["exceptionLocations"] = [{"path": p, "line": line, "type": kind, "count": count}
            for (p, line, kind), count in group["exceptionLocations"].items()]
        skills.append(group)
    observed = {row["skillId"] for row in skills}
    unobserved = sorted(set(expected_skills) - observed)
    for skill in unobserved:
        label(skill)
        skills.append(dict(skillId=skill, runCount=0, failureRate=rate(0,0),
                           toolSuccessRate=rate(0,0), traceCoverage=rate(0,0)))
    if not events:
        issues["no_events"] += 1
    result = dict(schemaVersion="demo1.skill-diagnostics.metrics.v1", contractVersion=CONTRACT,
                  skills=skills, runs=run_rows, issues=dict(issues), duplicateEventCount=duplicates,
                  unobservedSkills=unobserved, telemetryComplete=not issues and not unobserved,
                  eventCount=len(events), inputTraces=hashes, actualTokenUsage=None)
    result["summaryHash"] = digest(result)
    return result


def revalidate_metrics(root, value):
    if value.get("schemaVersion") != "demo1.skill-diagnostics.metrics.v1":
        raise ValueError("unsupported_metrics")
    if digest({k:v for k,v in value.items() if k != "summaryHash"}) != value.get("summaryHash"):
        raise ValueError("summary_changed")
    refs = value["inputTraces"]
    if any(file_hash(safe_path(root, ref["path"])) != ref["sha256"] for ref in refs):
        raise ValueError("trace_changed")
    replayed = aggregate(root, [r["path"] for r in refs], expected_skills=value["unobservedSkills"])
    if replayed["summaryHash"] != value["summaryHash"]:
        raise ValueError("summary_changed")
    return replayed


def compare(before, after, *, root=None):
    result = dict(schemaVersion="demo1.skill-diagnostics.comparison.v1", status="insufficient_evidence",
                  normalRegressions=0, resolvedFailures=0, evidenceCorrections=0,
                  beforeHash=before.get("summaryHash"), afterHash=after.get("summaryHash"),
                  statisticalSignificance="not_tested", mutationAuthorized=False,
                  evidenceTrust="local_artifacts_only", firstAttemptRegressions=0)
    for value in (before, after):
        if value.get("schemaVersion") != "demo1.skill-diagnostics.metrics.v1" or not value.get("telemetryComplete"):
            return result
        if digest({k:v for k,v in value.items() if k != "summaryHash"}) != value.get("summaryHash"):
            result["status"] = "not_comparable"
            return result
        if root is None:
            return result
        try:
            # A checksum alone is not proof. Replay the referenced current
            # traces and bound evidence, with the current metric implementation.
            revalidate_metrics(root, value)
        except (ValueError, OSError, KeyError, TypeError):
            result["status"] = "not_comparable"
            return result
    keys = ("skillId", "targetSkillId", "caseId", "inputRef", "cohortId", "environmentHash", "oracleHash", "policyHash", "contractVersion", "synthetic", "observationSource")
    left = {tuple(r[k] for k in keys): r for r in before["runs"]}
    right = {tuple(r[k] for k in keys): r for r in after["runs"]}
    if not left or left.keys() != right.keys() or len(left) != len(before["runs"]) or len(right) != len(after["runs"]):
        result["status"] = "not_comparable"
        return result
    result["status"] = "unchanged"
    uncertain = False
    regressed = False
    result["synthetic"] = any(r["synthetic"] for r in left.values())
    for key, old in left.items():
        new = right[key]
        if not old["complete"] or not new["complete"] or old["observationSource"] == "declared":
            result["status"] = "insufficient_evidence"
            return result
        if old["status"] == "succeeded" and new["status"] != "succeeded":
            result["normalRegressions"] += 1
        if old["status"] not in {"succeeded", "failed", "timed_out"} or new["status"] not in {"succeeded", "failed", "timed_out"}:
            uncertain = True
        if new["firstAttemptCount"] != old["firstAttemptCount"]:
            uncertain = True
        if new["firstAttemptFailed"] > old["firstAttemptFailed"]:
            result["firstAttemptRegressions"] += 1
            regressed = True
        if new["retryCount"] > old["retryCount"]:
            uncertain = True
        if old["status"] in {"failed", "timed_out"} and new["status"] == "succeeded":
            result["resolvedFailures"] += 1
        old_bad = old["unsupportedDecisionCount"] + old["contradictedDecisionCount"]
        new_bad = new["unsupportedDecisionCount"] + new["contradictedDecisionCount"]
        result["evidenceCorrections"] += max(0, old_bad - new_bad)
        if new_bad > old_bad or new["status"] in {"failed", "timed_out"}:
            regressed |= new_bad > old_bad or old["status"] == "succeeded"
            uncertain = True
    if result["normalRegressions"] or regressed:
        result["status"] = "regressed"
    elif uncertain:
        result["status"] = "insufficient_evidence"
    if result["status"] == "unchanged" and (result["resolvedFailures"] or result["evidenceCorrections"]):
        result["status"] = "improved"
    return result
