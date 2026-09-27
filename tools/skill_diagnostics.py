"""Read-only skill/source diagnostics and explicitly selected offline adapters."""
from __future__ import annotations

import argparse
import ast
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time

from skill_diagnostics_trace import (TraceRecorder, aggregate, compare, digest, file_hash,
                                    label, read_json, safe_path, write_json, revalidate_metrics, SECRET)

OWNER = Path(__file__).resolve().parents[1]
DEFAULT_SCOPES = (".agents/skills", "scripts", "tools", "main/java", "main/resources",
                  "app/src/main/java_clean", "app/src/main/resources")
EXCLUDE = {".git", "__pycache__", "node_modules", "build", "dist", ".gradle", "backups", "archives"}
SUFFIXES = {".py", ".ps1", ".java", ".md", ".yaml", ".yml", ".json", ".js"}
LIMITS = dict(maxFiles=5000, maxFileBytes=1048576, maxBytes=67108864, timeoutSeconds=60)
RULES = {"quiet_broad_except", "bare_except", "constant_exception_fallback", "unbounded_loop_candidate",
         "python_syntax", "broad_catch_candidate", "fallback_wording_candidate"}


def _module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _registry(root, limits=None):
    # Fixed installed helper, never an implementation discovered in the scanned root.
    helper = _module("skill_meta_registry", OWNER / "scripts/awx_skill_registry.py")
    shared = safe_path(root, ".agents/skills", exists=False)
    size = 0
    paths = sorted(shared.glob("*/SKILL.md"))
    limits = limits or LIMITS
    if len(paths) > limits["maxFiles"]:
        raise ValueError("registry_budget_exceeded")
    for path in paths:
        safe_path(root, path)
        current = path.stat().st_size
        size += current
        if current > limits["maxFileBytes"] or size > limits["maxBytes"]:
            raise ValueError("registry_budget_exceeded")
    report = helper.scan_skills(shared, [])
    # Registry labels are data. Fail without printing malformed or secret labels.
    for entry in report["entries"]:
        if not re.fullmatch(r"[\w.-]{1,96}", entry["name"]) or SECRET.search(entry["name"]):
            raise ValueError("invalid_skill_name")
    return report


def _findings(path, content):
    results = []
    def add(rule, line):
        results.append(dict(path=path, line=line, rule=rule, state="candidate"))
    text = content.decode("utf-8-sig")
    if path.endswith(".py"):
        try:
            tree = ast.parse(text)
        except (SyntaxError, RecursionError, ValueError, MemoryError) as error:
            add("python_syntax", getattr(error, "lineno", None) or 1)
            return results
        for node in ast.walk(tree):
            if isinstance(node, ast.ExceptHandler):
                broad = node.type is None or isinstance(node.type, ast.Name) and node.type.id in {"Exception", "BaseException"}
                if node.type is None:
                    add("bare_except", node.lineno)
                if broad and any(isinstance(x, ast.Pass) for x in node.body):
                    add("quiet_broad_except", node.lineno)
                if broad and any(isinstance(x, ast.Return) and isinstance(x.value, ast.Constant) for x in node.body):
                    add("constant_exception_fallback", node.lineno)
            if isinstance(node, ast.While) and isinstance(node.test, ast.Constant) and node.test.value is True:
                # Exits can be dynamic; this is only a localization candidate.
                add("unbounded_loop_candidate", node.lineno)
    elif path.endswith((".java", ".ps1")):
        for line, value in enumerate(text.splitlines(), 1):
            if re.search(r"catch\s*(?:\(\s*(?:Exception|Throwable)\b|\{)", value):
                add("broad_catch_candidate", line)
    elif path.endswith("/SKILL.md"):
        for line, value in enumerate(text.splitlines(), 1):
            if re.search(r"(?i)\b(?:assume success|ignore (?:all )?errors|always retry)\b", value):
                add("fallback_wording_candidate", line)
    return results


