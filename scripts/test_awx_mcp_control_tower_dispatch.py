import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOLBOX_PATH = ROOT / "scripts" / "awx_mcp_toolbox.py"
SPEC = importlib.util.spec_from_file_location("awx_mcp_toolbox", TOOLBOX_PATH)
toolbox = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(toolbox)


class DesktopDispatchPacketTest(unittest.TestCase):
    def test_written_producer_commands_pin_resolved_desktop_canonical_root(self):
        with tempfile.TemporaryDirectory(prefix="awx-dispatch-canonical-root-") as tmp:
            tmp_path = Path(tmp)
            patchdrop = tmp_path / "PatchDrop"
            dispatch_dir = patchdrop / "dispatch"
            mac_root = tmp_path / "macmini-worktree"
            notebook_root = tmp_path / "notebook-worktree"
            mac_root.mkdir(parents=True)
            notebook_root.mkdir(parents=True)

            result = toolbox.desktop_dispatch_packet(
                {
                    "nodeRole": "desktop",
                    "canonical_root": str(ROOT),
                    "patchdrop_root": str(patchdrop),
                    "dispatch_dir": str(dispatch_dir),
                    "write_dispatch": True,
                    "topic": "canonical root pin",
                    "producer_roots": {
                        "macmini": str(mac_root),
                        "notebook": str(notebook_root),
                    },
                    "role_pathspec": {
                        "macmini": ["scripts/awx_mcp_toolbox.py"],
                        "notebook": ["scripts/awx_mcp_completion_audit.py"],
                    },
                }
            )

            self.assertTrue(result["ok"], result)
            mac_text = (dispatch_dir / "canonical-root-pin-macmini.commands.txt").read_text(encoding="utf-8")
            notebook_text = (dispatch_dir / "canonical-root-pin-notebook.commands.txt").read_text(encoding="utf-8")
            self.assertNotIn("--canonical-root '.'", mac_text)
            self.assertNotIn("--canonical-root '.'", notebook_text)
            self.assertIn(f"--canonical-root '{str(ROOT).replace(chr(92), '/')}'", mac_text)
            self.assertIn(f"--canonical-root '{ROOT}'", notebook_text)

    def test_control_loop_surfaces_written_producer_command_files_as_next_actions(self):
        with tempfile.TemporaryDirectory(prefix="awx-control-loop-next-actions-") as tmp:
            tmp_path = Path(tmp)
            patchdrop = tmp_path / "PatchDrop"
            dispatch_dir = patchdrop / "dispatch"
            evidence_dir = tmp_path / "evidence"
            mac_root = tmp_path / "macmini-worktree"
            notebook_root = tmp_path / "notebook-worktree"
            for path in (patchdrop, dispatch_dir, evidence_dir, mac_root, notebook_root):
                path.mkdir(parents=True, exist_ok=True)

            result = toolbox.desktop_control_loop(
                {
                    "nodeRole": "desktop",
                    "root": str(ROOT),
                    "canonical_root": str(ROOT),
                    "patchdrop_root": str(patchdrop),
                    "dispatch_dir": str(dispatch_dir),
                    "evidence_dir": str(evidence_dir),
                    "write_dispatch": True,
                    "write_producer_kit": True,
                    "topic": "control loop producer next actions",
                    "producer_roots": {
                        "macmini": str(mac_root),
                        "notebook": str(notebook_root),
                    },
                    "role_pathspec": {
                        "macmini": ["scripts/awx_mcp_toolbox.py"],
                        "notebook": ["scripts/awx_mcp_completion_audit.py"],
                    },
                }
            )

            producer_actions = [
                action for action in result["nextActions"]
                if action.get("action") == "run-producer-command-file"
            ]
            self.assertEqual(2, len(producer_actions), result["nextActions"])
            self.assertEqual({"macmini", "notebook"}, {action.get("nodeRole") for action in producer_actions})


if __name__ == "__main__":
    unittest.main()
