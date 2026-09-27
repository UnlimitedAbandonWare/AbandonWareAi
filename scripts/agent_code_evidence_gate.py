"""Reduce independently collected verification evidence to a review-only verdict.

The caller owns the immutable contract and oracle outside candidate write scope.
This module does not execute commands, collect missing proof, or grant mutation
authority. Feed it run_verified_command's run.json plus separately collected
tool, identity, oracle and mutation observations. Missing adapters stay HOLD.
"""
import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

MAX_INPUT_BYTES = 262144
HASH_NAMES = ("candidate", "fixture", "oracle", "root", "toolchain")
MUTATIONS = ("source", "apply", "rollback", "deploy", "provider", "database")
COUNTS = ("tests", "failures", "errors", "skipped")
SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")


def _hash(value):
    return isinstance(value, str) and SHA256.fullmatch(value) is not None


def _count(value):
    return type(value) is int and 0 <= value <= 10_000_000


def _timestamp(value):
    if not isinstance(value, str) or len(value) > 64:
        return None
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return result.astimezone(timezone.utc) if result.tzinfo else None
    except (ValueError, OverflowError):
        return None


def _result(holds, rejects, run=None, tests=0):
    run = run if isinstance(run, dict) else {}
    return {"schema": "agent_code_evidence_gate.v1",
            "verdict": "REJECT" if rejects else "HOLD" if holds else "PASS",
            "reasonCodes": sorted(holds | rejects), "mutationAllowed": False,
            "applyAllowed": False, "reviewOnly": True, "testCount": tests,
            "commandSha256": run.get("commandSha256") if _hash(run.get("commandSha256")) else None,
            "elapsedMs": run.get("elapsedMs") if _count(run.get("elapsedMs")) else None}


