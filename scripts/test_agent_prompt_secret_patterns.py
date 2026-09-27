import re
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "agent-prompts" / "prompts.manifest.yaml"

SECRET_SCAN_AGENT_IDS = {
    "demo1_ablation_harmony_patch_directive",
    "demo1_ai_debug_metrics_ledger",
    "demo1_context_contamination_scout_directive",
    "demo1_dual_codex_smb_safe_patch",
    "demo1_desktop_patchdrop_janitor",
    "demo1_desktop_referee_ablation_postprocess",
    "demo1_dynamic_rag_autonomous_patch_directive",
    "demo1_claude_peers_web_probe_agent_upgrade_5h",
    "demo1_three_node_smb_codex",
    "demo1_db_schema_source_patch_9h",
    "demo1_graphrag_kg_macmini_patchdrop",
    "demo1_graphrag_only_source_patch_9h",
    "demo1_orch_debug_scan",
    "demo1_orch_kpi_dashboard",
    "demo1_quant_harmony_9h_safe_patch",
    "demo1_quant_metric_normalization_antigravity",
}

SUPABASE_REGEX_FRAGMENT = re.compile(r"sb_\((?:\?:)?secret\|publishable\)_")
SUPABASE_PAT_REGEX_FRAGMENT = re.compile(r"sbp_\[A-Za-z0-9_-\]\{10,\}")

ADDITIONAL_SECRET_SCAN_DOCS = {
    ".agents/skills/demo1-autonomous-patch-conductor/SKILL.md",
    ".agents/skills/demo1-subsystem-patch-directive/SKILL.md",
    ".agents/skills/macmini-safe-patch-assistant/SKILL.md",
    ".agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md",
    "agent-prompts/agents/demo1_design_selfask_corrector/patch_directive_ko.md",
    "agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md",
}

ACTIVE_SECRET_ASSERTION_TESTS = {
    "scripts/test_awx_mcp_toolbox.py",
}


def load_manifest():
    with MANIFEST.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def render_agent_prompt(manifest_root, agent):
    parts = []
    for part_kind in agent.get("merge", {}).get("order", ["trait", "system"]):
        if part_kind == "trait":
            configured_paths = agent.get("traits", [])
        elif part_kind == "system":
            configured_paths = [agent["system"]]
        else:
            continue
        for configured_path in configured_paths:
            path = Path(configured_path)
            if not path.is_absolute():
                path = manifest_root / path
            parts.append(path.read_text(encoding="utf-8"))
    return "\n\n".join(parts)


class AgentPromptSecretPatternTest(unittest.TestCase):
    def test_secret_scan_prompts_include_supabase_key_patterns(self):
        manifest_root = MANIFEST.parent
        agents = {agent["id"]: agent for agent in load_manifest()["agents"]}

        for agent_id in sorted(SECRET_SCAN_AGENT_IDS):
            with self.subTest(agent_id=agent_id):
                agent = agents[agent_id]
                source_path = manifest_root / agent["system"]

                source = source_path.read_text(encoding="utf-8")
                rendered = render_agent_prompt(manifest_root, agent)

                self.assertRegex(source, SUPABASE_REGEX_FRAGMENT)
                self.assertRegex(source, SUPABASE_PAT_REGEX_FRAGMENT)
                self.assertRegex(rendered, SUPABASE_REGEX_FRAGMENT)
                self.assertRegex(rendered, SUPABASE_PAT_REGEX_FRAGMENT)

    def test_standalone_secret_scan_docs_include_supabase_key_patterns(self):
        for relative_path in sorted(ADDITIONAL_SECRET_SCAN_DOCS):
            with self.subTest(path=relative_path):
                text = (ROOT / relative_path).read_text(encoding="utf-8")

                self.assertRegex(text, SUPABASE_REGEX_FRAGMENT)
                self.assertRegex(text, SUPABASE_PAT_REGEX_FRAGMENT)

    def test_active_secret_regex_assertions_include_supabase_key_patterns(self):
        weak_lines = []
        for relative_path in sorted(ACTIVE_SECRET_ASSERTION_TESTS):
            text = (ROOT / relative_path).read_text(encoding="utf-8")
            for line_number, line in enumerate(text.splitlines(), 1):
                if "assertNotRegex" not in line:
                    continue
                if "sk-[A-Za-z0-9" not in line and "pcsk_" not in line:
                    continue
                if not SUPABASE_REGEX_FRAGMENT.search(line):
                    weak_lines.append(f"{relative_path}:{line_number}")
                    continue
                if not SUPABASE_PAT_REGEX_FRAGMENT.search(line):
                    weak_lines.append(f"{relative_path}:{line_number}")

        self.assertEqual([], weak_lines)


if __name__ == "__main__":
    unittest.main()
