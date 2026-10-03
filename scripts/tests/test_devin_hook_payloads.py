"""Real-shape payloads for the Devin hooks. Does not change deny policy.

Allow, deny, and malformed cases call the live scripts. A deny stays exit 2.
A missing relative -File from the home directory stays non-zero.
"""
from __future__ import annotations

import json
import subprocess
import unittest
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HOME = Path.home()
SRC = "C:/AbandonWare/demo-1/demo-1/src"
HOOKS = ROOT / ".devin" / "hooks.v1.json"
PRE_EDIT = ROOT / "scripts" / "devin_pre_edit_guard.ps1"
WORK_GUARD = ROOT / "scripts" / "agent_work_guard.ps1"
CAPABILITIES = ROOT / ".codex" / "hooks" / "project_capabilities_hook.ps1"
TRIAGE = ROOT / ".codex" / "hooks" / "source_edit_triage.ps1"


def _run(script: Path, payload: str, cwd: Path, timeout: int = 20):
    proc = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(script),
        ],
        input=payload.encode("utf-8"),
        cwd=str(cwd),
        capture_output=True,
        timeout=timeout,
    )
    out = proc.stdout.decode("utf-8", "replace")
    err = proc.stderr.decode("utf-8", "replace")
    return proc.returncode, out, err


def _live_lease_paths():
    locks = ROOT / "__patch_drop__" / "source-edit-locks"
    found = []
    now = datetime.now(timezone.utc)
    if not locks.is_dir():
        return found
    for lease_path in locks.glob("*/lease.json"):
        try:
            data = json.loads(lease_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        exp_raw = data.get("expiresAtUtc") or ""
        try:
            exp = datetime.fromisoformat(str(exp_raw).replace("Z", "+00:00"))
        except ValueError:
            exp = None
        if exp is not None and exp.tzinfo is None:
            exp = exp.replace(tzinfo=timezone.utc)
        if exp is not None and exp < now:
            continue
        targets = data.get("targetPaths") or []
        if isinstance(targets, dict):
            targets = list(targets.keys())
        for item in targets:
            if isinstance(item, dict):
                item = item.get("path") or ""
            text = str(item).replace("\\", "/").lstrip("./")
            if text and not text.startswith("__"):
                found.append(text)
    return found


class ConfiguredCommands(unittest.TestCase):
    def test_hook_files_are_absolute(self):
        data = json.loads(HOOKS.read_text(encoding="utf-8"))
        commands = []

        def walk(node):
            if isinstance(node, dict):
                if "command" in node:
                    commands.append(node["command"])
                for value in node.values():
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)

        walk(data)
        self.assertGreaterEqual(len(commands), 4)
        for command in commands:
            self.assertIn(SRC, command.replace("\\", "/"))
            self.assertNotRegex(command, r"-File\s+(?:scripts|\.?\.?codex)[/\\]")


class ExecHook(unittest.TestCase):
    def test_allow_existing_file(self):
        payload = json.dumps({
            "hook_event_name": "PreToolUse",
            "tool_name": "exec",
            "tool_input": {"command": "Get-Content -LiteralPath AGENTS.md -TotalCount 1"},
        })
        code, out, _err = _run(WORK_GUARD, payload, HOME)
        self.assertEqual(code, 0)
        self.assertNotIn('"decision": "block"', out)
        self.assertNotIn('"decision":"block"', out)

    def test_deny_missing_path(self):
        payload = json.dumps({
            "hook_event_name": "PreToolUse",
            "tool_name": "exec",
            "tool_input": {
                "command": "Get-Content -LiteralPath main/java/DefinitelyMissingZz.java"
            },
        })
        code, out, _err = _run(WORK_GUARD, payload, HOME)
        self.assertEqual(code, 2)
        self.assertIn("block", out)

    def test_malformed_fails_open(self):
        code, _out, err = _run(WORK_GUARD, "{not-json", HOME)
        self.assertEqual(code, 0)
        self.assertIn("hook-json-unreadable", err)

    def test_post_exec_allow_does_not_block(self):
        payload = json.dumps({
            "hook_event_name": "PostToolUse",
            "tool_name": "exec",
            "tool_input": {"command": "Get-Content -LiteralPath AGENTS.md -TotalCount 1"},
        })
        code, out, _err = _run(WORK_GUARD, payload, HOME)
        self.assertEqual(code, 0)
        self.assertNotIn("block", out)


class EditHook(unittest.TestCase):
    def test_allow_unleased_file(self):
        leased = set(_live_lease_paths())
        target = "AGENTS.md"
        self.assertNotIn(target, leased)
        payload = json.dumps({
            "tool_name": "edit",
            "tool_input": {"file_path": target},
        })
        code, out, _err = _run(PRE_EDIT, payload, HOME)
        self.assertEqual(code, 0, out)
        self.assertNotIn("block", out)

    def test_deny_live_lease_target(self):
        leased = _live_lease_paths()
        target = ""
        for rel in leased:
            if rel.startswith("scripts/") or rel.startswith("docs/"):
                target = rel
                break
        if not target and leased:
            target = leased[0]
        self.assertTrue(target, "a live lease target is required for the deny payload")
        payload = json.dumps({
            "tool_name": "edit",
            "tool_input": {"file_path": target},
        })
        code, out, err = _run(PRE_EDIT, payload, HOME)
        self.assertEqual(code, 2, err or out)
        self.assertIn("block", out)

    def test_malformed_is_guard_error(self):
        code, _out, err = _run(PRE_EDIT, "{not-json", HOME)
        self.assertEqual(code, 1)
        self.assertIn("devin-pre-edit-guard-error", err)

    def test_relative_file_from_home_is_nonzero(self):
        proc = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                r"scripts\devin_pre_edit_guard.ps1",
            ],
            input=b'{"tool_name":"edit","tool_input":{"file_path":"AGENTS.md"}}',
            cwd=str(HOME),
            capture_output=True,
            timeout=20,
        )
        self.assertNotEqual(proc.returncode, 0)


class UserPromptHooks(unittest.TestCase):
    def test_capabilities_exits_zero(self):
        code, _out, _err = _run(CAPABILITIES, '{"prompt":"status"}', HOME, timeout=45)
        self.assertEqual(code, 0)

    def test_triage_edit_prompt_exits_zero(self):
        payload = json.dumps({"prompt": "edit the project readme wording"})
        code, out, _err = _run(TRIAGE, payload, HOME)
        self.assertEqual(code, 0)
        self.assertNotIn('"decision":"block"', out)

    def test_triage_malformed_exits_zero(self):
        code, _out, _err = _run(TRIAGE, "{not-json", HOME)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
