import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
PROMPT_ID = "demo1_stuff3_sapa_command_router"
PACK = ROOT / "agent-prompts" / "agents" / PROMPT_ID
SYSTEM = PACK / "system_ko.md"
META = PACK / "meta.yaml"
MANIFEST = ROOT / "agent-prompts" / "prompts.manifest.yaml"
BUILD_SCRIPT = ROOT / "agent-prompts" / "build.py"
OUTPUT_PATH = f"out/{PROMPT_ID}.prompt"


class Stuff3SapaCommandRouterPromptTest(unittest.TestCase):
    def _manifest_agent(self):
        manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
        matches = [
            agent for agent in manifest.get("agents", []) if agent.get("id") == PROMPT_ID
        ]
        self.assertEqual(1, len(matches), f"expected one {PROMPT_ID} manifest entry")
        return matches[0]

    def _build_prompt_in_temp(self):
        agent = copy.deepcopy(self._manifest_agent())
        agent["system"] = str(ROOT / "agent-prompts" / agent["system"])
        agent["traits"] = [
            str(ROOT / "agent-prompts" / path) for path in agent.get("traits", [])
        ]

        with tempfile.TemporaryDirectory() as directory:
            temp_root = Path(directory)
            output = temp_root / "built.prompt"
            agent["output"]["path"] = str(output)
            manifest_path = temp_root / "manifest.yaml"
            manifest_path.write_text(
                yaml.safe_dump(
                    {"agents": [agent]}, allow_unicode=True, sort_keys=False
                ),
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    sys.executable,
                    str(BUILD_SCRIPT),
                    "--manifest",
                    str(manifest_path),
                    "--agent",
                    PROMPT_ID,
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            return output.read_text(encoding="utf-8")

    def test_pack_is_registered_as_one_system_only_agent(self):
        self.assertTrue(SYSTEM.is_file(), f"missing prompt source: {SYSTEM}")
        self.assertTrue(META.is_file(), f"missing prompt metadata: {META}")

        meta = yaml.safe_load(META.read_text(encoding="utf-8"))
        self.assertEqual(PROMPT_ID, meta["id"])
        self.assertEqual("1.0.0", meta["version"])
        self.assertEqual("ko", meta["language"])
        self.assertEqual("system_ko.md", meta["system"])
        self.assertTrue(meta["manifest_registered"])

        agent = self._manifest_agent()
        self.assertEqual(f"agents/{PROMPT_ID}/system_ko.md", agent["system"])
        self.assertEqual([], agent["traits"])
        self.assertEqual(["system"], agent["merge"]["order"])
        self.assertEqual(OUTPUT_PATH, agent["output"]["path"])

    def test_manifest_build_emits_the_bounded_command_router_contract(self):
        prompt = self._build_prompt_in_temp()

        required = (
            "routerVersion=demo1.stuff3.sapa-command-router.v1",
            "maxActiveLanes=2",
            "maxSecondaryLanes=1",
            "alwaysOnEnsemble=false",
            "memoryScope=codex_only",
            "memoryMutation=explicit_request_only",
            "attachmentMode=claim_map",
            "사파:",
            "메모리:",
            "기억:",
            "앙상블:",
            "반증:",
            "디버그:",
            "재현:",
            "지표:",
            "점수:",
            "메모리 저장:",
            "메모리 수정:",
            "메모리 삭제:",
        )
        for marker in required:
            self.assertIn(marker, prompt)

    def test_lane_contracts_fail_closed_without_always_on_fanout(self):
        prompt = self._build_prompt_in_temp()

        for marker in (
            "SUPPORT_CONTRACT",
            "SUPPORT_SCENARIO",
            "FALSIFY",
            "NEUTRAL",
            "APPLY | HOLD | REJECT",
            "다수결 금지",
            "diagnoseDoesNotAuthorizeMutation=true",
            "memory_source",
            "rollout_id",
            "verified_current",
            "stale_risk",
            "rawValue",
            "unit",
            "direction",
            "owner",
            "verifier",
            "freshness",
            "normalized",
            "confidence",
            "evidence_needed",
        ):
            self.assertIn(marker, prompt)

        self.assertNotIn("alwaysOnEnsemble=true", prompt)
        self.assertNotIn("memoryScope=runtime", prompt)

    def test_canonical_double_colon_interface_keeps_legacy_single_colon_aliases(self):
        prompt = self._build_prompt_in_temp()

        canonical_commands = (
            "사파:: <자연어 자동 분류>",
            "메모리:·기억:: <Codex 작업 기억 조회>",
            "앙상블:·반증:: <독립 SUPPORT/FALSIFY/NEUTRAL 판정>",
            "디버그:·재현:: <재현과 원인 진단>",
            "지표:·점수:: <정량 정규화>",
            "메모리 저장|수정|삭제:: <명시적 요청일 때만 기억 변경>",
        )
        for command in canonical_commands:
            self.assertIn(command, prompt)

        self.assertIn("기존 단일 콜론 입력은 호환 별칭", prompt)
        for legacy_alias in (
            "`사파:`",
            "`메모리:`",
            "`기억:`",
            "`앙상블:`",
            "`반증:`",
            "`디버그:`",
            "`재현:`",
            "`지표:`",
            "`점수:`",
        ):
            self.assertIn(legacy_alias, prompt)

    def test_stuff3_profile_is_analogy_only_and_defense_only(self):
        prompt = self._build_prompt_in_temp()

        for marker in (
            "좌표 경계",
            "문제 공간 축소",
            "문맥",
            "rerank",
            "민감도",
            "불확실성",
            "타이밍",
            "지터",
            "defenseOnly=true",
            "noCheatBypassSteps=true",
            "noPiiEvasion=true",
            "검증된 사실로 승격하지 않는다",
        ):
            self.assertIn(marker, prompt)

    def test_common_output_schema_order_and_system_only_merge_are_stable(self):
        prompt = self._build_prompt_in_temp()
        fields = (
            "normalized_intent",
            "primary_lane",
            "secondary_lane",
            "translation",
            "evidence",
            "decision",
            "next_single_action",
            "evidence_needed",
        )
        positions = [prompt.index(field) for field in fields]
        self.assertEqual(sorted(positions), positions)

        expected = SYSTEM.read_text(encoding="utf-8").replace("\r\n", "\n")
        actual = prompt.replace("\r\n", "\n")
        self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
