import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "awx_skill_router.py"


def run(*args, root=None):
    cmd = [sys.executable, "-B", str(SCRIPT), *args]
    if root:
        cmd += ["--root", str(root)]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120,
                          cwd=str(ROOT))
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


def _write_index(root: Path, text: str):
    idx = root / ".agents" / "skills-intent-index.yaml"
    idx.parent.mkdir(parents=True, exist_ok=True)
    idx.write_text(text, encoding="utf-8")


class SkillRouterLintTest(unittest.TestCase):
    def test_lint_live_index_clean(self):
        code, out = run("lint")
        self.assertEqual(code, 0, out)
        self.assertTrue(out["ok"])
        self.assertEqual(out["errors"], [])
        self.assertGreaterEqual(out["counts"]["intents"], 10)

    def test_lint_detects_duplicate_and_broken(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".agents" / "skills" / "real-skill").mkdir(parents=True)
            (root / ".agents" / "skills" / "real-skill" / "SKILL.md") \
                .write_text("---\nname: real-skill\n---\n", encoding="utf-8")
            _write_index(root, """schemaVersion: 1
intents:
  - intent: a
    match: ["x"]
    primary_skill: real-skill
  - intent: a
    match: ["y"]
    primary_skill: ghost-skill
    forbid_families: [no-such-family]
families: {}
default_forbid_families: [also-missing]
""")
            code, out = run("lint", root=root)
            self.assertEqual(code, 4, out)
            joined = " ".join(out["errors"])
            self.assertIn("duplicate-intent:a", joined)
            self.assertIn("broken-skill-ref:ghost-skill", joined)
            self.assertIn("intent[a]-unknown-family:no-such-family", joined)
            self.assertIn("default-forbid-unknown-family:also-missing",
                          joined)

    def test_lint_detects_unindexed_skill(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".agents" / "skills" / "real-skill").mkdir(parents=True)
            (root / ".agents" / "skills" / "real-skill" / "SKILL.md") \
                .write_text("x", encoding="utf-8")
            (root / ".agents" / "skills" / "new-skill").mkdir(parents=True)
            (root / ".agents" / "skills" / "new-skill" / "SKILL.md") \
                .write_text("x", encoding="utf-8")
            _write_index(root, """schemaVersion: 1
intents:
  - intent: a
    match: ["x"]
    primary_skill: real-skill
""")
            code, out = run("lint", root=root)
            self.assertEqual(code, 0, out)
            self.assertTrue(any("unindexed-skills:1:new-skill" in w
                                for w in out["warnings"]))


class SkillRouterRegressionTest(unittest.TestCase):
    def test_builtin_regression_suite(self):
        code, out = run("regression")
        self.assertEqual(code, 0, out)
        self.assertEqual(out["passed"], out["total"])
        self.assertEqual(out["failures"], [])

    def test_resolve_korean_meta_display(self):
        code, out = run("resolve", "안경에 힌트 캡션을 보여줘")
        self.assertEqual(code, 0, out)
        self.assertEqual(out["primary"],
                         "demo1-meta-display-simple-caption")

    def test_resolve_single_primary_only(self):
        # router must never emit a 5+ skill pile: exactly one primary
        code, out = run("resolve", "Fold 안경 힌트가 안 나와요 버그 디버그")
        self.assertEqual(code, 0, out)
        self.assertIsNotNone(out["primary"])
        self.assertIsInstance(out["primary"], str)


if __name__ == "__main__":
    unittest.main()
