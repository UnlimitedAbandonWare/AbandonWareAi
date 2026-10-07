#!/usr/bin/env python3
"""Offline contract verifier for Jev Choice `factMeta` mock fixtures.

Mirrors the wire semantics of (read-only anchors):
  main/java/com/example/lms/assist/JevGatewayClient.java   - transport, model check,
      strict answer key-set, observation(), validMetaDistribution(), cost()
  main/java/com/example/lms/assist/JevEvaluationRuntime.java - await() threshold acceptance
  main/java/com/example/lms/assist/JevChoiceAdvisor.java  - FACT_META labels, factMetaVerdict()

A fixture verdict is usable only when the whole chain accepts it; anything else
falls back to the baseline highModel FACT_META_CHECK call. Live API calls: 0.

Usage:
  python -B scripts/verify_jev_fact_meta_contract.py [--fixtures PATH] [--json]
Exit: 0 = every fixture judged exactly as its `expect` block declares.
"""
import json
import re
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FIXTURES = ROOT / "data/agent-handoff/codex-jev-assist/jev_fact_meta_fixtures.json"
LABELS = {"CONSISTENT", "MISMATCH", "INSUFFICIENT"}
FACT_META_ID = "factMeta"
EPSILON = 1e-6

# JevGatewayClient.PLAN_GATE (plan/policy wording inside a 403 detail payload).
PLAN_GATE = re.compile(
    r"(?i)(?:zero[\s_-]*data[\s_-]*retention|\bzdr\b|\bplan\b|\bpro\b|\benterprise\b)"
)


def _probability(node):
    """Java probability(): number, finite, 0<=v<=1 -> float else None."""
    if isinstance(node, bool) or not isinstance(node, (int, float)):
        return None
    value = float(node)
    return value if value == value and 0.0 <= value <= 1.0 else None


def _observation(node, allowed):
    """Java observation(): -> (choice, prob|None, schema_valid)."""
    invalid = ("", None, False)
    if not isinstance(node, dict):
        return invalid
    choice = node.get("choice") if isinstance(node.get("choice"), str) else ""
    if choice not in allowed:
        return invalid
    if "type" in node and node.get("type") != "choice":
        return invalid
    scalar = None
    if "probability" in node:
        scalar = _probability(node.get("probability"))
        if scalar is None:
            return invalid
    distribution = None
    if "probabilities" in node:
        values = node.get("probabilities")
        if not isinstance(values, dict):
            return invalid
        for key, raw in values.items():
            if key not in allowed or _probability(raw) is None:
                return invalid
        distribution = _probability(values.get(choice))
        if distribution is None:
            return invalid
    if scalar is not None and distribution is not None and abs(scalar - distribution) > EPSILON:
        return invalid
    return (choice, scalar if scalar is not None else distribution, True)


def _valid_meta_distribution(answer, labels):
    """Java validMetaDistribution(): exact label set, chosen = max, sum ~= 1."""
    values = answer.get("probabilities") if isinstance(answer, dict) else None
    if not isinstance(values, dict) or len(values) != len(labels):
        return False
    raw_choice = values.get(answer.get("choice"), None)
    chosen = _probability(raw_choice) if raw_choice is not None else -1.0
    total = 0.0
    for label in labels:
        p = _probability(values.get(label))
        if p is None or p > chosen:
            return False
        total += p
    return abs(total - 1.0) <= EPSILON


def _cost(node):
    """Java cost(): providerMetadata.gateway.cost (or gateway.cost) textual >=0."""
    gateway = node.get("providerMetadata", {}).get("gateway", {})
    value = gateway.get("cost") if "cost" in gateway else node.get("gateway", {}).get("cost")
    if not isinstance(value, str):
        return None
    try:
        parsed = Decimal(value)
    except InvalidOperation:
        return None
    return parsed if parsed >= 0 else None


def _plan_gate_403(body):
    """Java planGate403(): PLAN_GATE wording inside the error detail."""
    detail = ""
    try:
        root = json.loads(body) if isinstance(body, (str, bytes)) else body
        error = root.get("error") if isinstance(root, dict) else None
        if isinstance(error, str):
            detail = error
        elif isinstance(error, dict):
            detail = " ".join(str(error.get(k, "")) for k in ("message", "code", "type"))
        elif isinstance(root, dict):
            detail = str(root.get("message", ""))
    except Exception:
        detail = body if isinstance(body, str) else ""
    return bool(PLAN_GATE.search(detail))


def _retry_after_ms(headers):
    """Java retryAfterMs(): Retry-After seconds * 1000, default 30000."""
    raw = (headers or {}).get("Retry-After") or (headers or {}).get("retry-after")
    try:
        return int(str(raw).strip()) * 1000
    except (TypeError, ValueError):
        return 30000


