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
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "deliver_to_downloads.py"
HOOKS = ROOT / ".codex" / "hooks.json"


def run_tool(*args, cwd=None, stdin_text=None):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, cwd=cwd or ROOT, timeout=30,
        input=stdin_text,
    )


def write_transcript(path, first_user_text, extra_user_texts=(),
                     developer_texts=()):
    """Synthetic codex rollout jsonl: session_meta + response_item records."""
    now = datetime.now(timezone.utc).isoformat()
    lines = [{"type": "session_meta", "timestamp": now,
              "payload": {"id": "fixture-session-0001", "cwd": str(path.parent)}}]
    for t in developer_texts:
        lines.append({"type": "response_item", "timestamp": now,
                      "payload": {"role": "developer", "type": "message",
                                  "content": [{"type": "input_text", "text": t}]}})
    lines.append({"type": "response_item", "timestamp": now,
                  "payload": {"role": "user", "type": "message",
                              "content": [{"type": "input_text",
                                           "text": first_user_text}]}})
    for t in extra_user_texts:
        lines.append({"type": "response_item", "timestamp": now,
                      "payload": {"role": "user", "type": "message",
                                  "content": [{"type": "input_text", "text": t}]}})
    path.write_text("\n".join(json.dumps(x) for x in lines) + "\n",
                    encoding="utf-8")
    return path


def hook_stop_event(transcript=None, session_id=None, stop_active=False,
                    cwd=None):
    ev = {"session_id": session_id or "fixture-session-0001",
          "transcript_path": str(transcript) if transcript else None,
          "cwd": cwd or str(ROOT),
          "hook_event_name": "Stop", "stop_hook_active": stop_active,
          "model": "fixture", "permission_mode": "default",
          "last_assistant_message": None}
    return json.dumps(ev)


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

    def test_t9_hooks_json_dot_scoped_deliver_hook(self):
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
        self.assertEqual(len(deliver_entries), 1,
                         "exactly one scoped deliver_to_downloads Stop hook")
        ev_name, entry = deliver_entries[0]
        self.assertEqual(ev_name, "Stop")
        blob = json.dumps(entry, ensure_ascii=False)
        self.assertIn("--hook-stop", blob,
                      "Stop hook must be DOT-BRIEF-scoped, not a global --scan")
        # a bare global --scan revival is forbidden (all-session pollution)
        for e in deliver_entries:
            cmd_blob = json.dumps(e[1], ensure_ascii=False)
            self.assertNotRegex(
                cmd_blob,
                r"deliver_to_downloads\.py\"?\s+--scan\b")
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