def scan(root, scopes=None, *, cache=None, limits=None):
    root = Path(root).absolute()
    scopes = list(DEFAULT_SCOPES if scopes is None else scopes)
    budget = dict(LIMITS)
    if limits:
        if set(limits) - set(budget) or any(type(v) not in (int, float) or not 0 < v <= LIMITS[k] for k,v in limits.items()):
            raise ValueError("invalid_limit")
        budget.update(limits)
    started = time.perf_counter()
    checker = digest({"schema": 1, "scanner": file_hash(Path(__file__)),
                      "registry": file_hash(OWNER / "scripts/awx_skill_registry.py"),
                      "trace": file_hash(OWNER / "tools/skill_diagnostics_trace.py"),
                      "scopes": scopes, "limits": budget})
    old = cache if isinstance(cache, dict) and cache.get("checkerHash") == checker else {}
    old_entries = old.get("entries", {})
    issues = Counter()
    files = []; findings = []; entries = {}
    metrics = dict(filesDiscovered=0, filesRead=0, bytesRead=0, cacheHits=0, cacheMisses=0)
    seen = set()
    stopped = False
    for scope in scopes:
        start = safe_path(root, scope, exists=False)
        if not start.exists():
            issues["scope_unavailable"] += 1
            continue
        candidates = [(start.parent, [], [start.name])] if start.is_file() else os.walk(start, followlinks=False,
                    onerror=lambda _: issues.update(["directory_unavailable"]))
        for directory, dirs, names in candidates:
            kept = []
            for name in sorted(dirs):
                if name.lower() in EXCLUDE:
                    continue
                try:
                    safe_path(root, Path(directory) / name, exists=False)
                    kept.append(name)
                except ValueError:
                    issues["reparse_path"] += 1
            dirs[:] = kept
            for name in sorted(names):
                path = Path(directory) / name
                if path.suffix.lower() not in SUFFIXES:
                    continue
                relative = path.relative_to(root).as_posix()
                if relative.casefold() in seen:
                    continue
                seen.add(relative.casefold())
                metrics["filesDiscovered"] += 1
                if metrics["filesDiscovered"] > budget["maxFiles"] or time.perf_counter()-started >= budget["timeoutSeconds"]:
                    issues["budget_exceeded"] += 1; stopped = True; break
                try:
                    safe_path(root, path)
                    pre = path.stat()
                    if pre.st_size > budget["maxFileBytes"]:
                        issues["file_too_large"] += 1; continue
                    if metrics["bytesRead"] + pre.st_size > budget["maxBytes"]:
                        issues["budget_exceeded"] += 1; stopped = True; break
                    with path.open("rb") as stream:
                        content = stream.read(int(budget["maxFileBytes"]) + 1)
                    metrics["filesRead"] += 1; metrics["bytesRead"] += len(content)
                    post = path.stat()
                    if len(content) != pre.st_size or (pre.st_mtime_ns, pre.st_size) != (post.st_mtime_ns, post.st_size):
                        issues["source_changed_during_read"] += 1; continue
                    sha = hashlib.sha256(content).hexdigest()
                    cached = old_entries.get(relative, {})
                    previous = cached.get("findings", [])
                    valid = (cached.get("sha256") == sha and isinstance(previous, list) and
                             all(isinstance(f, dict) and set(f) == {"path", "line", "rule", "state"} and
                                 f["path"] == relative and f["rule"] in RULES and f["state"] == "candidate" and
                                 type(f["line"]) is int and f["line"] > 0 for f in previous))
                    if valid:
                        found = previous; metrics["cacheHits"] += 1
                    else:
                        found = _findings(relative, content); metrics["cacheMisses"] += 1
                    files.append(dict(path=relative, sha256=sha, bytes=len(content)))
                    entries[relative] = dict(sha256=sha, findings=found)
                    findings.extend(found)
                except (OSError, ValueError, UnicodeError):
                    issues["file_unavailable_or_unsupported_encoding"] += 1
            if stopped:
                break
        if stopped:
            break
    registry = None
    if ".agents/skills" in scopes:
        try:
            if stopped or time.perf_counter()-started >= budget["timeoutSeconds"]:
                raise ValueError("registry_budget_exceeded")
            registry = _registry(root, budget)
        except (OSError, ValueError, UnicodeError):
            issues["registry_input_invalid"] += 1
    metrics["analysisDurationMs"] = round((time.perf_counter()-started)*1000, 3)
    metrics["actualTokenUsage"] = None
    return dict(schemaVersion="demo1.skill-diagnostics.scan.v1", complete=not issues,
                scopes=scopes, limits=budget, files=files, findings=findings,
                issues=dict(issues), metrics=metrics, registry=registry,
                sourceFingerprint=digest(files), checkerHash=checker,
                cache=dict(schemaVersion="demo1.skill-diagnostics.cache.v1", checkerHash=checker, entries=entries),
                mutationAuthorized=False)


