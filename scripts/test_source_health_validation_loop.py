import importlib.util
import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "source_health_validation_loop.py"
SPEC = importlib.util.spec_from_file_location("source_health_validation_loop", SCRIPT_PATH)
source_health_validation_loop = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(source_health_validation_loop)


class SourceHealthValidationLoopTest(unittest.TestCase):
    def test_gate_input_is_not_overwritten_by_report_or_cycle_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "verification").mkdir()
            path = root / "verification/input.json"
            path.write_text("{}")
            for output_option in ("--output", "--cycles-output"):
                with self.subTest(option=output_option), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit):
                        source_health_validation_loop.main([
                            "--root", str(root), "--code-gate-input", str(path), output_option, str(path)])
                    self.assertEqual("{}", path.read_text())

    def test_optional_evidence_gate_is_fail_closed_without_changing_ledger_authority(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "verification").mkdir()
            (root / "verification/source-health-scorecard.json").write_text("{}")
            report, cycles = source_health_validation_loop.build_report(
                root, 1, 1, root / "missing-evidence.json")
            self.assertEqual("HOLD", report["agentCodeEvidenceGate"]["verdict"])
            self.assertFalse(report["mutationAllowed"])
            self.assertTrue(all(row["mutatedSource"] is False for row in cycles))
            with contextlib.redirect_stdout(io.StringIO()):
                code = source_health_validation_loop.main([
                    "--root", str(root), "--code-gate-input", str(root / "missing-evidence.json")])
            self.assertEqual(3, code)

    def test_once_cycle_records_scorecard_sidecars_and_next_pattern(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            scorecard = {
                "decision": "source_health_scorecard",
                "strictEvidenceAdjustedScore": 78.1425,
                "failurePatternKind": "cross_subsystem_concentration",
                "patternId": "FP-S01S08-CROSS-CONCENTRATION",
                "amplifiedSignalScore": 0.6884,
                "failurePatternPrediction": {
                    "producerValidationQueue": {
                        "schema": "producer_validation_queue.v1",
                        "maxDurationHours": 9,
                        "runtimeProductBehavior": False,
                        "assignments": [
                            {
                                "producerRole": "macmini",
                                "failurePatternKind": "cross_subsystem_concentration",
                                "patternId": "FP-S01S08-CROSS-CONCENTRATION",
                                "amplifiedSignalScore": 0.7,
                                "requiredTraceStoreKeys": [
                                    "sourceHealth.failurePatternKind",
                                    "sourceHealth.patternId",
                                    "hypernova.twpmP",
                                    "hypernova.cvarPhi",
                                    "hypernova.riskKAlloc",
                                    "hypernova.clampApplied",
                                    "sourceHealth.amplifiedSignalScore",
                                ],
                                "amplifierTraceKeys": [
                                    "hypernova.twpmP",
                                    "hypernova.cvarPhi",
                                    "hypernova.riskKAlloc",
                                    "hypernova.clampApplied",
                                    "sourceHealth.amplifiedSignalScore",
                                ],
                                "debugEventNdjsonPath": "verification/source-health-failure-pattern-events.ndjson",
                                "patchDropManifestPath": "verification/source-health-patchdrop-manifest-contract.json",
                                "directCanonicalSourceEdit": False,
                                "evidenceOnly": True,
                            },
                            {
                                "producerRole": "notebook",
                                "failurePatternKind": "external_supabase_evidence_gap",
                                "patternId": "FP-EXT-SUPABASE-LIVE-PROOF",
                                "amplifiedSignalScore": 0.48,
                                "requiredTraceStoreKeys": [
                                    "sourceHealth.failurePatternKind",
                                    "sourceHealth.patternId",
                                    "supabase.projectScopeStatus",
                                    "hypernova.twpmP",
                                    "hypernova.cvarPhi",
                                    "hypernova.riskKAlloc",
                                    "hypernova.clampApplied",
                                    "sourceHealth.amplifiedSignalScore",
                                ],
                                "amplifierTraceKeys": [
                                    "hypernova.twpmP",
                                    "hypernova.cvarPhi",
                                    "hypernova.riskKAlloc",
                                    "hypernova.clampApplied",
                                    "sourceHealth.amplifiedSignalScore",
                                ],
                                "debugEventNdjsonPath": "verification/source-health-failure-pattern-events.ndjson",
                                "patchDropManifestPath": "verification/source-health-patchdrop-manifest-contract.json",
                                "directCanonicalSourceEdit": False,
                                "evidenceOnly": True,
                            },
                        ],
                    }
                },
                "failurePatternEvidenceArtifacts": {
                    "schema": "failure_pattern_evidence_artifacts.v1",
                    "debugEventNdjsonPath": "verification/source-health-failure-pattern-events.ndjson",
                    "patchDropManifestPath": "verification/source-health-patchdrop-manifest-contract.json",
                    "patchDropManifestHash": "abc123",
                    "runtimeScoreClaim": False,
                    "producerExecutionObserved": False,
                },
            }
            (verification / "source-health-scorecard.json").write_text(
                json.dumps(scorecard),
                encoding="utf-8",
            )
            (verification / "source-health-failure-pattern-events.ndjson").write_text(
                json.dumps(
                    {
                        "eventType": "source_health.failure_pattern_prediction",
                        "failurePatternKind": "cross_subsystem_concentration",
                        "patternId": "FP-S01S08-CROSS-CONCENTRATION",
                        "requiredEvidenceSinks": [
                            "TraceStore",
                            "DebugEventStore",
                            "CFVM Failure Pattern",
                        ],
                        "traceStoreKeys": [
                            "sourceHealth.failurePatternKind",
                            "sourceHealth.patternId",
                            "hypernova.twpmP",
                            "hypernova.cvarPhi",
                            "hypernova.riskKAlloc",
                            "hypernova.clampApplied",
                            "sourceHealth.amplifiedSignalScore",
                        ],
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            (verification / "source-health-patchdrop-manifest-contract.json").write_text(
                json.dumps(
                    {
                        "schema": "patchdrop.producer_manifest.failure_pattern.v1",
                        "producerRoles": ["macmini", "notebook"],
                        "requiredEvidenceSinks": [
                            "TraceStore",
                            "DebugEventStore",
                            "CFVM Failure Pattern",
                        ],
                        "traceStoreKeys": [
                            "sourceHealth.failurePatternKind",
                            "sourceHealth.patternId",
                            "hypernova.twpmP",
                            "hypernova.cvarPhi",
                            "hypernova.riskKAlloc",
                            "hypernova.clampApplied",
                            "sourceHealth.amplifiedSignalScore",
                        ],
                    }
                ),
                encoding="utf-8",
            )

            output = verification / "source-health-validation-loop.json"
            cycles = verification / "source-health-validation-cycles.ndjson"
            rc = source_health_validation_loop.main(
                [
                    "--root",
                    str(root),
                    "--output",
                    str(output),
                    "--cycles-output",
                    str(cycles),
                    "--max-duration-hours",
                    "9",
                    "--max-cycles",
                    "1",
                    "--skip-gradle",
                ]
            )

            self.assertEqual(0, rc)
            report = json.loads(output.read_text(encoding="utf-8"))
            cycle_rows = [
                json.loads(line)
                for line in cycles.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]

            self.assertEqual("source_health.validation_loop.v1", report["schema"])
            self.assertEqual("agent_safe_patch_budget", report["timeboxKind"])
            self.assertEqual(9, report["maxDurationHours"])
            self.assertFalse(report["runtimeProductBehavior"])
            self.assertEqual(1, report["cycleCount"])
            self.assertEqual("cross_subsystem_concentration", report["nearestFailurePatternKind"])
            self.assertEqual("FP-S01S08-CROSS-CONCENTRATION", report["nearestPatternId"])
            self.assertIn("sourceHealthScorecard", report["requiredGates"])
            self.assertIn("harmonyPressureReport", report["requiredGates"])
            self.assertIn("hypernova.cvarPhi", report["amplifierTraceKeys"])
            self.assertIn("hypernova.riskKAlloc", report["amplifierTraceKeys"])
            self.assertIn("TraceStore", report["requiredEvidenceSinks"])
            self.assertIn("DebugEventStore", report["requiredEvidenceSinks"])
            self.assertIn("CFVM Failure Pattern", report["requiredEvidenceSinks"])
            self.assertEqual(["macmini", "notebook"], report["producerRoles"])
            self.assertEqual("producer_validation_queue.v1", report["producerValidationQueue"]["schema"])
            self.assertEqual(9, report["producerValidationQueue"]["maxDurationHours"])
            self.assertFalse(report["producerValidationQueue"]["runtimeProductBehavior"])
            self.assertEqual(2, report["producerValidationQueue"]["assignmentCount"])
            self.assertEqual(["macmini", "notebook"], report["producerValidationQueue"]["producerRoles"])
            self.assertEqual(2, len(report["producerAssignments"]))
            first_assignment = report["producerAssignments"][0]
            self.assertEqual("macmini", first_assignment["producerRole"])
            self.assertEqual("local-worktree", first_assignment["sourceRootKind"])
            self.assertFalse(first_assignment["directCanonicalSourceEdit"])
            self.assertTrue(first_assignment["evidenceOnly"])
            self.assertEqual(
                "verification/source-health-failure-pattern-events.ndjson",
                first_assignment["debugEventNdjsonPath"],
            )
            self.assertEqual(
                "verification/source-health-patchdrop-manifest-contract.json",
                first_assignment["patchDropManifestPath"],
            )
            self.assertIn("TraceStore keys", first_assignment["requiredEvidenceArtifacts"])
            self.assertIn("DebugEvent NDJSON", first_assignment["requiredEvidenceArtifacts"])
            self.assertIn("PatchDrop manifest", first_assignment["requiredEvidenceArtifacts"])
            self.assertIn("sourceHealth.failurePatternKind", first_assignment["requiredTraceStoreKeys"])
            self.assertIn("hypernova.cvarPhi", first_assignment["amplifierTraceKeys"])
            self.assertTrue(report["sidecarProof"]["debugEventNdjsonPresent"])
            self.assertTrue(report["sidecarProof"]["patchDropManifestPresent"])
            self.assertTrue(report["sidecarProof"]["traceStoreReady"])
            self.assertEqual(0, report["secretPatternHits"])
            self.assertEqual(0, report["windowsAbsPathHits"])
            self.assertEqual(1, len(cycle_rows))
            self.assertEqual(
                "FP-S01S08-CROSS-CONCENTRATION",
                cycle_rows[0]["patternId"],
            )
            self.assertIn("hypernova.cvarPhi", cycle_rows[0]["requiredTraceStoreKeys"])
            self.assertIn("hypernova.riskKAlloc", cycle_rows[0]["requiredTraceStoreKeys"])
            self.assertIn("DebugEventStore", cycle_rows[0]["requiredEvidenceSinks"])
            self.assertIn("CFVM Failure Pattern", cycle_rows[0]["requiredEvidenceSinks"])
            self.assertFalse(cycle_rows[0]["mutatedSource"])

    def test_cli_output_uses_relative_paths_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "source-health-scorecard.json").write_text(
                json.dumps(
                    {
                        "decision": "source_health_scorecard",
                        "failurePatternKind": "cross_subsystem_concentration",
                        "patternId": "FP-S01S08-CROSS-CONCENTRATION",
                        "amplifiedSignalScore": 0.5,
                    }
                ),
                encoding="utf-8",
            )
            stdout = io.StringIO()

            with contextlib.redirect_stdout(stdout):
                rc = source_health_validation_loop.main(
                    [
                        "--root",
                        str(root),
                        "--output",
                        "verification/source-health-validation-loop.json",
                        "--cycles-output",
                        "verification/source-health-validation-cycles.ndjson",
                        "--skip-gradle",
                    ]
                )

            self.assertEqual(0, rc)
            rendered = stdout.getvalue()
            self.assertIn("report=verification/source-health-validation-loop.json", rendered)
            self.assertNotIn(str(root), rendered)
            self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]")

    def test_output_paths_must_remain_inside_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            for field, expected_reason in (
                ("output", "output_path_outside_root"),
                ("cycles", "cycles_output_path_outside_root"),
            ):
                with self.subTest(field=field):
                    root = Path(tmp) / f"root-{field}"
                    root.mkdir()
                    output_arg = "verification/report.json"
                    cycles_arg = "verification/cycles.ndjson"
                    if field == "output":
                        output_arg = f"../outside-{field}/report.json"
                    else:
                        cycles_arg = f"../outside-{field}/cycles.ndjson"

                    stdout = io.StringIO()
                    stderr = io.StringIO()
                    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                        with self.assertRaises(SystemExit) as raised:
                            source_health_validation_loop.main(
                                [
                                    "--root",
                                    str(root),
                                    "--output",
                                    output_arg,
                                    "--cycles-output",
                                    cycles_arg,
                                    "--skip-gradle",
                                ]
                            )

                    self.assertEqual(2, raised.exception.code)
                    self.assertIn(expected_reason, stderr.getvalue())
                    self.assertEqual("", stdout.getvalue())
                    self.assertFalse((Path(tmp) / f"outside-{field}").exists())
                    self.assertFalse((root / "verification" / "report.json").exists())
                    self.assertFalse((root / "verification" / "cycles.ndjson").exists())

    def test_output_and_cycles_paths_must_not_alias(self):
        with tempfile.TemporaryDirectory() as tmp:
            for mode in ("same-path", "hard-link"):
                with self.subTest(mode=mode):
                    root = Path(tmp) / mode
                    verification = root / "verification"
                    verification.mkdir(parents=True)
                    output = verification / "report.json"
                    cycles = output if mode == "same-path" else verification / "cycles.ndjson"
                    marker = "benign-preservation-marker"
                    if mode == "hard-link":
                        output.write_text(marker, encoding="utf-8")
                        os.link(output, cycles)

                    stdout = io.StringIO()
                    stderr = io.StringIO()
                    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                        with self.assertRaises(SystemExit) as raised:
                            source_health_validation_loop.main(
                                [
                                    "--root",
                                    str(root),
                                    "--output",
                                    str(output),
                                    "--cycles-output",
                                    str(cycles),
                                    "--skip-gradle",
                                ]
                            )

                    self.assertEqual(2, raised.exception.code)
                    self.assertIn("output_paths_conflict", stderr.getvalue())
                    self.assertEqual("", stdout.getvalue())
                    if mode == "same-path":
                        self.assertFalse(output.exists())
                    else:
                        self.assertEqual(marker, output.read_text(encoding="utf-8"))
                        self.assertEqual(marker, cycles.read_text(encoding="utf-8"))

    def test_default_agent_budget_cycles_cover_producer_queue(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            trace_keys = [
                "sourceHealth.failurePatternKind",
                "sourceHealth.patternId",
                "hypernova.twpmP",
                "hypernova.cvarPhi",
                "hypernova.riskKAlloc",
                "hypernova.clampApplied",
                "sourceHealth.amplifiedSignalScore",
            ]
            assignments = [
                ("macmini", "cross_subsystem_concentration", "FP-S01S08-CROSS-CONCENTRATION", 0.7),
                ("notebook", "external_supabase_evidence_gap", "FP-EXT-SUPABASE-LIVE-PROOF", 0.48),
                ("macmini", "test_tree_contamination", "FP-TEST-TREE-CONTAMINATION", 0.32),
                ("notebook", "trace_silent_swallow", "FP-TRACE-SILENT-SWALLOW", 0.21),
                ("macmini", "aspect_order_hotspot", "FP-AOP-ORDER-HOTSPOT", 0.09),
            ]
            manifest = {
                "schema": "patchdrop.producer_manifest.failure_pattern.v1",
                "requiredEvidenceSinks": [
                    "TraceStore",
                    "DebugEventStore",
                    "CFVM Failure Pattern",
                ],
                "traceStoreKeys": trace_keys,
            }
            manifest_path = verification / "source-health-patchdrop-manifest-contract.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            scorecard = {
                "decision": "source_health_scorecard",
                "failurePatternKind": "cross_subsystem_concentration",
                "patternId": "FP-S01S08-CROSS-CONCENTRATION",
                "amplifiedSignalScore": 0.6884,
                "failurePatternPrediction": {
                    "producerValidationQueue": {
                        "schema": "producer_validation_queue.v1",
                        "maxDurationHours": 9,
                        "runtimeProductBehavior": False,
                        "assignments": [
                            {
                                "producerRole": role,
                                "failurePatternKind": kind,
                                "patternId": pattern_id,
                                "amplifiedSignalScore": score,
                                "requiredTraceStoreKeys": trace_keys,
                                "amplifierTraceKeys": source_health_validation_loop.REQUIRED_AMPLIFIER_TRACE_KEYS,
                                "directCanonicalSourceEdit": False,
                                "evidenceOnly": True,
                            }
                            for role, kind, pattern_id, score in assignments
                        ],
                    }
                },
                "failurePatternEvidenceArtifacts": {
                    "debugEventNdjsonPath": "verification/source-health-failure-pattern-events.ndjson",
                    "patchDropManifestPath": "verification/source-health-patchdrop-manifest-contract.json",
                },
            }
            (verification / "source-health-scorecard.json").write_text(
                json.dumps(scorecard),
                encoding="utf-8",
            )
            (verification / "source-health-failure-pattern-events.ndjson").write_text(
                json.dumps(
                    {
                        "eventType": "source_health.failure_pattern_prediction",
                        "requiredEvidenceSinks": [
                            "TraceStore",
                            "DebugEventStore",
                            "CFVM Failure Pattern",
                        ],
                        "traceStoreKeys": trace_keys,
                    }
                )
                + "\n",
                encoding="utf-8",
            )

            output = verification / "source-health-validation-loop.json"
            cycles = verification / "source-health-validation-cycles.ndjson"
            rc = source_health_validation_loop.main(
                [
                    "--root",
                    str(root),
                    "--output",
                    str(output),
                    "--cycles-output",
                    str(cycles),
                    "--skip-gradle",
                ]
            )

            self.assertEqual(0, rc)
            report = json.loads(output.read_text(encoding="utf-8"))
            cycle_rows = [
                json.loads(line)
                for line in cycles.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            self.assertEqual(5, report["producerValidationQueue"]["assignmentCount"])
            self.assertEqual(5, report["cycleCount"])
            self.assertEqual([item[2] for item in assignments], [row["patternId"] for row in cycle_rows])
            self.assertEqual(
                {"macmini", "notebook"},
                {row["producerRole"] for row in cycle_rows},
            )


if __name__ == "__main__":
    unittest.main()
