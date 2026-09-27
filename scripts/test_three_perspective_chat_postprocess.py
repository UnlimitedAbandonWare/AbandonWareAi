import copy
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "agent-prompts" / "agents" / "demo1_three_perspective_chat_postprocess"
MANIFEST = ROOT / "agent-prompts" / "prompts.manifest.yaml"
BUILD_SCRIPT = ROOT / "agent-prompts" / "build.py"
SKILL = ROOT / ".agents" / "skills" / "demo1-agentic-chat-postprocess"
NEUTRAL_FILES = (
    PACK / "neutral_judge_ko.md",
    PACK / "codex_neutral_prompt_ko.md",
)
ACTIVE_QUERY_PATHS = (
    "agents/demo1_three_perspective_chat_postprocess/positive_scenarios_ko.md",
    "agents/demo1_three_perspective_chat_postprocess/negative_counter_ko.md",
    "agents/demo1_three_perspective_chat_postprocess/neutral_judge_ko.md",
)
SHARED_PATHS = (
    "agents/demo1_three_perspective_chat_postprocess/scenario_matrix_ko.md",
)
CANONICAL_PACKETS = (
    "packetType=POSITIVE_QUERY",
    "packetType=NEGATIVE_QUERY",
    "packetType=NEUTRAL_QUERY",
)
LEGACY_INPUT_ALIAS = "legacyInputAliases=SUPPORT_CONTRACT,SUPPORT_SCENARIO,FALSIFY"
LEGACY_PACKET_TYPES = ("SUPPORT_CONTRACT", "SUPPORT_SCENARIO", "FALSIFY")
POSITIVE_SCHEMA = (
    "candidateGoal",
    "scenarioWorlds[2..4]",
    "scenarioId",
    "premise",
    "causalMechanism",
    "expectedObservation",
    "evidenceNeeded",
    "falsifier",
    "baseRateStatus",
    "noneOrUnknown",
    "validatedAssumptions",
    "reusableAssets",
    "expectedUserValue",
    "minimalVerification",
    "evidenceIds",
    "unknowns",
)
NEGATIVE_SCHEMA = (
    "challengedGoal",
    "scenarioAttacks",
    "scenarioId",
    "counterexample",
    "alternativeCause",
    "boundaryOrAuthorityRisk",
    "costAndBlastRadius",
    "smallestDisconfirmingProbe",
    "evidenceIds",
    "falsifiers",
    "missingEvidence",
    "safetyRisks",
)
NEUTRAL_SCHEMA = (
    "forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]",
    "reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]",
    "forwardVerdict",
    "reverseVerdict",
    "forwardDecisiveEvidenceIds",
    "reverseDecisiveEvidenceIds",
    "orderStable",
    "verdict",
    "selectedOrRewrittenGoal",
    "goalScore",
    "decisiveEvidence",
    "rejectedClaims",
    "nextSingleProof",
    "confidence",
    "artifactVerdict",
    "runtimeLineageVerdict",
)
REPORT_FIELDS = (
    "runId",
    "seed",
    "scenarioId",
    "observation",
    "evidenceSnapshotHash",
    "positiveQuery",
    "negativeQuery",
    "neutralQuery",
    "forwardOrder",
    "reverseOrder",
    "forwardVerdict",
    "reverseVerdict",
    "forwardDecisiveEvidenceIds",
    "reverseDecisiveEvidenceIds",
    "orderStable",
    "designVerdict",
    "artifactVerdict",
    "statisticalUpliftVerdict",
    "runtimeLineageVerdict",
    "evidenceIds",
    "failureClass",
    "toolDecision",
    "nextAction",
)


def fenced_text_block_after(text, anchor):
    anchor_start = text.index(anchor)
    fence_start = text.index("```text", anchor_start) + len("```text")
    fence_end = text.index("```", fence_start)
    return tuple(
        line.strip()
        for line in text[fence_start:fence_end].splitlines()
        if line.strip()
    )


def markdown_table(text):
    rows = []
    for line in text.splitlines():
        if not line.startswith("|"):
            continue
        cells = tuple(cell.strip() for cell in line.strip("|").split("|"))
        if cells and all(set(cell) <= {"-", ":"} for cell in cells):
            continue
        rows.append(cells)
    header, *body = rows
    return header, tuple(dict(zip(header, row)) for row in body)


