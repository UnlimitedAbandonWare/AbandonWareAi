"""Behavioral oracles for the diagnostic layer; fixtures contain no private data."""
import copy
import gc
import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))


def context(case="case-1", **changes):
    value = dict(skillId="fixture-skill", skillHash="1" * 64,
                 sourceFingerprint="2" * 64, caseId=case, inputRef=case,
                 cohortId="fixed-cohort", environmentHash="3" * 64,
                 oracleHash="4" * 64, policyHash="5" * 64, caseRef="case-packet")
    value.update(changes)
    return value


class DiagnosticsTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((ROOT / "tools/skill_diagnostics_trace.py").exists(),
                        "Missing approved correlated trace and metrics implementation")
        self.assertTrue((ROOT / "tools/skill_diagnostics.py").exists(),
                        "Missing approved diagnostic discovery implementation")
        self.trace = importlib.import_module("skill_diagnostics_trace")
        self.diag = importlib.import_module("skill_diagnostics")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def record(self, name, case="case-1", status="succeeded", **kw):
        with self.trace.TraceRecorder(self.root, name, context(case, **kw)) as rec:
            rec.decision("inspect", evidence_required=False)
            with rec.tool("fixture-tool"):
                pass
            rec.set_result(status)
        return name

    def metrics(self, names):
        return self.trace.aggregate(self.root, names)

    def group(self, names):
        return self.metrics(names)["skills"][0]

    def test_no_observations_are_null_not_zero(self):
        result = self.trace.aggregate(self.root, [], expected_skills=["absent"])
        self.assertEqual(result["unobservedSkills"], ["absent"])
        self.assertIsNone(result["skills"][0]["failureRate"]["value"])
        self.assertFalse(result["telemetryComplete"])

    def test_retries_parallel_calls_and_run_success_are_separate(self):
        error = ValueError("PRIVATE_SENTINEL_never_log")
        with self.trace.TraceRecorder(self.root, "retry.jsonl", context()) as rec:
            rec.decision("inspect", evidence_required=False)
            try:
                with rec.tool("fixture-tool", call_id="call-a", attempt=1):
                    raise error
            except ValueError as actual:
                self.assertIs(actual, error)
            with rec.tool("fixture-tool", call_id="call-b", attempt=1):
                pass
            with rec.tool("fixture-tool", call_id="call-a", attempt=2):
                pass
        m = self.group(["retry.jsonl"])
        self.assertEqual(m["retryCount"], 1)
        self.assertEqual(m["toolSuccessRate"], dict(numerator=2, denominator=3, value=2/3))
        self.assertEqual(m["logicalToolSuccessRate"]["value"], 1)
        self.assertEqual(m["failureRate"]["value"], 0)
        self.assertEqual(m["exceptionCount"], 1)
        self.assertNotIn("PRIVATE_SENTINEL", (self.root / "retry.jsonl").read_text())

    def test_exception_transparency_and_no_double_count(self):
        error = RuntimeError("secret payload")
        with self.assertRaises(RuntimeError) as caught:
            with self.trace.TraceRecorder(self.root, "fail.jsonl", context()) as rec:
                rec.decision("inspect", evidence_required=False)
                with rec.tool("fixture-tool"):
                    raise error
        self.assertIs(caught.exception, error)
        m = self.group(["fail.jsonl"])
        self.assertEqual(m["exceptionCount"], 1)
        self.assertEqual(m["failureRate"]["value"], 1)

    def test_recycled_exception_ids_do_not_erase_distinct_failures(self):
        with self.trace.TraceRecorder(self.root, "many.jsonl", context()) as rec:
            rec.decision("inspect", evidence_required=False)
            for _ in range(50):
                try:
                    with rec.tool("fixture-tool"):
                        raise ValueError()
                except ValueError:
                    pass
                gc.collect()
        self.assertEqual(self.group(["many.jsonl"])["exceptionCount"], 50)
        self.assertFalse(rec.seen_exceptions)

    def test_timeout_and_cancel_are_not_success(self):
        for name, exc in [("timeout", TimeoutError()), ("cancel", KeyboardInterrupt())]:
            try:
                with self.trace.TraceRecorder(self.root, name + ".jsonl", context(name)) as rec:
                    rec.decision("inspect", evidence_required=False)
                    with rec.tool("fixture-tool"):
                        raise exc
            except BaseException:
                pass
        m = self.group(["timeout.jsonl", "cancel.jsonl"])
        self.assertEqual(m["failureRate"], dict(numerator=1, denominator=1, value=1.0))
        self.assertEqual(m["runStatuses"]["cancelled"], 1)

    def test_missing_decision_is_incomplete(self):
        with self.trace.TraceRecorder(self.root, "gap.jsonl", context()) as rec:
            with rec.tool("fixture-tool"):
                pass
        m = self.metrics(["gap.jsonl"])
        self.assertFalse(m["telemetryComplete"])
        self.assertGreater(m["issues"].get("missing_stage", 0), 0)

    def test_evidence_missing_is_not_automatically_hallucination(self):
        with self.trace.TraceRecorder(self.root, "missing.jsonl", context()) as rec:
            rec.decision("guess")
            with rec.tool("fixture-tool"):
                pass
        m = self.group(["missing.jsonl"])
        self.assertEqual(m["unsupportedDecisionCount"], 1)
        self.assertEqual(m["contradictedDecisionCount"], 0)

    def evidence(self, outcome="pass", **changes):
        packet = dict(schemaVersion="demo1.skill-diagnostics.verification.v1",
                      caseId="case-1", inputRef="case-1", sourceFingerprint="2"*64,
                      oracleHash="4"*64, outcome=outcome, validatorId="fixed-test")
        packet.update(changes)
        path = self.write("evidence.json", json.dumps(packet))
        return {"path": "evidence.json", "sha256": self.trace.file_hash(path)}

    def test_contradiction_requires_bound_validator(self):
        ref = self.evidence("contradiction")
        with self.trace.TraceRecorder(self.root, "bound.jsonl", context()) as rec:
            rec.decision("claim", evidence_refs=[ref])
            with rec.tool("fixture-tool"):
                pass
        m = self.group(["bound.jsonl"])
        self.assertEqual(m["contradictedDecisionCount"], 1)
        self.assertEqual(m["unsupportedDecisionCount"], 0)

    def test_mismatched_evidence_is_not_validated(self):
        ref = self.evidence("contradiction", inputRef="other-input")
        with self.trace.TraceRecorder(self.root, "mismatch.jsonl", context()) as rec:
            rec.decision("claim", evidence_refs=[ref])
            with rec.tool("fixture-tool"):
                pass
        m = self.group(["mismatch.jsonl"])
        self.assertEqual(m["contradictedDecisionCount"], 0)
        self.assertEqual(m["unsupportedDecisionCount"], 1)

    def test_evidence_revalidated_after_bytes_change(self):
        ref = self.evidence()
        with self.trace.TraceRecorder(self.root, "stale.jsonl", context()) as rec:
            rec.decision("claim", evidence_refs=[ref])
            with rec.tool("fixture-tool"):
                pass
        self.write("evidence.json", "{}")
        self.assertEqual(self.group(["stale.jsonl"])["unsupportedDecisionCount"], 1)

    def test_duplicate_events_do_not_inflate_metrics(self):
        self.record("events.jsonl")
        original = (self.root / "events.jsonl").read_text()
        self.write("duplicate.jsonl", original + original)
        m = self.metrics(["duplicate.jsonl"])
        self.assertEqual(m["duplicateEventCount"], 5)
        self.assertEqual(m["skills"][0]["runCount"], 1)

    def test_conflicting_events_and_truncated_json_fail_closed(self):
        self.record("events.jsonl")
        lines = (self.root / "events.jsonl").read_text().splitlines()
        conflict = json.loads(lines[-1]); conflict["status"] = "failed"
        self.write("bad.jsonl", "\n".join(lines) + "\n" + json.dumps(conflict) + "\n{\"bad\"")
        m = self.metrics(["bad.jsonl"])
        self.assertFalse(m["telemetryComplete"])
        self.assertIn("conflicting_event", m["issues"])
        self.assertIn("invalid_json", m["issues"])

    def test_conflicting_duplicates_never_select_a_winner(self):
        self.record("events.jsonl")
        lines = (self.root / "events.jsonl").read_text().splitlines()
        conflict = json.loads(lines[-1]); conflict["status"] = "failed"
        for name, rows in [("first", [json.dumps(conflict)] + lines),
                           ("last", lines + [json.dumps(conflict)])]:
            self.write(name + ".jsonl", "\n".join(rows) + "\n")
            group = self.group([name + ".jsonl"])
            self.assertIsNone(group["failureRate"]["value"])
            self.assertIsNone(group["toolSuccessRate"]["value"])

    def test_retry_recovery_cannot_hide_first_attempt_regression(self):
        self.record("before.jsonl")
        with self.trace.TraceRecorder(self.root, "after.jsonl", context()) as rec:
            rec.decision("inspect", evidence_required=False)
            with rec.tool("fixture-tool", call_id="call-a") as tool:
                tool["status"] = "failed"
            with rec.tool("fixture-tool", call_id="call-a", attempt=2):
                pass
        group = self.group(["after.jsonl"])
        self.assertEqual(group["firstAttemptFailureRate"]["value"], 1)
        self.assertEqual(group["retryRecoveredCallCount"], 1)
        result = self.trace.compare(self.metrics(["before.jsonl"]), self.metrics(["after.jsonl"]), root=self.root)
        self.assertEqual(result["status"], "regressed")

    def test_nonterminal_outcomes_cannot_support_an_improvement(self):
        self.record("before.jsonl", status="failed")
        self.record("after.jsonl", status="cancelled")
        result = self.trace.compare(self.metrics(["before.jsonl"]), self.metrics(["after.jsonl"]), root=self.root)
        self.assertEqual(result["status"], "insufficient_evidence")

    def test_comparison_replays_traces_and_detects_resigned_report(self):
        self.record("before.jsonl", status="failed")
        self.record("after.jsonl")
        before = self.metrics(["before.jsonl"]); after = self.metrics(["after.jsonl"])
        forged = copy.deepcopy(after)
        forged["runs"][0]["unsupportedDecisionCount"] = 99
        forged["summaryHash"] = self.trace.digest({k:v for k,v in forged.items() if k != "summaryHash"})
        self.assertEqual(self.trace.compare(before, forged, root=self.root)["status"], "not_comparable")
        self.write("after.jsonl", "{}\n")
        self.assertEqual(self.trace.compare(before, after, root=self.root)["status"], "not_comparable")

    def test_file_budget_counts_oversized_candidates(self):
        for index in range(4):
            self.write("tools/large%d.py" % index, "x"*100)
        result = self.diag.scan(self.root, ["tools"], limits={"maxFiles":2,"maxFileBytes":50})
        self.assertIn("budget_exceeded", result["issues"])
        self.assertLessEqual(result["metrics"]["filesDiscovered"], 3)

    def test_broken_root_and_retry_links_are_not_complete(self):
        self.record("events.jsonl")
        original = [json.loads(line) for line in (self.root / "events.jsonl").read_text().splitlines()]
        for name in ("root", "retry"):
            rows = copy.deepcopy(original)
            if name == "root":
                rows[-1]["spanId"] = "wrong-root"
            else:
                rows[2]["retryOf"] = rows[3]["retryOf"] = "unrelated-call"
            self.write(name + ".jsonl", "\n".join(json.dumps(x) for x in rows) + "\n")
            self.assertFalse(self.metrics([name + ".jsonl"])["telemetryComplete"])

    def test_cross_thread_use_is_explicitly_incomplete(self):
        with self.trace.TraceRecorder(self.root, "thread.jsonl", context()) as rec:
            rec.decision("inspect", evidence_required=False)
            def work():
                with rec.tool("fixture-tool"):
                    pass
            worker = threading.Thread(target=work)
            worker.start(); worker.join()
        self.assertFalse(rec.telemetry_complete)
        self.assertFalse(self.metrics(["thread.jsonl"])["telemetryComplete"])

    def test_trace_byte_cap_preserves_target_result(self):
        with self.trace.TraceRecorder(self.root, "small.jsonl", context(), max_bytes=2500) as rec:
            rec.decision("inspect", evidence_required=False)
            with rec.tool("fixture-tool"):
                answer = 42
        self.assertEqual(answer, 42)
        self.assertLessEqual((self.root / "small.jsonl").stat().st_size, 2500)
        self.assertFalse(rec.telemetry_complete)
        self.assertFalse(self.metrics(["small.jsonl"])["telemetryComplete"])

    def test_unknown_fields_and_nan_are_rejected(self):
        self.record("events.jsonl")
        rows = [json.loads(x) for x in (self.root / "events.jsonl").read_text().splitlines()]
        rows[-1]["durationMs"] = float("nan")
        rows[1]["privatePrompt"] = "sensitive"
        self.write("invalid.jsonl", "\n".join(json.dumps(x) for x in rows) + "\n")
        m = self.metrics(["invalid.jsonl"])
        self.assertFalse(m["telemetryComplete"])
        self.assertNotIn("sensitive", json.dumps(m))

    def test_missing_nullable_links_are_invalid_events(self):
        self.record("events.jsonl")
        rows = [json.loads(x) for x in (self.root / "events.jsonl").read_text().splitlines()]
        del rows[2]["retryOf"]
        del rows[-1]["parentSpanId"]
        self.write("invalid.jsonl", "\n".join(json.dumps(x) for x in rows) + "\n")
        m = self.metrics(["invalid.jsonl"])
        self.assertEqual(m["issues"]["invalid_event"], 2)
        self.assertIsNone(m["skills"][0]["failureRate"]["value"])

    def test_recorder_failure_preserves_target_and_marks_incomplete(self):
        with self.trace.TraceRecorder(self.root, "sink.jsonl", context()) as rec:
            rec.decision("inspect", evidence_required=False)
            rec.stream.close()
            with rec.tool("fixture-tool"):
                answer = 42
        self.assertEqual(answer, 42)
        self.assertFalse(rec.telemetry_complete)
        self.assertFalse(self.metrics(["sink.jsonl"])["telemetryComplete"])

    def test_exclusive_output_and_path_escape(self):
        self.record("exists.jsonl")
        with self.assertRaises((FileExistsError, ValueError)):
            self.record("exists.jsonl")
        with self.assertRaises(ValueError):
            self.record("../escape.jsonl")

    def test_latency_and_comparison_of_fixed_cases(self):
        for before, status, case in [(True,"failed","bad"),(True,"succeeded","normal"),
                                     (False,"succeeded","bad"),(False,"succeeded","normal")]:
            self.record(("before-" if before else "after-") + case + ".jsonl", case, status,
                        sourceFingerprint=("2" if before else "6")*64)
        before = self.metrics(["before-bad.jsonl", "before-normal.jsonl"])
        after = self.metrics(["after-bad.jsonl", "after-normal.jsonl"])
        self.assertEqual(self.trace.compare(before, after, root=self.root)["status"], "improved")
        self.assertEqual(after["skills"][0]["runLatencyMs"]["n"], 2)
        self.assertIsNotNone(after["skills"][0]["runLatencyMs"]["p95"])

    def test_comparison_rejects_cohort_changes_and_normal_regression(self):
        self.record("before.jsonl", status="succeeded")
        self.record("after.jsonl", status="failed")
        before, after = self.metrics(["before.jsonl"]), self.metrics(["after.jsonl"])
        self.assertEqual(self.trace.compare(before, after, root=self.root)["status"], "regressed")
        after["runs"][0]["inputRef"] = "different"
        self.assertEqual(self.trace.compare(before, after, root=self.root)["status"], "not_comparable")

    def test_comparison_does_not_accept_fabricated_aggregate(self):
        self.record("a.jsonl")
        before = self.metrics(["a.jsonl"])
        forged = copy.deepcopy(before); forged["telemetryComplete"] = False
        self.assertEqual(self.trace.compare(before, forged)["status"], "insufficient_evidence")

    def test_scan_candidates_and_unchanged_cache(self):
        self.write("tools/problem.py", "try:\n    risky()\nexcept Exception:\n    pass\n")
        self.write("tools/normal.py", "try:\n    risky()\nexcept ValueError:\n    raise\n")
        self.write("tools/__pycache__/skip.py", "except\n")
        self.write(".agents/skills/sample/SKILL.md", "---\nname: sample\ndescription: Use when testing.\n---\n")
        first = self.diag.scan(self.root, scopes=["tools", ".agents/skills"])
        self.assertTrue(any(x["rule"] == "quiet_broad_except" for x in first["findings"]))
        self.assertTrue(all(x["state"] == "candidate" for x in first["findings"]))
        self.assertFalse(any("__pycache__" in x["path"] for x in first["files"]))
        second = self.diag.scan(self.root, scopes=["tools", ".agents/skills"], cache=first["cache"])
        self.assertEqual(first["findings"], second["findings"])
        self.assertEqual(second["metrics"]["cacheMisses"], 0)
        self.assertGreater(second["metrics"]["cacheHits"], 0)

    def test_cache_uses_content_not_mtime(self):
        path = self.write("tools/a.py", "value = 1\n")
        stamp = path.stat().st_mtime_ns
        first = self.diag.scan(self.root, scopes=["tools"])
        self.write("tools/a.py", "try:\n    x()\nexcept:\n    pass\n")
        os.utime(path, ns=(stamp, stamp))
        second = self.diag.scan(self.root, scopes=["tools"], cache=first["cache"])
        self.assertEqual(second["metrics"]["cacheMisses"], 1)
        self.assertGreater(len(second["findings"]), 0)

    def test_scan_limits_and_invalid_python_are_visible(self):
        self.write("tools/a.py", "def broken(:\n")
        self.write("tools/b.py", "x" * 100)
        result = self.diag.scan(self.root, scopes=["tools"], limits={"maxFileBytes":50})
        self.assertFalse(result["complete"])
        self.assertIn("file_too_large", result["issues"])
        self.assertTrue(any(x["rule"] == "python_syntax" for x in result["findings"]))

    def test_scan_never_imports_discovered_scripts(self):
        marker = self.root / "side-effect.txt"
        self.write("tools/evil.py", "from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()\n")
        self.diag.scan(self.root, scopes=["tools"])
        self.assertFalse(marker.exists())

    def test_offline_registry_adapter_has_real_trace(self):
        self.write(".agents/skills/sample/SKILL.md", "---\nname: sample\ndescription: Use when testing.\n---\n")
        result = self.diag.run_adapter(self.root, "run", "registry")
        self.assertEqual(result["adapterStatus"], "succeeded")
        self.assertTrue(result["metrics"]["telemetryComplete"])
        self.assertEqual(result["metrics"]["skills"][0]["toolSuccessRate"]["value"], 1)
        self.assertEqual(result["result"]["sharedCount"], 1)

    def test_malformed_skill_adapter_is_failed_not_false_green(self):
        self.write(".agents/skills/sample/SKILL.md", "# no frontmatter\n")
        result = self.diag.run_adapter(self.root, "run", "registry")
        self.assertEqual(result["adapterStatus"], "failed")
        self.assertEqual(result["metrics"]["skills"][0]["failureRate"]["value"], 1)

    def test_log_adapter_preserves_redacted_existing_contract(self):
        self.write("build.log", "error: cannot find symbol\nPRIVATE_SENTINEL\n")
        result = self.diag.run_adapter(self.root, "run", "log-diagnose", log="build.log", synthetic_input=True)
        self.assertEqual(result["result"]["schemaVersion"], "awx.ai-debug-observation.v1")
        self.assertFalse(result["result"]["claimsVerified"])
        self.assertNotIn("PRIVATE_SENTINEL", json.dumps(result))
        self.assertTrue(result["metrics"]["skills"][0]["synthetic"])

    def test_synthetic_reproduction_is_labeled_and_normal_case_preserved(self):
        result = self.diag.reproduce(self.root, "repro")
        self.assertTrue(result["synthetic"])
        self.assertEqual(result["comparison"]["status"], "improved")
        self.assertEqual(result["comparison"]["normalRegressions"], 0)

    def test_triage_reuses_case_evidence_and_never_authorizes_patch(self):
        self.record("red-1.jsonl", status="failed")
        self.record("red-2.jsonl", status="failed")
        metrics = self.metrics(["red-1.jsonl", "red-2.jsonl"])
        self.trace.write_json(self.root, "metrics.json", metrics)
        target = self.write("tools/target.py", "raise ValueError()\n")
        result = self.diag.triage(self.root, "metrics.json", case_ref="case-packet",
                                  evidence_id="E-1", targets=["tools/target.py"])
        self.assertEqual(result["evidenceRow"]["evidenceId"], "E-1")
        self.assertEqual(result["candidateState"], "reproduced")
        self.assertIn("run_failure", result["observedClasses"])
        self.assertNotIn("tool_execution_failure", result["observedClasses"])
        self.assertFalse(result["mutationAuthorized"])
        self.assertFalse(result["causal"])
        self.assertEqual(result["targetPreimages"][0]["sha256"], self.trace.file_hash(target))
        self.assertIn("causal_probe", result["missingRepairEvidence"])

    def test_triage_revalidates_input_and_requires_repeatable_case(self):
        self.record("once.jsonl", status="failed")
        self.trace.write_json(self.root, "metrics.json", self.metrics(["once.jsonl"]))
        result = self.diag.triage(self.root, "metrics.json", case_ref="case-packet", evidence_id="E-1")
        self.assertEqual(result["candidateState"], "candidate")
        self.write("once.jsonl", "{}\n")
        with self.assertRaises(ValueError):
            self.diag.triage(self.root, "metrics.json", case_ref="case-packet", evidence_id="E-1")


if __name__ == "__main__":
    unittest.main()
