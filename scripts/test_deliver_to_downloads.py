#!/usr/bin/env python3
"""T1-T10 for scripts/deliver_to_downloads.py — temp fixtures only.

Never touches the real user.downloads: every run passes --downloads and --log
into a fresh temp dir. T9 additionally validates the live .codex/hooks.json
edit (add-only: entry count preserved + exactly one new deliver hook).
"""

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "deliver_to_downloads.py"
HOOKS = ROOT / ".codex" / "hooks.json"


def run_tool(*args, cwd=None):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, cwd=cwd or ROOT, timeout=30,
    )


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dtd-test-"))
        self.src = self.tmp / "src"
        self.dl = self.tmp / "downloads"
        self.log = self.tmp / "log" / "deliver.jsonl"
        self.src.mkdir(parents=True)
        self.dl.mkdir()

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def tool(self, *args):
        return run_tool(*args, "--downloads", str(self.dl), "--log", str(self.log))

    def write_src(self, name, content=b"x", subdir=""):
        d = self.src / subdir if subdir else self.src
        d.mkdir(parents=True, exist_ok=True)
        p = d / name
        p.write_bytes(content)
        return p

    def tree_snapshot(self, base):
        snap = {}
        for dirpath, _d, files in os.walk(base):
            for f in files:
                p = Path(dirpath) / f
                snap[str(p.relative_to(base))] = p.stat().st_size
        return snap


class TestDeliver(Fixture):
    def test_t1_file_delivered_match(self):
        src = self.write_src("demo1_x_directive_20261004.md", b"hello deliver")
        r = self.tool("--file", str(src))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("DELIVERED ", r.stdout)
        self.assertIn(" MATCH", r.stdout)
        self.assertIn(str(self.dl / src.name), r.stdout)
        self.assertTrue((self.dl / src.name).exists())
        self.assertIn("sha12=", r.stdout)

    def test_t2_same_name_same_sha_skip(self):
        src = self.write_src("demo1_x_directive_20261004.md", b"same bytes")
        self.tool("--file", str(src))
        r = self.tool("--file", str(src))
        self.assertEqual(r.returncode, 0)
        self.assertIn("SKIP_SAME", r.stdout)
        self.assertEqual(len(list(self.dl.iterdir())), 1)

    def test_t3_same_name_diff_sha_v2(self):
        src = self.write_src("demo1_x_directive_20261004.md", b"version one")
        self.tool("--file", str(src))
        first = self.dl / src.name
        first_bytes = first.read_bytes()
        src.write_bytes(b"version two changed")
        r = self.tool("--file", str(src))
        self.assertEqual(r.returncode, 0)
        self.assertIn("DELIVERED", r.stdout)
        v2 = self.dl / f"{src.stem}_v2{src.suffix}"
        self.assertTrue(v2.exists(), f"expected {v2}")
        self.assertEqual(first.read_bytes(), first_bytes)

    def test_t4_source_unchanged(self):
        src = self.write_src("demo1_x_report_20261004.md", b"keep me")
        before = src.read_bytes()
        self.tool("--file", str(src))
        self.assertTrue(src.exists())
        self.assertEqual(src.read_bytes(), before)

    def test_t5_old_file_ignored(self):
        old = self.write_src("demo1_old_directive_20200101.md", b"ancient")
        old_ts = time.time() - 10 * 24 * 3600
        os.utime(old, (old_ts, old_ts))
        r = self.tool("--scan", "--since-minutes", "240", "--roots", str(self.src))
        self.assertEqual(r.returncode, 0)
        self.assertNotIn("DELIVERED", r.stdout)
        self.assertFalse((self.dl / old.name).exists())

    def test_t6_excludes(self):
        self.write_src("random_notes.json", b"{}")
        self.write_src(".env.local", b"A=1")
        self.write_src("mysecret_directive_x.md", b"s")
        self.write_src("big_report_x.md", b"z" * (1024 * 1024 + 1))
        good = self.write_src("good_directive_x.md", b"ok")
        r = self.tool("--scan", "--since-minutes", "240", "--roots", str(self.src))
        self.assertEqual(r.returncode, 0)
        names = [p.name for p in self.dl.iterdir()]
        self.assertEqual(names, [good.name])

    def test_t7_no_writes_outside_downloads(self):
        self.write_src("demo1_d_directive_20261004.md", b"q")
        snap_src = self.tree_snapshot(self.src)
        snap_tmp = self.tree_snapshot(self.tmp)
        self.tool("--scan", "--since-minutes", "240", "--roots", str(self.src))
        self.assertEqual(self.tree_snapshot(self.src), snap_src)
        after = self.tree_snapshot(self.tmp)
        new_paths = {k for k in after if k not in snap_tmp}
        for rel in new_paths:
            self.assertTrue(rel.startswith("downloads") or rel.startswith("log"),
                            f"unexpected write outside downloads/log: {rel}")

    def test_t8_missing_root_exit0_warn(self):
        r = self.tool("--scan", "--since-minutes", "60",
                      "--roots", str(self.tmp / "no-such-dir"))
        self.assertEqual(r.returncode, 0)
        self.assertIn("WARN", r.stderr + r.stdout)

    def test_t9_hooks_json_no_deliver_hook(self):
        data = json.loads(HOOKS.read_text(encoding="utf-8"))
        hooks = data["hooks"]
        post = hooks.get("PostToolUse", [])
        self.assertTrue(any(e.get("matcher") == "^Bash$" for e in post),
                        "pre-existing ^Bash$ PostToolUse entry missing")
        self.assertTrue(any(e.get("matcher") == "^(apply_patch|write|edit|notebook_edit)$"
                            for e in hooks.get("PreToolUse", [])),
                        "pre-existing edit PreToolUse entry missing")
        deliver_entries = [
            (ev_name, e) for ev_name, entries in hooks.items() for e in entries
            if "deliver_to_downloads" in json.dumps(e, ensure_ascii=False)
        ]
        self.assertEqual(len(deliver_entries), 0,
                         "deliver_to_downloads hook must stay removed (DEMO1-DOT-FILE-CARD)")
        self.assertTrue(hooks.get("UserPromptSubmit"))
        self.assertTrue(hooks.get("PreToolUse"))

    def test_t10_scan_1000_files_under_3s(self):
        big = self.src / "many" / "task"
        big.mkdir(parents=True)
        for i in range(950):
            (big / f"noise_{i:04}.txt").write_text(f"n{i}")
        for i in range(50):
            (big / f"f{i:04}_directive_20261004.md").write_text(f"c{i}")
        t0 = time.monotonic()
        r = self.tool("--scan", "--since-minutes", "240",
                      "--roots", str(self.src), "--deadline-sec", "30")
        elapsed = time.monotonic() - t0
        self.assertEqual(r.returncode, 0)
        self.assertLess(elapsed, 3.0, f"scan took {elapsed:.2f}s")
        self.assertEqual(len(list(self.dl.iterdir())), 50)


if __name__ == "__main__":
    unittest.main(verbosity=2)
