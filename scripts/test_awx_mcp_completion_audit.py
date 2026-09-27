import importlib.util
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMPLETION_AUDIT_PATH = ROOT / "scripts" / "awx_mcp_completion_audit.py"
SPEC = importlib.util.spec_from_file_location("awx_mcp_completion_audit", COMPLETION_AUDIT_PATH)
completion_audit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(completion_audit)
TOOLBOX_PATH = ROOT / "scripts" / "awx_mcp_toolbox.py"
TOOLBOX_SPEC = importlib.util.spec_from_file_location("awx_mcp_toolbox_for_completion_test", TOOLBOX_PATH)
toolbox = importlib.util.module_from_spec(TOOLBOX_SPEC)
assert TOOLBOX_SPEC.loader is not None
TOOLBOX_SPEC.loader.exec_module(toolbox)


class CompletionAuditDemandDrivenEvidenceTest(unittest.TestCase):
    @staticmethod
    def _tool_audit_row(tool_name="source_scan"):
        return {
            "requestId": "request-1",
            "sessionId": "session-1",
            "nodeRole": "desktop",
            "toolName": tool_name,
            "inputHash": "a" * 64,
            "outputCount": 1,
            "elapsedMs": 7,
            "decision": "read_only_probe",
            "failReason": "",
        }

    @staticmethod
    def _terminal_node_smoke_steps():
        decisions = {
            "agent_db_snapshot": "agent_db_snapshot_loaded",
            "trace_snapshot_probe": "trace_snapshot_loaded",
            "supabase_context_probe": "supabase_context_probe",
            "supabase_schema_snapshot": "supabase_schema_snapshot_evidence_needed",
        }
        return [
            {
                "toolName": tool_name,
                "exitCode": 0,
                "ok": True,
                "decision": decisions.get(tool_name, "ok"),
                "failReason": "",
                "localFallbackPresent": False,
                "outputCount": 0,
                "elapsedMs": 1,
                "evidence_needed": "",
            }
            for tool_name in sorted(completion_audit.REQUIRED_NODE_SMOKE_TOOLS)
        ]

    @staticmethod
    def _validate_node_smoke_with_both(steps):
        data = {
            "schemaVersion": "awx.mcp.node_smoke.v1",
            "nodeRole": "desktop",
            "ok": True,
            "rawSecretPatternHits": 0,
            "steps": steps,
        }
        return (
            completion_audit.validate_external_node_smoke(data, "desktop", raw_secret_hits=0),
            toolbox.validate_node_smoke_evidence(data, "desktop"),
        )

    def test_external_node_smoke_rejects_named_tools_without_terminal_outcomes(self):
        decisions = {
            "agent_db_snapshot": "agent_db_snapshot_loaded",
            "trace_snapshot_probe": "trace_snapshot_loaded",
            "supabase_context_probe": "supabase_context_probe",
            "supabase_schema_snapshot": "supabase_schema_snapshot_evidence_needed",
        }
        steps = [
            {
                "toolName": tool_name,
                "decision": decisions.get(tool_name, "ok"),
                "failReason": "",
            }
            for tool_name in sorted(completion_audit.REQUIRED_NODE_SMOKE_TOOLS)
        ]

        for summary in self._validate_node_smoke_with_both(steps):
            self.assertFalse(summary["valid"])
            self.assertIn("missing-terminal-outcome:", summary["failReason"])

    def test_external_node_smoke_accepts_allowlisted_terminal_steps(self):
        for summary in self._validate_node_smoke_with_both(self._terminal_node_smoke_steps()):
            self.assertTrue(summary["valid"], summary["failReason"])

    def test_external_node_smoke_rejects_unallowlisted_step_fields(self):
        steps = self._terminal_node_smoke_steps()
        steps[0]["rawResponse"] = "private response text"

        for summary in self._validate_node_smoke_with_both(steps):
            self.assertFalse(summary["valid"])
            self.assertIn("unallowlisted-step-fields:", summary["failReason"])

    def test_external_node_smoke_rejects_partial_duplicate_step(self):
        steps = self._terminal_node_smoke_steps()
        steps.append({"toolName": "source_scan"})

        for summary in self._validate_node_smoke_with_both(steps):
            self.assertFalse(summary["valid"])
            self.assertIn("invalid-step-shape:", summary["failReason"])

    def test_tool_execution_evidence_does_not_treat_static_readiness_as_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = completion_audit.tool_execution_evidence_summary(
                Path(tmp),
                ["source_scan"],
                readiness=True,
                now=datetime(2026, 8, 31, tzinfo=timezone.utc),
            )

        self.assertTrue(summary["ready"])
        self.assertFalse(summary["attempted"])
        self.assertFalse(summary["executed"])
        self.assertFalse(summary["evidenceObserved"])
        self.assertEqual("evidence_needed", summary["status"])
        self.assertEqual("tool_execution_audit_missing", summary["failReason"])
        self.assertEqual(["source_scan"], summary["missingTools"])

    def test_tool_execution_evidence_accepts_fresh_allowlisted_terminal_row(self):
        now = datetime(2026, 8, 31, 12, 0, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            audit_path = root / ".codex" / "awx-control-tower.audit.jsonl"
            audit_path.parent.mkdir(parents=True)
            audit_path.write_text(json.dumps(self._tool_audit_row()) + "\n", encoding="utf-8")
            os.utime(audit_path, (now.timestamp(), now.timestamp()))

            summary = completion_audit.tool_execution_evidence_summary(
                root,
                ["source_scan"],
                readiness=True,
                now=now,
            )

        self.assertTrue(summary["ready"])
        self.assertTrue(summary["attempted"])
        self.assertTrue(summary["executed"])
        self.assertTrue(summary["evidenceObserved"])
        self.assertTrue(summary["fresh"])
        self.assertEqual("observed", summary["status"])
        self.assertEqual(1, summary["observedToolCount"])
        self.assertEqual(1, summary["executedToolCount"])
        self.assertEqual([], summary["missingTools"])
        self.assertEqual(0, summary["rawSecretPatternHits"])

    def test_tool_execution_evidence_rejects_unallowlisted_secret_bearing_row(self):
        now = datetime(2026, 8, 31, 12, 0, tzinfo=timezone.utc)
        row = self._tool_audit_row()
        synthetic_secret = "sk-" + ("x" * 24)
        row["rawResponse"] = synthetic_secret
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            audit_path = root / ".codex" / "awx-control-tower.audit.jsonl"
            audit_path.parent.mkdir(parents=True)
            audit_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
            os.utime(audit_path, (now.timestamp(), now.timestamp()))

            summary = completion_audit.tool_execution_evidence_summary(
                root,
                ["source_scan"],
                readiness=True,
                now=now,
            )

        rendered = json.dumps(summary)
        self.assertFalse(summary["attempted"])
        self.assertFalse(summary["executed"])
        self.assertFalse(summary["evidenceObserved"])
        self.assertEqual("tool_execution_audit_secret_risk", summary["failReason"])
        self.assertEqual(1, summary["unallowlistedFieldRowCount"])
        self.assertEqual(1, summary["rawSecretPatternHits"])
        self.assertNotIn(synthetic_secret, rendered)

    def test_browser_boundary_preserves_allowlisted_plugin_bootstrap_blocker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact_path = root / "var" / "codex-smoke" / "browser-ui-smoke.json"
            artifact_path.parent.mkdir(parents=True)
            artifact_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.local.browser_ui_smoke.v1",
                        "generatedAt": datetime.now(timezone.utc).isoformat(),
                        "ok": False,
                        "decision": "evidence_needed",
                        "reachable": False,
                        "localhost": True,
                        "publicDomain": False,
                        "targetAccepted": True,
                        "screenshotCaptured": False,
                        "targetContentVisible": False,
                        "browserSurface": "iab",
                        "statusClass": "browser_plugin_bootstrap_failed",
                        "nextAction": "reload_bundled_browser_plugin",
                        "evidenceNeeded": "browser_plugin_bootstrap_failed",
                        "storesRawUrl": False,
                        "storesScreenshotPath": False,
                        "secretHits": 0,
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.browser_use_ui_boundary_summary(root)

        self.assertFalse(summary["ready"])
        self.assertTrue(summary["safePendingProof"])
        self.assertEqual("browser_plugin_bootstrap_failed", summary["evidenceNeededRaw"])
        self.assertEqual("reload_bundled_browser_plugin", summary["nextAction"])
        self.assertEqual(
            ["Browser plugin bootstrap failed / reload bundled Browser plugin before retrying DOM proof"],
            summary["evidenceNeeded"],
        )

    def test_browser_boundary_requires_explicit_status_for_plugin_bootstrap_blocker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact_path = root / "var" / "codex-smoke" / "browser-ui-smoke.json"
            artifact_path.parent.mkdir(parents=True)
            artifact_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.local.browser_ui_smoke.v1",
                        "generatedAt": datetime.now(timezone.utc).isoformat(),
                        "ok": False,
                        "decision": "evidence_needed",
                        "reachable": False,
                        "browserSurface": "iab",
                        "nextAction": "reload_bundled_browser_plugin",
                        "evidenceNeeded": "browser_plugin_bootstrap_failed",
                        "storesRawUrl": False,
                        "storesScreenshotPath": False,
                        "secretHits": 0,
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.browser_use_ui_boundary_summary(root)

        self.assertTrue(summary["safePendingProof"])
        self.assertEqual("", summary["statusClass"])
        self.assertEqual(
            [
                "Browser UI smoke evidence needed / rerun scripts\\refresh_local_interaction_smokes.ps1 with a path-free BrowserProbePath"
            ],
            summary["evidenceNeeded"],
        )

    def test_browser_boundary_requires_explicit_unreachable_for_plugin_bootstrap_blocker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact_path = root / "var" / "codex-smoke" / "browser-ui-smoke.json"
            artifact_path.parent.mkdir(parents=True)
            artifact_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.local.browser_ui_smoke.v1",
                        "generatedAt": datetime.now(timezone.utc).isoformat(),
                        "ok": False,
                        "decision": "evidence_needed",
                        "browserSurface": "iab",
                        "statusClass": "browser_plugin_bootstrap_failed",
                        "nextAction": "reload_bundled_browser_plugin",
                        "evidenceNeeded": "browser_plugin_bootstrap_failed",
                        "storesRawUrl": False,
                        "storesScreenshotPath": False,
                        "secretHits": 0,
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.browser_use_ui_boundary_summary(root)

        self.assertTrue(summary["safePendingProof"])
        self.assertEqual(
            [
                "Browser UI smoke evidence needed / rerun scripts\\refresh_local_interaction_smokes.ps1 with a path-free BrowserProbePath"
            ],
            summary["evidenceNeeded"],
        )

    def test_browser_boundary_redacts_unallowlisted_action(self):
        raw_action = "https://private.example/C:\\sensitive\\browser"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact_path = root / "var" / "codex-smoke" / "browser-ui-smoke.json"
            artifact_path.parent.mkdir(parents=True)
            artifact_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.local.browser_ui_smoke.v1",
                        "generatedAt": datetime.now(timezone.utc).isoformat(),
                        "ok": False,
                        "decision": "evidence_needed",
                        "reachable": False,
                        "browserSurface": "iab",
                        "nextAction": raw_action,
                        "evidenceNeeded": "browser_ui_smoke_evidence_needed",
                        "storesRawUrl": False,
                        "storesScreenshotPath": False,
                        "secretHits": 0,
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.browser_use_ui_boundary_summary(root)

        self.assertEqual("", summary["nextAction"])
        self.assertFalse(summary["safePendingProof"])
        self.assertNotIn(raw_action, json.dumps(summary))

    def test_browser_boundary_does_not_forward_untrusted_evidence_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact_path = root / "var" / "codex-smoke" / "browser-ui-smoke.json"
            artifact_path.parent.mkdir(parents=True)
            artifact_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.local.browser_ui_smoke.v1",
                        "generatedAt": datetime.now(timezone.utc).isoformat(),
                        "ok": False,
                        "decision": "evidence_needed",
                        "nextAction": "rerun_browser_local_ui_smoke",
                        "evidenceNeeded": "untrusted free-form evidence text",
                        "storesRawUrl": False,
                        "storesScreenshotPath": False,
                        "secretHits": 0,
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.browser_use_ui_boundary_summary(root)

        self.assertTrue(summary["safePendingProof"])
        self.assertEqual(
            [
                "Browser UI smoke evidence needed / rerun scripts\\refresh_local_interaction_smokes.ps1 with a path-free BrowserProbePath"
            ],
            summary["evidenceNeeded"],
        )

    def test_supabase_runtime_proof_is_hard_only_when_explicitly_required(self):
        supabase_failures = [
            {"id": "supabase.schema-snapshot-artifact", "failReason": "project_ref_missing"},
            {"id": "supabase.readonly-snapshot-smoke", "failReason": "project_ref_missing"},
        ]

        self.assertEqual([], completion_audit.hard_failures(supabase_failures))
        self.assertEqual(
            supabase_failures,
            completion_audit.hard_failures(
                supabase_failures,
                require_supabase_proof=True,
            ),
        )
        self.assertEqual(
            [],
            completion_audit.optional_evidence_failures(
                supabase_failures,
                require_supabase_proof=True,
            ),
        )

    def test_supabase_project_scoped_artifact_gap_is_not_a_local_hard_failure(self):
        failures = [
            {"id": "supabase.schema-snapshot-artifact", "failReason": "project_ref_missing"},
            {"id": "goal-next.status", "failReason": "stale_local_status"},
            {"id": "java.spring-boot3-java17", "failReason": "local_contract_missing"},
        ]

        self.assertEqual(
            [
                {"id": "goal-next.status", "failReason": "stale_local_status"},
                {"id": "java.spring-boot3-java17", "failReason": "local_contract_missing"},
            ],
            completion_audit.hard_failures(failures),
        )

    def test_optional_ui_and_supabase_gaps_are_not_local_hard_failures(self):
        failures = [
            {"id": "computer-use.gui-proof-boundary", "failReason": "computer_use_smoke_missing"},
            {"id": "browser-use.ui-proof-boundary", "failReason": "browser_ui_smoke_missing"},
            {"id": "supabase.schema-snapshot-artifact", "failReason": "project_ref_missing"},
            {"id": "java.langchain4j-1.0.1", "failReason": "version_mismatch"},
        ]

        self.assertEqual(
            [{"id": "java.langchain4j-1.0.1", "failReason": "version_mismatch"}],
            completion_audit.hard_failures(failures),
        )

    def test_source_health_optional_local_interaction_gap_is_not_a_local_hard_failure(self):
        failures = [
            {
                "id": "source.health-scorecard",
                "failReason": (
                    "Source health scorecard artifact is missing, does not preserve the structured "
                    "Supabase live-proof next action, or is unsafe: localInteractionBrowserReady, "
                    "localInteractionRefreshReady"
                ),
            },
            {"id": "java.langchain4j-1.0.1", "failReason": "version_mismatch"},
        ]

        self.assertEqual(
            [{"id": "java.langchain4j-1.0.1", "failReason": "version_mismatch"}],
            completion_audit.hard_failures(failures),
        )

    def test_source_health_validation_loop_gap_is_supporting_for_desktop_only_audit(self):
        failures = [
            {
                "id": "source.health-scorecard",
                "failReason": (
                    "Source health scorecard artifact is missing, does not preserve the structured "
                    "Supabase live-proof next action, or is unsafe: sourceHealthValidationLoop, "
                    "sourceHealthValidationLoopFresh, sourceHealthValidationLoopFreshnessStatus"
                ),
            },
            {"id": "java.langchain4j-1.0.1", "failReason": "version_mismatch"},
        ]

        self.assertEqual(
            [{"id": "java.langchain4j-1.0.1", "failReason": "version_mismatch"}],
            completion_audit.hard_failures(failures),
        )

    def test_optional_ui_actions_are_not_primary_by_default(self):
        requirements = [
            {
                "id": "computer-use-gui-proof",
                "status": "evidence_needed",
                "evidenceNeeded": ["Computer Use GUI smoke evidence needed"],
            },
            {
                "id": "browser-ui-proof",
                "status": "evidence_needed",
                "evidenceNeeded": ["Browser UI smoke evidence needed"],
            },
        ]

        primary = completion_audit.completion_audit_next_actions(requirements, [])
        optional = completion_audit.completion_audit_next_actions(
            requirements,
            [],
            include_optional_ui_actions=True,
        )

        self.assertNotIn("collect-computer-use-gui-proof", primary)
        self.assertNotIn("collect-browser-dom-proof", primary)
        self.assertIn("collect-computer-use-gui-proof", optional)
        self.assertIn("collect-browser-dom-proof", optional)
        self.assertIn("rerun_local_interaction_smoke_refresh", optional)

    def test_desktop_only_ready_status_is_audit_ready(self):
        summary = {
            "statusPresent": True,
            "statusGeneratedAt": True,
            "statusFresh": True,
            "statusFreshnessStatus": "current",
            "schemaVersion": "awx.goal_next_auto.status.v1",
            "statusDecision": "desktop_only_ready",
            "firstAction": "",
            "firstActionSource": "",
            "externalInputGateStatus": "local_or_unknown",
            "externalInputGateSource": "",
            "externalInputGateAction": "",
            "externalInputGateLocalPatchJustified": True,
            "externalInputGateMutationAllowed": False,
            "externalInputGateEvidenceNeeded": "",
            "externalInputGateSecretHits": 0,
            "externalInputGateWindowsAbsPathHits": 0,
            "rawSecretPatternHits": 0,
        }

        self.assertTrue(completion_audit.goal_next_status_ready(summary))
        summary["statusDecision"] = "ok"
        self.assertTrue(completion_audit.goal_next_status_ready(summary), summary)


if __name__ == "__main__":
    unittest.main()
