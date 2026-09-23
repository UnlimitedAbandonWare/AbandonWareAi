#!/usr/bin/env python3
"""test_agent_session_watch.py — synthetic-fixture tests for the shared
agent session watchdog. No real session stores are read; every case builds a
temp rollout file."""
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_session_watch as w  # noqa: E402

CWD = "C:\\proj\\root"


def rec(t, payload):
    return json.dumps({"type": t, "payload": payload})


def meta(cwd=CWD):
    return rec("session_meta", {"id": "s-1", "cwd": cwd, "originator": "test",
                                "thread_source": "user"})


def call(cid, tool, cmd=""):
    inp = 'const r = await tools.%s({"cmd":%s});' % (
        tool, json.dumps(cmd)) if cmd else 'const r = await tools.%s({});' % tool
    return rec("response_item", {"type": "custom_tool_call", "call_id": cid,
                                 "name": "exec", "input": inp})


def patch_call(cid, text):
    return rec("response_item", {"type": "custom_tool_call", "call_id": cid,
                                 "name": "exec",
                                 "input": 'tools.apply_patch({patch:"""%s\n*** End Patch"""});'
                                 % text})


def out(cid, text):
    return rec("response_item", {"type": "custom_tool_call_output",
                                 "call_id": cid,
                                 "output": [{"type": "input_text",
                                             "text": text}]})


def ok(cid):
    return out(cid, "Script completed\nWall time 0.1 seconds\nOutput:\nok")


def fail(cid, why="Exit code: 1"):
    return out(cid, "Script failed\nWall time 0.1\nOutput:\n" + why)


def write(tmp, name, lines, age_hours=0):
    p = Path(tmp) / name
    p.write_text("\n".join(lines), encoding="utf-8")
    if age_hours:
        old = time.time() - age_hours * 3600
        os.utime(p, (old, old))
    return p