def _new_output(root, output):
    directory = safe_path(root, output, exists=False)
    directory.mkdir(parents=True, exist_ok=False)
    return directory


def run_adapter(root, output, adapter, *, log=None, synthetic_input=False):
    root = Path(root).absolute()
    if adapter not in {"registry", "log-diagnose"}:
        raise ValueError("adapter_not_allowlisted")
    directory = _new_output(root, output)
    if adapter == "registry":
        observation = scan(root, [".agents/skills"])
        inputs = {"adapter": adapter, "snapshot": observation["sourceFingerprint"]}
        target_id = "repo-skill-family"
        helper = OWNER / "scripts/awx_skill_registry.py"
    else:
        path = safe_path(root, log or "")
        inputs = {"adapter": adapter, "logHash": file_hash(path, 1000000)}
        target_id = "demo1-debugging-with-two-tools"
        helper = OWNER / "tools/ai_debug_assist.py"
    fingerprint = digest({"adapterSource": file_hash(helper), "wrapperSource": file_hash(Path(__file__)),
                          "traceSource": file_hash(OWNER / "tools/skill_diagnostics_trace.py"),
                          "logClassifier": file_hash(OWNER / "tools/build_error_miner.py")})
    evidence_path = directory / "input-evidence.json"
    write_json(root, evidence_path, dict(schemaVersion="demo1.skill-diagnostics.adapter-input.v1", **inputs))
    ctx = dict(skillId="demo1-observed-debugging-meta", targetSkillId=target_id,
               skillHash=file_hash(helper), sourceFingerprint=fingerprint,
               caseId=adapter, inputRef=digest(inputs), cohortId="offline-adapter-v1",
               environmentHash=digest({"python": sys.version_info[:3], "platform": sys.platform}),
               oracleHash=digest({"contract": adapter, "version": 1}), policyHash=digest({"retry":0,"external":False}),
               caseRef="adapter-case")
    event_path = directory / "events.jsonl"
    result = None
    with TraceRecorder(root, event_path, ctx, synthetic=synthetic_input) as rec:
        rec.decision("invoke-offline-adapter", evidence_refs=[{
            "path": evidence_path.relative_to(root).as_posix(), "sha256": file_hash(evidence_path)}])
        try:
            with rec.tool(adapter) as tool:
                if adapter == "registry":
                    result = _registry(root)
                    success = result["schemaVersion"] == "awx.skills.registry.v1" and result["conflictCount"] == 0
                else:
                    helper_module = _module("skill_meta_debug", helper)
                    result = helper_module.diagnose(path, [], root=root, use_mcp=False, use_ai=False)
                    success = result.get("status") == "observed" and result.get("claimsVerified") is False
                tool["status"] = "succeeded" if success else "failed"
                rec.set_result(tool["status"])
        except (OSError, ValueError, UnicodeError) as error:
            # The adapter failed; classify without returning exception text.
            result = dict(schemaVersion="demo1.skill-diagnostics.adapter-error.v1", reasonCode="invalid_adapter_input")
            rec.set_result("failed")
    metrics = aggregate(root, [event_path])
    report = dict(schemaVersion="demo1.skill-diagnostics.adapter.v1", adapter=adapter,
                  adapterStatus=rec.status, result=result, metrics=metrics,
                  providerAttemptEvidence="not_observed", externalCalls=0,
                  targetSemanticSuccess="not_evaluated", syntheticInput=synthetic_input)
    write_json(root, directory / "result.json", result)
    write_json(root, directory / "metrics.json", metrics)
    write_json(root, directory / "summary.json", report)
    return report


