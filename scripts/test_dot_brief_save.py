#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""scripts/dot_brief_save.py 계약 테스트.

fixture는 전부 tempfile 아래. 실제 Downloads / %TEMP% / agent-prompts 에는
DOT_BRIEF_SAVE_* 환경변수 override를 통해서만 접근하므로 실 경로 쓰기 0건.
실행: python -B scripts/test_dot_brief_save.py  (exit 0 = 전부 PASS)
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # src/
SCRIPT = ROOT / "scripts" / "dot_brief_save.py"
MAX_BYTES = 256 * 1024


def sha12(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


class DotBriefSaveTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dot-brief-test-")
        t = Path(self._tmp.name)
        self.downloads = t / "downloads"
        self.temp_root = t / "temp"
        self.prompts = t / "agent-prompts"
        self.srcdir = t / "inbox"
        for d in (self.downloads, self.temp_root, self.prompts, self.srcdir):
            d.mkdir(parents=True)
        self.log = t / "logs" / "log.jsonl"
        self.env = {
            "DOT_BRIEF_SAVE_DOWNLOADS": str(self.downloads),
            "DOT_BRIEF_SAVE_TEMP": str(self.temp_root),
            "DOT_BRIEF_SAVE_AGENT_PROMPTS": str(self.prompts),
            "DOT_BRIEF_SAVE_LOG": str(self.log),
        }

    def tearDown(self):
        self._tmp.cleanup()

    # ---------- helpers ----------
    def run_cli(self, *args, stdin: bytes | None = None):
        env = os.environ.copy()
        env.update(self.env)
        env["PYTHONIOENCODING"] = "utf-8"
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), *args],
            capture_output=True, input=stdin, env=env, cwd=ROOT,
        )

    def payload(self, proc):
        out = proc.stdout.decode("utf-8", "replace").strip().splitlines()
        self.assertTrue(out, f"no stdout; stderr={proc.stderr.decode('utf-8','replace')}")
        return json.loads(out[-1])

    def make_src(self, name="brief.txt", data=b"body") -> Path:
        p = self.srcdir / name
        p.write_bytes(data)
        return p

    def target_files(self):
        """저장 대상 루트(=downloads + agent-prompts + log) 아래 실제로 생성된 파일."""
        files = []
        for base in (self.downloads, self.prompts, self.log.parent):
            if base.exists():
                files += [p for p in base.rglob("*") if p.is_file()]
        return files

    def save_args(self, src, agent="DEVIN", topic="skill-harmony", date="20261002"):
        return ("save", "--agent", agent, "--topic", topic,
                "--date", date, "--from", str(src))

    # ---------- tests ----------
    def test_01_save_ok(self):
        body = "안녕 Codex, 지시서 본문입니다.\nsecond line\n".encode("utf-8")
        src = self.make_src(data=body)
        proc = self.run_cli(*self.save_args(src))
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        res = self.payload(proc)
        self.assertTrue(res["ok"])
        self.assertEqual(res["via"], "dot")
        self.assertEqual(res["sha12"], sha12(body))
        self.assertEqual(res["size"], len(body))
        self.assertEqual(len(res["paths"]), 2)
        dl = self.downloads / "PASTE_DEVIN_skill-harmony_20261002.txt"
        pp = self.prompts / "devin-skill-harmony-20261002" / "BRIEF.txt"
        self.assertTrue(dl.is_file(), res["paths"])
        self.assertTrue(pp.is_file(), res["paths"])
        self.assertEqual(dl.read_bytes(), body)
        self.assertEqual(pp.read_bytes(), body)
        self.assertIn(str(dl), res["paths"][0].replace("/", "\\") + res["paths"][0])
        # log: 경로·크기·sha만, 본문 없음
        self.assertTrue(self.log.is_file())
        line = json.loads(self.log.read_text(encoding="utf-8").splitlines()[-1])
        self.assertEqual(line["sha12"], sha12(body))
        self.assertNotIn("지시서", json.dumps(line, ensure_ascii=False))

    def test_02_collision_v2(self):
        original = b"original bytes - keep me"
        (self.downloads / "PASTE_DEVIN_skill-harmony_20261002.txt").write_bytes(original)
        pp_dir = self.prompts / "devin-skill-harmony-20261002"
        pp_dir.mkdir(parents=True)
        (pp_dir / "BRIEF.txt").write_bytes(b"old prompt brief")
        src = self.make_src(data=b"new content")
        proc = self.run_cli(*self.save_args(src))
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        res = self.payload(proc)
        self.assertTrue(res["ok"])
        v2 = self.downloads / "PASTE_DEVIN_skill-harmony_20261002_v2.txt"
        self.assertTrue(v2.is_file())
        self.assertEqual(v2.read_bytes(), b"new content")
        self.assertEqual(
            (self.downloads / "PASTE_DEVIN_skill-harmony_20261002.txt").read_bytes(),
            original, "원본 덮어쓰기 발생")
        self.assertTrue((self.prompts / "devin-skill-harmony-20261002_v2" / "BRIEF.txt").is_file())
        self.assertEqual((pp_dir / "BRIEF.txt").read_bytes(), b"old prompt brief")

    def test_03_bad_filename_rejected(self):
        src = self.make_src()
        for args in (
            ("save", "--agent", "devin", "--topic", "ok-topic", "--date", "20261002", "--from", str(src)),
            ("save", "--agent", "DEVIN", "--topic", "ok-topic", "--date", "2026102", "--from", str(src)),
            ("save", "--agent", "DEVIN", "--topic", "../escape", "--date", "20261002", "--from", str(src)),
            ("save", "--agent", "DEV/IN", "--topic", "ok", "--date", "20261002", "--from", str(src)),
        ):
            proc = self.run_cli(*args)
            self.assertNotEqual(proc.returncode, 0, args)
            self.assertFalse(self.payload(proc)["ok"], args)
        self.assertEqual(self.target_files(), [])

    def test_04_out_of_scope_path_rejected(self):
        # 디렉터리를 --from으로 → 거부
        proc = self.run_cli(*self.save_args(self.srcdir))
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(self.payload(proc)["ok"])
        # 없는 파일 → 거부
        proc = self.run_cli(*self.save_args(self.srcdir / "nope.txt"))
        self.assertNotEqual(proc.returncode, 0)
        # 소스 파일명은 출력 경로에 반영되지 않는다(출력명은 --agent/--topic/--date 결정)
        weird = self.make_src("..\\..\\weird.bin".replace("..\\", "x"), data=b"ok")
        proc = self.run_cli(*self.save_args(weird))
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        res = self.payload(proc)
        for p in res["paths"]:
            ap = Path(p).resolve()
            self.assertTrue(str(ap).startswith(str(self.downloads.resolve()))
                            or str(ap).startswith(str(self.prompts.resolve())), p)
        self.assertTrue((self.downloads / "PASTE_DEVIN_skill-harmony_20261002.txt").is_file())

    def test_05_secret_blocked(self):
        for marker in (b"sk-ABCDEF0123456789", b"api key ghp_0123456789abcdef",
                       b"-----BEGIN " + b"PRIVATE KEY-----", b"pass" + b"word=hunter2"):
            for p in self.downloads.rglob("*"):
                p.unlink()
            for p in sorted(self.prompts.rglob("*"), reverse=True):
                if p.is_file():
                    p.unlink()
                else:
                    p.rmdir()
            if self.log.exists():
                self.log.unlink()
            src = self.make_src(data=b"brief body " + marker)
            proc = self.run_cli(*self.save_args(src))
            self.assertNotEqual(proc.returncode, 0, marker)
            res = self.payload(proc)
            self.assertFalse(res["ok"])
            self.assertEqual(res["error"], "BLOCKED_SECRET")
            self.assertEqual(self.target_files(), [], marker)

    def test_06_oversize_rejected(self):
        src = self.make_src(data=b"x" * (MAX_BYTES + 1))
        proc = self.run_cli(*self.save_args(src))
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(self.payload(proc)["ok"])
        self.assertEqual(self.target_files(), [])

    def test_07_utf8_no_bom(self):
        raw = b"\xef\xbb\xbf" + "한글 지시서 — BOM 없이 저장돼야 한다\n".encode("utf-8")
        src = self.make_src(data=raw)
        proc = self.run_cli(*self.save_args(src))
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        for p in (self.downloads / "PASTE_DEVIN_skill-harmony_20261002.txt",
                  self.prompts / "devin-skill-harmony-20261002" / "BRIEF.txt"):
            data = p.read_bytes()
            self.assertFalse(data.startswith(b"\xef\xbb\xbf"), p)
            self.assertIn("한글 지시서", data.decode("utf-8"))

    def test_08_dry_run_and_check(self):
        src = self.make_src(data=b"dry body")
        proc = self.run_cli(*self.save_args(src), "--dry-run")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        res = self.payload(proc)
        self.assertTrue(res.get("dryRun") or res.get("check") or res["ok"])
        self.assertEqual(self.target_files(), [])
        proc = self.run_cli(*self.save_args(src), "--check")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        self.assertEqual(self.target_files(), [])

    def test_09_rescue_list_only(self):
        d = self.temp_root / "demo1-fixture-a"
        d.mkdir()
        (d / "PASTE_DEVIN_rescue-one_20261002.txt").write_bytes(b"lost brief one")
        proc = self.run_cli("rescue", "--hours", "24")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        res = self.payload(proc)
        self.assertTrue(res["ok"])
        self.assertEqual(len(res["candidates"]), 1)
        self.assertEqual(res["candidates"][0]["action"], "listed")
        self.assertEqual(self.target_files(), [], "list-only rescue가 파일을 만들었다")

    def test_10_rescue_apply(self):
        d = self.temp_root / "demo1-fixture-b"
        d.mkdir()
        f_new = d / "PASTE_DEVIN_rescue-one_20261002.txt"
        f_same = d / "PASTE_DEVIN_rescue-two_20261002.txt"
        f_diff = d / "PASTE_DEVIN_rescue-three_20261002.txt"
        f_new.write_bytes(b"new bytes")
        f_same.write_bytes(b"same bytes")
        f_diff.write_bytes(b"different bytes")
        (self.downloads / "PASTE_DEVIN_rescue-two_20261002.txt").write_bytes(b"same bytes")
        (self.downloads / "PASTE_DEVIN_rescue-three_20261002.txt").write_bytes(b"older other")
        proc = self.run_cli("rescue", "--hours", "24", "--apply")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        res = self.payload(proc)
        self.assertTrue(res["ok"])
        actions = {Path(c["src"]).name: c["action"] for c in res["candidates"]}
        self.assertEqual(actions["PASTE_DEVIN_rescue-one_20261002.txt"], "copied")
        self.assertEqual(actions["PASTE_DEVIN_rescue-two_20261002.txt"], "skipped-same-sha")
        self.assertEqual(actions["PASTE_DEVIN_rescue-three_20261002.txt"], "copied")
        self.assertEqual(
            (self.downloads / "PASTE_DEVIN_rescue-three_20261002.txt").read_bytes(),
            b"older other", "원본 덮어쓰기 발생")
        self.assertEqual(
            (self.downloads / "PASTE_DEVIN_rescue-three_20261002_v2.txt").read_bytes(),
            b"different bytes")


if __name__ == "__main__":
    unittest.main(verbosity=2)
