"""Fixture-tree tests for routing_policy_wording_check.py.

Synthetic temp dirs only; no repo tree, network, GPU, or secret access.
"""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, str(Path(__file__).resolve().parent))
import routing_policy_wording_check as rpwc

SCRIPT = Path(__file__).resolve().parent / "routing_policy_wording_check.py"


def write_config(root, phrases, extra=None):
    cfg = {"version": 1,
           "default_allow_if_line_contains": ["폐기", "retired", "금지"],
           "phrases": phrases}
    if extra:
        cfg.update(extra)
    cdir = Path(root) / "configs"
    cdir.mkdir(parents=True, exist_ok=True)
    (cdir / "retired-phrases.json").write_text(
        json.dumps(cfg, ensure_ascii=False), encoding="utf-8")


def write_doc(root, rel, text):
    p = Path(root) / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


PHRASE_KO = {"id": "ko-retired", "type": "literal", "pattern": "무료·로컬 우선",
             "replacement_hint": "use new order", "since": "2026-10-03",
             "scope_globs": ["**/*"], "allow_if_line_contains": []}
PHRASE_RE = {"id": "old-action", "type": "regex",
             "pattern": r"-Action\s+(acquire|release)\b",
             "replacement_hint": "use begin|end", "since": "2026-10-05",
             "scope_globs": ["**/*"],
             "allow_if_line_contains": ["없음"]}
PHRASE_SCOPED = {"id": "docs-only", "type": "literal", "pattern": "ZZZOLDTOKEN",
                 "replacement_hint": "", "since": "2026-10-05",
                 "scope_globs": ["docs/**"], "allow_if_line_contains": []}


def run_cli(root, *extra):
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = rpwc.main(["--root", str(root), *extra])
    return code, buf.getvalue()


class RoutingPolicyWordingCheckTest(unittest.TestCase):

    def test_clean_tree_exit_0(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md", "all good\n")
            code, out = run_cli(d)
            self.assertEqual(0, code)
            self.assertIn("findings=0", out)

    def test_retired_phrase_detected_exit_3(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md",
                      "첫 줄\n무료·로컬 우선으로 한다\n")
            code, out = run_cli(d)
            self.assertEqual(3, code)
            self.assertIn("docs/agents-rules/x.md:2: ko-retired", out)
            self.assertIn("use new order", out)

    def test_retirement_explanation_allowed(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md",
                      '"무료·로컬 우선"은 폐기된 규칙이다\n')
            code, out = run_cli(d)
            self.assertEqual(0, code, out)

    def test_bak_file_excluded_by_default(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md.bak-20261003",
                      "무료·로컬 우선\n")
            code, out = run_cli(d)
            self.assertEqual(0, code, out)
            self.assertIn("backups_excluded=1", out)

    def test_include_backups_lists_separately(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md.bak-20261003",
                      "무료·로컬 우선\n")
            code, out = run_cli(d, "--include-backups")
            self.assertEqual(0, code, out)
            self.assertIn("BACKUP docs/agents-rules/x.md.bak-20261003:1", out)

    def test_archive_dir_excluded(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/archive/x.md", "무료·로컬 우선\n")
            code, out = run_cli(d)
            self.assertEqual(0, code, out)

    def test_json_output_shape(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md", "무료·로컬 우선\n")
            code, out = run_cli(d, "--json")
            self.assertEqual(3, code)
            data = json.loads(out)
            self.assertEqual("awx.routing-wording-check.v1",
                             data["schemaVersion"])
            self.assertEqual(1, data["findingCount"])
            f = data["findings"][0]
            self.assertEqual("docs/agents-rules/x.md", f["file"])
            self.assertEqual(1, f["line"])
            self.assertEqual("ko-retired", f["id"])

    def test_missing_config_exit_4(self):
        with tempfile.TemporaryDirectory() as d:
            write_doc(d, "docs/agents-rules/x.md", "x\n")
            code, _ = run_cli(d)
            self.assertEqual(4, code)

    def test_empty_config_exit_4(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [])
            code, _ = run_cli(d)
            self.assertEqual(4, code)

    def test_invalid_json_exit_4(self):
        with tempfile.TemporaryDirectory() as d:
            cdir = Path(d) / "configs"
            cdir.mkdir(parents=True)
            (cdir / "retired-phrases.json").write_text("{oops", encoding="utf-8")
            code, _ = run_cli(d)
            self.assertEqual(4, code)

    def test_regex_entry_detects_old_action(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_RE])
            write_doc(d, "docs/agents-rules/x.md",
                      "run source_edit_session.ps1 -Action acquire now\n")
            code, out = run_cli(d)
            self.assertEqual(3, code)
            self.assertIn("old-action", out)

    def test_regex_allow_marker_skips(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_RE])
            write_doc(d, "docs/agents-rules/y.md",
                      "-Action acquire 는 없음\n")
            code, out = run_cli(d)
            self.assertEqual(0, code, out)

    def test_allow_marker_on_wrapped_next_line(self):
        # A hard-wrapped sentence puts "폐기" on the line after the phrase;
        # that is still an explanation of the retirement, not usage.
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md",
                      '- (R13) 순서: Codex → 유료 → 무료 → 로컬. "무료·로컬 우선"은\n'
                      '  폐기된 규칙이다.\n')
            code, out = run_cli(d)
            self.assertEqual(0, code, out)

    def test_scope_globs_restrict(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_SCOPED])
            write_doc(d, ".agents/skills/demo-x/SKILL.md", "ZZZOLDTOKEN\n")
            code1, _ = run_cli(d)
            self.assertEqual(0, code1)
            write_doc(d, "docs/agents-rules/x.md", "ZZZOLDTOKEN\n")
            code2, _ = run_cli(d)
            self.assertEqual(3, code2)

    def test_cp949_console_output_safe(self):
        with tempfile.TemporaryDirectory() as d:
            write_config(d, [PHRASE_KO])
            write_doc(d, "docs/agents-rules/x.md",
                      "무료·로컬 우선 — 한국어 힌트 포함\n")
            env = dict(os.environ, PYTHONIOENCODING="cp949")
            proc = subprocess.run(
                [sys.executable, "-B", str(SCRIPT), "--root", d],
                capture_output=True, text=True, env=env)
            self.assertIn(proc.returncode, (0, 3), proc.stderr)
            self.assertNotIn("UnicodeEncodeError", proc.stderr)
            self.assertNotIn("Traceback", proc.stderr)


if __name__ == "__main__":
    unittest.main()