def evaluate(run, evidence, contract, *, now=None):
    """Pure reducer. Mandatory candidate failures dominate missing prerequisites."""
    holds, rejects, run_rejects = set(), set(), set()
    now = now or datetime.now(timezone.utc)
    if not isinstance(contract, dict) or contract.get("schema") != "agent_code_evidence_contract.v1":
        return _result({"contract_missing_or_invalid"}, set())
    if not isinstance(evidence, dict):
        return _result({"evidence_missing"}, set())
    expected = contract.get("hashes")
    observed = evidence.get("hashes")
    expected = expected if isinstance(expected, dict) else {}
    observed = observed if isinstance(observed, dict) else {}
    for key in HASH_NAMES:
        if not _hash(expected.get(key)) or not _hash(observed.get(key)):
            holds.add("identity_evidence_missing")
        elif expected[key].lower() != observed[key].lower():
            if key == "candidate":
                rejects.add("candidate_hash_mismatch")
            else:
                holds.add(key + "_identity_mismatch")
    after = evidence.get("oracleAfterSha256")
    if not _hash(after) or not _hash(observed.get("oracle")):
        holds.add("oracle_evidence_missing")
    elif after.lower() != observed["oracle"].lower():
        rejects.add("hidden_oracle_mutated")
    for key, reason in (("oracleIndependent", "oracle_independence_unproven"),
                        ("sandboxAvailable", "sandbox_unavailable"),
                        ("requiredToolsAvailable", "required_tool_unavailable"),
                        ("evidenceComplete", "evidence_incomplete"),
                        ("deterministic", "nondeterministic_verdict")):
        if evidence.get(key) is not True:
            holds.add(reason)
    for key, reason in (("staticNewHighCount", "static_analysis_new_high"),
                        ("secretHitCount", "secret_leak_risk")):
        if not _count(evidence.get(key)):
            holds.add("analysis_evidence_missing")
        elif evidence[key] > 0:
            rejects.add(reason)
    if evidence.get("expectedSignalMatches") is False:
        rejects.add("expected_signal_mismatch")
    elif evidence.get("expectedSignalMatches") is not True:
        holds.add("expected_signal_unproven")
    mutations = evidence.get("mutationCounts")
    mutations = mutations if isinstance(mutations, dict) else {}
    for key in MUTATIONS:
        if not _count(mutations.get(key)):
            holds.add("mutation_evidence_missing")
        elif mutations[key] != 0:
            rejects.add("mutation_observed")

    if not isinstance(run, dict):
        return _result(holds | {"run_missing"}, rejects)
    run_id = contract.get("runId")
    if not isinstance(run_id, str) or not 1 <= len(run_id) <= 128 or run.get("runId") != run_id:
        holds.add("run_identity_mismatch")
    started, ended = _timestamp(run.get("startedAt")), _timestamp(run.get("endedAt"))
    max_age = contract.get("maxAgeSeconds")
    if (not _count(max_age) or not 1 <= max_age <= 86400 or started is None or ended is None
            or not isinstance(now, datetime) or now.tzinfo is None
            or not started <= ended <= now or (now - ended).total_seconds() > max_age):
        holds.add("run_freshness_unproven")
    if not _hash(run.get("commandSha256")):
        holds.add("command_identity_missing")
    elif not _hash(contract.get("commandSha256")) or run["commandSha256"].lower() != contract["commandSha256"].lower():
        holds.add("command_identity_mismatch")
    if not isinstance(contract.get("scope"), str) or not contract["scope"] or run.get("scope") != contract["scope"]:
        holds.add("run_scope_mismatch")
    try:
        run_hash = hashlib.sha256(json.dumps(run, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    except (ValueError, TypeError, RecursionError):
        run_hash = None
    if not _hash(contract.get("runSha256")) or run_hash != contract["runSha256"].lower():
        holds.add("run_hash_mismatch")
    failures = run.get("failures")
    if not isinstance(failures, list) or len(failures) > 100:
        holds.add("run_result_invalid")
        failures = []
    for failure in failures:
        if not isinstance(failure, str):
            holds.add("run_result_invalid")
        elif failure.startswith("stale_xml:"):
            holds.add("junit_evidence_stale")
        elif failure.startswith("missing_xml:"):
            holds.add("junit_evidence_missing")
        elif failure == "junit_failure":
            run_rejects.add("junit_failed")
        else:
            holds.add("run_evidence_incomplete")
    status = run.get("status")
    exit_code, verification_code = run.get("exitCode"), run.get("verificationExitCode")
    if status == "failed" and type(exit_code) is int and exit_code > 0:
        run_rejects.add("command_failed")
    elif status != "passed" or type(exit_code) is not int or exit_code != 0:
        holds.add("command_success_unproven")
    if type(verification_code) is not int or verification_code != 0:
        holds.add("verification_incomplete")

    required = contract.get("requiredSuites")
    suites = run.get("expectedSuites")
    valid_suites = (isinstance(required, list) and 1 <= len(required) <= 100
                    and all(isinstance(s, str) and 1 <= len(s) <= 256 for s in required)
                    and len(set(required)) == len(required))
    if (not valid_suites or not isinstance(suites, list) or len(suites) > 100
            or not all(isinstance(s, str) for s in suites) or sorted(required) != sorted(suites)):
        holds.add("required_suites_unproven")
    totals = run.get("totals")
    totals = totals if isinstance(totals, dict) else {}
    valid_totals = all(_count(totals.get(k)) for k in COUNTS)
    tests = totals["tests"] if valid_totals else 0
    if not valid_totals:
        holds.add("junit_counts_invalid")
    elif totals["failures"] or totals["errors"]:
        run_rejects.add("junit_failed")
    if tests <= 0 or (valid_totals and totals["skipped"] >= tests):
        holds.add("junit_zero_executed")
    files = run.get("resultFiles")
    if not isinstance(files, list) or not 1 <= len(files) <= 100:
        holds.add("junit_results_missing")
        files = []
    names, summed = [], dict.fromkeys(COUNTS, 0)
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("suite"), str) or not _hash(item.get("sha256")):
            holds.add("junit_result_invalid")
            continue
        counts = item.get("counts")
        if not isinstance(counts, dict) or not all(_count(counts.get(k)) for k in COUNTS):
            holds.add("junit_counts_invalid")
            continue
        if counts["tests"] <= 0 or sum(counts[k] for k in ("failures", "errors", "skipped")) > counts["tests"]:
            holds.add("junit_counts_invalid")
        names.append(item["suite"])
        for key in COUNTS:
            summed[key] += counts[key]
    if not valid_suites or sorted(names) != sorted(required) or len(set(names)) != len(names):
        holds.add("junit_suite_coverage_missing")
    if not valid_totals or any(summed[k] != totals.get(k) for k in COUNTS):
        holds.add("junit_counts_mismatch")
    if holds.intersection({"run_freshness_unproven", "run_identity_mismatch", "run_hash_mismatch",
                           "command_identity_missing", "command_identity_mismatch", "run_scope_mismatch",
                           "root_identity_mismatch", "identity_evidence_missing"}):
        if run_rejects:
            holds.add("candidate_failure_unverified")
    else:
        rejects.update(run_rejects)
    return _result(holds, rejects, run, tests)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_key")
        result[key] = value
    return result


def _read_bounded(path):
    with path.open("rb") as stream:
        raw = stream.read(MAX_INPUT_BYTES + 1)
    if len(raw) > MAX_INPUT_BYTES:
        raise ValueError("input_oversized")
    return raw


def _parse_object(raw):
    value = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_unique_object,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non_finite")))
    if not isinstance(value, dict):
        raise ValueError("input_not_object")
    return value


def _same_file(first, second):
    return first == second or (first.exists() and second.exists() and first.samefile(second))


def evaluate_file(path, *, contract_path=None, contract_sha256=None, now=None, protected_outputs=()):
    """Read observations against a separately pinned, gate-owned contract and oracle.

    The contract path and pin must be supplied by the gate owner, outside the
    candidate's envelope. The pin covers command, run record and oracle baseline.
    Filesystem separation supplements the caller's existing sandbox/ownership
    boundary; this function does not create or attest that sandbox.
    """
    try:
        path = Path(path).resolve()
        outputs = tuple(Path(p).resolve() for p in protected_outputs)
        if any(_same_file(path, output) for output in outputs):
            return _result({"output_input_conflict"}, set())
        if contract_path is None or not _hash(contract_sha256):
            return _result({"independent_contract_missing"}, set())
        contract_path = Path(contract_path).resolve()
        if any(_same_file(contract_path, output) for output in outputs):
            return _result({"output_input_conflict"}, set())
        raw_contract = _read_bounded(contract_path)
        if hashlib.sha256(raw_contract).hexdigest() != contract_sha256.lower():
            return _result({"contract_identity_mismatch"}, set())
        contract = _parse_object(raw_contract)
        for key in ("candidateRoot", "oraclePath"):
            if not isinstance(contract.get(key), str) or not 1 <= len(contract[key]) <= 1024:
                return _result({"oracle_independence_unproven"}, set())
        candidate_root = (contract_path.parent / contract["candidateRoot"]).resolve()
        oracle_path = (contract_path.parent / contract["oraclePath"]).resolve()
        if (not candidate_root.is_dir() or contract_path.is_relative_to(candidate_root)
                or oracle_path.is_relative_to(candidate_root) or _same_file(path, contract_path)
                or _same_file(path, oracle_path) or _same_file(contract_path, oracle_path)):
            return _result({"oracle_independence_unproven"}, set())
        if any(_same_file(oracle_path, output) for output in outputs):
            return _result({"output_input_conflict"}, set())
        oracle_hash = hashlib.sha256(_read_bounded(oracle_path)).hexdigest()
        envelope = _parse_object(_read_bounded(path))
        evidence = envelope.get("evidence")
        if isinstance(evidence, dict):
            evidence = dict(evidence, oracleAfterSha256=oracle_hash)
        return evaluate(envelope.get("run"), evidence, contract, now=now)
    except (OSError, ValueError, TypeError, RuntimeError):
        return _result({"input_missing_or_invalid"}, set())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", help="Independently collected run and observation envelope")
    parser.add_argument("--contract", help="Immutable gate-owned contract outside candidate write scope")
    parser.add_argument("--contract-sha256", help="Contract hash supplied independently by the gate owner")
    args = parser.parse_args(argv)
    result = evaluate_file(args.input, contract_path=args.contract, contract_sha256=args.contract_sha256) if args.input else _result({"input_missing_or_invalid"}, set())
    print(json.dumps(result, sort_keys=True))
    return {"PASS": 0, "HOLD": 3, "REJECT": 2}[result["verdict"]]


if __name__ == "__main__":
    raise SystemExit(main())
