import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "agent-prompts" / "prompts.manifest.yaml"
BUILD = ROOT / "agent-prompts" / "build.py"
EXPECTED = {
    "demo1_macsrc_defect_intake": {
        "system": "agents/demo1_macsrc_defect_intake/system_ko.md",
        "output": "out/demo1_macsrc_defect_intake.prompt",
        "skill": "$demo1-macsrc-defect-intake",
    },
    "demo1_macsrc_guarded_patch_session": {
        "system": "agents/demo1_macsrc_guarded_patch_session/system_ko.md",
        "output": "out/demo1_macsrc_guarded_patch_session.prompt",
        "skill": "$demo1-macsrc-guarded-patch-session",
    },
    "demo1_macsrc_patch_postprocess": {
        "system": "agents/demo1_macsrc_patch_postprocess/system_ko.md",
        "output": "out/demo1_macsrc_patch_postprocess.prompt",
        "skill": "$demo1-macsrc-patch-postprocessor",
    },
}
SKILL_FILES = {
    "intake": ROOT / ".agents" / "skills" / "demo1-macsrc-defect-intake" / "SKILL.md",
    "session": ROOT / ".agents" / "skills" / "demo1-macsrc-guarded-patch-session" / "SKILL.md",
    "postprocess": ROOT / ".agents" / "skills" / "demo1-macsrc-patch-postprocessor" / "SKILL.md",
}


class MacSrcDefectSessionSkillFamilyTest(unittest.TestCase):
    def setUp(self):
        self.manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
        self.agents = {row["id"]: row for row in self.manifest["agents"]}

    def test_three_prompt_entries_are_registered_exactly(self):
        for agent_id, expected in EXPECTED.items():
            with self.subTest(agent_id=agent_id):
                row = self.agents[agent_id]
                self.assertEqual(expected["system"], row["system"])
                self.assertEqual([], row["traits"])
                self.assertEqual(["system"], row["merge"]["order"])
                self.assertEqual("project_overrides_global", row["merge"]["conflict"])
                self.assertEqual(expected["output"], row["output"]["path"])
                self.assertEqual("utf-8", row["output"]["encoding"])

    def test_each_registered_prompt_builds_from_utf8_source(self):
        for agent_id, expected in EXPECTED.items():
            with self.subTest(agent_id=agent_id), tempfile.TemporaryDirectory() as directory:
                row = copy.deepcopy(self.agents[agent_id])
                row["system"] = str(ROOT / "agent-prompts" / row["system"])
                output = Path(directory) / f"{agent_id}.prompt"
                row["output"]["path"] = str(output)
                temp_manifest = Path(directory) / "manifest.yaml"
                temp_manifest.write_text(
                    yaml.safe_dump({"agents": [row]}, allow_unicode=True, sort_keys=False),
                    encoding="utf-8",
                )
                completed = subprocess.run(
                    [sys.executable, str(BUILD), "--manifest", str(temp_manifest), "--agent", agent_id],
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    check=False,
                )
                self.assertEqual(0, completed.returncode, completed.stderr)
                built = output.read_text(encoding="utf-8")
                self.assertIn(expected["skill"], built)
                self.assertNotIn("�", built)
    def test_each_skill_declares_the_operational_contract(self):
        required = (
            "## Non-Trigger",
            "## Operational Contract",
            "owner",
            "mutation surface",
            "timeout",
            "bounded output",
            "fail-closed",
            "rollback",
            "removal",
            "non-duplication",
            "falsifying test",
        )
        for name, path in SKILL_FILES.items():
            with self.subTest(skill=name):
                text = path.read_text(encoding="utf-8")
                for token in required:
                    self.assertIn(token, text)

    def test_automatic_probe_and_tri_query_setup_are_discoverable(self):
        intake = SKILL_FILES["intake"].read_text(encoding="utf-8")
        postprocess = SKILL_FILES["postprocess"].read_text(encoding="utf-8")
        self.assertIn("prepare_autograder_probe.ps1", intake)
        self.assertIn("prepare_patch_tri_query.ps1", postprocess)


if __name__ == "__main__":
    unittest.main()
