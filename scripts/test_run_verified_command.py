import importlib.util
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("runner", Path(__file__).with_name("run_verified_command.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class VerifiedCommandTest(unittest.TestCase):
    def execute(self, code, *, initial=None, timeout=10):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            if initial:
                (root / "TEST-example.Suite.xml").write_text(initial)
            child = root / "child.py"
            child.write_text(code)
            result = runner.run([sys.executable, str(child)], root, root / "evidence",
                suites=["example.Suite"], xml_dir=root, timeout=timeout)
            self.assertTrue((root / "evidence/run.json").is_file())
            return result

    def xml(self, failures=0):
        return '<testsuite name="example.Suite" tests="1" failures="'+str(failures)+'"><testcase name="one"/></testsuite>'

    def writer(self, xml):
        return 'from pathlib import Path\nPath("TEST-example.Suite.xml").write_text('+repr(xml)+')\n'

    def test_success_requires_actual_exit_and_fresh_scoped_xml(self):
        r = self.execute(self.writer(self.xml()))
        self.assertEqual((r["status"], r["exitCode"], r["totals"]["tests"]), ("passed", 0, 1))

    def test_failure_is_not_hidden_by_successful_log_write(self):
        r = self.execute(self.writer(self.xml())+'print("log copied")\nraise SystemExit(7)')
        self.assertEqual((r["status"], r["verificationExitCode"]), ("failed", 7))

    def test_missing_results_cannot_pass_even_with_zero_exit(self):
        self.assertEqual(self.execute('print("BUILD SUCCESSFUL")')["status"], "evidence_incomplete")

    def test_old_xml_is_not_reused(self):
        r = self.execute('print("UP-TO-DATE")', initial=self.xml())
        self.assertIn("stale_xml:example.Suite", r["failures"])
        self.assertEqual(r["totals"]["tests"], 0)

    def test_interruption_remains_interruption(self):
        r = self.execute('import threading\nthreading.Event().wait()', timeout=0.3)
        self.assertEqual(r["status"], "interrupted")
        self.assertNotEqual(r["verificationExitCode"], 0)

    def test_junit_failure_with_zero_command_exit_cannot_pass(self):
        r = self.execute(self.writer(self.xml(1)))
        self.assertIn("junit_failure", r["failures"])
        self.assertNotEqual(r["verificationExitCode"], 0)

    def test_mismatched_suite_is_rejected(self):
        r = self.execute(self.writer(self.xml().replace('name="example.Suite"', 'name="other.Suite"')))
        self.assertIn("invalid_xml:example.Suite", r["failures"])


class BoundReceiptTest(unittest.TestCase):
    """Network-free real child runs; the contract doctor is a separate test seam."""
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "source.py").write_text("value = 1\n")
        self.child = self.root / "check.py"
        self.child.write_text("# synthetic focused test\n")
        self.command = [str(Path(sys.executable).resolve()), str(self.child)]
        self.contract = {"schemaVersion": "awx.request-contract.v1", "taskId": "synthetic",
            "revision": 1, "instructionRef": "synthetic-instruction", "stages": [{"id": "one",
            "sourceFiles": ["source.py"], "testFiles": ["check.py"],
            "successTests": [{"id": "focused", "commandId": "focused", "expectation": "synthetic pass",
                "knowledgeRef": "known", "argvSha256": hashlib.sha256(json.dumps(self.command).encode()).hexdigest(),
                "expectedSuites": ["example.Suite"]}]}]}
        self.contract_path = self.root / "request-contract.json"
        self.contract_path.write_text(json.dumps(self.contract))
        self.doctor = patch.object(runner, "check_request_contract", create=True,
            return_value={"status": "READY", "stages": [{"id": "one", "status": "READY"}], "errors": []})
        self.doctor.start()
        self.addCleanup(self.doctor.stop)

    def execute(self, *, phase="GREEN", exit_code=0, failures=0, skipped=0, code="", sources=None,
                suites=("example.Suite",), timeout=10):
        outcome = "<failure/>" if failures else "<skipped/>" if skipped else ""
        xml = '<testsuite name="example.Suite" tests="1" failures="%d" skipped="%d"><testcase name="one">%s</testcase></testsuite>' % (failures, skipped, outcome)
        self.child.write_text("from pathlib import Path\n" + code +
            "Path('TEST-example.Suite.xml').write_text(%r)\nraise SystemExit(%d)\n" % (xml, exit_code))
        output = self.root / ("receipt-" + str(len(list(self.root.glob("receipt-*")))))
        report = runner.run(self.command, self.root, output, suites=suites,
            xml_dir=self.root, timeout=timeout, sources=sources if sources is not None else ["source.py", "check.py"],
            contract="request-contract.json", stage="one", phase=phase, command_id="focused")
        return report, output

    def test_actual_green_binds_contract_files_and_integrity(self):
        report, output = self.execute()
        self.assertEqual(report["verificationPhaseOutcome"], "GREEN")
        self.assertEqual(report["sourceIdentity"], report["sourceIdentityEnd"])
        self.assertEqual(report["contractBinding"]["revision"], 1)
        self.assertEqual(report["logSha256"], hashlib.sha256((output / report["log"]).read_bytes()).hexdigest())
        self.assertTrue(runner.validate_bound_receipt(output, expected_phase="GREEN")["ok"])

    def test_red_requires_failed_fresh_tests_and_actual_nonzero_exit(self):
        report, output = self.execute(phase="RED", failures=1, exit_code=1)
        self.assertEqual((report["status"], report["verificationPhaseOutcome"]), ("failed", "RED"))
        self.assertTrue(runner.validate_bound_receipt(output, expected_phase="RED")["ok"])
        invalid, _ = self.execute(phase="RED", failures=1, exit_code=0)
        self.assertEqual(invalid["verificationPhaseOutcome"], "UNCONFIRMED")

    def test_missing_sources_aliases_and_no_junit_are_rejected_before_launch(self):
        for sources in (["source.py"], ["source.py", str(self.child)], ["source.py", "./check.py"]):
            with self.subTest(sources=sources), self.assertRaises(ValueError):
                self.execute(sources=sources)
        with self.assertRaises(ValueError):
            self.execute(suites=())

    def test_hold_stage_and_wrong_command_are_rejected_before_launch(self):
        runner.check_request_contract.return_value = {"status": "HOLD", "stages": [{"id": "one", "status": "HOLD"}]}
        with self.assertRaises(ValueError):
            self.execute()
        runner.check_request_contract.return_value = {"status": "READY", "stages": [{"id": "one", "status": "READY"}]}
        self.contract["stages"][0]["successTests"][0]["commandId"] = "unbound-command"
        self.contract_path.write_text(json.dumps(self.contract))
        with self.assertRaises(ValueError):
            self.execute()

    def test_source_drift_and_all_skipped_cannot_be_green(self):
        report, _ = self.execute(code="Path('source.py').write_text('value = 2')\n")
        self.assertNotEqual(report["status"], "passed")
        self.assertEqual(report["verificationPhaseOutcome"], "UNCONFIRMED")
        self.assertIn("source_identity_drift", report["failures"])
        skipped, _ = self.execute(skipped=1)
        self.assertEqual(skipped["verificationPhaseOutcome"], "UNCONFIRMED")

    def test_tampered_log_xml_and_current_test_reject_reuse(self):
        for artifact in ("log", "xml", "test", "contract"):
            with self.subTest(artifact=artifact):
                report, output = self.execute()
                target = {"log": output / report["log"], "xml": output / report["resultFiles"][0]["path"],
                    "test": self.child, "contract": self.contract_path}[artifact]
                original = target.read_bytes()
                if artifact == "contract":
                    changed = json.loads(original)
                    changed["revision"] += 1
                    target.write_text(json.dumps(changed))
                else:
                    target.write_bytes(original + b" ")
                self.assertFalse(runner.validate_bound_receipt(output, expected_phase="GREEN")["ok"])
                target.write_bytes(original)

    def test_historical_red_source_may_differ_but_test_must_match(self):
        report, output = self.execute(phase="RED", failures=1, exit_code=1)
        (self.root / "source.py").write_text("value = 2\n")
        self.assertTrue(runner.validate_bound_receipt(output, expected_phase="RED")["ok"])
        self.child.write_text("# changed test\n")
        self.assertFalse(runner.validate_bound_receipt(output, expected_phase="RED")["ok"])

    def test_launchfailure_timeout_missingxml_cannot_count_red(self):
        report, _ = self.execute(phase="RED", code="raise SystemExit(9)\n")
        self.assertEqual(report["verificationPhaseOutcome"], "UNCONFIRMED")
        interrupted, _ = self.execute(phase="RED", code="import time\ntime.sleep(2)\n", timeout=0.05)
        self.assertEqual(interrupted["verificationPhaseOutcome"], "UNCONFIRMED")
        missing = [str(self.root / "missing-executable")]
        self.contract["stages"][0]["successTests"][0]["argvSha256"] = hashlib.sha256(json.dumps(missing).encode()).hexdigest()
        self.contract_path.write_text(json.dumps(self.contract))
        failed = runner.run(missing, self.root, self.root / "launch-failed",
            suites=["example.Suite"], xml_dir=self.root, sources=["source.py", "check.py"],
            contract="request-contract.json", stage="one", phase="RED", command_id="focused")
        self.assertEqual((failed["status"], failed["verificationPhaseOutcome"]), ("launch_failed", "UNCONFIRMED"))

    def test_future_junit_and_header_without_failed_cases_are_rejected(self):
        future, _ = self.execute(code="import os, time\n"
            "# Set the timestamp after the synthetic writer via an atexit callback.\n"
            "import atexit\natexit.register(lambda: os.utime('TEST-example.Suite.xml', ns=(time.time_ns()+10**12, time.time_ns()+10**12)))\n")
        self.assertEqual(future["verificationPhaseOutcome"], "UNCONFIRMED")
        self.assertIn("future_xml:example.Suite", future["failures"])
        report, output = self.execute(phase="RED", failures=1, exit_code=1)
        xml_path = output / report["resultFiles"][0]["path"]
        xml_path.write_text(xml_path.read_text().replace("<failure/>", ""))
        report["resultFiles"][0]["sha256"] = hashlib.sha256(xml_path.read_bytes()).hexdigest()
        (output / "run.json").write_text(json.dumps(report))
        self.assertFalse(runner.validate_bound_receipt(output, expected_phase="RED")["ok"])

    def test_other_hold_stage_does_not_block_ready_stage_and_binding_mismatch_rejected(self):
        runner.check_request_contract.return_value = {"status": "HOLD", "stages": [
            {"id": "one", "status": "READY"}, {"id": "later", "status": "HOLD"}]}
        report, output = self.execute()
        self.assertEqual(report["verificationPhaseOutcome"], "GREEN")
        self.assertFalse(runner.validate_bound_receipt(output, binding={"revision": 2})["ok"])

    def test_directory_and_sensitive_contract_targets_are_never_hashed(self):
        self.contract["stages"][0]["sourceFiles"] = [".env-fixture"]
        (self.root / ".env-fixture").write_text("synthetic = true")
        self.contract_path.write_text(json.dumps(self.contract))
        with self.assertRaises(ValueError):
            self.execute(sources=[".env-fixture", "check.py"])

    def test_xml_written_after_command_exit_cannot_be_collected(self):
        original = runner.fingerprint
        def late_xml(path):
            if path.name == "TEST-example.Suite.xml":
                time.sleep(0.005)  # Ensure a distinct Windows file-time tick after the cutoff.
                changed = time.time_ns()
                os.utime(path, ns=(changed, changed))
            return original(path)
        with patch.object(runner, "fingerprint", side_effect=late_xml):
            report, _ = self.execute()
        self.assertEqual(report["verificationPhaseOutcome"], "UNCONFIRMED")
        self.assertIn("late_xml:example.Suite", report["failures"])

    def test_modify_and_restore_source_cannot_claim_observed_stability(self):
        report, _ = self.execute(code="original = Path('source.py').read_bytes()\n"
            "Path('source.py').write_text('value = 9')\n"
            "Path('source.py').write_bytes(original)\n"
            "import os, time\nos.utime('source.py', ns=(time.time_ns(), time.time_ns()))\n")
        self.assertEqual(report["sourceIdentity"], report["sourceIdentityEnd"])
        self.assertIn("source_stat_drift", report["failures"])
        self.assertEqual(report["verificationPhaseOutcome"], "UNCONFIRMED")

    def test_sensitive_root_cannot_bypass_relative_name_guard(self):
        root = self.root / ".secrets"
        root.mkdir()
        (root / "fixture.json").write_text("{}")
        with self.assertRaises(ValueError):
            runner.exact_file(root, "fixture.json")

    def test_real_doctor_accepts_explicit_synthetic_contract(self):
        self.doctor.stop()
        self.contract.update(goal="Verify a synthetic source", knowledge=[{"id": "known", "kind": "fact",
            "summary": "Synthetic contract only", "sourceRef": "fixture:synthetic"}])
        self.contract["stages"][0].update(dependsOn=[], requiredKnowledge=["known"],
            inputs=[{"name": "source", "type": "file", "knowledgeRef": "known"}],
            outputs=[{"name": "result", "type": "JUnit", "knowledgeRef": "known"}],
            api={"applicable": False, "reason": "Local synthetic test", "knowledgeRef": "known"},
            errors=[], steps=["Run a focused fixture", "Verify the recorded outcome"])
        self.contract_path.write_text(json.dumps(self.contract))
        report, output = self.execute()
        self.assertEqual(report["verificationPhaseOutcome"], "GREEN")
        self.assertTrue(runner.validate_bound_receipt(output, expected_phase="GREEN")["ok"])

    def test_planned_command_and_suites_must_match_before_launch(self):
        self.contract["stages"][0]["successTests"][0]["argvSha256"] = "0" * 64
        self.contract["stages"][0]["successTests"][0]["expectedSuites"] = ["example.Suite"]
        self.contract_path.write_text(json.dumps(self.contract))
        with self.assertRaises(ValueError):
            self.execute()
        self.contract["stages"][0]["successTests"][0]["argvSha256"] = hashlib.sha256(json.dumps(self.command).encode()).hexdigest()
        self.contract["stages"][0]["successTests"][0]["expectedSuites"] = ["UnrelatedSuite"]
        self.contract_path.write_text(json.dumps(self.contract))
        with self.assertRaises(ValueError):
            self.execute()

    def test_foreign_cwd_is_rejected_before_contract_or_sources_are_read(self):
        report, output = self.execute()
        report["cwd"] = str(self.root / "foreign")
        (output / "run.json").write_text(json.dumps(report))
        with patch.object(runner, "bind_contract") as binding:
            checked = runner.validate_bound_receipt(output, expected_phase="GREEN", expected_root=self.root)
        self.assertFalse(checked["ok"])
        binding.assert_not_called()

    def test_empty_or_incomplete_stability_cannot_reuse_receipt(self):
        for shape in ([], None, [{"path": "source.py"}]):
            with self.subTest(shape=shape):
                report, output = self.execute()
                report["sourceStability"] = report["sourceStabilityEnd"] = shape
                (output / "run.json").write_text(json.dumps(report))
                self.assertFalse(runner.validate_bound_receipt(output, expected_phase="GREEN")["ok"])


if __name__ == "__main__":
    unittest.main()