class CodexScan(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.stale_ms = time.time() * 1000 - 48 * 3600_000

    def tearDown(self):
        self.tmp.cleanup()

    def scan(self, p):
        return w.scan_codex(p, self.stale_ms, 400000)

    def test_clean_session_no_findings(self):
        p = write(self.tmp.name, "r.jsonl",
                  [meta(),
                   rec("event_msg", {"type": "task_started"}),
                   call("c1", "exec_command", "dir"),
                   ok("c1"),
                   rec("event_msg", {"type": "task_complete"})])
        r = self.scan(p)
        self.assertEqual(r["findings"], [])
        self.assertEqual(r["stats"]["toolCalls"], 1)
        self.assertEqual(r["stats"]["toolFailed"], 0)

    def test_p1_repeated_context_miss_is_auto(self):
        tgt = "C:\\proj\\root\\main\\Foo.java"
        miss = ("Script failed\nScript error:\napply_patch verification failed: "
                "Failed to find expected lines in %s:\n @Test void x()" % tgt)
        lines = [meta()]
        for i in range(3):
            lines += [patch_call("p%d" % i, "*** Update File: %s" % tgt),
                      out("p%d" % i, miss)]
        p = write(self.tmp.name, "r.jsonl", lines)
        r = self.scan(p)
        auto = [f for f in r["findings"]
                if f["pattern"] == "P1" and f["severity"] == "auto"]
        self.assertTrue(auto, r["findings"])
        self.assertIn(tgt, auto[0]["evidence"]["files"])

    def test_p1_single_miss_is_warn_only(self):
        tgt = "C:\\proj\\root\\main\\Foo.java"
        miss = ("apply_patch verification failed: Failed to find expected "
                "lines in %s:\n ctx" % tgt)
        p = write(self.tmp.name, "r.jsonl",
                  [meta(), patch_call("p0", "*** Update File: %s" % tgt),
                   out("p0", miss)])
        r = self.scan(p)
        sev = [f["severity"] for f in r["findings"] if f["pattern"] == "P1"]
        self.assertEqual(sev, ["warn"])

    def test_p3_repeated_failed_command_is_auto(self):
        lines = [meta()]
        for i in range(4):
            lines += [call("c%d" % i, "exec_command", "gradlew.bat test"),
                      fail("c%d" % i)]
        p = write(self.tmp.name, "r.jsonl", lines)
        r = self.scan(p)
        self.assertTrue(any(f["pattern"] == "P3" and f["severity"] == "auto"
                            for f in r["findings"]), r["findings"])
        self.assertEqual(r["stats"]["maxFailStreak"], 4)
        self.assertTrue(any(f["pattern"] == "P4" for f in r["findings"]))

    def test_p5_compaction_heavy(self):
        lines = [meta()] + [rec("compacted", {"x": 1}) for _ in range(12)]
        p = write(self.tmp.name, "r.jsonl", lines)
        r = self.scan(p)
        self.assertTrue(any(f["pattern"] == "P5" for f in r["findings"]))

    def test_p6_single_developer_switch_is_template(self):
        p = write(self.tmp.name, "r.jsonl",
                  [meta(), rec("response_item",
                               {"type": "message", "role": "developer",
                                "content": [{"type": "input_text",
                                             "text": "<model_switch> x"}]})])
        r = self.scan(p)
        self.assertFalse(any(f["pattern"] == "P6" for f in r["findings"]),
                         r["findings"])

    def test_p6_assistant_switch_is_warn(self):
        p = write(self.tmp.name, "r.jsonl",
                  [meta(), rec("response_item",
                               {"type": "message", "role": "assistant",
                                "content": [{"type": "input_text",
                                             "text": "<model_switch> x"}]})])
        r = self.scan(p)
        self.assertTrue(any(f["pattern"] == "P6" for f in r["findings"]))

    def test_p6_two_developer_switches_is_warn(self):
        switch = rec("response_item",
                     {"type": "message", "role": "developer",
                      "content": [{"type": "input_text",
                                   "text": "<model_switch> x"}]})
        p = write(self.tmp.name, "r.jsonl", [meta(), switch, switch])
        r = self.scan(p)
        self.assertTrue(any(f["pattern"] == "P6" for f in r["findings"]))

    def test_p7_goal_conflict(self):
        p = write(self.tmp.name, "r.jsonl",
                  [meta(), call("g1", "create_goal"),
                   fail("g1", "cannot create a new goal because this thread "
                              "has an unfinished goal")])
        r = self.scan(p)
        self.assertTrue(any(f["pattern"] == "P7" for f in r["findings"]))

    def test_p8_edit_outside_cwd(self):
        lines = [meta(),
                 patch_call("p0", "*** Update File: C:\\other\\ Evil.java\n@@"),
                 ok("p0")]
        p = write(self.tmp.name, "r.jsonl", lines)
        r = self.scan(p)
        hits = [f for f in r["findings"] if f["pattern"] == "P8"]
        self.assertTrue(hits, r["findings"])
        self.assertIn("C:\\other\\ Evil.java", hits[0]["evidence"]["targets"])

    def test_p9_stale_incomplete(self):
        p = write(self.tmp.name, "r.jsonl",
                  [meta(), rec("event_msg", {"type": "task_started"})],
                  age_hours=100)
        r = self.scan(p)
        self.assertTrue(any(f["pattern"] == "P9" for f in r["findings"]))

    def test_p9_fresh_incomplete_not_stale(self):
        p = write(self.tmp.name, "r.jsonl",
                  [meta(), rec("event_msg", {"type": "task_started"})])
        r = self.scan(p)
        self.assertFalse(any(f["pattern"] == "P9" for f in r["findings"]))

    def test_p10_fanout_info_only(self):
        lines = [meta()]
        for i in range(21):
            lines += [call("s%d" % i, "followup_task"), ok("s%d" % i)]
        p = write(self.tmp.name, "r.jsonl", lines)
        r = self.scan(p)
        hits = [f for f in r["findings"] if f["pattern"] == "P10"]
        self.assertTrue(hits)
        self.assertEqual(hits[0]["severity"], "info")

    def test_p11_cannot_find_path_inside_script_completed(self):
        missing = ("Script completed\nWall time 0.7 seconds\nOutput:\n"
                   "Get-Content: Cannot find path "
                   "'C:\\proj\\root\\main\\java\\Missing.java' because it "
                   "does not exist.")
        p = write(self.tmp.name, "r.jsonl",
                  [meta(),
                   call("c1", "exec_command",
                        "Get-Content main/java/Missing.java"),
                   out("c1", missing)])
        r = self.scan(p)
        hits = [f for f in r["findings"] if f["pattern"] == "P11"]
        self.assertTrue(hits, r["findings"])
        self.assertEqual(hits[0]["severity"], "warn")
        self.assertEqual(r["stats"]["toolFailed"], 1)

    def test_p11_three_soft_fails_are_auto(self):
        missing = ("Script completed\nOutput:\nCannot find path "
                   "'C:\\proj\\root\\main\\java\\A.java' because it does not exist.")
        lines = [meta()]
        for i in range(3):
            lines += [call("c%d" % i, "exec_command",
                           "Get-Content main/java/A.java"),
                      out("c%d" % i, missing)]
        p = write(self.tmp.name, "r.jsonl", lines)
        r = self.scan(p)
        hits = [f for f in r["findings"]
                if f["pattern"] == "P11" and f["severity"] == "auto"]
        self.assertTrue(hits, r["findings"])

    def test_p12_same_file_mutated_retry_is_auto(self):
        miss = ("Script completed\nOutput:\nCannot find path "
                "'C:\\proj\\root\\main\\java\\Foo.java' because it does not exist.")
        lines = [meta()]
        cmds = [
            "Get-Content -Raw main/java/Foo.java",
            "Get-Content -LiteralPath main/java/Foo.java | Select-Object -First 20",
            "$p='main/java/Foo.java'; Get-Content $p",
        ]
        for i, cmd in enumerate(cmds):
            lines += [call("c%d" % i, "exec_command", cmd), out("c%d" % i, miss)]
        p = write(self.tmp.name, "r.jsonl", lines)
        r = self.scan(p)
        hits = [f for f in r["findings"]
                if f["pattern"] == "P12" and f["severity"] == "auto"]
        self.assertTrue(hits, r["findings"])
        files = hits[0]["evidence"]["files"]
        self.assertTrue(any("Foo.java" in k for k in files), files)


class GenericScan(unittest.TestCase):
    def test_grok_error_markers(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = write(tmp, "chat_history.jsonl",
                      ['{"type":"system","content":"x"}',
                       '{"type":"tool_result","isError":true}',
                       '{"type":"tool_result","isError":true}',
                       '{"type":"tool_result","isError":true}'])
            r = w.scan_generic(p, "grok", 0, 400000)
            self.assertTrue(any(f["pattern"] == "G1" for f in r["findings"]))

    def test_clean_generic(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = write(tmp, "events.jsonl",
                      ['{"ts":"2026-09-19T00:00:00Z","type":"mcp_config_resolved"}'])
            r = w.scan_generic(p, "grok", 0, 400000)
            self.assertEqual(r["findings"], [])


class Discovery(unittest.TestCase):
    def test_stores_shape(self):
        stores = w.discover_stores()
        agents = {s["agent"] for s in stores}
        self.assertEqual(agents, {"codex", "grok", "devin", "cline"})
        for s in stores:
            self.assertIn("present", s)
            self.assertIn("sessionFiles", s)

    def test_gather_explicit_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = write(tmp, "x.jsonl", [meta()])
            files, bounded, skipped = w.gather_files(
                "codex", w.agent_homes(), 0, 64, 32, explicit_file=str(p))
            self.assertEqual(len(files), 1)
            self.assertFalse(bounded)
            self.assertEqual(skipped, [])

    def test_gather_dir_skips_apply_sidecar(self):
        with tempfile.TemporaryDirectory() as tmp:
            write(tmp, "apply-9only.jsonl", [meta()])
            write(tmp, "rollout-2026-09-10T00-00-00-abcd.jsonl", [meta()])
            files, bounded, skipped = w.gather_files(
                "codex", w.agent_homes(), 0, 64, 32, explicit_dir=tmp)
            names = [p.name for _, p in files]
            self.assertIn("rollout-2026-09-10T00-00-00-abcd.jsonl", names)
            self.assertNotIn("apply-9only.jsonl", names)
            self.assertFalse(bounded)
            self.assertEqual(skipped, [])

    def test_gather_home_still_scans_oversize_and_reports_p13(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            sess = home / "sessions" / "2026" / "09" / "10"
            sess.mkdir(parents=True)
            big = sess / "rollout-2026-09-10T00-00-00-big.jsonl"
            big.write_bytes(b"x" * (2 * 1048576))
            small = sess / "rollout-2026-09-10T00-00-00-small.jsonl"
            small.write_text(meta() + "\n", encoding="utf-8")
            files, bounded, skipped = w.gather_files(
                "codex", {"codex": home, "grok": home, "devin": home,
                          "cline": home},
                0, 64, 1)
            names = [p.name for _, p in files]
            self.assertIn("rollout-2026-09-10T00-00-00-small.jsonl", names)
            self.assertIn("rollout-2026-09-10T00-00-00-big.jsonl", names)
            self.assertEqual(len(skipped), 1)
            self.assertEqual(skipped[0]["reason"], "oversize")


class Advise(unittest.TestCase):
    def test_advise_auto_skips_without_auto(self):
        out = w.advise_auto_findings({"sessions": [{"findings": []}]})
        self.assertEqual("skipped", out["status"])


if __name__ == "__main__":
    unittest.main()
