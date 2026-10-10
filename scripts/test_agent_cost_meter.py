#!/usr/bin/env python3
"""Contract tests for agent_cost_meter (synthetic fixtures only)."""
from pathlib import Path
import json
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_cost_meter as m  # noqa: E402


def _rec(ts, payload):
    return json.dumps({"timestamp": ts, "ordinal": 0,
                       "type": "event_msg", "payload": payload})


def _cmd_item(i, cmd, secs, nanos, out, status="completed", exit_code=0):
    return {
        "type": "item_completed",
        "item": {
            "type": "CommandExecution",
            "id": "exec-%d" % i,
            "command": ["pwsh.exe", "-Command", cmd],
            "status": status,
            "exit_code": exit_code,
            "duration": (None if secs is None
                         else {"secs": secs, "nanos": nanos}),
            "aggregated_output": out,
        },
    }


class SessionScan(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sdir = Path(self.tmp.name) / "sessions" / "2026" / "10" / "10"
        self.sdir.mkdir(parents=True)
        f = self.sdir / "rollout-2026-10-10T01-00-00-aaa.jsonl"
        lines = [
            _rec("2026-10-10T01:00:00Z", {"type": "task_started"}),
            _rec("2026-10-10T01:00:01Z", _cmd_item(
                1, "Get-Content .agents/skills/demo1-x/SKILL.md",
                0, 500_000_000, "skill-body")),
            _rec("2026-10-10T01:00:02Z", _cmd_item(
                2, "Get-Content AGENTS.md; Get-Content scripts/codex_work_checkpoint.py",
                1, 0, "a" * 2000)),
            _rec("2026-10-10T01:00:03Z", _cmd_item(
                3, "rg foo scripts", None, None, "", status="failed",
                exit_code=1)),
            _rec("2026-10-10T02:30:00Z", _cmd_item(
                4, "Get-Content SKILL.md", 3, 0, "b" * 100)),
        ]
        f.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_counts_and_targets(self):
        s = m.scan_sessions(self.sdir.parents[1],
                            m.parse_ts("2026-10-10T00:00:00Z"),
                            m.parse_ts("2026-10-10T02:00:00Z"), 10)
        self.assertEqual(1, s["sessionsInWindow"])
        self.assertEqual(3, s["toolCalls"]["commandExecutions"])
        self.assertEqual(3, s["toolCalls"]["itemCompletedByType"]
                         ["CommandExecution"])
        # SKILL.md 버킷 집계
        tg = {t["target"]: t for t in s["targetsTop"]}
        self.assertIn("SKILL.MD", tg)
        self.assertEqual(1, tg["SKILL.MD"]["calls"])
        self.assertIn("AGENTS.MD", tg)
        self.assertIn("scripts/codex_work_checkpoint.py", tg)
        # 실행시간: duration 없는 호출은 missing으로
        self.assertEqual(2, s["commandTimingMs"]["n"])
        self.assertEqual(1, s["commandTimingMs"]["missingDuration"])
        self.assertAlmostEqual(1500.0, s["commandTimingMs"]["sumMs"])

    def test_since_until_window(self):
        s = m.scan_sessions(self.sdir.parents[1],
                            m.parse_ts("2026-10-10T02:00:00Z"), None, 10)
        self.assertEqual(1, s["toolCalls"]["commandExecutions"])

    def test_p95_contract(self):
        self.assertEqual(10, m.p95_nearest_rank([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]))
        self.assertIsNone(m.p95_nearest_rank([]))
        self.assertEqual("NOT_AVAILABLE", m.stats_block([])["p95Ms"])


class HookTraceScan(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.trace = Path(self.tmp.name) / "hook-trace.jsonl"
        rows = [
            {"at": "2026-10-10T01:00:00Z", "phase": "enter",
             "event": "PreToolUse", "toolName": "Bash",
             "toolUseId": "id-1", "decision": "", "exit": None,
             "elapsedMs": None},
            {"at": "2026-10-10T01:00:00Z", "phase": "exit",
             "event": "PreToolUse", "toolName": "Bash",
             "toolUseId": "id-1", "decision": "block", "exit": 2,
             "elapsedMs": 7},
            {"at": "2026-10-10T01:00:00Z", "phase": "exit",
             "wrapper": "ps1", "exit": 2, "elapsedMs": 139},
            {"at": "2026-10-10T01:01:00Z", "phase": "exit",
             "event": "PostToolUse", "toolName": "Bash",
             "toolUseId": "id-2", "decision": "allow", "exit": 0,
             "elapsedMs": 1},
            {"at": "2026-10-10T01:01:00Z", "phase": "exit",
             "wrapper": "ps1", "exit": 0, "elapsedMs": 131},
            # 중복 행(dedup 검증)
            {"at": "2026-10-10T01:01:00Z", "phase": "exit",
             "event": "PostToolUse", "toolName": "Bash",
             "toolUseId": "id-2", "decision": "allow", "exit": 0,
             "elapsedMs": 1},
        ]
        self.trace.write_text("\n".join(json.dumps(r) for r in rows),
                              encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_block_and_dedup(self):
        h = m.scan_hook_trace(self.trace, None, None)
        self.assertEqual(1, h["blockCount"])
        self.assertEqual("id-1", h["blocks"][0]["toolUseId"])
        self.assertEqual(12, len(h["blocks"][0]["toolUseIdSha12"]))
        # python dedup: id-1 exit + id-2 exit = 2건 중복 제거 후
        self.assertEqual(2, h["pythonElapsed"]["n"])
        self.assertEqual(2, h["psWrapperElapsed"]["n"])
        self.assertEqual(270.0, h["psWrapperElapsed"]["sumMs"])


class Compare(unittest.TestCase):
    def test_delta(self):
        b = {"sessions": {"sessionsInWindow": 5,
                          "toolCalls": {"commandExecutions": 100},
                          "commandTimingMs": {"sumMs": 10.0},
                          "targetsTop": [{"target": "SKILL.MD", "calls": 10,
                                          "outputMB": 1.0}]},
             "hookTrace": {"pythonElapsed": {"sumMs": 5.0, "n": 2},
                           "psWrapperElapsed": {"sumMs": 7.0},
                           "blockCount": 1}}
        a = {"sessions": {"sessionsInWindow": 6,
                          "toolCalls": {"commandExecutions": 80},
                          "commandTimingMs": {"sumMs": 8.0},
                          "targetsTop": [{"target": "SKILL.MD", "calls": 4,
                                          "outputMB": 0.4}]},
             "hookTrace": {"pythonElapsed": {"sumMs": 3.0, "n": 2},
                           "psWrapperElapsed": {"sumMs": 6.0},
                           "blockCount": 0}}
        c = m.compare(b, a, 5)
        calls = [r for r in c["metrics"] if r["metric"] == "commandExecutions"][0]
        self.assertEqual(-20, calls["delta"])
        self.assertEqual("SKILL.MD", c["targets"][0]["target"])
        self.assertEqual(-6, c["targets"][0]["callsDelta"])


if __name__ == "__main__":
    unittest.main()
