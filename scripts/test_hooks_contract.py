#!/usr/bin/env python3
"""Hook stdout/stderr/exit contract tests (DEMO1-HOOKS-HYGIENE-20260928-R1).

Runs each wired hook entrypoint from .codex/hooks.json and .devin/hooks.v1.json
with synthetic stdin events, then asserts the host-visible contract:

- exit code is 0 unless the hook is making a real policy deny (exit 2)
- stdout is empty or exactly one JSON object whose fields are supported by the
  target host (Codex strict-parses PreToolUse output; decision:"allow" /
  "approve" marks the run failed even on exit 0)
- stderr/trace stays out of stdout; no BOM, traceback, or second JSON object
- $-interpolating -Command payloads are forbidden in commandWindows (the outer
  shell eats the variables and the hook executes mangled text)

Run: python -B scripts/test_hooks_contract.py   (Windows host shell: powershell)
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PWSH = os.environ.get("HOOK_PWSH") or "powershell"
TIMEOUT = 60

CODEX_PRE_ALLOWED = {"decision", "reason", "hookSpecificOutput",
                     "systemMessage", "continue", "suppressOutput"}
CODEX_PRE_VALUES = {"block"}
DEVIN_PRE_VALUES = {"approve", "block"}


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def iter_hooks(cfg):
    for event, groups in (cfg.get("hooks") or cfg or {}).items():
        for gi, group in enumerate(groups or []):
            for hi, hook in enumerate(group.get("hooks") or []):
                yield event, gi, hi, hook


def command_for(hook):
    return hook.get("commandWindows") or hook.get("command")


def run_hook(command, stdin_text, timeout=TIMEOUT):
    proc = subprocess.run(
        ["cmd", "/c", command], input=stdin_text.encode("utf-8"),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        cwd=str(ROOT), timeout=timeout)
    return proc.returncode, proc.stdout, proc.stderr


def parse_stdout(out: bytes):
    """Return (kind, payload): kind in empty|object|invalid."""
    if not out or not out.strip():
        return "empty", None
    if out.startswith(b"\xef\xbb\xbf"):
        return "invalid", "bom-prefix"
    try:
        text = out.decode("utf-8")
    except UnicodeDecodeError:
        return "invalid", "not-utf8"
    try:
        payload = json.loads(text)
    except ValueError:
        return "invalid", "not-json"
    if not isinstance(payload, dict):
        return "invalid", "not-object"
    stripped = text.strip()
    if not (stripped.startswith("{") and stripped.endswith("}")):
        return "invalid", "extra-text"
    return "object", payload


class CommandShape(unittest.TestCase):
    def test_no_dollar_interpolation_in_command_windows(self):
        # powershell -Command strings are re-interpolated by the host's outer
        # command layer; any $var is eaten before the hook sees it.
        for cfg_path in (ROOT / ".codex" / "hooks.json",
                         ROOT / ".devin" / "hooks.v1.json"):
            cfg = load(cfg_path)
            for event, gi, hi, hook in iter_hooks(cfg):
                cmd = command_for(hook) or ""
                if "-Command" in cmd or "-c " in cmd.split("powershell")[-1]:
                    self.assertNotIn("$", cmd,
                                     "%s %s[%s][%s] interpolates away $vars"
                                     % (cfg_path.name, event, gi, hi))


class CodexContract(unittest.TestCase):
    """Each Codex-wired hook gets exit+stdout checked against the strict schema."""

    def setUp(self):
        self.cfg = load(ROOT / ".codex" / "hooks.json")
        self.by_msg = {}
        for event, gi, hi, hook in iter_hooks(self.cfg):
            self.by_msg[hook.get("statusMessage") or "%s:%s:%s"
                        % (event, gi, hi)] = (event, command_for(hook))

    def hook_run(self, msg, event):
        self.assertIn(msg, self.by_msg)
        return run_hook(self.by_msg[msg][1], json.dumps(event))

    def test_pre_allow_empty_stdout(self):
        event = {"hook_event_name": "PreToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "Get-Content AGENTS.md"},
                 "tool_use_id": "ct-a", "session_id": "ct"}
        code, out, err = self.hook_run("Checking path/retry work guard", event)
        self.assertEqual(0, code)
        kind, payload = parse_stdout(out)
        self.assertEqual("empty", kind,
                         "codex allow must not print JSON: %r" % out[:200])

    def test_pre_block_documented_shape(self):
        event = {"hook_event_name": "PreToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "Get-Content main/java/"
                                           "DefinitelyMissingZz.java"},
                 "tool_use_id": "ct-b", "session_id": "ct"}
        code, out, err = self.hook_run("Checking path/retry work guard", event)
        if code == 2:
            kind, payload = parse_stdout(out)
            self.assertEqual("object", kind)
            if "decision" in payload:
                self.assertIn(payload["decision"], CODEX_PRE_VALUES)
            if "hookSpecificOutput" in payload:
                self.assertEqual("deny", payload["hookSpecificOutput"]
                                 .get("permissionDecision"))
            self.assertTrue(err.strip(), "block should name a reason")
        else:
            # Path check may pass when the verifier root differs; then the
            # stdout must still be contract-clean (empty on allow).
            self.assertEqual(0, code)
            kind, _ = parse_stdout(out)
            self.assertEqual("empty", kind)

    def test_post_tool_use_silent(self):
        event = {"hook_event_name": "PostToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "Get-Content AGENTS.md"},
                 "tool_response": {"success": True, "output": "ok"},
                 "tool_use_id": "ct-c", "session_id": "ct"}
        code, out, err = self.hook_run("Recording path/retry work guard", event)
        self.assertEqual(0, code)
        kind, _ = parse_stdout(out)
        self.assertEqual("empty", kind)

    def test_malformed_stdin_soft_fails(self):
        code, out, err = run_hook(
            self.by_msg["Checking path/retry work guard"][1], "{not json")
        self.assertEqual(0, code)
        kind, _ = parse_stdout(out)
        self.assertEqual("empty", kind)
        self.assertNotIn(b"Traceback", err)

    def test_user_prompt_capabilities(self):
        event = {"hook_event_name": "UserPromptSubmit",
                 "prompt": "hello", "session_id": "ct"}
        code, out, err = self.hook_run(
            "Checking project resource capabilities", event)
        self.assertEqual(0, code)
        kind, payload = parse_stdout(out)
        if kind == "object":
            hso = payload.get("hookSpecificOutput", {})
            self.assertEqual("UserPromptSubmit", hso.get("hookEventName"))

    def test_user_prompt_source_triage(self):
        for prompt in ("hello world", "please edit main/java/Foo.java"):
            event = {"hook_event_name": "UserPromptSubmit",
                     "prompt": prompt, "session_id": "ct"}
            code, out, err = self.hook_run(
                "Checking source-edit preflight", event)
            self.assertEqual(0, code, "%r: exit=%s err=%r" % (prompt, code, err))
            kind, payload = parse_stdout(out)
            if kind == "object":
                self.assertEqual("UserPromptSubmit", payload.get(
                    "hookSpecificOutput", {}).get("hookEventName"))

    def test_pre_edit_guard_clean_allow(self):
        event = {"hook_event_name": "PreToolUse", "tool_name": "write",
                 "tool_input": {"file_path": "docs/hooks-contract-test.md"},
                 "tool_use_id": "ct-d", "session_id": "ct"}
        code, out, err = self.hook_run(
            "Checking source-edit lease conflicts", event)
        self.assertIn(code, (0, 2))
        kind, payload = parse_stdout(out)
        if kind == "object":
            bad = set(payload) - CODEX_PRE_ALLOWED
            self.assertFalse(bad, "unsupported keys: %s" % bad)
            if "decision" in payload:
                self.assertIn(payload["decision"], CODEX_PRE_VALUES)


class DevinContract(unittest.TestCase):
    """Devin host: decision approve|block; allow never emitted as 'allow'."""

    def setUp(self):
        self.cfg = load(ROOT / ".devin" / "hooks.v1.json")

    def _cmd(self, matcher_event, matcher):
        for event, gi, hi, hook in iter_hooks(self.cfg):
            if event != matcher_event:
                continue
            if group_matcher := (self.cfg[matcher_event][gi].get("matcher")):
                if matcher not in group_matcher:
                    continue
            return command_for(hook)
        return None

    def test_exec_pre_allow_approve(self):
        cmd = self._cmd("PreToolUse", "exec")
        self.assertTrue(cmd)
        event = {"hook_event_name": "PreToolUse", "tool_name": "exec",
                 "tool_input": {"command": "Get-Content AGENTS.md"},
                 "session_id": "dt", "prompt_id": "pt"}
        code, out, err = run_hook(cmd, json.dumps(event))
        self.assertEqual(0, code)
        kind, payload = parse_stdout(out)
        if kind == "object":
            self.assertIn(payload.get("decision"), DEVIN_PRE_VALUES)

    def test_exec_pre_block(self):
        cmd = self._cmd("PreToolUse", "exec")
        event = {"hook_event_name": "PreToolUse", "tool_name": "exec",
                 "tool_input": {"command": "Get-Content main/java/"
                                           "DefinitelyMissingZz.java"},
                 "session_id": "dt", "prompt_id": "pt"}
        code, out, err = run_hook(cmd, json.dumps(event))
        self.assertIn(code, (0, 2))
        if code == 2:
            kind, payload = parse_stdout(out)
            self.assertEqual("object", kind)
            self.assertEqual("block", payload.get("decision"))


if __name__ == "__main__":
    unittest.main()
