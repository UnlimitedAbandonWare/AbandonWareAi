#!/usr/bin/env python3
"""Unit tests for scripts/orchestra_cadence_barrier.py and
scripts/subagent_tool_dispatcher.py — 100% local fixtures, no network
(the only socket use is a loopback listener bound inside the test).

Run: python -B scripts/test_subagent_cadence.py   (exit 0 = PASS)
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import orchestra_cadence_barrier as barrier  # noqa: E402
import subagent_tool_dispatcher as dispatcher  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


def _utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat()


def run_main(main_fn, argv):
    """In-process CLI call -> (exit_code, stdout_text)."""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = main_fn(argv)
    return code, buf.getvalue()


def run_subprocess(script: str, argv, cwd=ROOT):
    """Real `python -B` CLI call -> CompletedProcess."""
    return subprocess.run(
        [sys.executable, "-B", str(ROOT / "scripts" / script)] + argv,
        cwd=str(cwd), capture_output=True, text=True, timeout=120)


def make_lock(root: Path, topic: str, **lease) -> Path:
    d = root / "__patch_drop__" / "source-edit-locks" / (topic + ".lock")
    d.mkdir(parents=True, exist_ok=True)
    payload = {"schemaVersion": "awx.source_edit_session.lease.v1",
               "topic": topic, "ownerId": "test", "ownerProcessId": 0,
               "expiresAtUtc": _utc(datetime.now(timezone.utc)
                                    + timedelta(hours=3)),
               "targetPaths": []}
    payload.update(lease)
    (d / "lease.json").write_text(json.dumps(payload), encoding="utf-8")
    return d


def make_journal(root: Path, task_id: str, status="in_progress",
                 age_hours=0.0, scope=None) -> Path:
    d = root / "data" / "agent-handoff" / "codex-autonomy" / task_id
    d.mkdir(parents=True, exist_ok=True)
    j = {"schemaVersion": "awx.work_journal.v1", "taskId": task_id,
         "agent": "devin", "purpose": "fixture " + task_id,
         "plannedScope": scope or [], "status": status,
         "startedAtUtc": _utc(datetime.now(timezone.utc)
                              - timedelta(hours=age_hours + 1)),
         "updatedAtUtc": _utc(datetime.now(timezone.utc)
                              - timedelta(hours=age_hours))}
    (d / "journal.json").write_text(json.dumps(j), encoding="utf-8")
    return d


def make_signal(store: Path, part: str, agent: str, sid: str,
                body) -> Path:
    d = store / part / agent
    d.mkdir(parents=True, exist_ok=True)
    p = d / (sid + ".json")
    if isinstance(body, (dict, list)):
        p.write_text(json.dumps(body), encoding="utf-8")
    else:
        p.write_text(body, encoding="utf-8")
    return p


def make_diag_report(root: Path, patterns) -> Path:
    d = root / "var" / "diagnostics"
    d.mkdir(parents=True, exist_ok=True)
    report = {"schemaVersion": "awx.agent-session-watch.v1",
              "sessions": [{"file": None, "agent": "codex",
                            "findings": [
                                {"pattern": p, "name": "fixture-" + p,
                                 "severity": "auto"}
                                for p in patterns]}]}
    p = d / "session-watch-20990101T000000.json"
    p.write_text(json.dumps(report), encoding="utf-8")
    return p


def make_codex_jsonl(root: Path, soft_fails: int) -> Path:
    d = root / "sessions"
    d.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps({"type": "session_meta", "payload": {
        "id": "fixture-session", "cwd": str(root)}})]
    for i in range(soft_fails):
        lines.append(json.dumps({"type": "response_item", "payload": {
            "type": "function_call", "call_id": "c%d" % i,
            "name": "exec_command", "input": "{}"}}))
        lines.append(json.dumps({"type": "response_item", "payload": {
            "type": "function_call_output", "call_id": "c%d" % i,
            "output": "Cannot find path 'C:\\nope\\missing%d.py' "
                      "because it does not exist." % i}}))
    p = d / "rollout-2099-01-01T00-00-00-fixture.jsonl"
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p


class PreFlightTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_clean_root_ready(self):
        code, out = run_main(barrier.main,
                             ["pre-flight", "--root", str(self.root)])
        data = json.loads(out)
        self.assertEqual(code, 0)
        self.assertTrue(data["ready"])
        self.assertIsNone(data["wait_reason"])
        self.assertEqual(data["locks"]["stale"], 0)
        self.assertEqual(data["journals"]["zombie_journals"], [])

    def test_stale_orphan_locks_and_zombie_journal(self):
        dead = subprocess.Popen([sys.executable, "-c", "pass"])
        dead.wait()
        make_lock(self.root, "live-lock",
                  ownerProcessId=os.getpid(),
                  targetPaths=["scripts/live_a.py"])
        make_lock(self.root, "expired-lock",
                  expiresAtUtc=_utc(datetime.now(timezone.utc)
                                    - timedelta(hours=2)),
                  targetPaths=["scripts/stale_b.py"])
        make_lock(self.root, "orphan-lock", ownerProcessId=dead.pid)
        corrupt = self.root / "__patch_drop__" / "source-edit-locks" \
            / "corrupt-lock.lock"
        corrupt.mkdir(parents=True)
        (corrupt / "lease.json").write_text("{not-json", encoding="utf-8")
        make_journal(self.root, "zombie-task", age_hours=30.0)
        make_journal(self.root, "fresh-task", age_hours=0.5)
        make_journal(self.root, "done-task", status="done", age_hours=50.0)
        code, out = run_main(barrier.main,
                             ["pre-flight", "--root", str(self.root)])
        data = json.loads(out)
        self.assertEqual(code, 3)
        self.assertFalse(data["ready"])
        self.assertIn("residue-present", data["wait_reason"])
        topics = {l["topic"]: l["reason"] for l in
                  data["locks"]["stale_locks"]}
        self.assertEqual(topics["expired-lock"], "expired")
        self.assertEqual(topics["orphan-lock"], "owner-process-absent")
        self.assertEqual(topics["corrupt-lock"], "lease-json-unreadable")
        self.assertNotIn("live-lock", topics)
        zids = [j["taskId"] for j in data["journals"]["zombie_journals"]]
        self.assertEqual(zids, ["zombie-task"])

    def test_targets_scoped_verdicts(self):
        make_lock(self.root, "live-lock", ownerProcessId=os.getpid(),
                  targetPaths=["scripts/live_a.py"])
        make_lock(self.root, "stale-lock",
                  expiresAtUtc=_utc(datetime.now(timezone.utc)
                                    - timedelta(hours=2)),
                  targetPaths=["scripts/stale_b.py"])
        make_journal(self.root, "zombie-task", age_hours=30.0,
                     scope=["scripts/zombie_c.py"])
        manifest = self.root / "targets.json"

        def probe(paths):
            manifest.write_text(json.dumps(
                {"targets": [{"path": p, "sha256": None} for p in paths]}),
                encoding="utf-8")
            return run_main(barrier.main, ["pre-flight", "--root",
                                           str(self.root), "--targets",
                                           str(manifest)])

        code, out = probe(["scripts/unrelated_new.py"])
        data = json.loads(out)
        self.assertTrue(data["ready"], out)
        code, out = probe(["scripts/live_a.py"])
        self.assertTrue(json.loads(out)["wait_reason"]
                        .startswith("live-lease-conflict:"))
        self.assertEqual(code, 3)
        code, out = probe(["scripts/stale_b.py"])
        self.assertTrue(json.loads(out)["wait_reason"]
                        .startswith("stale-lock-overlap-reclaimable:"))
        code, out = probe(["scripts/zombie_c.py"])
        self.assertTrue(json.loads(out)["wait_reason"]
                        .startswith("zombie-journal-scope-overlap:"))

    def test_devin_session_locks_counted(self):
        home = self.root / "devinhome"
        locks = home / "cli" / "session_locks"
        locks.mkdir(parents=True)
        for i in range(3):
            p = locks / ("s%d.lock" % i)
            p.write_text("x", encoding="utf-8")
            if i == 0:
                old = time.time() - 48 * 3600
                os.utime(p, (old, old))
        code, out = run_main(barrier.main,
                             ["pre-flight", "--root", str(self.root),
                              "--devin-home", str(home)])
        data = json.loads(out)
        self.assertEqual(data["devinSessionLocks"]["count"], 3)
        self.assertEqual(data["devinSessionLocks"]["staleCount"], 1)

    def test_preflight_under_one_second(self):
        for i in range(12):
            make_lock(self.root, "lock-%02d" % i)
        for i in range(10):
            make_journal(self.root, "j-%02d" % i, age_hours=40.0 + i)
        started = time.monotonic()
        code, out = run_main(barrier.main,
                             ["pre-flight", "--root", str(self.root)])
        elapsed = time.monotonic() - started
        data = json.loads(out)
        self.assertLess(elapsed, 1.0)
        self.assertLess(data["elapsedMs"], 1000)
        self.assertEqual(len(data["journals"]["zombie_journals"]), 10)


class DispatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _block(self, argv):
        code, out = run_main(dispatcher.main, ["dispatch"] + argv)
        self.assertEqual(code, 0, out)
        lines = out.strip().splitlines()
        self.assertEqual(len(lines), 3, out)
        return lines

    def test_pattern_p11(self):
        lines = self._block(["--root", str(self.root), "--patterns", "P11"])
        self.assertEqual(lines[0], "@source-inspection")
        self.assertTrue(lines[1].startswith("Project Root:"))
        self.assertIn("agent_recovery_status.py guide", lines[2])

    def test_pattern_p1_and_p15(self):
        lines = self._block(["--root", str(self.root), "--patterns", "P1"])
        self.assertEqual(lines[0], "@source-inspection")
        self.assertIn("apply-patch-context-miss", lines[2])
        lines = self._block(["--root", str(self.root), "--patterns", "P15"])
        self.assertEqual(lines[0], "@agent-scope-lease")
        self.assertIn("agent_scope_lease.py who", lines[2])

    def test_java_target(self):
        lines = self._block(["--root", str(self.root), "--target-files",
                             "main/java/com/x/FooService.java"])
        self.assertEqual(lines[0], "@demo1-toolchain-auto-select")
        self.assertIn("gradlew.bat test --tests \"*FooService*\"", lines[2])

    def test_web_target(self):
        lines = self._block(["--root", str(self.root), "--target-files",
                             "main/resources/static/js/chat.js"])
        self.assertEqual(lines[0], "@frontend-display-debug")
        self.assertIn("Debug-Meta-Display.bat", lines[2])

    def test_orchestration_target(self):
        lines = self._block(["--root", str(self.root), "--target-files",
                             "scripts/orchestra_signal.py"])
        self.assertEqual(lines[0], "@demo1-orchestra-synergy")
        self.assertIn("agent_quick_signal.py", lines[2])

    def test_default_fallback(self):
        lines = self._block(["--root", str(self.root),
                             "--target-files", "docs/README.txt"])
        self.assertEqual(lines[0], "@demo1-orchestra-synergy")
        self.assertIn("agent_signal_digest.py", lines[2])

    def test_pattern_beats_target(self):
        lines = self._block(["--root", str(self.root), "--patterns", "P15",
                             "--target-files",
                             "main/java/com/x/Foo.java"])
        self.assertEqual(lines[0], "@agent-scope-lease")

    def test_diag_dir_fallback(self):
        make_diag_report(self.root, ["P15"])
        lines = self._block(["--root", str(self.root)])
        self.assertEqual(lines[0], "@agent-scope-lease")

    def test_session_file_scan(self):
        session = make_codex_jsonl(self.root, soft_fails=3)
        code, out = run_main(dispatcher.main, [
            "dispatch", "--root", str(self.root),
            "--parent-session-file", str(session), "--json"])
        data = json.loads(out)
        ids = [p["id"] for p in data["patterns"]]
        self.assertIn("P11", ids)
        self.assertEqual(data["skill"], "@source-inspection")

    def test_missing_session_file_uses_diag(self):
        make_diag_report(self.root, ["P1"])
        lines = self._block(["--root", str(self.root),
                             "--parent-session-file",
                             str(self.root / "gone.jsonl")])
        self.assertEqual(lines[0], "@source-inspection")


class PostBarrierTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = self.root / "data" / "agent-handoff" / "orchestra"

    def tearDown(self):
        self.tmp.cleanup()

    def _post(self, sid, extra=None):
        argv = ["post-barrier", "--root", str(self.root),
                "--signal-id", sid] + (extra or [])
        return run_main(barrier.main, argv)

    def test_valid_signal_ready_no_port(self):
        make_signal(self.store, "inbox", "devin", "abc123def456",
                    {"schemaVersion": "awx.orchestra-signal.v1",
                     "id": "abc123def456", "status": "new"})
        code, out = self._post("abc123def456")
        data = json.loads(out)
        self.assertEqual(code, 0, out)
        self.assertTrue(data["ready_for_next_agent"])
        self.assertTrue(data["signal"]["atomic_fs_settled"])
        self.assertTrue(data["signal"]["jsonValid"])
        self.assertFalse(data["port"]["requested"])

    def test_signal_with_open_port(self):
        make_signal(self.store, "outbox", "codex", "feed12345678",
                    {"id": "feed12345678"})
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]
        try:
            code, out = self._post("feed12345678", [
                "--verify-port", str(port), "--timeout-sec", "3"])
        finally:
            srv.close()
        data = json.loads(out)
        self.assertEqual(code, 0, out)
        self.assertTrue(data["port"]["open"])
        self.assertTrue(data["ready_for_next_agent"])

    def test_port_timeout_not_ready(self):
        make_signal(self.store, "inbox", "devin", "aaaa1111bbbb",
                    {"id": "aaaa1111bbbb"})
        blocker = socket.socket()
        blocker.bind(("127.0.0.1", 0))
        blocker.listen(1)
        port = blocker.getsockname()[1]
        blocker.close()  # port now closed
        code, out = self._post("aaaa1111bbbb", [
            "--verify-port", str(port), "--timeout-sec", "0.6"])
        data = json.loads(out)
        self.assertEqual(code, 3)
        self.assertFalse(data["port"]["open"])
        self.assertFalse(data["ready_for_next_agent"])
        self.assertTrue(data["wait_reason"].startswith("port-not-open"))

    def test_missing_signal(self):
        code, out = self._post("000000000000")
        data = json.loads(out)
        self.assertEqual(code, 3)
        self.assertFalse(data["signal"]["found"])
        self.assertEqual(data["wait_reason"], "signal-not-settled")

    def test_zero_byte_signal(self):
        make_signal(self.store, "inbox", "devin", "zero12345678", "")
        code, out = self._post("zero12345678")
        data = json.loads(out)
        self.assertEqual(code, 3)
        self.assertFalse(data["signal"]["atomic_fs_settled"])

    def test_corrupt_json_signal(self):
        make_signal(self.store, "archive", "x", "badbadbadbad",
                    "{not json at all")
        code, out = self._post("badbadbadbad")
        data = json.loads(out)
        self.assertEqual(code, 3)
        self.assertFalse(data["signal"]["jsonValid"])

    def test_real_signal_via_orchestra_signal(self):
        """Integration: emit a real signal, then pass the barrier."""
        proc = subprocess.run(
            [sys.executable, "-B", str(ROOT / "scripts" / "orchestra_signal.py"),
             "new", "--store", str(self.store), "--from", "devin",
             "--to", "codex", "--summary", "cadence barrier fixture signal",
             "--files", "scripts/orchestra_cadence_barrier.py"],
            capture_output=True, text=True, timeout=60)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        sid = json.loads(proc.stdout)["id"]
        code, out = self._post(sid)
        data = json.loads(out)
        self.assertEqual(code, 0, out)
        self.assertTrue(data["signal"]["atomic_fs_settled"])
        self.assertTrue(data["signal"]["schemaValid"])


class SubprocessSmokeTest(unittest.TestCase):
    """Real `python -B` end-to-end on the live repo (read-only checks)."""

    def test_pre_flight_cli(self):
        proc = run_subprocess("orchestra_cadence_barrier.py",
                              ["pre-flight", "--root", str(ROOT)])
        self.assertIn(proc.returncode, (0, 3))
        data = json.loads(proc.stdout)
        self.assertEqual(data["action"], "pre-flight")
        self.assertIn("stale_locks", data["locks"])
        self.assertIn("zombie_journals", data["journals"])

    def test_dispatch_cli_three_lines(self):
        proc = run_subprocess("subagent_tool_dispatcher.py",
                              ["dispatch", "--patterns", "P11"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        lines = proc.stdout.strip().splitlines()
        self.assertEqual(len(lines), 3)
        self.assertEqual(lines[0], "@source-inspection")

    def test_post_barrier_cli_missing(self):
        proc = run_subprocess(
            "orchestra_cadence_barrier.py",
            ["post-barrier", "--signal-id", "ffffffffffff"])
        self.assertEqual(proc.returncode, 3)
        self.assertFalse(json.loads(proc.stdout)["ready_for_next_agent"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
