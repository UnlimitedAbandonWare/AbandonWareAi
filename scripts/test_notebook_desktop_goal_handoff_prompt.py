from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
PROMPT_ROOT = ROOT / "agent-prompts"
PROMPT = PROMPT_ROOT / "agents/demo1_notebook_desktop_goal_handoff/system_ko.md"
META = PROMPT_ROOT / "agents/demo1_notebook_desktop_goal_handoff/meta.yaml"
MANIFEST = PROMPT_ROOT / "prompts.manifest.yaml"
AGENTS = ROOT / "AGENTS.md"
THREE_NODE = PROMPT_ROOT / "agents/demo1_three_node_smb_codex/system_ko.md"
PLAN = ROOT / "docs/superpowers/plans/2026-08-02-notebook-desktop-goal-handoff.md"
VALIDATOR = ROOT / "scripts/validate_goal_directive_packets.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("goal_directive_validator", VALIDATOR)
    if spec is None or spec.loader is None:
        raise RuntimeError("validator-import-unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VALIDATOR_MODULE = load_validator()


def canonical_json(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def schema_keys(text: str, label: str) -> set[str]:
    match = re.search(rf"(?m)^{re.escape(label)} keys exactly: ([A-Za-z0-9_,]+)$", text)
    if match is None:
        raise AssertionError(f"missing-schema-line:{label}")
    return set(match.group(1).split(","))


def valid_run_artifact() -> dict[str, object]:
    snapshot = {
        "summary": "Bounded synthetic evidence for prompt contract regression.",
        "evidenceRows": [
            {
                "evidenceId": "ev1",
                "owner": "notebook",
                "observedAt": "2026-08-02T00:00:00Z",
                "observation": {"reason": "evidence-needed"},
                "verificationCommand": "verify-bounded-fixture",
            }
        ],
    }
    snapshot_hash = hashlib.sha256(canonical_json(snapshot).encode("utf-8")).hexdigest()
    score_values = {
        "evidenceStrength": 0.9,
        "causalStrength": 0.8,
        "verificationFeasibility": 0.8,
        "userValue": 0.9,
        "reversibility": 0.8,
        "costEfficiency": 0.9,
        "timeFit": 0.8,
        "blastRadius": 0.1,
        "ambiguity": 0.1,
        "authorityOrSafetyExpansion": 0.0,
    }
    score_inputs = {
        name: {"value": value, "evidenceIds": ["ev1"]}
        for name, value in score_values.items()
    }
    positive = {
        "packetType": "POSITIVE_QUERY",
        "evidenceSnapshotHash": snapshot_hash,
        "candidateGoal": "Validate a bounded handoff artifact.",
        "scenarioWorlds": [
            {
                "scenarioId": "s1",
                "premise": "The schema matches.",
                "causalMechanism": "Exact keys close schema drift.",
                "expectedObservation": "The validator accepts the packet.",
                "evidenceNeeded": ["ev1"],
                "falsifier": "A required key differs.",
            },
            {
                "scenarioId": "s2",
                "premise": "The evidence references close.",
                "causalMechanism": "Every packet references the frozen snapshot.",
                "expectedObservation": "No unsupported evidence ID is reported.",
                "evidenceNeeded": ["ev1"],
                "falsifier": "A packet invents an evidence ID.",
            },
        ],
        "validatedAssumptions": [],
        "reusableAssets": ["goal directive validator"],
        "expectedUserValue": "A deterministic handoff boundary.",
        "minimalVerification": "Run the validator command.",
        "evidenceIds": ["ev1"],
        "unknowns": [],
    }
    negative = {
        "packetType": "NEGATIVE_QUERY",
        "evidenceSnapshotHash": snapshot_hash,
        "challengedGoal": "Validate a bounded handoff artifact.",
        "scenarioAttacks": [
            {
                "scenarioId": "s1",
                "counterExample": "An extra key makes the packet invalid.",
                "alternativeCause": "The controller copied an obsolete schema.",
                "boundaryOrAuthorityRisk": "Invalid packets cannot authorize handoff.",
                "costAndBlastRadius": "Low and bounded to prompt tooling.",
                "smallestDisconfirmingProbe": "Run the exact validator.",
                "evidenceIds": ["ev1"],
            },
            {
                "scenarioId": "s2",
                "counterExample": "An invented evidence ID breaks closure.",
                "alternativeCause": "Neutral acquired evidence.",
                "boundaryOrAuthorityRisk": "Unsupported evidence can expand authority.",
                "costAndBlastRadius": "Low and bounded to one fixture.",
                "smallestDisconfirmingProbe": "Inspect validator failure classes.",
                "evidenceIds": ["ev1"],
            },
        ],
        "falsifiers": ["Validator rejects the artifact."],
        "counterExamples": ["An obsolete packet schema."],
        "authorityRisks": [],
        "safetyRisks": [],
        "missingEvidence": [],
        "smallestDisconfirmingProbe": "Run the exact validator.",
        "evidenceIds": ["ev1"],
    }
    neutral = {
        "packetType": "NEUTRAL_QUERY",
        "evidenceSnapshotHash": snapshot_hash,
        "forwardOrder": ["POSITIVE_QUERY", "NEGATIVE_QUERY"],
        "reverseOrder": ["NEGATIVE_QUERY", "POSITIVE_QUERY"],
        "forwardVerdict": "APPLY",
        "reverseVerdict": "APPLY",
        "forwardDecisiveEvidenceIds": ["ev1"],
        "reverseDecisiveEvidenceIds": ["ev1"],
        "orderStable": True,
        "verdict": "APPLY",
        "selectedOrRewrittenGoal": "Validate a bounded handoff artifact.",
        "scoreInputs": score_inputs,
        "goalScore": 81.5,
        "decisiveEvidence": ["ev1"],
        "rejectedClaims": [],
        "nextSingleProof": "Run the exact validator.",
        "confidence": "H",
    }
    return {
        "schemaVersion": "1.0",
        "userRequest": "Validate one bounded handoff artifact.",
        "evidenceSnapshot": snapshot,
        "evidenceSnapshotHash": snapshot_hash,
        "requiresLiteralSubagents": False,
        "processMode": "single-agent-logical-roles",
        "actualAgentCount": 1,
        "packets": {
            "POSITIVE_QUERY": positive,
            "NEGATIVE_QUERY": negative,
            "NEUTRAL_QUERY": neutral,
        },
    }


class HandoffPromptTests(unittest.TestCase):
    def run_validator(self, document: dict[str, object]) -> tuple[int, dict[str, object]]:
        with tempfile.TemporaryDirectory(prefix="awx-handoff-validator-") as temporary_directory:
            fixture = Path(temporary_directory) / "run-artifact.json"
            fixture.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, "-B", "-X", "utf8", str(VALIDATOR), "validate", "--input", str(fixture)],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual("", completed.stderr)
        return completed.returncode, json.loads(completed.stdout)

    def manifest_row(self, agent_id: str) -> dict[str, object]:
        manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
        rows = [row for row in manifest["agents"] if row["id"] == agent_id]
        self.assertEqual(1, len(rows))
        return rows[0]

    def test_metadata_and_manifest_registration_are_exact(self) -> None:
        metadata = yaml.safe_load(META.read_text(encoding="utf-8"))
        self.assertEqual(
            {
                "id": "demo1_notebook_desktop_goal_handoff",
                "version": "1.0.0",
                "language": "ko",
                "scope": "notebook-readonly-goal-to-desktop-prompt-tooling-handoff",
                "system": "system_ko.md",
                "manifest_registered": True,
                "owner": "desktop",
            },
            metadata,
        )
        row = self.manifest_row("demo1_notebook_desktop_goal_handoff")
        self.assertEqual("agents/demo1_notebook_desktop_goal_handoff/system_ko.md", row["system"])
        self.assertEqual([], row["traits"])
        self.assertEqual(["system"], row["merge"]["order"])
        self.assertEqual("out/demo1_notebook_desktop_goal_handoff.prompt", row["output"]["path"])

    def test_generated_outputs_equal_manifest_composition(self) -> None:
        for agent_id in ("demo1_notebook_desktop_goal_handoff", "demo1_three_node_smb_codex"):
            with self.subTest(agent_id=agent_id):
                row = self.manifest_row(agent_id)
                parts: list[str] = []
                for item in row.get("merge", {}).get("order", ["trait", "system"]):
                    if item == "system":
                        parts.append((PROMPT_ROOT / row["system"]).read_text(encoding="utf-8").replace("\r\n", "\n"))
                    elif item == "trait":
                        parts.extend(
                            (PROMPT_ROOT / path).read_text(encoding="utf-8").replace("\r\n", "\n")
                            for path in row.get("traits", [])
                        )
                expected = "\n\n".join(parts)
                observed = (PROMPT_ROOT / row["output"]["path"]).read_text(
                    encoding=row["output"].get("encoding", "utf-8")
                ).replace("\r\n", "\n")
                self.assertEqual(expected, observed)

    def test_controller_has_literal_triad_sections_in_execution_order(self) -> None:
        text = PROMPT.read_text(encoding="utf-8")
        headings = (
            "## Literal triad",
            "### POSITIVE_QUERY",
            "### NEGATIVE_QUERY",
            "### NEUTRAL_QUERY",
            "## GoalContract",
            "## SourceDirective",
        )
        self.assertEqual([], [heading for heading in headings if heading not in text])
        positions = [text.index(heading) for heading in headings]
        self.assertEqual(sorted(positions), positions)
        self.assertEqual(1, text.count("### POSITIVE_QUERY"))
        self.assertEqual(1, text.count("### NEGATIVE_QUERY"))
        self.assertEqual(1, text.count("### NEUTRAL_QUERY"))

    def test_controller_schema_is_tied_to_validator_contract(self) -> None:
        text = PROMPT.read_text(encoding="utf-8")
        expected = {
            "RunArtifact": VALIDATOR_MODULE.TOP_LEVEL_KEYS,
            "EvidenceSnapshot": VALIDATOR_MODULE.EVIDENCE_SNAPSHOT_KEYS,
            "EvidenceRow": VALIDATOR_MODULE.EVIDENCE_ROW_KEYS,
            "PositivePacket": VALIDATOR_MODULE.POSITIVE_KEYS,
            "ScenarioWorld": VALIDATOR_MODULE.SCENARIO_KEYS,
            "NegativePacket": VALIDATOR_MODULE.NEGATIVE_KEYS,
            "ScenarioAttack": VALIDATOR_MODULE.ATTACK_KEYS,
            "NeutralVerdict": VALIDATOR_MODULE.NEUTRAL_KEYS,
            "ScoreInputs": set(VALIDATOR_MODULE.SCORE_KEYS),
        }
        for label, keys in expected.items():
            with self.subTest(label=label):
                self.assertEqual(set(keys), schema_keys(text, label))
        self.assertIn("## Canonical validator schemas", text)
        self.assertIn("## Output classifications", text)
        schema_section = text.split("## Canonical validator schemas", 1)[1].split("## Output classifications", 1)[0]
        for removed in ("noneOrUnknown", "claims", "baseRateStatus", "artifactVerdict", "runtimeLineageVerdict"):
            self.assertNotRegex(schema_section, rf"\b{removed}\b")
        self.assertNotRegex(schema_section, r"\bcounterexample\b")
        self.assertIn("counterExample", schema_section)
        self.assertIn("runtimeLineageVerdict=HOLD", text.split("## Output classifications", 1)[1])

    def test_controller_requires_validator_order_and_score_gates(self) -> None:
        text = PROMPT.read_text(encoding="utf-8")
        self.assertIn(
            "python scripts\\validate_goal_directive_packets.py validate --input <run-artifact.json>",
            text,
        )
        self.assertIn("verdict disagreement OR decisive-evidence-set disagreement", text)
        self.assertIn("orderStable=false", text)
        self.assertIn("final verdict=HOLD", text)
        self.assertIn("goalScore below 50 forbids APPLY", text)

    def test_validator_accepts_one_valid_end_to_end_fixture(self) -> None:
        exit_code, result = self.run_validator(valid_run_artifact())
        self.assertEqual(0, exit_code, result)
        self.assertTrue(result["valid"], result)
        self.assertEqual([], result["failureClasses"])

    def test_validator_rejects_order_instability_without_final_hold(self) -> None:
        document = valid_run_artifact()
        neutral = document["packets"]["NEUTRAL_QUERY"]
        neutral["reverseVerdict"] = "HOLD"
        neutral["orderStable"] = False
        exit_code, result = self.run_validator(document)
        self.assertEqual(2, exit_code, result)
        self.assertIn("order-unstable", result["failureClasses"])

    def test_validator_rejects_apply_below_50(self) -> None:
        document = valid_run_artifact()
        neutral = document["packets"]["NEUTRAL_QUERY"]
        for score_input in neutral["scoreInputs"].values():
            score_input["value"] = 0.0
        neutral["goalScore"] = 0.0
        exit_code, result = self.run_validator(document)
        self.assertEqual(2, exit_code, result)
        self.assertIn("goal-score-below-threshold", result["failureClasses"])

    def test_incomplete_literal_triad_is_terminal(self) -> None:
        text = PROMPT.read_text(encoding="utf-8")
        self.assertIn("TriadExecutionStatus", text)
        self.assertIn("literal-subagents-unavailable", text)
        self.assertIn("triad-role-failed", text)
        self.assertIn("emit only TriadExecutionStatus", text)

    def test_three_node_public_identity_and_stale_network_contract(self) -> None:
        text = THREE_NODE.read_text(encoding="utf-8")
        self.assertIn(
            "publicIdentityFields=[canonicalWorkspace,backingShareIdentityVerified,backingShareIdentityReason]",
            text,
        )
        for stale in (
            "nas_path_used",
            "nas_connected",
            "nas-path-confusion",
            "nas-write-permission",
            "notebook-offline-bundle-missing",
            "/Volumes/NAS-WinSrc",
            "/Volumes/WinSrc",
            "user@DESKTOP-HOST",
        ):
            self.assertNotIn(stale, text)
        self.assertNotRegex(text, re.compile(r"\\\\[A-Za-z0-9._-]+\\"))
        self.assertNotRegex(text, re.compile(r"(?im)^\s*(?:net use|New-PSDrive)\b"))

    def test_three_node_reconciles_desktop_and_notebook_write_authority(self) -> None:
        text = THREE_NODE.read_text(encoding="utf-8")
        self.assertIn("Desktop exclusively owns its local final-verification checkout.", text)
        self.assertIn(
            "YDRIVE_SMB_GUARDED_DIRECT is the sole Notebook canonical Y-drive write exception after every gate passes.",
            text,
        )
        self.assertIn(
            "PatchDrop-only producer handoff applies to LOCAL_PRODUCER and Mac mini, not YDRIVE_SMB_GUARDED_DIRECT.",
            text,
        )

    def test_three_node_keeps_write_gates_and_desktop_proof(self) -> None:
        text = THREE_NODE.read_text(encoding="utf-8")
        for token in (
            "canonicalWorkspace=Y:\\",
            "YDRIVE_SMB_GUARDED_DIRECT",
            "sourceWriteRoot=Y:\\",
            "sourceWriteRoot=null",
            "authorizedMutation=false",
            "shared lease",
            "preimage",
            "index-lock",
            "focused verification",
            "postimage",
            "manifest-pinned cumulative v3",
            "desktopFinalProof=evidence_needed",
        ):
            self.assertIn(token, text)
        self.assertNotIn("MACSRC_SMB_DIRECT", text)

    def test_three_node_desktop_apply_uses_only_guarded_consumer_path(self) -> None:
        text = THREE_NODE.read_text(encoding="utf-8")
        section = text.split("### 6.2 Desktop", 1)[1].split("## 7.", 1)[0]
        self.assertIn("janitor_apply_one.ps1", section)
        self.assertIn("manifest-pinned cumulative v3", section)
        self.assertIn("Desktop Gradle verification", section)
        self.assertNotIn("git apply", section)
        self.assertNotIn("Move-Item", section)

    def test_plan_uses_current_validator_and_regression_contract(self) -> None:
        text = PLAN.read_text(encoding="utf-8")
        for removed in ("noneOrUnknown", "baseRateStatus", "artifactVerdict"):
            self.assertNotRegex(text, rf"\b{removed}\b")
        self.assertNotRegex(text, r"\bcounterexample\b")
        self.assertIn(
            "python scripts\\validate_goal_directive_packets.py validate --input <run-artifact.json>",
            text,
        )
        self.assertIn("one valid end-to-end validator fixture", text)
        self.assertIn("one order-unstable rejection", text)
        self.assertIn("one below-50 rejection", text)
        self.assertIn("python -B -X utf8 scripts\\test_notebook_desktop_goal_handoff_prompt.py -v", text)

    def test_agents_has_short_handoff_pointer_and_unchanged_baseline(self) -> None:
        text = AGENTS.read_text(encoding="utf-8")
        self.assertEqual(1, text.count("demo1_notebook_desktop_goal_handoff"))
        self.assertEqual(1, text.count("scripts\\verify_ydrive_backing_identity.ps1"))
        self.assertEqual(
            1,
            text.count("30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9"),
        )


if __name__ == "__main__":
    unittest.main()
