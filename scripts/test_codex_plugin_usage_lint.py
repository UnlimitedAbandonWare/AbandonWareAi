#!/usr/bin/env python3
"""Tests for scripts/codex_plugin_usage_lint.py — report-block contract."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
LINT = ROOT / "scripts" / "codex_plugin_usage_lint.py"

GOOD = """최종 보고서

외부 API: 없음

PLUGIN_USAGE:
- Superpowers: USED(seam->RED->GREEN cycle)
- Browser: NOT_USED
- Vercel: NOT_RUN(이번 작업은 Jev 무관)
- GitHub: UNAVAILABLE(오프라인 환경)
- GLM: USED(2/3, marker ok)

결론: DONE
"""


def run_lint(text: str | None = None, path: str | None = None):
    with tempfile.TemporaryDirectory() as td:
        target = Path(path) if path else Path(td) / "report.md"
        if text is not None:
            target.write_text(text, encoding="utf-8")
        proc = subprocess.run(
            [sys.executable, "-B", str(LINT), "--report", str(target),
             "--json"],
            capture_output=True, timeout=60)
        proc.stdout = (proc.stdout or b"").decode("utf-8", "replace")
        proc.stderr = (proc.stderr or b"").decode("utf-8", "replace")
        try:
            payload = json.loads((proc.stdout or "").strip().splitlines()[-1])
        except (ValueError, IndexError):
            payload = {"verdict": "?", "errors": ["unparsable-output"],
                       "warnings": [], "secretPatterns": [],
                       "stdout": proc.stdout, "stderr": proc.stderr}
        return proc.returncode, payload


class PluginUsageLintTests(unittest.TestCase):

    def test_ok_minimal(self):
        code, res = run_lint(GOOD)
        self.assertEqual(code, 0, res)
        self.assertEqual(res["verdict"], "OK")
        self.assertEqual(res["pluginLines"], 5)

    def test_ok_glm_session_unavailable(self):
        text = GOOD.replace("- GLM: USED(2/3, marker ok)",
                            "GLM=SESSION_UNAVAILABLE(native 키 없음)")
        code, res = run_lint(text)
        self.assertEqual(code, 0, res)

    def test_missing_api_line(self):
        text = GOOD.replace("외부 API: 없음\n", "")
        code, res = run_lint(text)
        self.assertEqual(code, 2)
        self.assertIn("missing-line:외부 API", res["errors"])

    def test_missing_usage_block(self):
        code, res = run_lint("외부 API: 없음\n\n끝.\n")
        self.assertEqual(code, 2)
        self.assertIn("missing-block:PLUGIN_USAGE", res["errors"])

    def test_empty_usage_block(self):
        code, res = run_lint("외부 API: 없음\n\nPLUGIN_USAGE:\n\n본문.\n")
        self.assertEqual(code, 2)
        self.assertIn("empty-block:PLUGIN_USAGE", res["errors"])

    def test_used_without_reason(self):
        text = GOOD.replace("- Superpowers: USED(seam->RED->GREEN cycle)",
                            "- Superpowers: USED")
        code, res = run_lint(text)
        self.assertEqual(code, 2)
        self.assertTrue(any("USED-no-reason" in e for e in res["errors"]))

    def test_not_run_without_reason(self):
        text = GOOD.replace("- Vercel: NOT_RUN(이번 작업은 Jev 무관)",
                            "- Vercel: NOT_RUN")
        code, res = run_lint(text)
        self.assertEqual(code, 2)
        self.assertTrue(any("NOT_RUN-no-reason" in e for e in res["errors"]))

    def test_unavailable_without_reason(self):
        text = GOOD.replace("- GitHub: UNAVAILABLE(오프라인 환경)",
                            "- GitHub: UNAVAILABLE")
        code, res = run_lint(text)
        self.assertEqual(code, 2)

    def test_unknown_status(self):
        text = GOOD.replace("- Browser: NOT_USED", "- Browser: MAYBE")
        code, res = run_lint(text)
        self.assertEqual(code, 2)
        self.assertTrue(any("bad-status" in e for e in res["errors"]))

    def test_glm_over_budget(self):
        text = GOOD.replace("- GLM: USED(2/3, marker ok)",
                            "- GLM: USED(4/3, marker ok)")
        code, res = run_lint(text)
        self.assertEqual(code, 2)
        self.assertTrue(any("glm-over-budget" in e for e in res["errors"]))

    def test_glm_used_without_marker_ok(self):
        text = GOOD.replace("- GLM: USED(2/3, marker ok)",
                            "GLM=USED(2/3)")
        code, res = run_lint(text)
        self.assertEqual(code, 2)
        self.assertTrue(any("marker-ok" in e for e in res["errors"]))

    def test_secret_openai_like(self):
        fake = "sk-" + ("T5" * 20)  # split so scanners never see a real shape
        code, res = run_lint(GOOD + f"key: {fake}\n")
        self.assertEqual(code, 3)
        self.assertIn("openai-key", res["secretPatterns"])
        self.assertNotIn(fake, proc_safe(res))

    def test_secret_github_pat(self):
        fake = "ghp" + "_" + ("aB3" * 12)
        code, res = run_lint(GOOD + f"sample: {fake}\n")
        self.assertEqual(code, 3)
        self.assertIn("github-pat", res["secretPatterns"])

    def test_secret_bearer(self):
        fake = "Bearer " + ("xY9" * 10)
        code, res = run_lint(GOOD + f"auth: {fake}\n")
        self.assertEqual(code, 3)
        self.assertIn("bearer-token", res["secretPatterns"])

    def test_weak_pass_claim_warns_but_ok(self):
        code, res = run_lint(GOOD + "검증 PASS: HTTP 200 확인됨\n")
        self.assertEqual(code, 0)
        self.assertEqual(res["verdict"], "WARN")
        self.assertTrue(res["warnings"])

    def test_missing_file_no_input(self):
        code, res = run_lint(path="no/such/report-zzz.md")
        self.assertEqual(code, 4)
        self.assertEqual(res["verdict"], "NO_INPUT")

    def test_empty_report_is_format_error(self):
        code, res = run_lint("   \n")
        self.assertEqual(code, 2)

    def test_not_used_needs_no_reason(self):
        text = GOOD.replace("- Browser: NOT_USED",
                            "- Browser: NOT_USED\n- Sites: NOT_USED\n"
                            "- Data: NOT_USED")
        code, res = run_lint(text)
        self.assertEqual(code, 0, res)


def proc_safe(res: dict) -> str:
    """Serialize result fields that may echo input (they must never do so)."""
    return json.dumps(res, ensure_ascii=False)


if __name__ == "__main__":
    unittest.main()
