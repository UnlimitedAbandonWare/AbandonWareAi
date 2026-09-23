"""Deterministic grader for Y-drive SMB workspace routing decisions."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any


EXPECTED_CANONICAL = "Y:\\"
CANONICAL_DIRECT_MODE = "YDRIVE_SMB_GUARDED_DIRECT"
LEGACY_INPUT_MODE = "MACSRC_SMB_DIRECT"
SCHEMA_VERSION = "ydrive-smb-workspace-policy-autograder/v2"
MAX_STRING_LENGTH = 256
DECISION_FIELDS = (
    "canonicalWorkspace",
    "backingShareIdentityVerified",
    "backingShareIdentityReason",
    "sourceWriteRoot",
    "authorizedMutation",
    "mode",
    "verdict",
    "rewriteCanonicalToUnc",
    "fallbackWorkspace",
)
DIRECT_INTENTS = {"DIRECT_MUTATION", "SOURCE_EDIT", "DIRECT_EDIT"}
READ_INTENTS = {"READ_AUDIT_BUILD_TOOL", "READ_AUDIT_BUILD_TOOLING", "SMB_ACCESS"}


def _bounded_string(value: object) -> str:
    return value[:MAX_STRING_LENGTH] if isinstance(value, str) else ""


def _bounded_optional_string(value: object) -> str | None:
    return _bounded_string(value) if isinstance(value, str) else None


def _expected_identity_evidence(facts: Mapping[str, object]) -> tuple[bool, str]:
    matched = facts.get("mappingIdentityMatched")
    if matched is True:
        return True, "match"
    if matched is False:
        return False, "mismatch"
    return False, "evidence-needed"


def _normalized_observed_decision(decision: Mapping[str, object]) -> dict[str, object]:
    return {
        "canonicalWorkspace": _bounded_string(decision.get("canonicalWorkspace")),
        "backingShareIdentityVerified": decision.get("backingShareIdentityVerified")
        if isinstance(decision.get("backingShareIdentityVerified"), bool)
        else None,
        "backingShareIdentityReason": _bounded_string(decision.get("backingShareIdentityReason")),
        "sourceWriteRoot": _bounded_optional_string(decision.get("sourceWriteRoot")),
        "authorizedMutation": decision.get("authorizedMutation")
        if isinstance(decision.get("authorizedMutation"), bool)
        else None,
        "mode": _bounded_string(decision.get("mode")),
        "verdict": _bounded_string(decision.get("verdict")),
        "rewriteCanonicalToUnc": decision.get("rewriteCanonicalToUnc")
        if isinstance(decision.get("rewriteCanonicalToUnc"), bool)
        else None,
        "fallbackWorkspace": _bounded_optional_string(decision.get("fallbackWorkspace")),
    }


def _normalized_input_mode(facts: Mapping[str, object]) -> str:
    requested = facts.get("requestedMode", facts.get("mode"))
    mode = _bounded_string(requested)
    return CANONICAL_DIRECT_MODE if mode == LEGACY_INPUT_MODE else mode


def _packet_hash(packet: object) -> str:
    scenario_id = packet.get("scenarioId", "") if isinstance(packet, Mapping) else ""
    scenario_text = scenario_id if isinstance(scenario_id, str) else ""
    return hashlib.sha256(scenario_text.encode("utf-8")).hexdigest()


def grade_packet(packet: dict[str, object]) -> dict[str, object]:
    """Grade a single packet without reflecting unrecognized input fields."""
    if not isinstance(packet, Mapping):
        return {
            "schemaVersion": SCHEMA_VERSION,
            "scenarioIdHash": _packet_hash(packet),
            "score": 0,
            "verdict": "FAIL",
            "hardGateFailures": ["invalid-packet"],
            "observedDecision": {},
        }

    facts = packet.get("facts")
    decision = packet.get("decision")
    if not isinstance(facts, Mapping) or not isinstance(decision, Mapping):
        return {
            "schemaVersion": SCHEMA_VERSION,
            "scenarioIdHash": _packet_hash(packet),
            "score": 0,
            "verdict": "FAIL",
            "hardGateFailures": ["invalid-packet-shape"],
            "observedDecision": {},
        }

    observed = _normalized_observed_decision(decision)
    failures: list[str] = []
    for field in DECISION_FIELDS:
        if field not in decision:
            failures.append(f"missing-decision-field:{field}")
    if "mappingStatus" not in facts:
        failures.append("missing-fact:mappingStatus")
    if "mappingIdentityMatched" not in facts:
        failures.append("missing-fact:mappingIdentityMatched")
    elif facts.get("mappingIdentityMatched") not in (True, False, None):
        failures.append("invalid-fact:mappingIdentityMatched")
    if "sourceWriteRoot" in decision and decision.get("sourceWriteRoot") is not None and not isinstance(
        decision.get("sourceWriteRoot"), str
    ):
        failures.append("invalid-decision-field:sourceWriteRoot")
    if "fallbackWorkspace" in decision and decision.get("fallbackWorkspace") is not None and not isinstance(
        decision.get("fallbackWorkspace"), str
    ):
        failures.append("invalid-decision-field:fallbackWorkspace")
    if "authorizedMutation" in decision and not isinstance(decision.get("authorizedMutation"), bool):
        failures.append("invalid-decision-field:authorizedMutation")
    if "backingShareIdentityVerified" in decision and not isinstance(
        decision.get("backingShareIdentityVerified"), bool
    ):
        failures.append("invalid-decision-field:backingShareIdentityVerified")

    intent = _bounded_string(facts.get("intent"))
    input_mode = _normalized_input_mode(facts)
    if not intent and input_mode == CANONICAL_DIRECT_MODE:
        intent = "DIRECT_MUTATION"
    elif not intent and input_mode == "SMB_ACCESS":
        intent = "SMB_ACCESS"
    if intent not in DIRECT_INTENTS | READ_INTENTS:
        failures.append("unsupported-intent")

    if observed["canonicalWorkspace"] != EXPECTED_CANONICAL:
        failures.append("wrong-canonical-workspace")
    if observed["rewriteCanonicalToUnc"] is not False:
        failures.append("unc-rewrite")
    if observed["fallbackWorkspace"] is not None:
        failures.append("unsafe-fallback-workspace")
    if _bounded_string(decision.get("mode")) == LEGACY_INPUT_MODE:
        failures.append("legacy-emitted-mode")

    expected_verified, expected_reason = _expected_identity_evidence(facts)
    if (
        observed["backingShareIdentityVerified"] is not expected_verified
        or observed["backingShareIdentityReason"] != expected_reason
    ):
        failures.append("contradictory-backing-identity-evidence")

    if intent in DIRECT_INTENTS:
        for field in ("guardProven", "targetBoundaryProven", "indexLockPresent"):
            if field not in facts:
                failures.append(f"missing-fact:{field}")
        gates_pass = (
            _bounded_string(facts.get("mappingStatus")).upper() == "OK"
            and facts.get("mappingIdentityMatched") is True
            and facts.get("guardProven") is True
            and facts.get("targetBoundaryProven") is True
            and facts.get("indexLockPresent") is False
        )
        expected_verdict = "APPLY" if gates_pass else "HOLD"
        if observed["verdict"] != expected_verdict:
            failures.append("wrong-apply-hold-gate")
        if gates_pass:
            if observed["sourceWriteRoot"] != EXPECTED_CANONICAL:
                failures.append("wrong-source-write-root")
            if observed["authorizedMutation"] is not True:
                failures.append("direct-apply-not-authorized")
            if observed["mode"] != CANONICAL_DIRECT_MODE:
                failures.append("wrong-direct-mode")
        else:
            if observed["sourceWriteRoot"] is not None:
                failures.append("hold-write-root-not-null")
            if observed["authorizedMutation"] is not False:
                failures.append("hold-authorized-mutation")
            if observed["mode"] != "HOLD":
                failures.append("wrong-hold-mode")
    elif intent in READ_INTENTS:
        read_gates_pass = (
            _bounded_string(facts.get("mappingStatus")).upper() == "OK"
            and facts.get("mappingIdentityMatched") is True
        )
        if observed["sourceWriteRoot"] is not None:
            failures.append("read-only-write-root-not-null")
        if observed["authorizedMutation"] is not False:
            failures.append("read-only-authorized-mutation")
        expected_verdict = "APPLY" if read_gates_pass else "HOLD"
        expected_mode = "SMB_ACCESS" if read_gates_pass else "HOLD"
        if observed["mode"] != expected_mode:
            failures.append("wrong-read-access-mode")
        if observed["verdict"] != expected_verdict:
            failures.append("wrong-apply-hold-gate")

    return {
        "schemaVersion": SCHEMA_VERSION,
        "scenarioIdHash": _packet_hash(packet),
        "score": 100 if not failures else 0,
        "verdict": "PASS" if not failures else "FAIL",
        "hardGateFailures": failures,
        "observedDecision": observed,
    }


def _fixture_packet(
    scenario_id: str,
    decision: dict[str, object],
    facts: dict[str, object] | None = None,
) -> dict[str, object]:
    return {
        "scenarioId": scenario_id,
        "facts": facts or {
            "intent": "DIRECT_MUTATION",
            "mappingStatus": "OK",
            "mappingIdentityMatched": True,
            "guardProven": True,
            "targetBoundaryProven": True,
            "indexLockPresent": False,
        },
        "decision": decision,
    }


def run_self_test() -> int:
    """Run literal positive and adversarial schema-v2 fixtures."""
    canonical_direct = {
        "canonicalWorkspace": EXPECTED_CANONICAL,
        "backingShareIdentityVerified": True,
        "backingShareIdentityReason": "match",
        "sourceWriteRoot": EXPECTED_CANONICAL,
        "authorizedMutation": True,
        "mode": CANONICAL_DIRECT_MODE,
        "verdict": "APPLY",
        "rewriteCanonicalToUnc": False,
        "fallbackWorkspace": None,
    }
    hold_facts = {
        "intent": "DIRECT_MUTATION",
        "mappingStatus": "MISMATCH",
        "mappingIdentityMatched": False,
        "guardProven": True,
        "targetBoundaryProven": True,
        "indexLockPresent": False,
    }
    hold_direct = {
        **canonical_direct,
        "backingShareIdentityVerified": False,
        "backingShareIdentityReason": "mismatch",
        "sourceWriteRoot": None,
        "authorizedMutation": False,
        "mode": "HOLD",
        "verdict": "HOLD",
    }
    read_facts = {
        "intent": "READ_AUDIT_BUILD_TOOL",
        "mappingStatus": "OK",
        "mappingIdentityMatched": True,
    }
    read_access = {
        "canonicalWorkspace": EXPECTED_CANONICAL,
        "backingShareIdentityVerified": True,
        "backingShareIdentityReason": "match",
        "sourceWriteRoot": None,
        "authorizedMutation": False,
        "mode": "SMB_ACCESS",
        "verdict": "APPLY",
        "rewriteCanonicalToUnc": False,
        "fallbackWorkspace": None,
    }
    fixtures: list[tuple[str, dict[str, object], str, str | None]] = [
        (
            "deliberate-unc-canonical-fail",
            _fixture_packet(
                "deliberate-unc-canonical-fail",
                {
                    "canonicalWorkspace": "\\\\DESKTOP-M5NOV6K\\MacSrc",
                    "backingShareIdentityVerified": True,
                    "backingShareIdentityReason": "match",
                    "sourceWriteRoot": "\\\\DESKTOP-M5NOV6K\\MacSrc",
                    "authorizedMutation": True,
                    "mode": CANONICAL_DIRECT_MODE,
                    "verdict": "APPLY",
                    "rewriteCanonicalToUnc": True,
                    "fallbackWorkspace": None,
                },
            ),
            "FAIL",
            None,
        ),
        (
            "canonical-direct-pass",
            _fixture_packet("canonical-direct-pass", canonical_direct),
            "PASS",
            None,
        ),
        (
            "successful-direct-hold-rejected",
            _fixture_packet("successful-direct-hold-rejected", hold_direct),
            "FAIL",
            None,
        ),
        ("read-only-access", _fixture_packet("read-only-access", read_access, read_facts), "PASS", None),
        ("mapping-mismatch", _fixture_packet("mapping-mismatch", hold_direct, hold_facts), "PASS", None),
        (
            "missing-guard",
            _fixture_packet(
                "missing-guard",
                {
                    **hold_direct,
                    "backingShareIdentityVerified": True,
                    "backingShareIdentityReason": "match",
                },
                {**hold_facts, "mappingStatus": "OK", "mappingIdentityMatched": True, "guardProven": False},
            ),
            "PASS",
            None,
        ),
        (
            "index-lock",
            _fixture_packet(
                "index-lock",
                {
                    **hold_direct,
                    "backingShareIdentityVerified": True,
                    "backingShareIdentityReason": "match",
                },
                {**hold_facts, "mappingStatus": "OK", "mappingIdentityMatched": True, "indexLockPresent": True},
            ),
            "PASS",
            None,
        ),
        (
            "legacy-input-normalizes",
            _fixture_packet("legacy-input-normalizes", canonical_direct, {key: value for key, value in {**hold_facts, "mappingStatus": "OK", "mappingIdentityMatched": True, "requestedMode": LEGACY_INPUT_MODE}.items() if key != "intent"}),
            "PASS",
            CANONICAL_DIRECT_MODE,
        ),
        (
            "legacy-output-rejected",
            _fixture_packet("legacy-output-rejected", {**canonical_direct, "mode": LEGACY_INPUT_MODE}),
            "FAIL",
            LEGACY_INPUT_MODE,
        ),
        (
            "unc-canonical-rejected",
            _fixture_packet("unc-canonical-rejected", {**canonical_direct, "canonicalWorkspace": "\\\\DESKTOP-M5NOV6K\\MacSrc"}),
            "FAIL",
            None,
        ),
        (
            "onedrive-fallback-rejected",
            _fixture_packet("onedrive-fallback-rejected", {**canonical_direct, "fallbackWorkspace": "C:\\Users\\nninn\\OneDrive\\worktree"}),
            "FAIL",
            None,
        ),
        (
            "local-clone-fallback-rejected",
            _fixture_packet("local-clone-fallback-rejected", {**canonical_direct, "fallbackWorkspace": "C:\\AbandonWare\\worktrees\\awx-notebook"}),
            "FAIL",
            None,
        ),
        (
            "direct-authorization-false-rejected",
            _fixture_packet("direct-authorization-false-rejected", {**canonical_direct, "authorizedMutation": False}),
            "FAIL",
            None,
        ),
        (
            "read-only-write-authorization-rejected",
            _fixture_packet(
                "read-only-write-authorization-rejected",
                {**read_access, "sourceWriteRoot": EXPECTED_CANONICAL, "authorizedMutation": True},
                read_facts,
            ),
            "FAIL",
            None,
        ),
        (
            "hold-write-authorization-rejected",
            _fixture_packet(
                "hold-write-authorization-rejected",
                {**hold_direct, "sourceWriteRoot": EXPECTED_CANONICAL, "authorizedMutation": True},
                hold_facts,
            ),
            "FAIL",
            None,
        ),
        (
            "identity-contradiction-rejected",
            _fixture_packet(
                "identity-contradiction-rejected",
                {
                    **canonical_direct,
                    "backingShareIdentityVerified": False,
                    "backingShareIdentityReason": "evidence-needed",
                },
            ),
            "FAIL",
            None,
        ),
        (
            "omitted-authorization-rejected",
            _fixture_packet(
                "omitted-authorization-rejected",
                {key: value for key, value in canonical_direct.items() if key != "authorizedMutation"},
            ),
            "FAIL",
            None,
        ),
    ]
    failures = 0
    for name, packet, expected, expected_mode in fixtures:
        result = grade_packet(packet)
        observed = result.get("verdict")
        normalized_mode = result.get("observedDecision", {}).get("mode")
        if observed != expected or (expected_mode is not None and normalized_mode != expected_mode):
            failures += 1
            print(f"FAIL {name}: expected {expected}, got {observed}", file=sys.stderr)
    bounded_probe = grade_packet(
        _fixture_packet(
            "bounded-allowlisted-output",
            {
                **canonical_direct,
                "backingShareIdentityReason": "x" * (MAX_STRING_LENGTH + 1),
                "unexpected": "do-not-echo",
            },
        )
    )
    bounded_observed = bounded_probe.get("observedDecision", {})
    if (
        set(bounded_observed) != set(DECISION_FIELDS)
        or len(str(bounded_observed.get("backingShareIdentityReason", ""))) > MAX_STRING_LENGTH
        or "unexpected" in json.dumps(bounded_probe, sort_keys=True)
    ):
        failures += 1
        print("FAIL bounded-allowlisted-output", file=sys.stderr)
    fixture_count = len(fixtures) + 1
    print(f"self-test fixtures={fixture_count} failures={failures}")
    return 0 if failures == 0 else 1


def main(argv: list[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(prog="ydrive_smb_workspace_policy_autograder.py")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--input", type=Path, metavar="PATH")
    mode.add_argument("--self-test", action="store_true")
    try:
        parsed = parser.parse_args(arguments)
    except SystemExit as exc:
        return int(exc.code)
    if parsed.self_test:
        return run_self_test()

    try:
        with parsed.input.open("r", encoding="utf-8") as stream:
            raw_packet: Any = json.load(stream)
    except OSError:
        print("input-read-error", file=sys.stderr)
        return 2
    except json.JSONDecodeError:
        raw_packet = None
    result = grade_packet(raw_packet)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["verdict"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
