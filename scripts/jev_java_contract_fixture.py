#!/usr/bin/env python3
"""Offline Java evaluate payload extracted from JevGatewayClient.

Reads the client source and stops with exit 2 on drift. Does not call a
provider and does not invent missing fields. Request bodies omit
zeroDataRetention.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CLI = ROOT / "main" / "java" / "com" / "example" / "lms" / "assist" / "JevGatewayClient.java"
DEFAULT_OUT = ROOT / "data" / "agent-handoff" / "grok-jev-v2-support-20260930"
MOCK_PATH = ROOT / "scripts" / "jev_mock_gateway.py"
STATE_KEYS = ("query", "surface", "baselineRoute", "externalDecisionAllowed")
SYNTHETIC_QUERY = "합성 질문: 오늘 서울 날씨 알려줘"
EVALUATE_URL = "https://ai-gateway.vercel.sh/v1/evaluate"

PARITY_REASONS = (
    ("model_substring", None, "wrong_model"),
    ("model_bare", None, None),
    ("model_missing", "model_unverified", "model_unverified"),
    ("choice_number", "invalid_response", "invalid_response"),
    ("choice_object", "invalid_response", "invalid_response"),
    ("route_missing", "invalid_response", "invalid_response"),
    ("provider_403", "permission_denied", "permission_denied"),
    ("billing_402", "billing-blocked", "billing-blocked"),
    ("upstream_503", "upstream_error", "upstream_error"),
    ("slow_body", "timeout", "timeout"),
    ("status_401", "auth_invalid", "auth_invalid"),
    ("status_429", "rate_limited", "rate_limited"),
    ("zdr_plan_gate", "plan_gate", "plan_gate"),
)


class DriftError(Exception):
    pass


def _concat_literals(expr: str) -> str:
    parts = []
    i = 0
    while i < len(expr):
        if expr[i].isspace() or expr[i] == "+":
            i += 1
            continue
        if expr[i] != '"':
            raise DriftError("instructions-not-literals")
        j = i + 1
        while j < len(expr) and expr[j] != '"':
            if expr[j] == "\\":
                raise DriftError("instructions-escape")
            j += 1
        if j >= len(expr):
            raise DriftError("instructions-unclosed")
        parts.append(expr[i + 1:j])
        i = j + 1
    if not parts:
        raise DriftError("instructions-absent")
    return "".join(parts)


def _string_pairs(expr: str) -> list[tuple[str, str]]:
    pairs = []
    i = 0
    while i < len(expr):
        if expr[i].isspace() or expr[i] == ",":
            i += 1
            continue
        if expr[i] != '"':
            raise DriftError("criteria-not-pairs")
        def take_string(start: int) -> tuple[str, int]:
            j = start + 1
            while j < len(expr) and expr[j] != '"':
                if expr[j] == "\\":
                    raise DriftError("criteria-escape")
                j += 1
            if j >= len(expr):
                raise DriftError("criteria-unclosed")
            return expr[start + 1:j], j + 1
        left, i = take_string(i)
        while i < len(expr) and (expr[i].isspace() or expr[i] == ","):
            if expr[i] == ",":
                i += 1
                break
            i += 1
        else:
            raise DriftError("criteria-separator")
        while i < len(expr) and expr[i].isspace():
            i += 1
        if i >= len(expr) or expr[i] != '"':
            raise DriftError("criteria-value")
        right, i = take_string(i)
        pairs.append((left, right))
    if len(pairs) != 5:
        raise DriftError("criteria-count")
    return pairs


def _statement_end(text: str, start: int) -> int:
    i = start
    while i < len(text):
        if text[i] == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                if text[i] == "\\":
                    i += 2
                    continue
                i += 1
            if i >= len(text):
                raise DriftError("statement-unclosed")
            i += 1
            continue
        if text[i] == ";":
            return i
        i += 1
    raise DriftError("statement-semicolon")


def _assignment_expr(text: str, name: str) -> str:
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*", text)
    if not match:
        raise DriftError(name.lower() + "-absent")
    end = _statement_end(text, match.end())
    return text[match.end():end]


def extract_client(text: str) -> dict:
    instructions_expr = _assignment_expr(text, "INSTRUCTIONS")
    if "Map.of" in instructions_expr:
        raise DriftError("instructions-span")
    instructions = _concat_literals(instructions_expr.strip())
    criteria_expr = _assignment_expr(text, "CRITERIA")
    open_at = criteria_expr.find("Map.of(")
    close_at = criteria_expr.rfind(")")
    if open_at < 0 or close_at < open_at:
        raise DriftError("criteria-map")
    pairs = _string_pairs(criteria_expr[open_at + len("Map.of("):close_at])
    criteria = {}
    for key, value in pairs:
        if key in criteria:
            raise DriftError("criteria-duplicate")
        criteria[key] = value
    only_match = re.search(
        r'\.put\(\s*"only"\s*,\s*List\.of\((.*?)\)\s*\)', text, re.S)
    if not only_match:
        raise DriftError("only-absent")
    only_values = re.findall(r'"([^"\\]*)"', only_match.group(1))
    if not only_values or re.sub(r'"[^"\\]*"', "", only_match.group(1)).strip(" \t\r\n,") != "":
        raise DriftError("only-shape")
    zdr = re.search(
        r'if\s*\(\s*zeroDataRetention\s*\)\s*gatewayOptions\.put\(\s*"zeroDataRetention"\s*,\s*true\s*\)\s*;',
        text)
    if not zdr:
        raise DriftError("zdr-conditional")
    state_match = re.search(r'\.put\(\s*"state"\s*,\s*Map\.of\((.*?)\)\s*\)\s*;', text, re.S)
    if not state_match:
        raise DriftError("state-map")
    state_keys = re.findall(r'"([^"\\]+)"\s*,', state_match.group(1))
    if tuple(state_keys) != STATE_KEYS:
        raise DriftError("state-keys")
    if not re.search(r'"externalDecisionAllowed"\s*,\s*true\b', state_match.group(1)):
        raise DriftError("state-external")
    return {
        "instructions": instructions,
        "criteria": criteria,
        "only": only_values,
        "zdrConditional": True,
        "stateKeys": list(state_keys),
    }


def build_request(extracted: dict) -> dict:
    body = {
        "model": "typesafe-ai/jev",
        "state": {
            "query": SYNTHETIC_QUERY,
            "surface": "focus",
            "baselineRoute": "RECENT_ONLY",
            "externalDecisionAllowed": True,
        },
        "questions": {
            "routeDecision": {
                "type": "choice",
                "instructions": extracted["instructions"],
                "criteria": extracted["criteria"],
            }
        },
        "providerOptions": {"gateway": {"only": list(extracted["only"])}},
    }
    if "zeroDataRetention" in json.dumps(body):
        raise DriftError("zdr-key-emitted")
    return {
        "method": "POST",
        "url": EVALUATE_URL,
        "headers": {"Content-Type": "application/json"},
        "body": body,
        "note": "SYNTHETIC; Authorization 없음",
    }


def _load_mock():
    spec = importlib.util.spec_from_file_location("jev_mock_gateway", MOCK_PATH)
    if spec is None or spec.loader is None:
        raise DriftError("mock-import")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_parity(cli_text: str) -> dict:
    mock = _load_mock()
    substring_now = None if re.search(r'\.contains\(\s*"jev"\s*\)', cli_text) else "wrong_model"
    rows = []
    for name, reason_now, reason_after in PARITY_REASONS:
        now = substring_now if name == "model_substring" else reason_now
        if name in mock.SCENARIOS:
            status, raw, slow = mock.scenario_response(name, "HYBRID")
            sample = json.loads(raw.decode("utf-8"))
        elif name == "status_401":
            status, sample, slow = 401, json.loads(mock.response_body(401).decode("utf-8")), False
        elif name == "status_429":
            status, sample, slow = 429, json.loads(mock.response_body(429).decode("utf-8")), False
        else:
            raise DriftError("parity-scenario")
        row = {
            "scenario": name,
            "status": status,
            "bodySample": sample,
            "expectedJavaReasonNow": now,
            "expectedAfterWP": reason_after,
            "evidence": "SYNTHETIC_UNVERIFIED",
        }
        if name == "slow_body":
            row["slowBody"] = slow
            row["javaHttpStatus"] = 0
        if name == "status_401":
            row["linkedExisting"] = "STATUSES"
        if name == "status_429":
            row["linkedExisting"] = "retry-after"
        if name == "zdr_plan_gate":
            row["linkedExisting"] = "scenario"
        if name == "model_bare":
            row["wp1Option"] = "A"
        rows.append(row)
    return {
        "evidence": "SYNTHETIC_UNVERIFIED",
        "observedSubstringCheck": bool(re.search(r'\.contains\(\s*"jev"\s*\)', cli_text)),
        "rows": rows,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Extract a synthetic Jev evaluate fixture from Java source.")
    parser.add_argument("--cli", default=str(DEFAULT_CLI))
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT))
    args = parser.parse_args(argv)
    path = Path(args.cli)
    try:
        text = path.read_text(encoding="utf-8")
        extracted = extract_client(text)
        request = build_request(extracted)
        parity = build_parity(text)
    except (OSError, DriftError) as exc:
        print("drift: " + str(exc))
        return 2
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "java_evaluate_request.json").write_text(
        json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "contract_parity.json").write_text(
        json.dumps(parity, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "out": str(out),
        "criteria": list(extracted["criteria"]),
        "only": extracted["only"],
        "zdrConditional": extracted["zdrConditional"],
        "drift": False,
    }, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
