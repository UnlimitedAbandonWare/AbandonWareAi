#!/usr/bin/env python3
"""Tests for scripts/dot_card_check.py — temp fixtures only, real Downloads untouched."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "dot_card_check.py"


def run_tool(*args):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, cwd=ROOT, timeout=30,
    )


class DotCardCheckTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dcc-test-"))
        self.dl = self.tmp / "downloads"
        self.dl.mkdir()

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write_identity(self, **kw):
        p = self.tmp / "identity.json"
        p.write_text(json.dumps(kw), encoding="utf-8")
        return p

    def test_t1_identity_ok(self):
        p = self.write_identity(
            library_file_id="libfile_abcdef0123456789",
            file_id="file_00000000abcd",
            file_name="demo1_x_20261004.md",
            path="/demo1_x_20261004.md",
            local_path=str(self.dl / "demo1_x_20261004.md"),
        )
        r = run_tool("--identity", str(p))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("CARD_OK name=demo1_x_20261004.md libfile=libfile_abcd", r.stdout)

    def test_t2_identity_missing_keys(self):
        p = self.write_identity(file_id="file_x", path="/x.md")
        r = run_tool("--identity", str(p))
        self.assertEqual(r.returncode, 2)
        self.assertIn("CARD_MISSING missing=library_file_id", r.stdout)

    def test_t3_identity_bad_json(self):
        p = self.tmp / "bad.json"
        p.write_text("{not json", encoding="utf-8")
        r = run_tool("--identity", str(p))
        self.assertEqual(r.returncode, 2)
        self.assertIn("CARD_MISSING error=", r.stdout)

    def test_t4_audit_classifies_hook_and_user_download(self):
        (self.dl / "PASTE_DEVIN_x_20261004.txt").write_text("hook copy")
        (self.dl / "myreport_directive_2026.md").write_text("hook md")
        (self.dl / "demo1_x_20261004 (1).md").write_text("user clicked card")
        (self.dl / "random_notes.md").write_text("unrelated")
        # NTFS st_ctime = creation time and cannot be backdated via os.utime,
        # so window-exclusion of an "old" file is not fixture-testable here.
        r = run_tool("--downloads-audit", "--since-minutes", "60",
                     "--downloads", str(self.dl))
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("HOOK_SUSPECT PASTE_DEVIN_x_20261004.txt", r.stdout)
        self.assertIn("HOOK_SUSPECT myreport_directive_2026.md", r.stdout)
        self.assertIn("USER_DOWNLOAD demo1_x_20261004 (1).md", r.stdout)
        self.assertIn("OTHER random_notes.md", r.stdout)
        self.assertIn("hook_suspect=2", r.stdout)
        self.assertIn("user_download=1", r.stdout)

    def test_t5_audit_clean_window_exit0(self):
        (self.dl / "notes.md").write_text("x")
        r = run_tool("--downloads-audit", "--since-minutes", "60",
                     "--downloads", str(self.dl))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("hook_suspect=0", r.stdout)

    def test_t6_audit_missing_dir_exit3(self):
        r = run_tool("--downloads-audit", "--since-minutes", "60",
                     "--downloads", str(self.tmp / "no-such"))
        self.assertEqual(r.returncode, 3)
        self.assertIn("AUDIT_ERROR", r.stdout + r.stderr)

    # --- add-only: W4 SUB_REPORT_V1 6칸 검사 (F2·F4, 2026-10-06) ---

    # 관찰된 F2 형태: 전체 상태·입력 원본·lease·NOT_RUN은 있으나
    # 항목별 표(②)와 단계×위치 쓰기 장부(④)가 빠진 보고.
    F2_STYLE = (
        "결과: 새 지시서 2개 + SKIP 목록.\n"
        "상태: DONE — 산출물 PASTE_CODEX_new_20261005.md sha12=AA11BB22CC33\n"
        "입력 원본: PASTE_CODEX_EXISTING_GRAPH_HYBRID_REUSE_20261005.md "
        "sha12=BBDB91028C54 INPUT_FALLBACK=none\n"
        "own lease 잔존: 0\n"
        "NOT_RUN: 없음\n"
        "지시서 작성 단계 Downloads 외 쓰기 0. 지원 단계까지 합치면 workspace에 "
        "helper 사본·journal·검사 로그가 있어 0이 아님.\n")

    COMPLETE = (
        "상태: DONE\n"
        "산출물: PASTE_DEVIN_result_20261006.md sha12=A1B2C3D4E5F6\n"
        "항목별:\n"
        "  W1: PASS — 입력 폴백 규칙\n"
        "  W2: PASS — cover 하위 명령\n"
        "입력 원본: DX_A.txt sha12=9988776655AA INPUT_FALLBACK=used(Downloads)\n"
        "쓰기 장부:\n"
        "  support × repo=2 (data/agent-handoff/…), Downloads=0, scratch=0\n"
        "  brief × repo=0, Downloads=2 (PASTE_*.md), scratch=1 (helper copy)\n"
        "own lease 잔존: 0\n"
        "NOT_RUN: 없음\n")

    def write_report(self, text: str) -> Path:
        p = self.tmp / "report.txt"
        p.write_text(text, encoding="utf-8")
        return p

    def test_sub_report_f2_style_fails_fields_2_and_4(self):
        p = self.write_report(self.F2_STYLE)
        r = run_tool("--sub-report", str(p))
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("SUB_REPORT_V1 FAIL", r.stdout)
        self.assertIn("field2_item_status", r.stdout)
        self.assertIn("field4_write_ledger", r.stdout)
        self.assertNotIn("field1_overall_status,", r.stdout)
        self.assertNotIn("field3_input_source,", r.stdout)
        self.assertNotIn("field5_own_leases,", r.stdout)
        self.assertNotIn("field6_not_run", r.stdout.split("missing=")[-1])

    def test_sub_report_complete_passes(self):
        p = self.write_report(self.COMPLETE)
        r = run_tool("--sub-report", str(p))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("SUB_REPORT_V1 PASS fields=6/6", r.stdout)

    def test_sub_report_json_lists_missing_fields(self):
        p = self.write_report(self.F2_STYLE)
        r = run_tool("--sub-report", str(p), "--json")
        self.assertEqual(r.returncode, 2)
        payload = json.loads(r.stdout)
        self.assertEqual("FAIL", payload["verdict"])
        self.assertEqual(["field2_item_status", "field4_write_ledger"],
                         payload["missing"])

    def test_sub_report_missing_file_exit3(self):
        r = run_tool("--sub-report", str(self.tmp / "absent.txt"))
        self.assertEqual(r.returncode, 3)
        self.assertIn("SUB_REPORT_ERROR", r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
