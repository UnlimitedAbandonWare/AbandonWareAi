#!/usr/bin/env python3
"""Contract tests for hook_block_review: read-only classifier + verdict rules."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hook_block_review as h  # noqa: E402


class ReadOnlyClassifier(unittest.TestCase):
    """쓰기가 섞인 명령은 반드시 거부 — 지시서 A4 핵심."""

    def test_pure_reads_allowed(self):
        for cmd in [
            "Get-Content AGENTS.md",
            "Get-ChildItem .agents/skills | Select-Object Name",
            "rg -n foo scripts/main",
            "git status; git diff --stat",
            "python --version",
            "$env:COMPUTERNAME; Get-Content scripts/x.py | Select-Object -First 5",
            "cat docs/README.md",
            "Test-Path var/x; Get-FileHash AGENTS.md",
        ]:
            v = h.is_read_only_command(cmd)
            self.assertEqual("read-only", v["verdict"], cmd)

    def test_write_mixed_rejected(self):
        for cmd in [
            "Get-Content a.md > out.txt",
            "rg foo | Set-Content out.txt",
            "Get-Content a.md; Set-Content b.md x",
            "New-Item -ItemType Directory -Force var/x",
            "git add -A",
            "git commit -m x",
            "git push origin main",
            "python scripts/x.py",
            "python -c \"print(1)\"",
            ".\\gradlew.bat test",
            "powershell -File x.ps1",
            "node scripts/guard.js",
            "Remove-Item var/x -Recurse",
            "Copy-Item a b",
            "echo x | Out-File y.txt",
            "git log; Remove-Item t.txt",
            "npm install",
            "iex 'danger'",
        ]:
            v = h.is_read_only_command(cmd)
            self.assertEqual("write-mixed", v["verdict"], cmd)

    def test_unknown_not_readonly(self):
        for cmd in [
            "some_random_tool --flag",
            "git worktree list",        # 비표준 하위명령 → unknown
            "curl http://x",            # 네트워크 → unknown
        ]:
            v = h.is_read_only_command(cmd)
            self.assertEqual("unknown", v["verdict"], cmd)


def _rec(ts, ordinal, item):
    import json
    return json.dumps({"timestamp": ts, "ordinal": ordinal,
                       "type": "event_msg",
                       "payload": {"type": "item_completed", "item": item}})


def _cmd_item(i, cmd, status="completed", exit_code=0, out=""):
    return {"type": "CommandExecution", "id": i,
            "command": ["pwsh.exe", "-Command", cmd],
            "status": status, "exit_code": exit_code,
            "aggregated_output": out}


class VerdictRules(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        for d in ("main/java", "src/test/java", "scripts", "var", ".agents"):
            (self.root / d).mkdir(parents=True, exist_ok=True)
        (self.root / "AGENTS.md").write_text("# x\n")
        (self.root / "gradlew.bat").write_text("@echo off\n")
        (self.root / "settings.gradle.kts").write_text("//\n")
        import shutil
        shutil.copyfile(
            Path(__file__).with_name("agent_work_guard.py"),
            self.root / "scripts" / "agent_work_guard.py")
        self.sessions = self.root / "sessions"
        self.sessions.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_read_true_block(self):
        # 존재하지 않는 경로 읽기 → replay 도 block → true_block
        ev = {"at": "2026-10-10T01:00:00Z", "toolUseId": "exec-1",
              "toolName": "Bash", "event": "PreToolUse", "source": "t"}
        sess = self.sessions / "s1.jsonl"
        sess.write_text("\n".join([
            _rec("2026-10-10T01:00:01Z", 5, _cmd_item(
                "exec-1", "Get-Content scripts/nope_missing.py",
                status="failed", exit_code=1,
                out="Cannot find path 'scripts/nope_missing.py'")),
        ]) + "\n", encoding="utf-8")
        import shutil
        ledger = self.root / "ledger.json"
        ledger.write_text('{"schemaVersion":"x","entries":{}}')
        rows = h.review(self.root, self.root / "scripts" / "agent_work_guard.py",
                        self.sessions, [ev], ledger, None, None)
        self.assertEqual("true_block", rows[0]["verdict"], rows[0])

    def test_unknown_when_no_item(self):
        ev = {"at": "2026-10-10T01:00:00Z", "toolUseId": "ghost",
              "toolName": "Bash", "event": "PreToolUse", "source": "t"}
        ledger = self.root / "ledger.json"
        ledger.write_text('{"schemaVersion":"x","entries":{}}')
        rows = h.review(self.root, self.root / "scripts" / "agent_work_guard.py",
                        self.sessions, [ev], ledger, None, None)
        self.assertEqual("unknown", rows[0]["verdict"])

    def test_command_shape_no_raw(self):
        sh = h.command_shape("Get-Content scripts/x.py; Get-Content scripts/y.py")
        self.assertEqual("read", sh["intent"])
        self.assertEqual(2, sh["fileRefs"])
        self.assertNotIn("scripts/x.py", str(sh["fileSha12"]))


if __name__ == "__main__":
    unittest.main()