class TestHookStop(Fixture):
    """D1-D8: --hook-stop delivers only for [DOT-BRIEF]-first-message sessions.

    Contract PASTE_DEVIN_dot-only-downloads_20261004: the Stop hook never does
    a global scan; it reads the hook event on stdin, opens the transcript, and
    only when the FIRST user-role message carries the literal tag does it copy
    matching deliverables. stdout is always a single `{}` JSON line.
    """

    def hook(self, event_obj, roots_dir=None):
        """Run --hook-stop; event cwd is pointed at the temp fixture dir."""
        cwd = str(roots_dir or self.src)
        try:
            ev = json.loads(event_obj)
            ev["cwd"] = cwd
            event_obj = json.dumps(ev)
        except (ValueError, TypeError):
            pass  # broken-stdin cases pass through unchanged
        return run_tool("--hook-stop", "--quiet",
                        "--downloads", str(self.dl), "--log", str(self.log),
                        stdin_text=event_obj, cwd=cwd)

    def read_log(self):
        if not self.log.exists():
            return None
        return json.loads(self.log.read_text(encoding="utf-8").splitlines()[-1])

    def test_d1_plain_session_no_tag_no_delivery(self):
        tr = write_transcript(self.tmp / "rollout-plain.jsonl",
                              "just fix the thing please")
        self.write_src("PASTE_CODEX_PLAIN_20261005.md", b"brief body")
        r = self.hook(hook_stop_event(transcript=tr))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.strip(), "{}")
        self.assertEqual(list(self.dl.iterdir()), [])
        rec = self.read_log()
        self.assertFalse(rec["dot"])
        self.assertEqual(rec["reason"], "tag-absent")

    def test_d2_tag_in_skill_context_not_user_message(self):
        tr = write_transcript(
            self.tmp / "rollout-skillctx.jsonl", "write the brief",
            developer_texts=["skill body mentioning [DOT-BRIEF] contract"])
        self.write_src("PASTE_CODEX_SKILLCTX_20261005.md", b"brief")
        r = self.hook(hook_stop_event(transcript=tr))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.strip(), "{}")
        self.assertEqual(list(self.dl.iterdir()), [])

    def test_d3_first_user_message_tag_delivers(self):
        tr = write_transcript(self.tmp / "rollout-dot.jsonl",
                              "[DOT-BRIEF] write the Codex directive")
        self.write_src("PASTE_CODEX_SOME_BRIEF_20261005.md", b"dot brief body")
        self.write_src("unrelated.bin", b"\x00\x01\x02")
        r = self.hook(hook_stop_event(transcript=tr))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.strip(), "{}")
        names = [p.name for p in self.dl.iterdir()]
        self.assertIn("PASTE_CODEX_SOME_BRIEF_20261005.md", names)
        self.assertNotIn("unrelated.bin", names)
        rec = self.read_log()
        self.assertTrue(rec["dot"])
        self.assertEqual(rec["delivered"], 1)
        self.assertEqual(rec["session8"], "fixture-")

    def test_d4_tag_in_later_user_message_only_no_delivery(self):
        tr = write_transcript(
            self.tmp / "rollout-late.jsonl", "plain first message",
            extra_user_texts=["[DOT-BRIEF] now it shows up"])
        self.write_src("PASTE_CODEX_LATE_20261005.md", b"late brief")
        r = self.hook(hook_stop_event(transcript=tr))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(list(self.dl.iterdir()), [])
        self.assertEqual(self.read_log()["reason"], "tag-absent")

    def test_d5_broken_transcript_and_stdin_fail_closed(self):
        broken = self.tmp / "rollout-broken.jsonl"
        broken.write_bytes(b"\x89not-json{")
        for ev in (hook_stop_event(transcript=broken),
                   hook_stop_event(transcript=self.tmp / "missing.jsonl"),
                   "{not-json",
                   ""):
            r = self.hook(ev)
            self.assertEqual(r.returncode, 0)
            self.assertEqual(r.stdout.strip(), "{}")
            self.assertEqual(list(self.dl.iterdir()), [])

    def test_d6_stop_hook_active_noop(self):
        tr = write_transcript(self.tmp / "rollout-active.jsonl",
                              "[DOT-BRIEF] first message")
        self.write_src("PASTE_CODEX_ACTIVE_20261005.md", b"x")
        r = self.hook(hook_stop_event(transcript=tr, stop_active=True))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(list(self.dl.iterdir()), [])
        self.assertEqual(self.read_log()["reason"], "stop-hook-active")

    def test_d7_dot_session_refused_files_stay_out(self):
        tr = write_transcript(self.tmp / "rollout-dotrefuse.jsonl",
                              "[DOT-BRIEF] go")
        self.write_src("PASTE_CODEX_BIG_20261005.md", b"z" * (1024 * 1024 + 1))
        self.write_src("PASTE_CODEX_token_20261005.md", b"named like a token")
        r = self.hook(hook_stop_event(transcript=tr))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(list(self.dl.iterdir()), [])
        rec = self.read_log()
        self.assertTrue(rec["dot"])
        self.assertEqual(rec["refused"], 2)

    def test_d8_scan_still_ignores_stdin(self):
        self.write_src("demo1_x_directive_20261005.md", b"scan me")
        r = run_tool("--scan", "--since-minutes", "240",
                     "--roots", str(self.src),
                     "--downloads", str(self.dl), "--log", str(self.log),
                     stdin_text="{not-json on stdin")
        self.assertEqual(r.returncode, 0)
        self.assertIn("DELIVERED", r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
