#!/usr/bin/env python3
"""test_dot_session_hygiene.py — dot_session_hygiene.py 유닛테스트 (읽기 전용).

격리: 모든 루트는 tmp env override (실제 Downloads/.codex 무접촉).
실행: python -B -m unittest scripts.test_dot_session_hygiene -v
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "dot_session_hygiene.py"
MARKED = '{"payload":{"role":"user","content":[{"text":"[DOT-BRIEF] PASTE_CODEX_X 지시서"}]}}'
UNMARKED = '{"payload":{"role":"user","content":[{"text":"그냥 바로 수정해"}]}}'


def write(path: Path, data: bytes, hours_old: float = 0.0) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    if hours_old > 0:
        t = time.time() - hours_old * 3600
        os.utime(path, (t, t))
    return path


class HygieneCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dot-hyg-"))
        self.sessions = self.tmp / "sessions"
        self.docs = self.tmp / "CodexDocs"
        self.downloads = self.tmp / "Downloads"
        self.prompts = self.tmp / "agent-prompts"
        for d in (self.sessions, self.docs, self.downloads, self.prompts):
            d.mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def env(self, large_mb="5"):
        e = dict(os.environ)
        e["DOT_HYGIENE_SESSIONS_ROOT"] = str(self.sessions)
        e["DOT_HYGIENE_CODEX_DOCS"] = str(self.docs)
        e["DOT_HYGIENE_DOWNLOADS"] = str(self.downloads)
        e["DOT_HYGIENE_AGENT_PROMPTS"] = str(self.prompts)
        e["DOT_HYGIENE_LARGE_MB"] = large_mb
        return e

    def run_cli(self, *args):
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), *args],
            cwd=str(ROOT), env=self.env(),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def payload(self, proc):
        line = proc.stdout.decode().strip().splitlines()[-1]
        return json.loads(line)

    def rollout(self, name="rollout-a.jsonl", size=0, marker=UNMARKED,
                hours_old=0.0):
        data = (marker + "\n").encode() + b"x" * max(size - len(marker) - 1, 0)
        return write(self.sessions / "2026" / "10" / "05" / name,
                     data, hours_old)


class TestDotSessionHygiene(HygieneCase):

    def test_01_large_rollout_flagged(self):
        self.rollout(size=6 * 1024 * 1024, marker=MARKED)
        proc = self.run_cli("--since-hours", "36", "--json")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        res = self.payload(proc)
        self.assertTrue(res["ok"])
        self.assertEqual(res["counts"].get("LARGE_ROLLOUT"), 1)
        hit = [f for f in res["findings"] if f["type"] == "LARGE_ROLLOUT"][0]
        self.assertIn("rollout-a.jsonl", hit["path"])

    def test_02_small_rollout_not_flagged(self):
        self.rollout(size=1024, marker=MARKED)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertIsNone(res["counts"].get("LARGE_ROLLOUT"))

    def test_03_partial_staging_flagged(self):
        write(self.docs / "2026-10-05" / "task-4" / "partial-frag.txt",
              b"partial")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("PARTIAL_STAGING"), 1)

    def test_04_brief_missing_downloads_flagged(self):
        write(self.prompts / "devin-x-20261005" / "BRIEF.txt", b"unique-body")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("BRIEF_NOT_IN_DOWNLOADS"), 1)

    def test_05_brief_matched_downloads_clean(self):
        body = b"matched-brief-body"
        write(self.prompts / "devin-x-20261005" / "BRIEF.txt", body)
        write(self.downloads / "PASTE_DEVIN_x_20261005.txt", body)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertIsNone(res["counts"].get("BRIEF_NOT_IN_DOWNLOADS"))

    def test_06_bare_auto_suspect_flagged(self):
        self.rollout(size=2048, marker=UNMARKED)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("BARE_AUTO_SUSPECT"), 1)

    def test_07_marked_rollout_not_bare(self):
        self.rollout(size=2048, marker=MARKED)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertIsNone(res["counts"].get("BARE_AUTO_SUSPECT"))

    def test_08_window_filter_excludes_old(self):
        self.rollout(size=6 * 1024 * 1024, marker=UNMARKED, hours_old=72)
        write(self.prompts / "devin-x-20261005" / "BRIEF.txt", b"old",
              hours_old=72)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"], {})

    def test_09_read_only_and_exit_zero(self):
        self.rollout(size=6 * 1024 * 1024, marker=UNMARKED)
        before = sorted(str(p) for p in self.tmp.rglob("*"))
        proc = self.run_cli("--since-hours", "36", "--json")
        after = sorted(str(p) for p in self.tmp.rglob("*"))
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(before, after, "읽기 전용 스크립트가 파일을 만들었다")

    def test_10_no_secrets_or_body_in_output(self):
        self.rollout(size=2048, marker=UNMARKED)
        proc = self.run_cli("--since-hours", "36", "--json")
        out = proc.stdout.decode()
        self.assertNotIn("그냥 바로 수정해", out, "본문 텍스트가 출력에 포함됐다")

    def test_11_json_shape_has_since_hours(self):
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["sinceHours"], 36.0)
        self.assertIn("roots", res)
        self.assertIn("findings", res)


GOAL_A = "PASTE_CODEX_ALPHA_20261005"
GOAL_B = "PASTE_CODEX_BRAVO_20261005"
GOAL_C = "PASTE_GROK_CHARLIE_20261005"
PAIR_DECL = "[DOT-ASSIST-PAIR] lanes=codex-alpha-1,devin-bravo-2 mode=read-only"


class TestAssistPairHygiene(HygieneCase):
    """ASSIST_PAIR: [DOT-ASSIST-PAIR] 선언 + ≤2 PASTE 식별자 + 작성 마커 없음
    → ASSIST_PAIR_OK(count만). 위반 조각은 assistPairBreach:true."""

    def rollout_body(self, text: str, name="rollout-p.jsonl", hours_old=0.0):
        body = '{"payload":{"role":"user","content":[{"text":"' + text + '"}]}}'
        return write(self.sessions / "2026" / "10" / "05" / name,
                     body.encode("utf-8"), hours_old)

    def test_p1_declared_pair_ok(self):
        self.rollout_body(f"{PAIR_DECL} {GOAL_A} 지원하다가 {GOAL_B} 서브딜러")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("ASSIST_PAIR_OK"), 1)
        self.assertIsNone(res["counts"].get("MULTI_GOAL_CONTAMINATION"))
        self.assertIsNone(res["counts"].get("ROLE_SWITCH_MID_SESSION"))
        self.assertIsNone(res["counts"].get("BARE_AUTO_SUSPECT"))
        hit = [f for f in res["findings"] if f["type"] == "ASSIST_PAIR_OK"][0]
        self.assertEqual(hit["declaredLanes"],
                         ["codex-alpha-1", "devin-bravo-2"])
        self.assertNotIn("assistPairBreach", hit)

    def test_p2_third_goal_breach(self):
        self.rollout_body(f"{PAIR_DECL} {GOAL_A} {GOAL_B} 그리고 {GOAL_C}")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("MULTI_GOAL_CONTAMINATION"), 1)
        self.assertIsNone(res["counts"].get("ASSIST_PAIR_OK"))
        hit = [f for f in res["findings"]
               if f["type"] == "MULTI_GOAL_CONTAMINATION"][0]
        self.assertTrue(hit["assistPairBreach"])
        self.assertEqual(hit["goalCount"], 3)

    def test_p3_write_marker_breach(self):
        self.rollout_body(f"{PAIR_DECL} {GOAL_A} 보다가 이제 지시서 작성 전환")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("ROLE_SWITCH_MID_SESSION"), 1)
        self.assertIsNone(res["counts"].get("ASSIST_PAIR_OK"))
        hit = [f for f in res["findings"]
               if f["type"] == "ROLE_SWITCH_MID_SESSION"][0]
        self.assertTrue(hit["assistPairBreach"])
        self.assertIn("지시서 작성", hit["writeMarkers"])

    def test_p4_undeclared_pair_still_serial_violation(self):
        self.rollout_body(f"{GOAL_A} 어시스트 + {GOAL_B} 어시스트")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("MULTI_GOAL_CONTAMINATION"), 1)
        self.assertIsNone(res["counts"].get("ASSIST_PAIR_OK"))
        hit = [f for f in res["findings"]
               if f["type"] == "MULTI_GOAL_CONTAMINATION"][0]
        self.assertNotIn("assistPairBreach", hit)

    def test_p5_decl_only_no_goals_ok(self):
        self.rollout_body(f"{PAIR_DECL} 레인 카드부터 읽는다")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("ASSIST_PAIR_OK"), 1)
        self.assertIsNone(res["counts"].get("BARE_AUTO_SUSPECT"))

    def test_p6_pair_no_body_in_output(self):
        secret = "페어 본문 문장은 출력되면 안 된다 QWERTY"
        self.rollout_body(f"{PAIR_DECL} {GOAL_A} {GOAL_B} {GOAL_C} {secret}")
        proc = self.run_cli("--since-hours", "36", "--json")
        self.assertNotIn(secret, proc.stdout.decode())


class TestRolloutNearCapacity(HygieneCase):
    """ROLLOUT_NEAR_CAPACITY: NEAR ≤ size < LARGE 예고 신호(메타만)."""

    def test_n1_near_flagged_below_large(self):
        size = 3 * 1024 * 1024 + 512 * 1024
        p = self.rollout(size=size, marker=MARKED)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("ROLLOUT_NEAR_CAPACITY"), 1)
        self.assertIsNone(res["counts"].get("LARGE_ROLLOUT"))
        hit = [f for f in res["findings"]
               if f["type"] == "ROLLOUT_NEAR_CAPACITY"][0]
        self.assertEqual(hit["sizeBytes"], p.stat().st_size)
        self.assertIn("rollout-a.jsonl", hit["path"])
        self.assertIn("mtime", hit)

    def test_n2_at_large_threshold_only_large(self):
        self.rollout(size=6 * 1024 * 1024, marker=MARKED)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("LARGE_ROLLOUT"), 1)
        self.assertIsNone(res["counts"].get("ROLLOUT_NEAR_CAPACITY"))


class TestSerialLaneHygiene(HygieneCase):
    """SERIAL_LANE 탐침: MULTI_GOAL_CONTAMINATION / ROLE_SWITCH_MID_SESSION."""

    def rollout_body(self, text: str, name="rollout-s.jsonl", hours_old=0.0):
        body = '{"payload":{"role":"user","content":[{"text":"' + text + '"}]}}'
        return write(self.sessions / "2026" / "10" / "05" / name,
                     body.encode("utf-8"), hours_old)

    def test_s1_multi_goal_flagged(self):
        self.rollout_body(f"{GOAL_A} 작업하다가 {GOAL_B} 도 붙여 넣음")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("MULTI_GOAL_CONTAMINATION"), 1)
        hit = [f for f in res["findings"]
               if f["type"] == "MULTI_GOAL_CONTAMINATION"][0]
        self.assertEqual(hit["goalCount"], 2)
        self.assertIn(GOAL_A, hit["goals"])
        self.assertIn(GOAL_B, hit["goals"])

    def test_s2_single_goal_clean(self):
        self.rollout_body(f"{GOAL_A} 지시서. 같은 {GOAL_A} 범위만 계속")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertIsNone(res["counts"].get("MULTI_GOAL_CONTAMINATION"))

    def test_s3_role_switch_flagged(self):
        self.rollout_body("서브딜러 지원으로 시작했는데 이제 지시서 작성만 해")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertEqual(res["counts"].get("ROLE_SWITCH_MID_SESSION"), 1)
        hit = [f for f in res["findings"]
               if f["type"] == "ROLE_SWITCH_MID_SESSION"][0]
        self.assertIn("서브딜러", hit["supportMarkers"])
        self.assertIn("지시서 작성", hit["writeMarkers"])

    def test_s4_one_role_family_clean(self):
        self.rollout_body("서브딜러 지원만 계속 — 지시서는 기존 것 참조")
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertIsNone(res["counts"].get("ROLE_SWITCH_MID_SESSION"))

    def test_s5_no_body_in_output(self):
        secret_sentence = "이 본문 문장은 출력되면 안 된다 ZXCVBNM"
        self.rollout_body(f"{GOAL_A} {GOAL_B} {secret_sentence}")
        proc = self.run_cli("--since-hours", "36", "--json")
        out = proc.stdout.decode()
        self.assertNotIn(secret_sentence, out)

    def test_s6_serial_lane_window_filter(self):
        self.rollout_body(f"{GOAL_A} 후 {GOAL_B}", hours_old=72)
        res = self.payload(self.run_cli("--since-hours", "36", "--json"))
        self.assertIsNone(res["counts"].get("MULTI_GOAL_CONTAMINATION"))
        self.assertIsNone(res["counts"].get("ROLE_SWITCH_MID_SESSION"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