class ThreePerspectiveChatPostprocessPromptTest(unittest.TestCase):
    def _manifest_agent(self):
        manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
        return next(
            agent
            for agent in manifest["agents"]
            if agent["id"] == "demo1_three_perspective_chat_postprocess"
        )

    def test_pack_is_registered_with_the_complete_role_set(self):
        meta = yaml.safe_load((PACK / "meta.yaml").read_text(encoding="utf-8"))

        self.assertEqual("3.0.0", meta["version"])
        self.assertTrue(meta["manifest_registered"])
        self.assertEqual("codex_neutral_prompt_ko.md", meta["standalone_prompt"])
        self.assertEqual("system_ko.md", meta["system"])
        self.assertEqual(
            [
                "positive_scenarios_ko.md",
                "negative_counter_ko.md",
                "neutral_judge_ko.md",
            ],
            meta["roles"],
        )

        agent = self._manifest_agent()
        self.assertEqual(
            "agents/demo1_three_perspective_chat_postprocess/system_ko.md",
            agent["system"],
        )
        self.assertEqual(
            list(ACTIVE_QUERY_PATHS + SHARED_PATHS),
            agent["traits"],
        )
        self.assertEqual(["system", "trait"], agent["merge"]["order"])
        self.assertEqual(
            "out/demo1_three_perspective_chat_postprocess.prompt",
            agent["output"]["path"],
        )

    def test_active_pack_has_exactly_three_canonical_query_roles(self):
        meta = yaml.safe_load((PACK / "meta.yaml").read_text(encoding="utf-8"))
        agent = self._manifest_agent()

        for relative_path, expected_packet in zip(ACTIVE_QUERY_PATHS, CANONICAL_PACKETS):
            with self.subTest(relative_path=relative_path):
                text = (ROOT / "agent-prompts" / relative_path).read_text(encoding="utf-8")
                declarations = re.findall(r"(?m)^packetType=([A-Z_]+)$", text)
                self.assertEqual([expected_packet.removeprefix("packetType=")], declarations)
                self.assertEqual(1, text.count(expected_packet))
                self.assertEqual(
                    [expected_packet],
                    [packet for packet in CANONICAL_PACKETS if packet in text],
                )
                self.assertIn("evidenceSnapshotHash", text)

        self.assertNotIn("positive_contract_ko.md", meta["roles"])
        self.assertNotIn(
            "agents/demo1_three_perspective_chat_postprocess/positive_contract_ko.md",
            agent["traits"],
        )

    def test_manifest_build_emits_the_agentic_execution_contract(self):
        agent = copy.deepcopy(self._manifest_agent())
        agent["system"] = str(ROOT / "agent-prompts" / agent["system"])
        agent["traits"] = [str(ROOT / "agent-prompts" / path) for path in agent["traits"]]

        with tempfile.TemporaryDirectory() as directory:
            temp_root = Path(directory)
            output = temp_root / "built.prompt"
            agent["output"]["path"] = str(output)
            manifest_path = temp_root / "manifest.yaml"
            manifest_path.write_text(
                yaml.safe_dump({"agents": [agent]}, allow_unicode=True, sort_keys=False),
                encoding="utf-8",
            )

            completed = subprocess.run(
                [
                    sys.executable,
                    str(BUILD_SCRIPT),
                    "--manifest",
                    str(manifest_path),
                    "--agent",
                    "demo1_three_perspective_chat_postprocess",
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            prompt = output.read_text(encoding="utf-8")

        for field in REPORT_FIELDS:
            self.assertIn(field, prompt)
        for required in CANONICAL_PACKETS + (
            "canonicalQueryCount=3",
            "legacyInputAliases=SUPPORT_CONTRACT,SUPPORT_SCENARIO,FALSIFY",
            "canonicalOutputOnly=true",
            "neutralMayAcquireEvidence=false",
            "majorityVote=false",
            "APPLY | HOLD | REJECT",
            "attachmentMode=advisory_expandable",
            "runBudgetMinutes=540",
            "PromptBuilder.build(PromptContext)",
            "AgentToolInvoker",
            "artifact-by-reference",
            "auxiliaryCalls=0..3",
            "runtimeEnsembleJudgeCalls=0",
            "finalWrapperCalls=1",
            "finalVerifierCalls=0..1",
            "failoverScope=operational_only",
            "heuristicAutoApproval=false",
            "distributedConsensus=false",
            "leaderReplacement=false",
            "alwaysOnReviewFanout=false",
        ):
            self.assertIn(required, prompt)

        self.assertNotIn("project/src/main/java", prompt)
        self.assertNotIn("3+1+1", prompt)
        self.assertNotIn("packetType=SUPPORT_CONTRACT", prompt)
        self.assertEqual(1, prompt.count(LEGACY_INPUT_ALIAS))
        canonical_only_prompt = prompt.replace(LEGACY_INPUT_ALIAS, "", 1)
        for legacy_packet in LEGACY_PACKET_TYPES:
            self.assertNotRegex(canonical_only_prompt, rf"\b{legacy_packet}\b")

    def test_system_report_is_canonical_and_legacy_alias_is_input_only(self):
        system = (PACK / "system_ko.md").read_text(encoding="utf-8")

        self.assertEqual(
            [packet.removeprefix("packetType=") for packet in CANONICAL_PACKETS],
            re.findall(r"(?m)^packetType=([A-Z_]+)$", system),
        )
        self.assertEqual(1, system.count(LEGACY_INPUT_ALIAS))
        canonical_only_system = system.replace(LEGACY_INPUT_ALIAS, "", 1)
        for legacy_packet in LEGACY_PACKET_TYPES:
            self.assertNotRegex(canonical_only_system, rf"\b{legacy_packet}\b")

        report_lines = fenced_text_block_after(system, "## 구조화된 최종 보고서")
        report_fields = tuple(line.split(":", 1)[0] for line in report_lines)
        self.assertEqual(REPORT_FIELDS, report_fields)
        report = dict(line.split(":", 1) for line in report_lines)
        self.assertIn("POSITIVE_QUERY", report["positiveQuery"])
        self.assertIn("NEGATIVE_QUERY", report["negativeQuery"])
        self.assertIn("NEUTRAL_QUERY", report["neutralQuery"])
        self.assertEqual(
            " [POSITIVE_QUERY,NEGATIVE_QUERY]",
            report["forwardOrder"],
        )
        self.assertEqual(
            " [NEGATIVE_QUERY,POSITIVE_QUERY]",
            report["reverseOrder"],
        )

    def test_repo_local_skill_is_discoverable(self):
        skill_text = (SKILL / "SKILL.md").read_text(encoding="utf-8")
        metadata = yaml.safe_load((SKILL / "agents" / "openai.yaml").read_text(encoding="utf-8"))

        self.assertIn("name: demo1-agentic-chat-postprocess", skill_text)
        self.assertIn("Use when", skill_text)
        self.assertIn("references/execution-contract.md", skill_text)
        self.assertIn("references/review-packets.md", skill_text)
        self.assertIn("references/stop-conditions.md", skill_text)
        self.assertIn(
            "$demo1-agentic-chat-postprocess",
            metadata["interface"]["default_prompt"],
        )

    def test_repo_local_skill_references_keep_source_specific_contracts(self):
        skill_text = (SKILL / "SKILL.md").read_text(encoding="utf-8")
        execution_text = (SKILL / "references" / "execution-contract.md").read_text(encoding="utf-8")
        review_packets_text = (SKILL / "references" / "review-packets.md").read_text(encoding="utf-8")
        stop_conditions_text = (SKILL / "references" / "stop-conditions.md").read_text(encoding="utf-8")

        for required in (
            "POSITIVE_QUERY",
            "NEGATIVE_QUERY",
            "NEUTRAL_QUERY",
            "canonicalQueryCount=3",
            "workflowMutationRequested=true -> canonicalQueryCount=3,offlineGraderRequired=true",
        ):
            with self.subTest(required=required):
                self.assertIn(required, skill_text)

        for required in (
            "score_three_way_long_tail_query.py",
            "decisionDependsOnSupabase",
            "MACSRC_SMB_DIRECT",
        ):
            with self.subTest(required=required):
                self.assertIn(required, execution_text)
        self.assertIn("runtimeLineageVerdict", stop_conditions_text)

        expected_roles = ["POSITIVE_QUERY", "NEGATIVE_QUERY", "NEUTRAL_QUERY"]
        self.assertEqual(
            expected_roles,
            re.findall(r"(?m)^## ([A-Z][A-Z0-9_]*)$", review_packets_text),
        )
        self.assertEqual(
            expected_roles,
            re.findall(r"(?m)^packetType=([A-Z_]+)(?:;|$)", review_packets_text),
        )
        for forbidden in (
            "SUPPORT_CONTRACT",
            "SUPPORT_SCENARIO",
            "FALSIFY",
            "OPTIMISTIC_CONFIRM",
            "fourth role",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, review_packets_text)

        neutral_section = review_packets_text.split("## NEUTRAL_QUERY", 1)[1].split("## Shared final report", 1)[0]
        for required in (
            "neutralMayAcquireEvidence=false",
            "forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]",
            "reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]",
            "Compare both orders without evidence acquisition",
        ):
            with self.subTest(neutral_required=required):
                self.assertIn(required, neutral_section)

        for source_name, source_text in (
            ("SKILL.md", skill_text),
            ("execution-contract.md", execution_text),
        ):
            with self.subTest(source_name=source_name):
                self.assertIn("$demo1-macsrc-smb-direct-patch", source_text)
                self.assertIn("Do not create an ad hoc or second mutation protocol.", source_text)

    def test_v2_design_gate_is_separate_from_artifact_statistics_and_runtime(self):
        system = (PACK / "system_ko.md").read_text(encoding="utf-8")
        skill_text = (SKILL / "SKILL.md").read_text(encoding="utf-8")
        execution_text = (SKILL / "references" / "execution-contract.md").read_text(encoding="utf-8")
        review_packets_text = (SKILL / "references" / "review-packets.md").read_text(encoding="utf-8")
        stop_conditions_text = (SKILL / "references" / "stop-conditions.md").read_text(encoding="utf-8")

        agent = copy.deepcopy(self._manifest_agent())
        agent["system"] = str(ROOT / "agent-prompts" / agent["system"])
        agent["traits"] = [str(ROOT / "agent-prompts" / path) for path in agent["traits"]]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "built.prompt"
            agent["output"]["path"] = str(output)
            manifest_path = Path(directory) / "manifest.yaml"
            manifest_path.write_text(
                yaml.safe_dump({"agents": [agent]}, allow_unicode=True, sort_keys=False),
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    sys.executable,
                    str(BUILD_SCRIPT),
                    "--manifest",
                    str(manifest_path),
                    "--agent",
                    "demo1_three_perspective_chat_postprocess",
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            prompt = output.read_text(encoding="utf-8")

        combined = "\n".join(
            (prompt, skill_text, execution_text, review_packets_text, stop_conditions_text)
        )
        for token in (
            "designVerdict=APPLY|HOLD|REJECT",
            "artifactVerdict=APPLY|HOLD|REJECT",
            "statisticalUpliftVerdict=INCONCLUSIVE|NO_UPLIFT|UPLIFT_CANDIDATE|REJECT",
            "runtimeLineageVerdict=APPLY|HOLD|REJECT",
            "designMetaGrader=scripts/score_three_way_long_tail_design.py",
            "designContract=agent-prompts/agents/demo1_three_perspective_chat_postprocess/v2_design_contract.json",
            "fixtureAuthority=sealed",
            "candidateMaySubmitBaseline=false",
            "candidateMaySubmitCaseCount=false",
            "candidateMaySubmitEvidenceRegistry=false",
            "neutralVerdictDerived=true",
        ):
            with self.subTest(token=token):
                self.assertIn(token, combined)

        self.assertIn(
            "python scripts\\score_three_way_long_tail_design.py --input $designContract --output $designResult",
            execution_text,
        )
        self.assertIn("v1 artifact grading", skill_text)
        self.assertIn("v2 design meta-grading", skill_text)
        self.assertIn("designVerdict=APPLY does not auto-promote", combined)
        self.assertNotIn("designVerdict=APPLY -> runtimeLineageVerdict=APPLY", combined)
        for forbidden in (
            "candidateMaySubmitBaseline=true",
            "candidateMaySubmitCaseCount=true",
            "candidateMaySubmitEvidenceRegistry=true",
        ):
            self.assertNotIn(forbidden, combined)

    def test_v2_design_report_fields_and_pressure_rows_are_stable(self):
        system = (PACK / "system_ko.md").read_text(encoding="utf-8")
        report_lines = fenced_text_block_after(system, "## 구조화된 최종 보고서")
        report_fields = tuple(line.split(":", 1)[0] for line in report_lines)
        self.assertEqual(REPORT_FIELDS, report_fields)
        self.assertEqual(
            ("designVerdict", "artifactVerdict", "statisticalUpliftVerdict", "runtimeLineageVerdict"),
            report_fields[report_fields.index("designVerdict"):report_fields.index("runtimeLineageVerdict") + 1],
        )
        self.assertEqual(
            [packet.removeprefix("packetType=") for packet in CANONICAL_PACKETS],
            re.findall(r"(?m)^packetType=([A-Z_]+)$", system),
        )

        header, rows = markdown_table((PACK / "scenario_matrix_ko.md").read_text(encoding="utf-8"))
        self.assertEqual(("ID", "가상 상황", "핵심 기대", "대표 반례"), header)
        by_id = {row["ID"]: row for row in rows}
        self.assertEqual(len(rows), len(by_id))
        expected_pressure = {
            "Q8": ("candidate-owned baseline/caseCount under deadline", "HOLD", "candidate-owned-baseline-or-count"),
            "Q9": ("same-family fluent paraphrase under senior authority", "HOLD", "claim-semantic-family-duplicate"),
            "Q10": ("safety-critical regression despite mean uplift", "REJECT", "safety-regression-observed"),
            "Q11": ("design APPLY presented as provider/runtime proof", "runtimeLineageVerdict=HOLD", "runtime-lineage-missing"),
        }
        for scenario_id, terms in expected_pressure.items():
            with self.subTest(scenario_id=scenario_id):
                row = by_id[scenario_id]
                self.assertIn(terms[0], row["가상 상황"])
                self.assertIn(terms[1], row["핵심 기대"])
                self.assertIn(terms[2], row["대표 반례"])

    def test_q9_same_family_paraphrase_has_one_canonical_stop_mapping(self):
        skill_text = (SKILL / "SKILL.md").read_text(encoding="utf-8")
        stop_conditions_text = (SKILL / "references" / "stop-conditions.md").read_text(encoding="utf-8")

        self.assertIn(
            "| Q9 same-family fluent paraphrase | HOLD with `failureClass=claim-semantic-family-duplicate` |",
            skill_text,
        )
        self.assertIn(
            "| Q9 same-family fluent paraphrase reuses a sealed semantic-family ID | claim-semantic-family-duplicate | Set HOLD and emit this canonical reason code exactly. |",
            stop_conditions_text,
        )

    def test_design_output_command_is_guarded_by_the_caller_run_root(self):
        execution_text = (SKILL / "references" / "execution-contract.md").read_text(encoding="utf-8")

        self.assertIn(
            "python scripts\\score_three_way_long_tail_design.py --input $designContract --output $designResult --run-root $designRunRoot",
            execution_text,
        )
        self.assertNotRegex(
            execution_text,
            r"(?m)^python scripts\\score_three_way_long_tail_design\.py --input \$designContract --output \$designResult$",
        )
        for required in (
            "outputMayAliasInput=false",
            "outsideRunRootRejected=true",
            "stdoutModeRequiresRunRoot=false",
        ):
            with self.subTest(required=required):
                self.assertIn(required, execution_text)

    def test_neutral_prompts_are_evidence_frozen_order_stable_judges(self):
        for path in NEUTRAL_FILES:
            with self.subTest(path=path.name):
                self.assertTrue(path.is_file(), f"missing neutral prompt: {path}")
                text = path.read_text(encoding="utf-8")
                self.assertEqual(
                    NEUTRAL_SCHEMA,
                    fenced_text_block_after(text, "payload는 다음 순서"),
                )

                for required in (
                    "packetType=NEUTRAL_QUERY",
                    "neutralMayAcquireEvidence=false",
                    "forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]",
                    "reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]",
                    "forwardVerdict",
                    "reverseVerdict",
                    "forwardDecisiveEvidenceIds",
                    "reverseDecisiveEvidenceIds",
                    "orderStable",
                    "verdict",
                    "selectedOrRewrittenGoal",
                    "goalScore",
                    "decisiveEvidence",
                    "rejectedClaims",
                    "nextSingleProof",
                    "confidence",
                    "artifactVerdict",
                    "runtimeLineageVerdict",
                    "orderStable=false",
                    "verdict=HOLD",
                ):
                    self.assertIn(required, text)

                self.assertNotIn("ACCEPT | HOLD | REJECT", text)
                self.assertIn("evidenceId", text)
                self.assertIn("다수결", text)

        standalone = (PACK / "codex_neutral_prompt_ko.md").read_text(encoding="utf-8")
        self.assertNotIn("runtime_disclaimer", standalone)
        self.assertIn(
            "runtime adjudicator 실행 증거가 없으면 `runtimeLineageVerdict=HOLD`로 "
            "판정하고 그 사유를 `rejectedClaims`에 기록한다.",
            standalone,
        )

    def test_positive_and_negative_queries_expose_falsifiable_world_contracts(self):
        positive = (PACK / "positive_scenarios_ko.md").read_text(encoding="utf-8")
        self.assertEqual(
            POSITIVE_SCHEMA,
            fenced_text_block_after(positive, "다음 payload 순서"),
        )
        self.assertIn("hypotheticalObservationsAreEvidence=false", positive)
        self.assertIn("baseRateStatus=noneOrUnknown", positive)
        self.assertIn("실제 evidence가 아니다", positive)

        negative = (PACK / "negative_counter_ko.md").read_text(encoding="utf-8")
        self.assertEqual(
            NEGATIVE_SCHEMA,
            fenced_text_block_after(negative, "다음 payload 순서"),
        )
        self.assertIn("scenarioId` 정확한 집합", negative)
        self.assertIn("failureClass=negative-coverage-gap", negative)
        self.assertIn("새 증거를 취득하지 않는다", negative)

        legacy = (PACK / "positive_contract_ko.md").read_text(encoding="utf-8")
        for required in (
            "status=legacy_reference_only",
            "legacyPacketType=SUPPORT_CONTRACT",
            "canonicalReplacement=POSITIVE_QUERY.validatedAssumptions",
            "activeQueryRole=false",
        ):
            self.assertIn(required, legacy)
        for packet in CANONICAL_PACKETS:
            self.assertNotIn(packet, legacy)

    def test_scenario_matrix_covers_three_way_decision_boundaries(self):
        matrix = (PACK / "scenario_matrix_ko.md").read_text(encoding="utf-8")
        header, rows = markdown_table(matrix)
        self.assertEqual(("ID", "가상 상황", "핵심 기대", "대표 반례"), header)
        self.assertTrue(all(len(row) == len(header) for row in rows))
        self.assertEqual(len(rows), len({row["ID"] for row in rows}))
        by_id = {row["ID"]: row for row in rows}
        self.assertEqual({f"Q{index}" for index in range(1, 8)}, set(by_id) & {f"Q{index}" for index in range(1, 8)})

        expectations = {
            "Q1": (("expectedObservation", "falsifier"), ("evidence",)),
            "Q2": (("집합이 정확히 같음",), ("negative-coverage-gap",)),
            "Q3": (("evidenceSnapshotHash",), ("evidence ID를 추가",)),
            "Q4": (("forward/reverse", "결정적 evidence ID"), ("APPLY",)),
            "Q5": (("인증 부재와 독립",), ("전역 HOLD",)),
            "Q6": (("scope", "read-only"), ("scope 증명 없이",)),
            "Q7": (("root", "lease/CAS guard"), ("모든 쓰기를 허용", "모든 접근을 거부")),
        }
        for scenario_id, (expected_terms, counter_terms) in expectations.items():
            with self.subTest(scenario_id=scenario_id):
                row = by_id[scenario_id]
                self.assertTrue(row["가상 상황"])
                for term in expected_terms:
                    self.assertIn(term, row["핵심 기대"])
                for term in counter_terms:
                    self.assertIn(term, row["대표 반례"])

    def test_s8_keeps_adverse_constraints_and_privacy_explicit(self):
        matrix = (PACK / "scenario_matrix_ko.md").read_text(encoding="utf-8")

        self.assertIn("| S8 |", matrix)
        for required in (
            "부채 있음",
            "현금흐름 빠듯",
            "지출 한도 제한",
            "위험 허용 낮음",
            "고가 구매",
            "관찰 사실과 추론",
            "정확한 금액",
        ):
            self.assertIn(required, matrix)

        self.assertNotRegex(matrix, r"(?:₩|\$)\s*\d")


if __name__ == "__main__":
    unittest.main()
