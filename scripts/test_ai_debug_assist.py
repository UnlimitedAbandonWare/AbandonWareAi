#!/usr/bin/env python3
"""Bounded AI debugging contract tests; no external process or provider calls."""

from __future__ import annotations

import contextlib
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "ai_debug_assist.py"
DELEGATE_PATH = ROOT / "tools" / "ox-alpha-delegate" / "ox_alpha_delegate.py"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class AiDebugAssistContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.assist = load_module("ai_debug_assist_under_test", MODULE_PATH)
        cls.delegate = load_module("ai_debug_assist_test_delegate", DELEGATE_PATH)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-ai-debug-contract-")
        self.root = Path(self.temp.name).resolve()
        self.assertEqual(self.root.parent, Path(tempfile.gettempdir()).resolve())
        self.addCleanup(self.temp.cleanup)
        self.log = self.root / "build.log"
        self.source = self.root / "sample.py"
        self.source.write_text("def boundary(value):\n    adjusted = value + 1\n    return adjusted\n", encoding="utf-8")
        self.log.write_text("error: cannot find symbol\nsymbol: method missing()\n", encoding="utf-8")

    def candidate(self):
        return {
            "status": "ok",
            "exitCode": 0,
            "fallbackUsed": False,
            "summary": "Boundary candidate requires an independent focused test.",
            "findings": [{
                "description": "The boundary may fail for an empty value.",
                "counterexample": "All callers may already reject empty values.",
                "expectedSideEffects": "Changing the boundary could change caller behavior.",
            }],
            "evidence": [
                {"file": "sample.py", "line": 1, "reason": "The boundary accepts the caller input."},
                {"file": "sample.py", "line": 2, "reason": "The arithmetic operation uses the input directly."},
                {"file": "sample.py", "line": 3, "reason": "The adjusted value is returned to the caller."},
            ],
            "proposedTests": ["Call boundary with an empty value and assert the documented outcome."],
            "changedFiles": [],
            "outputContractExceeded": False,
        }

    def proposal(self):
        candidate = self.candidate()
        return {
            "logSha256": hashlib.sha256(self.log.read_bytes()).hexdigest(),
            "sourceHashes": {"sample.py": hashlib.sha256(self.source.read_bytes()).hexdigest()},
            **{key: candidate[key] for key in ("summary", "findings", "evidence", "proposedTests")},
        }

    def write_proposal(self, packet):
        path = self.root / "proposal.json"
        path.write_text(json.dumps(packet, ensure_ascii=False), encoding="utf-8")
        return path

    @contextlib.contextmanager
    def dependencies(self, *, ai_result=None, ai_effect=None, mcp_effect=None):
        with (
            mock.patch.object(self.assist, "_load_delegate", return_value=self.delegate),
            mock.patch.object(self.delegate, "analyze_request", return_value=copy.deepcopy(ai_result if ai_result is not None else self.candidate()), side_effect=ai_effect) as ai,
            mock.patch.object(self.assist, "_mcp_classify", return_value={"status": "verified", "reason": "classification_verified"}, side_effect=mcp_effect) as mcp,
            mock.patch("subprocess.Popen", side_effect=AssertionError("external-process-forbidden")),
        ):
            yield ai, mcp

    def diagnose(self, **kwargs):
        return self.assist.diagnose(self.log, ["sample.py"], root=self.root, **kwargs)

    def assert_local_fallback(self, report):
        self.assertIsInstance(report, dict)
        self.assertIs(report["claimsVerified"], False)
        self.assertEqual(report["nextAction"], "local_analysis_required")
        self.assertEqual(report["ai"]["findings"], [])

    def test_missing_log_stops_before_mcp_or_ai(self):
        with self.dependencies() as (ai, mcp):
            report = self.assist.diagnose(self.root / "missing.log", ["sample.py"], root=self.root)
        self.assert_local_fallback(report)
        ai.assert_not_called()
        mcp.assert_not_called()

    def test_oversized_log_stops_before_mcp_or_ai(self):
        self.log.write_bytes(b"x" * 1_000_001)
        with self.dependencies() as (ai, mcp):
            report = self.diagnose()
        self.assert_local_fallback(report)
        ai.assert_not_called()
        mcp.assert_not_called()

    def test_missing_allowlisted_source_never_reaches_ai_analysis(self):
        with self.dependencies() as (ai, _):
            report = self.assist.diagnose(self.log, ["missing.py"], root=self.root, use_mcp=False)
        self.assert_local_fallback(report)
        ai.assert_not_called()

    def test_each_run_binds_the_exact_current_log_and_source_bytes(self):
        first_bytes = self.log.read_bytes()
        with self.dependencies():
            first = self.diagnose(use_mcp=False, use_ai=False)
            self.log.write_bytes(first_bytes + b"different observation\n")
            second = self.diagnose(use_mcp=False, use_ai=False)
        self.assertEqual(first["snapshot"]["logSha256"], hashlib.sha256(first_bytes).hexdigest())
        self.assertEqual(first["snapshot"]["logBytes"], len(first_bytes))
        self.assertNotEqual(first["snapshot"]["logSha256"], second["snapshot"]["logSha256"])
        self.assertEqual(second["snapshot"]["logSha256"], hashlib.sha256(self.log.read_bytes()).hexdigest())
        self.assertEqual(first["snapshot"]["sourceHashes"], {"sample.py": hashlib.sha256(self.source.read_bytes()).hexdigest()})

    def test_snapshot_exposes_only_class_counts_and_no_raw_log_or_secret(self):
        secret = "sk-" + "SYNTHETIC" * 6
        marker = "PRIVATE_LOG_SENTINEL_7264"
        self.log.write_text((f"error: cannot find symbol {marker} token={secret}\nsymbol: method missing()\n") * 3, encoding="utf-8")
        with self.dependencies():
            report = self.diagnose(use_mcp=False, use_ai=False)
        classes = report["snapshot"]["classes"]
        self.assertEqual(classes["JavacCannotFindSymbol"], 3)
        self.assertTrue(all(isinstance(count, int) and not isinstance(count, bool) for count in classes.values()))
        rendered = json.dumps(report, ensure_ascii=False)
        self.assertFalse(secret in rendered, "snapshot-secret-leaked")
        self.assertFalse(marker in rendered, "snapshot-raw-log-leaked")
        self.assertNotIn("examples", report["snapshot"])

    def test_mcp_exception_falls_back_to_actual_local_classification(self):
        with self.dependencies(mcp_effect=RuntimeError("PRIVATE_MCP_ERROR_SENTINEL")):
            report = self.diagnose(use_ai=False)
        self.assertEqual(report["mcp"]["status"], "fallback")
        self.assertEqual(report["snapshot"]["classes"]["JavacCannotFindSymbol"], 1)
        self.assertFalse("PRIVATE_MCP_ERROR_SENTINEL" in json.dumps(report), "mcp-exception-text-leaked")
        self.assert_local_fallback(report)

    def test_delegate_prompt_uses_counts_and_hash_without_raw_log_content(self):
        secret = "sk-" + "SYNTHETIC" * 6
        marker = "PRIVATE_PROMPT_LOG_SENTINEL_5628"
        self.log.write_text(f"error: cannot find symbol {marker} token={secret}\n", encoding="utf-8")
        with self.dependencies() as (ai, _):
            report = self.diagnose(use_mcp=False)
        request = ai.call_args.args[0]
        self.assertIn(hashlib.sha256(self.log.read_bytes()).hexdigest(), request["prompt"])
        self.assertIn("JavacCannotFindSymbol", request["prompt"])
        self.assertFalse(secret in request["prompt"], "prompt-secret-leaked")
        self.assertFalse(marker in request["prompt"], "prompt-raw-log-leaked")
        self.assertFalse(secret in json.dumps(report), "report-secret-leaked")

    def test_delegate_exception_falls_back_without_returning_exception_text(self):
        with self.dependencies(ai_effect=RuntimeError("PRIVATE_AI_FAILURE_SENTINEL")):
            report = self.diagnose(use_mcp=False)
        self.assert_local_fallback(report)
        self.assertFalse("PRIVATE_AI_FAILURE_SENTINEL" in json.dumps(report), "ai-exception-text-leaked")

    def test_delegate_failure_is_rejected_even_with_zero_exit_code(self):
        for state in ("timeout", "input_rejected", "internal_error"):
            with self.subTest(status=state):
                candidate = self.candidate()
                candidate.update(status=state, exitCode=0, fallbackUsed=True)
                with self.dependencies(ai_result=candidate):
                    report = self.diagnose(use_mcp=False)
                self.assert_local_fallback(report)
                self.assertNotEqual(report["ai"]["status"], "ok")
                self.assertIs(report["ai"]["fallbackUsed"], True)

    def test_successful_ai_findings_remain_unverified_until_focused_test(self):
        original_source = self.source.read_bytes()
        with self.dependencies() as (ai, _):
            report = self.diagnose(use_mcp=False)
        ai.assert_called_once()
        self.assertEqual(report["ai"]["status"], "ok")
        self.assertTrue(report["ai"]["findings"])
        self.assertTrue(report["ai"]["evidence"])
        self.assertTrue(report["ai"]["proposedTests"])
        self.assertIs(report["claimsVerified"], False)
        self.assertEqual(report["nextAction"], "focused_test_required")
        self.assertIs(report["ai"]["fallbackUsed"], False)
        self.assertEqual(self.source.read_bytes(), original_source)

    def test_source_changed_during_analysis_suppresses_candidate(self):
        def change_source(*args, **kwargs):
            self.source.write_text("def boundary(value):\n    return value - 1\n", encoding="utf-8")
            return self.candidate()

        with self.dependencies(ai_effect=change_source):
            report = self.diagnose(use_mcp=False)
        self.assert_local_fallback(report)
        self.assertEqual(report["ai"]["evidence"], [])
        self.assertEqual(report["ai"]["proposedTests"], [])
        self.assertNotEqual(report["ai"]["status"], "ok")
        self.assertIs(report["ai"]["fallbackUsed"], True)

    def test_oversized_delegate_output_cannot_expand_the_report_budget(self):
        candidate = self.candidate()
        candidate["findings"][0]["description"] = "candidate " * 20000
        candidate["proposedTests"] = ["test " * 20000] * 10
        with self.dependencies(ai_result=candidate):
            report = self.diagnose(use_mcp=False)
        self.assertLessEqual(len(json.dumps(report, ensure_ascii=False).encode("utf-8")), 64 * 1024)
        self.assertIs(report["claimsVerified"], False)

    def test_explicit_disabled_integrations_do_not_call_external_dependencies(self):
        with self.dependencies() as (ai, mcp):
            report = self.diagnose(use_mcp=False, use_ai=False)
        ai.assert_not_called()
        mcp.assert_not_called()
        self.assertEqual(report["mcp"]["status"], "disabled")
        self.assertEqual(report["ai"]["status"], "disabled")
        self.assert_local_fallback(report)

    def test_bound_supplied_candidate_never_calls_external_ai_and_remains_unverified(self):
        proposal_path = self.write_proposal(self.proposal())
        with self.dependencies() as (ai, mcp):
            report = self.diagnose(use_mcp=False, proposal_path=proposal_path)
        ai.assert_not_called()
        mcp.assert_not_called()
        self.assertEqual(report["ai"]["status"], "ok")
        self.assertEqual(report["ai"]["candidateOrigin"], "supplied_candidate")
        self.assertEqual(report["ai"]["model"], "caller_supplied")
        self.assertTrue(report["ai"]["findings"])
        self.assertIs(report["claimsVerified"], False)
        self.assertEqual(report["nextAction"], "focused_test_required")

    def test_supplied_candidate_rejects_stale_log_or_source_hash(self):
        for changed in ("log", "source"):
            with self.subTest(changed=changed):
                packet = self.proposal()
                if changed == "log":
                    packet["logSha256"] = "0" * 64
                else:
                    packet["sourceHashes"]["sample.py"] = "0" * 64
                with self.dependencies() as (ai, _):
                    report = self.diagnose(use_mcp=False, proposal_path=self.write_proposal(packet))
                ai.assert_not_called()
                self.assert_local_fallback(report)
                self.assertEqual(report["ai"]["status"], "proposal_snapshot_mismatch")

    def test_supplied_candidate_rejects_secret_shaped_text_without_publishing_it(self):
        packet = self.proposal()
        secret = "sk-" + "SYNTHETIC" * 6
        packet["summary"] = "Sensitive candidate includes token=" + secret
        with self.dependencies() as (ai, _):
            report = self.diagnose(use_mcp=False, proposal_path=self.write_proposal(packet))
        ai.assert_not_called()
        self.assert_local_fallback(report)
        self.assertNotEqual(report["ai"]["status"], "ok")
        self.assertFalse(secret in json.dumps(report), "raw-proposal-secret-leaked")

    def test_mcp_semantic_result_accepts_known_consistent_class_counts(self):
        reply = {
            "isError": False,
            "structuredContent": {
                "classes": {"cannot-find-symbol": 2, "duplicate-class-fqcn": 1},
                "outputCount": 3,
                "primaryClass": "cannot-find-symbol",
                "decision": "build_log_mined",
            },
        }
        observed = self.assist._mcp_result(reply)
        self.assertEqual(observed["classes"], reply["structuredContent"]["classes"])
        self.assertEqual(observed["primaryClass"], "cannot-find-symbol")

    def test_mcp_semantic_result_rejects_failed_or_inconsistent_evidence(self):
        baseline = {
            "isError": False,
            "structuredContent": {
                "classes": {"cannot-find-symbol": 1},
                "outputCount": 1,
                "primaryClass": "cannot-find-symbol",
                "decision": "build_log_mined",
            },
        }
        cases = []
        for label, count in (("boolean-count", True), ("negative-count", -1), ("float-count", 1.5)):
            reply = copy.deepcopy(baseline)
            reply["structuredContent"]["classes"]["cannot-find-symbol"] = count
            cases.append((label, reply))
        for label, field, value in (
            ("sum-mismatch", "outputCount", 2),
            ("unknown-class", "classes", {"unrecognized-error": 1}),
            ("primary-mismatch", "primaryClass", "spring-bean"),
        ):
            reply = copy.deepcopy(baseline)
            reply["structuredContent"][field] = value
            cases.append((label, reply))
        reply = copy.deepcopy(baseline)
        reply["isError"] = True
        cases.append(("tool-error", reply))
        for label, reply in cases:
            with self.subTest(reason=label), self.assertRaises(ValueError):
                self.assist._mcp_result(reply)

    def test_supplied_candidate_rejects_unicode_escaped_secret_after_json_decode(self):
        packet = self.proposal()
        secret = "sk-" + "SYNTHETIC" * 6
        packet["summary"] = "Sensitive candidate includes token=" + secret
        encoded = json.dumps(packet, ensure_ascii=True)
        escaped = "".join("\\u%04x" % ord(character) for character in secret)
        encoded = encoded.replace(secret, escaped)
        self.assertFalse(secret in encoded, "fixture-must-hide-secret-from-raw-text-scan")
        proposal_path = self.root / "proposal-escaped.json"
        proposal_path.write_text(encoded, encoding="utf-8")
        with self.dependencies() as (ai, _):
            report = self.diagnose(use_mcp=False, proposal_path=proposal_path)
        ai.assert_not_called()
        self.assertFalse(secret in json.dumps(report), "decoded-proposal-secret-leaked")
        self.assert_local_fallback(report)
        self.assertNotEqual(report["ai"]["status"], "ok")


if __name__ == "__main__":
    unittest.main()
