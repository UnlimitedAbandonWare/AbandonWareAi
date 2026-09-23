"""Synthetic, gate-owned fixtures; no application, provider or database execution."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from scripts import agent_code_evidence_gate as gate
from scripts import run_verified_command as runner


class AgentCodeEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 9, 10, tzinfo=timezone.utc)
        self.hashes = {name: hashlib.sha256(name.encode()).hexdigest()
                       for name in ("candidate", "fixture", "oracle", "root", "toolchain")}
        self.contract = {"schema": "agent_code_evidence_contract.v1", "hashes": self.hashes.copy(),
                         "runId": "fixture-run", "requiredSuites": ["Fixture"], "maxAgeSeconds": 300}
        self.run = dict(runId="fixture-run", scope="fixture", status="passed", exitCode=0, verificationExitCode=0,
                        startedAt=(self.now - timedelta(seconds=2)).isoformat(),
                        endedAt=(self.now - timedelta(seconds=1)).isoformat(), elapsedMs=1000,
                        commandSha256="a" * 64, expectedSuites=["Fixture"], failures=[],
                        totals=dict(tests=2, failures=0, errors=0, skipped=0),
                        resultFiles=[dict(suite="Fixture", sha256="b" * 64,
                                          counts=dict(tests=2, failures=0, errors=0, skipped=0))])
        self.evidence = dict(hashes=self.hashes.copy(), oracleAfterSha256=self.hashes["oracle"],
                             staticNewHighCount=0, secretHitCount=0, expectedSignalMatches=True,
                             oracleIndependent=True, sandboxAvailable=True, requiredToolsAvailable=True,
                             evidenceComplete=True, deterministic=True,
                             mutationCounts={key: 0 for key in
                                             ("source", "apply", "rollback", "deploy", "provider", "database")})
        self.pin_run()

    def pin_run(self):
        self.contract.update(commandSha256=self.run["commandSha256"], scope=self.run["scope"],
                             runSha256=hashlib.sha256(json.dumps(self.run, sort_keys=True,
                                                                separators=(",", ":")).encode()).hexdigest())

    def evaluate(self):
        return gate.evaluate(self.run, self.evidence, self.contract, now=self.now)

    def assert_verdict(self, verdict, reason=None):
        result = self.evaluate()
        self.assertEqual(verdict, result["verdict"])
        self.assertFalse(result["mutationAllowed"])
        self.assertFalse(result["applyAllowed"])
        if reason:
            self.assertIn(reason, result["reasonCodes"])
        return result

    def test_good_immutable_fixture_is_review_only_pass(self):
        before = copy.deepcopy((self.run, self.evidence, self.contract))
        self.assert_verdict("PASS")
        self.assertEqual(before, (self.run, self.evidence, self.contract))

    def test_missing_run_and_stale_success_are_hold(self):
        self.run = None
        self.assert_verdict("HOLD", "run_missing")
        self.setUp()
        self.run["failures"] = ["stale_xml:Fixture"]
        self.assert_verdict("HOLD", "junit_evidence_stale")

    def test_missing_or_partial_metadata_cannot_pass(self):
        for key in list(self.evidence):
            with self.subTest(key=key):
                self.setUp()
                del self.evidence[key]
                self.assert_verdict("HOLD")
        self.evidence = None
        self.assert_verdict("HOLD")

    def test_time_and_run_identity_are_required(self):
        for change in ({"endedAt": "invalid"}, {"runId": "different"},
                       {"endedAt": (self.now + timedelta(seconds=1)).isoformat()},
                       {"startedAt": (self.now - timedelta(hours=2)).isoformat(),
                        "endedAt": (self.now - timedelta(hours=1)).isoformat()}):
            with self.subTest(change=change):
                self.setUp()
                self.run.update(change)
                self.assert_verdict("HOLD")

    def test_unrelated_command_scope_or_replaced_result_cannot_pass(self):
        for key, value in (("commandSha256", "c" * 64), ("scope", "unrelated"), ("elapsedMs", 999)):
            self.setUp()
            self.run[key] = value
            self.assert_verdict("HOLD")

    def test_stale_failed_run_is_missing_current_evidence(self):
        self.run.update(status="failed", exitCode=1, verificationExitCode=1,
                        startedAt=(self.now - timedelta(hours=2)).isoformat(),
                        endedAt=(self.now - timedelta(hours=1)).isoformat())
        self.pin_run()
        self.assert_verdict("HOLD", "run_freshness_unproven")

    def test_environment_and_oracle_prerequisites_hold(self):
        for key in ("oracleIndependent", "sandboxAvailable", "requiredToolsAvailable",
                    "evidenceComplete", "deterministic"):
            with self.subTest(key=key):
                self.setUp()
                self.evidence[key] = False
                self.assert_verdict("HOLD")

    def test_candidate_failures_reject_even_when_advisory_evidence_is_missing(self):
        for key, value, reason in (("staticNewHighCount", 1, "static_analysis_new_high"),
                                   ("secretHitCount", 1, "secret_leak_risk"),
                                   ("expectedSignalMatches", False, "expected_signal_mismatch")):
            with self.subTest(key=key):
                self.setUp()
                self.evidence[key] = value
                self.evidence["requiredToolsAvailable"] = False
                self.assert_verdict("REJECT", reason)

    def test_hash_and_oracle_failure_are_not_majority_votes(self):
        self.evidence["hashes"]["candidate"] = "c" * 64
        self.assert_verdict("REJECT", "candidate_hash_mismatch")
        self.setUp()
        self.evidence["oracleAfterSha256"] = "c" * 64
        self.assert_verdict("REJECT", "hidden_oracle_mutated")
        for key in ("fixture", "root", "toolchain"):
            with self.subTest(key=key):
                self.setUp()
                self.evidence["hashes"][key] = "c" * 64
                self.assert_verdict("HOLD", key + "_identity_mismatch")

    def test_all_mutation_counters_are_required_and_zero(self):
        for key in self.evidence["mutationCounts"]:
            with self.subTest(key=key):
                self.evidence["mutationCounts"][key] = 1
                self.assert_verdict("REJECT", "mutation_observed")
                self.evidence["mutationCounts"][key] = 0
        self.evidence["mutationCounts"].pop("database")
        self.assert_verdict("HOLD", "mutation_evidence_missing")

    def test_compile_failure_and_junit_failure_reject(self):
        self.run.update(status="failed", exitCode=1, verificationExitCode=1)
        self.pin_run()
        self.assert_verdict("REJECT", "command_failed")
        self.setUp()
        self.run["totals"]["failures"] = 1
        self.run["resultFiles"][0]["counts"]["failures"] = 1
        self.run.update(status="evidence_incomplete", verificationExitCode=3, failures=["junit_failure"])
        self.pin_run()
        self.assert_verdict("REJECT", "junit_failed")

    def test_zero_all_skipped_and_inconsistent_tests_cannot_pass(self):
        for count in (0, -1, True, "2"):
            with self.subTest(count=count):
                self.setUp()
                self.run["totals"]["tests"] = count
                self.assert_verdict("HOLD")
        self.setUp()
        self.run["totals"]["skipped"] = 2
        self.run["resultFiles"][0]["counts"]["skipped"] = 2
        self.assert_verdict("HOLD", "junit_zero_executed")
        self.setUp()
        self.run["resultFiles"][0]["counts"]["tests"] = 1
        self.assert_verdict("HOLD", "junit_counts_mismatch")

    def test_result_files_cover_exact_required_suites(self):
        self.run["expectedSuites"].append(1)
        self.pin_run()
        self.assert_verdict("HOLD")
        for field in ("expectedSuites", "resultFiles"):
            self.setUp()
            self.run[field] = []
            self.assert_verdict("HOLD")
        self.setUp()
        self.run["resultFiles"].append(copy.deepcopy(self.run["resultFiles"][0]))
        self.assert_verdict("HOLD")

    def test_order_and_untrusted_text_do_not_change_verdict_or_output(self):
        expected = self.evaluate()
        self.run["privatePrompt"] = "PRIVATE_FIXTURE_CONTENT"
        self.pin_run()
        self.evidence["rawEnvironment"] = "PRIVATE_FIXTURE_CONTENT"
        self.evidence = dict(reversed(list(self.evidence.items())))
        self.assertEqual(expected, self.evaluate())
        serialized = json.dumps(self.evaluate())
        self.assertNotIn("PRIVATE_FIXTURE_CONTENT", serialized)
        self.assertLess(len(serialized), 4096)

    def test_existing_runner_missing_and_stale_xml_are_reused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            xml = root / "xml"
            xml.mkdir()
            for mode in ("missing", "stale"):
                if mode == "stale":
                    (xml / "TEST-Fixture.xml").write_text(
                        '<testsuite name="Fixture" tests="2"><testcase/><testcase/></testsuite>')
                self.run = runner.run([sys.executable, "-c", "pass"], root, root / mode,
                                      suites=["Fixture"], xml_dir=xml)
                self.contract["runId"] = self.run["runId"]
                self.pin_run()
                self.now = datetime.now(timezone.utc)
                self.assert_verdict("HOLD", "junit_evidence_" + mode)

    def test_existing_runner_fresh_result_can_feed_review_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            xml = root / "xml"
            xml.mkdir()
            command = "from pathlib import Path; Path('xml/TEST-Fixture.xml').write_text(" + repr(
                '<testsuite name="Fixture" tests="2"><testcase/><testcase/></testsuite>') + ")"
            self.run = runner.run([sys.executable, "-c", command], root, root / "run",
                                  suites=["Fixture"], xml_dir=xml)
            self.contract["runId"] = self.run["runId"]
            self.pin_run()
            self.now = datetime.now(timezone.utc)
            self.assert_verdict("PASS")

    def test_embedded_contract_cannot_supply_its_own_oracle_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "input.json"
            self.contract["hashes"]["oracle"] = "c" * 64
            self.evidence["hashes"]["oracle"] = "c" * 64
            self.evidence["oracleAfterSha256"] = "c" * 64
            path.write_text(json.dumps(dict(run=self.run, evidence=self.evidence, contract=self.contract)))
            self.assertEqual("HOLD", gate.evaluate_file(path, now=self.now)["verdict"])

    def test_external_pinned_contract_checks_actual_oracle_and_write_boundary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            candidate = root / "candidate"
            candidate.mkdir()
            oracle = root / "oracle.txt"
            oracle.write_text("immutable oracle fixture")
            oracle_hash = hashlib.sha256(oracle.read_bytes()).hexdigest()
            self.contract["hashes"]["oracle"] = oracle_hash
            self.evidence["hashes"]["oracle"] = oracle_hash
            self.evidence["oracleAfterSha256"] = oracle_hash
            self.contract.update(candidateRoot=str(candidate), oraclePath=str(oracle))
            contract = root / "contract.json"
            contract.write_text(json.dumps(self.contract))
            pin = hashlib.sha256(contract.read_bytes()).hexdigest()
            evidence = candidate / "observations.json"
            evidence.write_text(json.dumps(dict(run=self.run, evidence=self.evidence)))
            def read(**kwargs):
                return gate.evaluate_file(evidence, contract_path=contract, contract_sha256=pin,
                                          now=self.now, **kwargs)
            self.assertEqual("PASS", read()["verdict"])
            for protected in (evidence, contract, oracle):
                self.assertIn("output_input_conflict", read(protected_outputs=[protected])["reasonCodes"])
            oracle.write_text("changed oracle")
            self.assertEqual("REJECT", read()["verdict"])
            contract.write_text("{}")
            self.assertEqual("HOLD", read()["verdict"])

    def test_bounded_invalid_json_returns_fixed_hold(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "input.json"
            for content in ("PRIVATE_FIXTURE_CONTENT", "x" * 262145, '{"a":1,"a":2}'):
                path.write_text(content)
                result = gate.evaluate_file(path, now=self.now)
                self.assertEqual("HOLD", result["verdict"])
                self.assertNotIn("PRIVATE_FIXTURE_CONTENT", json.dumps(result))
            result = gate.evaluate_file(Path(tmp) / "missing.json", now=self.now)
            self.assertEqual("HOLD", result["verdict"])


if __name__ == "__main__":
    unittest.main()
