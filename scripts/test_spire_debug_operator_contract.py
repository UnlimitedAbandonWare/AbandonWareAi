from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / ".agents/skills/demo1-debugging-with-two-tools/SKILL.md"
OPENAI_META = ROOT / ".agents/skills/demo1-debugging-with-two-tools/agents/openai.yaml"
PROMPT = ROOT / "agent-prompts/agents/demo1_spire_debug_operator_9h/system_ko.md"
PROMPT_META = ROOT / "agent-prompts/agents/demo1_spire_debug_operator_9h/meta.yaml"
DESIGN = ROOT / "docs/superpowers/specs/2026-07-15-spire-debug-operator-design.md"
MANIFEST = ROOT / "agent-prompts/prompts.manifest.yaml"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


class SpireDebugOperatorContractTest(unittest.TestCase):
    def test_skill_frontmatter_and_openai_metadata_are_discoverable(self):
        skill = read(SKILL)
        metadata = read(OPENAI_META)
        self.assertIn("name: demo1-debugging-with-two-tools", skill)
        self.assertIn("Use when", skill)
        self.assertIn("$demo1-debugging-with-two-tools", metadata)

    def test_skill_bounds_each_cycle_and_success_memory(self):
        skill = read(SKILL)
        for token in (
            "one falsifiable hypothesis",
            "one patch intent",
            "one observation slot",
            "one verification slot",
        ):
            self.assertIn(token, skill)
        self.assertRegex(skill, r"(?i)(success_patterns_max:\s*5|keep at most five entries)")

    def test_skill_gates_p0_p1_p2_and_preserves_desktop_final_proof(self):
        skill = read(SKILL)
        self.assertIn("P0 → P1 → P2", skill)
        self.assertIn("P2 starts only after every known P0 and P1", skill)
        self.assertIn("Desktop final proof", skill)

    def test_prompt_has_bounded_nine_hour_budget_and_verification_reserve(self):
        prompt = read(PROMPT)
        self.assertRegex(
            prompt,
            r"(?i)(maximum nine-hour|nine hours is a maximum|maximum effort budget of 9 hours)",
        )
        self.assertIn("Reserve the final 45 minutes", prompt)
        self.assertIn("Stop early", prompt)

    def test_prompt_requires_two_tool_cycle_and_demand_driven_external_evidence(self):
        prompt = read(PROMPT)
        self.assertIn("$demo1-debugging-with-two-tools", prompt)
        self.assertIn("one observation tool and one verification tool", prompt)
        self.assertIn("Browser, Computer, and Supabase evidence is supporting", prompt)

    def test_manifest_registers_the_spire_agent_exactly_once(self):
        manifest = read(MANIFEST)
        ids = re.findall(r"(?m)^\s*- id:\s*([^\s#]+)", manifest)
        self.assertEqual(1, ids.count("demo1_spire_debug_operator_9h"))
        self.assertIn(
            "system: agents/demo1_spire_debug_operator_9h/system_ko.md",
            manifest,
        )

    def test_prompt_metadata_and_design_artifact_are_present(self):
        metadata = read(PROMPT_META)
        design = read(DESIGN)
        self.assertIn("id: demo1_spire_debug_operator_9h", metadata)
        self.assertIn("P0/P1/P2", design)
        self.assertIn("Desktop final", design)


if __name__ == "__main__":
    unittest.main()
