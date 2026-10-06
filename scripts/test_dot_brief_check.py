#!/usr/bin/env python3
"""test_dot_brief_check.py — dot_brief_check.py 유닛테스트 (읽기 전용).

격리: --root를 tmp fixture repo로 지정 — live tree·lease 무접촉.
실행: python -B scripts/test_dot_brief_check.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "dot_brief_check.py"

GOOD_BRIEF = """# PASTE_CODEX_TEST_20261005

### 0. 한 줄 목표
대상: ChatWorkflow 게이트 수정.

### 1. 사실
- main/java/com/example/lms/service/ChatWorkflow.java 실존.
- com.example.lms.service.FactVerifierService 사용 중.

### 2. 공통 규칙
- COMMON_RULES 적용. primary skill: @demo1-work-ledger

### 3. 작업 단계
- WP1: ChatWorkflow.java 최소 수정.

### 4. HOLD
- foreign live lease 충돌 시 중단.

### 5. 수용 조건
- gradle testClasses exit 0.
"""


class BriefCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dot-brief-"))
        self.repo = self.tmp / "repo"
        (self.repo / "main/java/com/example/lms/service").mkdir(parents=True)
        (self.repo / "src/test/java/com/example/lms/service").mkdir(parents=True)
        (self.repo / "scripts").mkdir(parents=True)
        (self.repo / "main/java/com/example/lms/service/ChatWorkflow.java").write_text(
            "class ChatWorkflow {}\n", encoding="utf-8")
        (self.repo / "main/java/com/example/lms/service/FactVerifierService.java").write_text(
            "class FactVerifierService {}\n", encoding="utf-8")
        (self.repo / "scripts/work_journal.py").write_text("# stub\n", encoding="utf-8")
        self.brief = self.tmp / "PASTE_CODEX_TEST_20261005.md"

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_check(self, text: str | None, *extra):
        if text is not None:
            self.brief.write_text(text, encoding="utf-8")
        cmd = [sys.executable, "-B", str(SCRIPT), "--brief", str(self.brief),
               "--root", str(self.repo), *extra]
        return subprocess.run(cmd, capture_output=True, encoding="utf-8",
                              errors="replace", timeout=60)

    def test_valid_brief_pass(self):
        r = self.run_check(GOOD_BRIEF, "--no-lease")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("VERDICT: PASS", r.stdout)
        self.assertIn("targets=3/3", r.stdout)

    def test_missing_fqcn_fails(self):
        bad = GOOD_BRIEF.replace(
            "com.example.lms.service.FactVerifierService",
            "com.example.lms.service.NonExistentWidget")
        r = self.run_check(bad, "--no-lease")
        self.assertEqual(r.returncode, 2)
        self.assertIn("VERDICT: FAIL", r.stdout)
        self.assertIn("미존재 대상 1건", r.stdout)

    def test_missing_hold_section_fails(self):
        bad = GOOD_BRIEF.replace("### 4. HOLD", "### 4. 참고")
        r = self.run_check(bad, "--no-lease")
        self.assertEqual(r.returncode, 2)
        self.assertIn("hold", r.stdout)

    def test_git_push_antipattern_fails(self):
        bad = GOOD_BRIEF + "\n### 6. 마무리\n- git push 로 배포한다.\n"
        r = self.run_check(bad, "--no-lease")
        self.assertEqual(r.returncode, 2)
        self.assertIn("git-push", r.stdout)

    def test_secret_antipattern_fails(self):
        bad = GOOD_BRIEF + "\n- .secrets 파일을 열어 값을 확인한다.\n"
        r = self.run_check(bad, "--no-lease")
        self.assertEqual(r.returncode, 2)

    def test_new_file_hint_not_missing(self):
        b = GOOD_BRIEF + ("\n- scripts/new_helper.py 신규 생성 후 "
                          "data/agent-handoff/x/report.json에 결과 기록.\n")
        r = self.run_check(b, "--no-lease")
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn("VERDICT: PASS", r.stdout)

    def test_soft_section_missing_warn(self):
        bad = GOOD_BRIEF.replace(
            "### 2. 공통 규칙\n- COMMON_RULES 적용. primary skill: @demo1-work-ledger\n",
            "")
        r = self.run_check(bad, "--no-lease")
        self.assertEqual(r.returncode, 1)
        self.assertIn("VERDICT: WARN", r.stdout)
        self.assertIn("common_rules", r.stdout)

    def test_no_lease_marks_skipped(self):
        r = self.run_check(GOOD_BRIEF, "--no-lease")
        self.assertIn("lease=skipped", r.stdout)

    def test_lease_scan_skipped_when_tool_absent(self):
        r = self.run_check(GOOD_BRIEF)  # fixture repo has no lease tool
        self.assertIn("lease=skipped", r.stdout)

    def test_missing_brief_exit3(self):
        cmd = [sys.executable, "-B", str(SCRIPT), "--brief",
               str(self.tmp / "absent.md"), "--root", str(self.repo)]
        r = subprocess.run(cmd, capture_output=True, encoding="utf-8",
                           errors="replace", timeout=60)
        self.assertEqual(r.returncode, 3)

    def test_json_schema(self):
        r = self.run_check(GOOD_BRIEF, "--no-lease", "--json")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        payload = json.loads(r.stdout)
        self.assertEqual(payload["schemaVersion"], "awx.dot-brief-check.v1")
        self.assertEqual(payload["verdict"], "PASS")
        self.assertEqual(payload["targets"]["total"], 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