def triage(root, metrics_path, *, case_ref, evidence_id, targets=()):
    """Evidence attachment for the existing DebugCasePacket, never a patch gate."""
    root = Path(root).absolute()
    label(case_ref); label(evidence_id)
    metrics = revalidate_metrics(root, read_json(root, metrics_path))
    if any(row["caseRef"] != case_ref for row in metrics["runs"]):
        raise ValueError("case_binding_mismatch")
    classes = set()
    repeated = Counter()
    for row in metrics["runs"]:
        if row["status"] == "timed_out":
            classes.add("timeout")
        if row["status"] == "failed":
            classes.add("run_failure")
        if row["unsupportedDecisionCount"]:
            classes.add("evidence_missing")
        if row["contradictedDecisionCount"]:
            classes.add("oracle_contradiction")
        if row["complete"] and (row["status"] in {"failed", "timed_out"} or row["contradictedDecisionCount"]):
            repeated[tuple(row[k] for k in ("skillId", "targetSkillId", "caseId", "inputRef", "sourceFingerprint",
                                            "environmentHash", "oracleHash", "policyHash", "status"))] += 1
    if not metrics["telemetryComplete"]:
        classes.add("trace_incomplete")
    for group in metrics["skills"]:
        if group.get("toolStatuses", {}).get("failed", 0):
            classes.add("tool_execution_failure")
        if group.get("retryCount", 0):
            classes.add("retry_observed")
    preimages = []
    for value in targets:
        path = safe_path(root, value)
        preimages.append(dict(path=path.relative_to(root).as_posix(), sha256=file_hash(path)))
    return dict(schemaVersion="demo1.skill-diagnostics.triage.v1", caseRef=case_ref,
                evidenceRow=dict(evidenceId=evidence_id, sourceRef=safe_path(root, metrics_path).relative_to(root).as_posix(),
                                 sha256=file_hash(safe_path(root, metrics_path)), observationLayer="local_trace_analysis",
                                 observationUnit="declared_trace_batch", eventCount=metrics["eventCount"],
                                 telemetryComplete=metrics["telemetryComplete"],
                                 performedNow=True, executionOwner="parent-local-python",
                                 providerAttemptEvidence="not_observed"),
                candidateState="reproduced" if metrics["telemetryComplete"] and any(v >= 2 for v in repeated.values()) else "candidate",
                observedClasses=sorted(classes) or ["no_observed_failure"], causal=False,
                targetPreimages=preimages, mutationAuthorized=False,
                missingRepairEvidence=["causal_probe", "semantic_red", "existing_source_owner_gate"],
                nextAction="attach_evidence_to_existing_DebugCasePacket_then_select_one_causal_probe")


