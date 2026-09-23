#!/usr/bin/env python3
"""Contract tests for agent_work_guard: allow create/search/recovery, per-agent I/O."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_work_guard as g  # noqa: E402


class PathVerdict(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "main" / "java").mkdir(parents=True)
        (self.root / "main" / "java" / "Foo.java").write_text("class Foo {}\n")
        (self.root / "src" / "test" / "java").mkdir(parents=True)
        (self.root / "src" / "test" / "java" / "FooTest.java").write_text("class T {}\n")
        (self.root / "AGENTS.md").write_text("# x\n")
        (self.root / "gradlew.bat").write_text("@echo off\n")
        (self.root / "settings.gradle.kts").write_text("//\n")

    def tearDown(self):
        self.tmp.cleanup()

    def test_existing_src_test_read_allowed(self):
        v = g.verdict_for_cmd(
            self.root, "Get-Content src/test/java/FooTest.java")
        self.assertEqual("allow", v["decision"], v)

    def test_create_missing_allowed(self):
        v = g.verdict_for_cmd(
            self.root, "New-Item -ItemType File main/java/NewFile.java")
        self.assertEqual("allow", v["decision"], v)
        self.assertEqual("create", v["intent"])

    def test_search_missing_allowed(self):
        v = g.verdict_for_cmd(self.root, "rg -n Foo main/java/Missing.java")
        self.assertEqual("allow", v["decision"])
        self.assertEqual("search", v["intent"])

    def test_outside_read_allowed(self):
        sibling = self.root.parent / "quarantine.jsonl"
        sibling.write_text("{}\n")
        v = g.verdict_for_cmd(
            self.root, "Get-Content -LiteralPath '%s'" % sibling)
        # no project-relative file extracted from abs path outside prefixes
        self.assertEqual("allow", v["decision"])

    def test_missing_read_blocked(self):
        v = g.verdict_for_cmd(
            self.root, "Get-Content -Raw main/java/Missing.java")
        self.assertEqual("block", v["decision"])
        self.assertEqual("path-does-not-exist", v["reason"])
        self.assertIn("create", v["next"])

    def test_wrong_src_main_read_blocked(self):
        v = g.verdict_for_cmd(
            self.root, "Get-Content src/main/java/Foo.java")
        self.assertEqual("block", v["decision"])
        self.assertEqual("doubled-src-prefix", v["reason"])


class RetryRecovery(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "main" / "java").mkdir(parents=True)
        self.ledger = str(self.root / "ledger.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_same_missing_retry_blocks_then_create_unlocks(self):
        rel = "main\\java\\Created.java"
        cmd_read = "Get-Content main/java/Created.java"
        for i in range(3):
            g.record(self.root, rel, "fail", "missing", self.ledger,
                     agent="grok", actor="root", call_id="c%d" % i)
        v = g.verdict_for_cmd(self.root, cmd_read, self.ledger,
                              agent="grok", actor="root")
        self.assertEqual("block", v["decision"])
        (self.root / "main" / "java" / "Created.java").write_text("ok\n")
        v2 = g.verdict_for_cmd(self.root, cmd_read, self.ledger,
                               agent="grok", actor="root")
        self.assertEqual("allow", v2["decision"], v2)

    def test_agents_do_not_share_fail_counts(self):
        rel = "main\\java\\Foo.java"
        (self.root / "main" / "java" / "Foo.java").write_text("x\n")
        for i in range(3):
            g.record(self.root, rel, "fail", "parser", self.ledger,
                     agent="codex", actor="root", call_id="x%d" % i)
        v_codex = g.verdict_for_cmd(
            self.root, "Get-Content main/java/Foo.java", self.ledger,
            agent="codex", actor="root")
        v_grok = g.verdict_for_cmd(
            self.root, "Get-Content main/java/Foo.java", self.ledger,
            agent="grok", actor="root")
        self.assertEqual("block", v_codex["decision"])
        self.assertEqual("allow", v_grok["decision"])

    def test_duplicate_call_id_not_counted_twice(self):
        rel = "main\\java\\Foo.java"
        (self.root / "main" / "java" / "Foo.java").write_text("x\n")
        a = g.record(self.root, rel, "fail", "parser", self.ledger,
                     agent="devin", actor="root", call_id="same")
        b = g.record(self.root, rel, "fail", "parser", self.ledger,
                     agent="devin", actor="root", call_id="same")
        self.assertEqual(1, a["fails"])
        self.assertFalse(b["recorded"])
        self.assertEqual(1, b["fails"])


class Adapters(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "main" / "java").mkdir(parents=True)
        self.ledger = str(self.root / "ledger.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_grok_pre_emits_deny(self):
        event = {"hookEventName": "PreToolUse", "toolName": "run_terminal_command",
                 "toolInput": {"command": "Get-Content main/java/NoSuch.java"},
                 "sessionId": "g1"}
        payload, code = g.hook(self.root, event, self.ledger)
        self.assertEqual(2, code)
        self.assertEqual("deny", payload["decision"])

    def test_codex_pre_emits_permission_deny(self):
        event = {"hook_event_name": "PreToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "Get-Content main/java/NoSuch.java"},
                 "tool_use_id": "tu1", "session_id": "s1"}
        payload, code = g.hook(self.root, event, self.ledger)
        self.assertEqual(2, code)
        self.assertEqual("block", payload["decision"])
        self.assertEqual(
            "deny",
            payload["hookSpecificOutput"]["permissionDecision"])

    def test_devin_pre_emits_block(self):
        event = {"hook_event_name": "PreToolUse", "tool_name": "exec",
                 "tool_input": {"command": "Get-Content main/java/NoSuch.java"},
                 "session_id": "d1", "prompt_id": "p1"}
        payload, code = g.hook(self.root, event, self.ledger)
        self.assertEqual(2, code)
        self.assertEqual("block", payload["decision"])

    def test_codex_post_uses_tool_response(self):
        (self.root / "main" / "java" / "Foo.java").write_text("x\n")
        event = {"hook_event_name": "PostToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "Get-Content main/java/Foo.java"},
                 "tool_response": {"output": "ParserError: Variable reference"},
                 "tool_use_id": "tu2"}
        payload, code = g.hook(self.root, event, self.ledger)
        self.assertEqual(0, code)
        self.assertEqual("fail", payload["result"])

    def test_devin_post_uses_success_false(self):
        event = {"hook_event_name": "PostToolUse", "tool_name": "exec",
                 "tool_input": {"command": "Get-Content main/java/NoSuch.java"},
                 "tool_response": {"success": False, "output": "",
                                   "error": "Cannot find path"}}
        payload, code = g.hook(self.root, event, self.ledger)
        self.assertEqual(0, code)
        self.assertEqual("fail", payload["result"])


class AdviseDoesNotPoison(unittest.TestCase):
    def test_advise_does_not_write_ledger(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "main" / "java").mkdir(parents=True)
            (root / "main" / "java" / "Foo.java").write_text("x\n")
            ledger = str(root / "ledger.json")
            findings = [{"pattern": "P12", "evidence": {
                "files": {"main\\java\\Foo.java": 3}}}]
            out = g.advise(root, findings, ledger)
            self.assertFalse(out["recordedToLedger"])
            self.assertFalse(Path(ledger).exists())


class SameFlowRetryRecovery(unittest.TestCase):
    """One flow: miss-read fail once, no double-count, create/search allowed, recover."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "main" / "java").mkdir(parents=True)
        self.ledger = str(self.root / "ledger.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_fail_once_create_search_then_read_recovers(self):
        rel = "main\\java\\Flow.java"
        read = "Get-Content main/java/Flow.java"
        post = {
            "hookEventName": "post_tool_use",
            "hook_event_name": "PostToolUse",
            "toolName": "run_terminal_command",
            "toolInput": {"command": read},
            "toolUseId": "call-1",
            "toolResult": {
                "type": "Bash",
                "exit_code": 1,
                "output_for_prompt": "Get-Content: Cannot find path 'main\\java\\Flow.java'",
            },
        }
        p1, c1 = g.hook(self.root, post, self.ledger)
        self.assertEqual(0, c1)
        self.assertEqual("fail", p1["result"])
        self.assertEqual(1, p1["recorded"][0]["fails"])
        # same toolUseId must not increment
        p1b, _ = g.hook(self.root, post, self.ledger)
        self.assertEqual(1, p1b["recorded"][0]["fails"])
        self.assertFalse(p1b["recorded"][0]["recorded"])
        create = {"hookEventName": "pre_tool_use", "toolName": "run_terminal_command",
                  "toolInput": {"command": "New-Item -ItemType File main/java/Flow.java"}}
        pc, cc = g.hook(self.root, create, self.ledger)
        self.assertEqual(0, cc)
        self.assertEqual("allow", pc["decision"])
        search = {"hookEventName": "pre_tool_use", "toolName": "run_terminal_command",
                  "toolInput": {"command": "rg -n class main/java/Flow.java"}}
        ps, cs = g.hook(self.root, search, self.ledger)
        self.assertEqual(0, cs)
        self.assertEqual("allow", ps["decision"])
        (self.root / "main" / "java" / "Flow.java").write_text("class Flow {}\n")
        pre = {"hookEventName": "pre_tool_use", "toolName": "run_terminal_command",
               "toolInput": {"command": read}, "toolUseId": "call-2"}
        pr, cr = g.hook(self.root, pre, self.ledger)
        self.assertEqual(0, cr)
        self.assertEqual("allow", pr["decision"])


class Status(unittest.TestCase):
    def test_status_shape(self):
        row = g.hook_status(Path("."))
        self.assertEqual("awx.agent-work-guard.v2", row["schemaVersion"])
        self.assertEqual("Bash", row["officialShellToolName"]["codex"])
        self.assertEqual("exec", row["officialShellToolName"]["devin"])
        self.assertIn("grok", row["hooks"])


if __name__ == "__main__":
    unittest.main()