def _transport_failure(status, headers, body):
    """Java exchange() non-200 mapping -> (reasonCode, retryAfterMs|None)."""
    if status == 401:
        return "auth_invalid", None
    if status == 402:
        return "billing-blocked", None
    if status == 403:
        return ("plan_gate" if _plan_gate_403(body) else "permission_denied"), None
    if status == 429:
        return "rate_limited", _retry_after_ms(headers)
    if 500 <= status <= 599:
        return "upstream_error", None
    if 300 <= status < 400:
        return "redirect", None
    return f"http_{status}", None


def evaluate(fixture, model, thresholds):
    """Replay one fixture through the mirrored contract."""
    status = int(fixture.get("httpStatus", 0))
    headers = fixture.get("headers") or {}
    body = fixture.get("body")
    out = {
        "verdict": None,
        "reasonCode": None,
        "schemaValid": False,
        "confidenceAccepted": False,
        "billedUsd": None,
        "fallbackToBaseline": True,
        "retryAfterMs": None,
    }
    if status != 200:
        reason, retry = _transport_failure(status, headers, body)
        out["reasonCode"] = reason
        out["retryAfterMs"] = retry
        return out
    if not isinstance(body, dict):
        out["reasonCode"] = "invalid_response"
        return out
    reported = body.get("model")
    if not isinstance(reported, str) or not reported:
        out["reasonCode"] = "model_unverified"
        return out
    alias = model.rsplit("/", 1)[-1]
    if reported.lower() not in (model.lower(), alias.lower()):
        out["reasonCode"] = "wrong_model"
        return out
    answers = body.get("answers")
    if not isinstance(answers, dict):
        out["reasonCode"] = "invalid_response"
        return out
    if set(answers.keys()) != {FACT_META_ID}:  # strictChoices: exact key set
        out["reasonCode"] = "invalid_response"
        return out
    billed = _cost(body)
    out["billedUsd"] = str(billed) if billed is not None else None
    choice, prob, schema_valid = _observation(answers.get(FACT_META_ID), LABELS)
    meta_ok = _valid_meta_distribution(answers.get(FACT_META_ID), LABELS)
    if not (schema_valid and meta_ok):
        choice, prob, schema_valid = "", None, False
    out["reasonCode"] = "ok"
    out["schemaValid"] = schema_valid
    threshold = thresholds.get(choice) if schema_valid else None
    accepted = schema_valid and prob is not None and threshold is not None and prob >= threshold
    out["confidenceAccepted"] = accepted
    if accepted:
        out["verdict"] = choice
        out["fallbackToBaseline"] = False
    return out


def main():
    args = sys.argv[1:]
    fixtures_path = DEFAULT_FIXTURES
    as_json = False
    i = 0
    while i < len(args):
        if args[i] == "--fixtures" and i + 1 < len(args):
            fixtures_path = Path(args[i + 1])
            i += 2
        elif args[i] == "--json":
            as_json = True
            i += 1
        else:
            print(f"unknown arg: {args[i]}", file=sys.stderr)
            return 2
    if not fixtures_path.is_file():
        print(f"MISSING fixtures file: {fixtures_path}", file=sys.stderr)
        return 2
    data = json.loads(fixtures_path.read_text(encoding="utf-8"))
    contract = data.get("requestContract", {})
    model = contract.get("model", "typesafe-ai/jev")
    thresholds = contract.get("assumedThresholds") or {}
    labels = set((contract.get("question") or {}).get("labels") or LABELS)
    if labels != LABELS:
        print(f"FAIL contract labels {sorted(labels)} != {sorted(LABELS)}", file=sys.stderr)
        return 1
    fixtures = data.get("fixtures") or []
    rows, mismatches = [], []
    for fixture in fixtures:
        actual = evaluate(fixture, model, thresholds)
        expect = fixture.get("expect") or {}
        diffs = [
            key for key in ("verdict", "reasonCode", "schemaValid", "confidenceAccepted",
                            "billedUsd", "fallbackToBaseline", "retryAfterMs")
            if key in expect and actual.get(key) != expect.get(key)
        ]
        rows.append((fixture.get("id"), actual, expect, diffs))
        if diffs:
            mismatches.append((fixture.get("id"), diffs, actual, expect))
    if as_json:
        print(json.dumps({"fixtureCount": len(rows), "mismatches": [
            {"id": fid, "diffs": d, "actual": a} for fid, d, a, _ in mismatches]}, indent=2))
    else:
        print(f"fixtures={len(rows)} model={model} thresholds={thresholds}")
        for fid, actual, expect, diffs in rows:
            mark = "PASS" if not diffs else "FAIL"
            print(f"  [{mark}] {fid}: verdict={actual['verdict']} reason={actual['reasonCode']} "
                  f"schemaValid={actual['schemaValid']} accepted={actual['confidenceAccepted']} "
                  f"billed={actual['billedUsd']} fallback={actual['fallbackToBaseline']}"
                  + (f"  diff={diffs}" if diffs else ""))
    if mismatches:
        print(f"RESULT: FAIL mismatches={len(mismatches)}")
        return 1
    print(f"RESULT: PASS fixtures={len(rows)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