def reproduce(root, output):
    """Execute an intentionally defective synthetic oracle and one minimal correction."""
    root = Path(root).absolute()
    directory = _new_output(root, output)
    before_paths = []; after_paths = []
    for variant in ("before", "after"):
        for case in ("normal", "missing-evidence"):
            has_evidence = case == "normal"
            # Fixed expected behavior; the candidate's guess is never the oracle.
            expected = "verified" if has_evidence else "evidence_needed"
            actual = "verified" if variant == "before" else ("verified" if has_evidence else "evidence_needed")
            ctx = dict(skillId="synthetic-evidence-gate", skillHash=digest(variant),
                       sourceFingerprint=digest({"variant":variant}), caseId=case, inputRef=case,
                       cohortId="synthetic-fixed-v1", environmentHash=digest("fixture-env-v1"),
                       oracleHash=digest("verified-iff-evidence-present"), policyHash=digest("no-retry"), caseRef="synthetic-case")
            evidence_path = directory / (variant + "-" + case + "-evidence.json")
            packet = dict(schemaVersion="demo1.skill-diagnostics.verification.v1",
                          caseId=case, inputRef=case, sourceFingerprint=ctx["sourceFingerprint"],
                          oracleHash=ctx["oracleHash"], validatorId="fixed-expectation",
                          outcome="pass" if actual == expected else "contradiction")
            write_json(root, evidence_path, packet)
            path = directory / (variant + "-" + case + ".jsonl")
            with TraceRecorder(root, path, ctx, synthetic=True) as rec:
                rec.decision("evaluate-evidence", evidence_refs=[{
                    "path": evidence_path.relative_to(root).as_posix(), "sha256":file_hash(evidence_path)}])
                with rec.tool("fixed-oracle") as tool:
                    tool["status"] = "succeeded" if actual == expected else "failed"
                    rec.set_result(tool["status"])
            (before_paths if variant == "before" else after_paths).append(path)
    before = aggregate(root, before_paths); after = aggregate(root, after_paths)
    comparison = compare(before, after, root=root)
    report = dict(schemaVersion="demo1.skill-diagnostics.reproduction.v1", synthetic=True,
                  productionImprovementClaim=False, comparison=comparison,
                  fixtureCases=2, originalNormalCasePreserved=True)
    write_json(root, directory / "before-metrics.json", before)
    write_json(root, directory / "after-metrics.json", after)
    write_json(root, directory / "comparison.json", comparison)
    write_json(root, directory / "summary.json", report)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(OWNER))
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("scan"); p.add_argument("--scope", action="append"); p.add_argument("--cache"); p.add_argument("--out", required=True)
    p = sub.add_parser("run"); p.add_argument("--adapter", choices=["registry","log-diagnose"], required=True); p.add_argument("--log"); p.add_argument("--synthetic-input", action="store_true"); p.add_argument("--out", required=True)
    p = sub.add_parser("report"); p.add_argument("--events", action="append", required=True); p.add_argument("--expected-skill", action="append", default=[]); p.add_argument("--out", required=True)
    p = sub.add_parser("compare"); p.add_argument("--before", required=True); p.add_argument("--after", required=True); p.add_argument("--out", required=True)
    p = sub.add_parser("triage"); p.add_argument("--metrics", required=True); p.add_argument("--case-ref", required=True); p.add_argument("--evidence-id", required=True); p.add_argument("--target", action="append", default=[]); p.add_argument("--out", required=True)
    p = sub.add_parser("reproduce"); p.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    try:
        root = Path(args.root).absolute()
        if args.command == "scan":
            old = read_json(root, args.cache) if args.cache else None
            result = scan(root, args.scope, cache=old)
            out = _new_output(root, args.out)
            write_json(root, out / "inventory.json", {k:v for k,v in result.items() if k not in {"cache", "findings"}})
            write_json(root, out / "findings.json", result["findings"])
            write_json(root, out / "cache.json", result["cache"])
            print(json.dumps(dict(complete=result["complete"], findingCount=len(result["findings"]), **result["metrics"])))
            return 0 if result["complete"] else 2
        if args.command == "run":
            result = run_adapter(root, args.out, args.adapter, log=args.log, synthetic_input=args.synthetic_input)
            print(json.dumps({k:result[k] for k in ("adapter", "adapterStatus", "externalCalls")}))
            return 0 if result["adapterStatus"] == "succeeded" else 2
        if args.command == "reproduce":
            result = reproduce(root, args.out)
        elif args.command == "report":
            result = aggregate(root, args.events, expected_skills=args.expected_skill)
            write_json(root, args.out, result)
        elif args.command == "triage":
            result = triage(root, args.metrics, case_ref=args.case_ref, evidence_id=args.evidence_id, targets=args.target)
            write_json(root, args.out, result)
        else:
            result = compare(read_json(root, args.before), read_json(root, args.after), root=root)
            write_json(root, args.out, result)
        print(json.dumps({key:result[key] for key in ("schemaVersion", "status", "telemetryComplete", "synthetic", "comparison") if key in result}))
        return 2 if result.get("telemetryComplete") is False or result.get("status") in {"regressed", "not_comparable", "insufficient_evidence"} else 0
    except (ValueError, OSError, UnicodeError, KeyError, TypeError):
        print(json.dumps({"status":"evidence_needed", "reasonCode":"invalid_or_unavailable_input", "rawErrorStored":False}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
