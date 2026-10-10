#!/usr/bin/env python3
"""Contract tests for instruction_change_probe."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import instruction_change_probe as p  # noqa: E402


class ProbeDiff(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "AGENTS.md").write_text("# rules v1\n")
        skill_dir = self.root / ".agents" / "skills" / "demo-one"
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text("---\nname: demo-one\n---\n")
        (self.root / ".agents" / "skills-intent-index.yaml").write_text("i: 1\n")
        (self.root / ".agents" / "skills" / "INDEX.md").write_text("# idx\n")

    def tearDown(self):
        self.tmp.cleanup()

    def _run(self, *extra):
        argv = ["--root", str(self.root), "--state",
                str(self.root / "state.json")] + list(extra)
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = p.main(argv)
        return code, buf.getvalue()

    def test_init_then_unchanged(self):
        code, out = self._run("--init")
        self.assertEqual(0, code)
        self.assertIn("INIT n=", out)
        code, out = self._run()
        self.assertEqual(0, code)
        self.assertEqual("UNCHANGED\n", out)

    def test_changed_only(self):
        self._run("--init")
        (self.root / "AGENTS.md").write_text("# rules v2\n")
        code, out = self._run("--check-only")
        self.assertEqual("CHANGED AGENTS.md\n", out)
        # check-only 는 상태를 갱신하지 않는다 → 다음 호출도 같은 결과
        code, out = self._run()
        self.assertIn("CHANGED AGENTS.md", out)
        # 기본 실행 후 상태 갱신 → 이후 UNCHANGED
        code, out = self._run()
        self.assertEqual("UNCHANGED\n", out)

    def test_added_removed(self):
        self._run("--init")
        d = self.root / ".agents" / "skills" / "demo-two"
        d.mkdir(parents=True)
        (d / "SKILL.md").write_text("---\nname: demo-two\n---\n")
        (self.root / "AGENTS.md").unlink()
        code, out = self._run()
        self.assertIn("ADDED .agents/skills/demo-two/SKILL.md", out)
        self.assertIn("REMOVED AGENTS.md", out)

    def test_no_state_init(self):
        state = self.root / "fresh.json"
        code, out = p.main(["--root", str(self.root), "--state", str(state)]), None
        self.assertEqual(0, code)


if __name__ == "__main__":
    unittest.main()
