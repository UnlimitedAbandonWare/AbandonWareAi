"""Scope coordinator tests; every mutation happens inside a temporary root."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "agent_scope_lease.py"


class AgentScopeLeaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-scope-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "__patch_drop__").mkdir()
        (self.root / "target.txt").write_text("user preimage\n")
        (self.root / "other.txt").write_text("other file\n")
        (self.root / "data").mkdir()
        # Stub the process inventory like the lease tests do: an unrelated
        # live git.exe is otherwise a writer hazard for every root.
        stub = self.root / "session_stub.ps1"
        stub.write_text(
            "function Get-CimInstance { @() }\n"
            f"& '{ROOT / '__patch_drop__' / 'source_edit_session.ps1'}' @args\n"
            "exit $LASTEXITCODE\n",
            encoding="utf-8")
        self.env = {**os.environ, "AWX_SCOPE_SESSION_PS1": str(stub)}
        self.env.pop("PSModulePath", None)

    def call(self, *args, expect=None, env=None):
        proc = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(self.root), *args],
            env=env or self.env, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=120)
        if expect is not None:
            self.assertEqual(proc.returncode, expect,
                             f"{args}: rc={proc.returncode} out={proc.stdout} err={proc.stderr[-400:]}")
        return proc, json.loads(proc.stdout.strip().splitlines()[-1])

    def claim(self, agent="devin", task=None, extra=()):
        args = ["claim", "--agent", agent, "--path", "target.txt",
                "--purpose", "synthetic claim"]
        if task:
            args += ["--task", task]
        return self.call(*(args + list(extra)), expect=0)[1]

    def foreign_lease(self, topic, targets, expired=True):
        # 다른 세션이 end 없이 남긴 잔류 lease 픽스처.
        now = datetime.now(timezone.utc)
        lease_id = hashlib.md5(("lease-" + topic).encode()).hexdigest()
        lock = (self.root / "__patch_drop__" / "source-edit-locks"
                / f"{topic}.lock")
        lock.mkdir(parents=True, exist_ok=True)
        expiry = (now - timedelta(hours=1) if expired
                  else now + timedelta(hours=2))
        doc = {"schemaVersion": "awx.source_edit_session.lease.v1",
               "leaseId": lease_id,
               "taskIdHash": hashlib.sha256(topic.encode()).hexdigest(),
               "ownerHash": hashlib.sha256(b"foreign-owner").hexdigest(),
               "ownerProcessId": 0, "ownerProcessStartedAtUtc": "",
               "startedAtUtc": (now - timedelta(hours=2)).isoformat(),
               "topic": topic, "role": "desktop", "ownerId": "foreign-owner",
               "root": str(self.root), "expiresAtUtc": expiry.isoformat(),
               "expiresAt": expiry.isoformat(), "mutationAllowed": True,
               "operation": "worktree-edit", "targetPaths": targets,
               "coordinationMode": "target-scoped"}
        path = lock / "lease.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        return path

    def stub_env(self, prologue):
        """커스텀 session stub: prologue가 매칭 시 먼저 실행되고, 아니면 실제
        source_edit_session.ps1로 위임한다."""
        stub = self.root / "session_stub_custom.ps1"
        stub.write_text(
            prologue +
            "function Get-CimInstance { @() }\n"
            f"& '{ROOT / '__patch_drop__' / 'source_edit_session.ps1'}' @args\n"
            "exit $LASTEXITCODE\n",
            encoding="utf-8")
        env = {**os.environ, "AWX_SCOPE_SESSION_PS1": str(stub)}
        env.pop("PSModulePath", None)
        return env

    def fail_once_env(self, reason):
        """첫 begin만 [source-edit-session][<reason>] + exit 6으로 실패."""
        marker = (self.root / f"stub-fired-{reason}.txt").as_posix()
        return self.stub_env(
            f"$m = '{marker}'\n"
            "if ($args -contains 'begin' -and "
            "-not (Test-Path -LiteralPath $m)) {\n"
            "  [IO.File]::WriteAllText($m, 'fired')\n"
            f"  Write-Host '[source-edit-session][{reason}] "
            "topic=x repositoryWideHold=false'\n"
            "  exit 6\n"
            "}\n")

    def test_check_rebuilds_native_module_path_without_session_wrapper(self):
        from shutil import which
        pwsh = which("pwsh")
        if not pwsh:
            self.skipTest("PowerShell 7 is required for the inherited-module-path fixture")
        env = {key: value for key, value in os.environ.items()
               if key.casefold() not in ("psmodulepath", "awx_scope_session_ps1")}
        env["PSMODULEPATH"] = str(Path(pwsh).resolve().parent / "Modules")
        _, row = self.call("check", "--path", "target.txt", expect=0, env=env)
        self.assertTrue(row["allowed"])
        self.assertEqual(row["leaseConflict"]["conflictingLeaseCount"], 0)

    def test_check_free_path_is_allowed(self):
        proc, row = self.call("check", "--path", "target.txt", expect=0)
        self.assertTrue(row["allowed"])
        self.assertEqual(row["leaseConflict"]["conflictingLeaseCount"], 0)

    def test_claim_who_done_cycle(self):
        claimed = self.claim(extra=["--feature", "nova-focus"])
        self.assertTrue(claimed["acquired"])
        task_id = claimed["taskId"]
        _, who = self.call("who", expect=0)
        mine = [c for c in who["claims"] if c["taskId"] == task_id]
        self.assertEqual(len(mine), 1)
        self.assertEqual(mine[0]["features"], ["nova-focus"])
        self.assertFalse(mine[0]["released"])
        proc, blocked = self.call("check", "--path", "target.txt")
        self.assertEqual(proc.returncode, 7)
        self.assertFalse(blocked["allowed"])
        self.assertIn("target.txt", blocked["leaseConflict"]["conflictingPaths"])
        proc, done = self.call("done", "--task", task_id, expect=0)
        self.assertTrue(done["released"])
        _, after = self.call("check", "--path", "target.txt", expect=0)
        self.assertTrue(after["allowed"])

    def test_second_writer_claim_on_same_path_is_rejected(self):
        self.claim(agent="codex")
        proc, row = self.call("claim", "--agent", "devin", "--path", "target.txt")
        self.assertEqual(proc.returncode, 7)
        self.assertFalse(row["acquired"])

    def test_disjoint_claims_coexist(self):
        first = self.claim(agent="codex")
        second = self.call("claim", "--agent", "devin", "--path", "other.txt",
                          "--purpose", "disjoint claim", expect=0)[1]
        self.assertTrue(second["acquired"])
        self.assertNotEqual(first["taskId"], second["taskId"])
        self.call("done", "--task", first["taskId"], expect=0)
        self.call("done", "--task", second["taskId"], expect=0)

    def test_feature_overlap_is_advisory_not_block(self):
        self.claim(agent="codex", extra=["--feature", "hint-paging"])
        proc, row = self.call("check", "--path", "other.txt",
                             "--feature", "hint-paging", expect=0)
        self.assertTrue(row["allowed"])
        kinds = {a["kind"] for a in row["advisories"]}
        self.assertIn("claim-overlap", kinds)
        hits = [a for a in row["advisories"] if a["kind"] == "claim-overlap"]
        self.assertEqual(hits[0]["features"], ["hint-paging"])

    def test_verify_detects_foreign_drift(self):
        claimed = self.claim()
        (self.root / "target.txt").write_text("foreign change\n")
        proc, row = self.call("verify", "--task", claimed["taskId"])
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(row["verified"])
        self.assertEqual(row["reason"], "preimage-changed")
        self.call("abort", "--task", claimed["taskId"], expect=0)

    def test_abort_releases_and_marks_claim(self):
        claimed = self.claim()
        proc, row = self.call("abort", "--task", claimed["taskId"], expect=0)
        self.assertTrue(row["released"])
        _, shown = self.call("show", "--task", claimed["taskId"], expect=0)
        self.assertTrue(shown["claims"][0]["claim"]["released"])
        self.assertEqual(shown["claims"][0]["claim"]["releaseReason"], "abort")

    def test_heartbeat_renews(self):
        claimed = self.claim()
        proc, row = self.call("heartbeat", "--task", claimed["taskId"], expect=0)
        self.assertTrue(row["renewed"])
        self.call("done", "--task", claimed["taskId"], expect=0)

    def test_who_reports_lease_lifecycle(self):
        claimed = self.claim()
        _, who = self.call("who", expect=0)
        lease = next(l for l in who["leases"]
                     if l["topic"] == claimed["topic"])
        self.assertEqual(lease["lifecycle"], "live")
        mine = next(c for c in who["claims"] if c["taskId"] == claimed["taskId"])
        self.assertEqual(mine["leaseLifecycle"], "live")
        self.call("done", "--task", claimed["taskId"], expect=0)

    def test_claim_recovers_stale_lease_and_acquires(self):
        # A 세션이 end 없이 끝나 만료된 잔류 lease -> claim이 회수 후 begin 재시도.
        self.foreign_lease("dead-session", ["target.txt"])
        claimed = self.claim()
        self.assertTrue(claimed["acquired"])
        lock = (self.root / "__patch_drop__" / "source-edit-locks"
                / "dead-session.lock")
        self.assertFalse(lock.exists())
        quarantined = list((self.root / "__patch_drop__"
                            / "source-edit-quarantine").glob("*/lease/lease.json"))
        self.assertEqual(len(quarantined), 1)
        self.call("done", "--task", claimed["taskId"], expect=0)

    def test_claim_blocked_by_live_lease_does_not_reclaim(self):
        self.foreign_lease("live-session", ["target.txt"], expired=False)
        proc, row = self.call("claim", "--agent", "devin",
                              "--path", "target.txt")
        self.assertEqual(proc.returncode, 7)
        self.assertFalse(row["acquired"])
        # live lease는 회수되지 않고 release 요청만 남는다
        lock = (self.root / "__patch_drop__" / "source-edit-locks"
                / "live-session.lock")
        self.assertTrue(lock.is_dir())
        flow = row["leaseConflictAutoflow"]
        self.assertEqual(flow["staleReclaim"]["reclaimed"], [])

    def test_check_markdown_only_path_adds_guidance(self):
        (self.root / "docs").mkdir(exist_ok=True)
        (self.root / "docs" / "note.md").write_text("# note\n")
        proc, row = self.call("check", "--path", "docs/note.md", expect=0)
        self.assertTrue(row["allowed"])
        self.assertIn("journal+checkpoint", row["markdownGuidance"])

    def test_check_mixed_paths_skip_markdown_guidance(self):
        (self.root / "docs").mkdir(exist_ok=True)
        (self.root / "docs" / "note.md").write_text("# note\n")
        proc, row = self.call("check", "--path", "docs/note.md",
                              "--path", "target.txt", expect=0)
        self.assertNotIn("markdownGuidance", row)

    def test_claim_markdown_acquires_with_guidance(self):
        (self.root / "docs").mkdir(exist_ok=True)
        (self.root / "docs" / "note.md").write_text("# note\n")
        proc, row = self.call("claim", "--agent", "devin",
                              "--path", "docs/note.md", expect=0)
        self.assertTrue(row["acquired"])
        self.assertIn("journal+checkpoint", row["markdownGuidance"])
        self.call("done", "--task", row["taskId"], expect=0)

    def test_attach_to_existing_task_releases_all_claims(self):
        first = self.claim(agent="codex")
        proc, second = self.call("claim", "--agent", "devin",
                                "--task", first["taskId"], "--topic", "second-scope",
                                "--path", "other.txt", expect=0)
        self.assertTrue(second["acquired"])
        self.assertEqual(second["taskId"], first["taskId"])
        _, shown = self.call("show", "--task", first["taskId"], expect=0)
        self.assertEqual(len(shown["claims"]), 2)
        proc, aborted = self.call("abort", "--task", first["taskId"], expect=0)
        self.assertEqual(sorted(aborted["topics"]), sorted([first["topic"], "second-scope"]))
        _, shown = self.call("show", "--task", first["taskId"], expect=0)
        self.assertTrue(all(c["claim"]["released"] for c in shown["claims"]))

    def test_claim_failure_surfaces_ps1_reason(self):
        # exit 6 침묵 회귀 방지: 실패 마커 사유가 JSON과 journal에 남아야 한다.
        env = self.stub_env(
            "if ($args -contains 'begin') {\n"
            "  Write-Host '[source-edit-session][git-operation-active] "
            "topic=x repositoryWideHold=false'\n"
            "  exit 6\n"
            "}\n")
        proc, row = self.call("claim", "--agent", "devin", "--path", "target.txt",
                              "--purpose", "synthetic claim", env=env)
        self.assertEqual(proc.returncode, 6)
        self.assertFalse(row["acquired"])
        self.assertEqual(row["reason"], "git-operation-active")
        journal = json.loads(
            (self.root / "data" / "agent-handoff" / "codex-autonomy"
             / row["taskId"] / "journal.json").read_bytes())
        notes = [str(e.get("text", "")) for e in journal.get("events", [])]
        self.assertTrue(any("reason=git-operation-active" in t for t in notes),
                        notes)

    def test_claim_new_file_absent_target_then_verify(self):
        # sha256 null(생성 예정) claim → 세션 내 생성 후 verify가
        # preimage-changed로 오탐 기각하지 않아야 한다.
        proc, row = self.call("claim", "--agent", "devin",
                              "--path", "new_thing.py", expect=0)
        self.assertTrue(row["acquired"])
        target = next(t for t in row["targets"] if t["path"] == "new_thing.py")
        self.assertIsNone(target["sha256"])
        (self.root / "new_thing.py").write_text("# created inside session\n")
        proc, ver = self.call("verify", "--task", row["taskId"], expect=0)
        self.assertTrue(ver["verified"])
        self.call("done", "--task", row["taskId"], expect=0)

    def test_claim_exit6_overlap_reason_triggers_autoflow_and_retry(self):
        # exit 6 + source-target-overlap 사유도 autoflow 평가 → stale 회수 후
        # begin 재시도로 claim 완료. 회수는 quarantine+journal 증거로 판정한다.
        self.foreign_lease("dead-six", ["target.txt"])
        env = self.fail_once_env("source-target-overlap")
        proc, row = self.call("claim", "--agent", "devin", "--path", "target.txt",
                              "--purpose", "synthetic claim", env=env, expect=0)
        self.assertTrue(row["acquired"])
        self.assertFalse((self.root / "__patch_drop__" / "source-edit-locks"
                          / "dead-six.lock").exists())
        quarantined = list((self.root / "__patch_drop__"
                            / "source-edit-quarantine").glob("*/lease/lease.json"))
        self.assertEqual(len(quarantined), 1)
        journal = json.loads(
            (self.root / "data" / "agent-handoff" / "codex-autonomy"
             / row["taskId"] / "journal.json").read_bytes())
        notes = [str(e.get("text", "")) for e in journal.get("events", [])]
        self.assertTrue(any("lease-reclaimed" in t for t in notes), notes)
        self.call("done", "--task", row["taskId"], expect=0)

    def test_claim_exit6_preimage_changed_retries_with_fresh_manifest(self):
        # manifest 작성~begin 사이 대상 변동(preimage-changed)은 현재 바이트로
        # manifest를 다시 선언해 begin을 한 번 재시도한다.
        env = self.fail_once_env("preimage-changed")
        proc, row = self.call("claim", "--agent", "devin", "--path", "target.txt",
                              "--purpose", "synthetic claim", env=env, expect=0)
        self.assertTrue(row["acquired"])
        self.call("done", "--task", row["taskId"], expect=0)

    def test_done_retries_journal_close_after_release_only(self):
        claimed = self.claim()
        journal_path = (self.root / "data" / "agent-handoff" / "codex-autonomy"
                        / claimed["taskId"] / "journal.json")
        self.call("done", "--task", claimed["taskId"], expect=0)
        self.assertEqual(json.loads(journal_path.read_bytes())["status"], "in_progress")

        _, row = self.call("done", "--task", claimed["taskId"],
                           "--close-result", "verified", expect=0)
        journal = json.loads(journal_path.read_bytes())
        self.assertEqual(journal["status"], "closed")
        self.assertEqual(journal["result"], "verified")
        self.assertTrue(row["released"])
        self.assertTrue(row["alreadyReleased"])
        self.assertTrue(row["journalClose"]["closed"])
        self.assertEqual(row["journalClose"]["exitCode"], 0)
        self.assertEqual(row["journalClose"]["requestedResult"], "verified")

    def test_done_reports_journal_close_failure_after_own_release(self):
        claimed = self.claim()
        foreign_path = self.foreign_lease("foreign-close-failure", ["other.txt"],
                                          expired=False)
        foreign_bytes = foreign_path.read_bytes()
        failure_tool = self.root / "journal_close_failure.py"
        failure_tool.write_text(
            "import json, runpy, sys\n"
            "if 'close' in sys.argv:\n"
            "    print(json.dumps({'status': 'error', 'reason': 'synthetic-close-denied'}))\n"
            "    sys.exit(9)\n"
            f"sys.path.insert(0, {str(ROOT / 'scripts')!r})\n"
            f"runpy.run_path({str(ROOT / 'scripts' / 'work_journal.py')!r}, run_name='__main__')\n",
            encoding="utf-8")
        launcher = (
            "import sys; from pathlib import Path; "
            f"sys.path.insert(0, {str(ROOT / 'scripts')!r}); "
            "import agent_scope_lease as scope; "
            f"scope.JOURNAL_PY = Path({str(failure_tool)!r}); "
            "sys.exit(scope.main(sys.argv[1:]))")
        proc = subprocess.run(
            [sys.executable, "-B", "-c", launcher, "--root", str(self.root),
             "done", "--task", claimed["taskId"], "--close-result", "verified"],
            env=self.env, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=120)
        self.assertEqual(proc.returncode, 6, proc.stdout + proc.stderr)
        row = json.loads(proc.stdout.strip().splitlines()[-1])
        self.assertTrue(row["released"])
        self.assertFalse(row["journalClose"]["closed"])
        self.assertEqual(row["journalClose"]["exitCode"], 9)
        self.assertEqual(row["journalClose"]["reason"], "synthetic-close-denied")
        _, shown = self.call("show", "--task", claimed["taskId"], expect=0)
        self.assertTrue(shown["claims"][0]["claim"]["released"])
        self.assertFalse((self.root / "__patch_drop__" / "source-edit-locks"
                          / f"{claimed['topic']}.lock").exists())
        self.assertEqual(foreign_path.read_bytes(), foreign_bytes)
        journal_path = (self.root / "data" / "agent-handoff" / "codex-autonomy"
                        / claimed["taskId"] / "journal.json")
        self.assertEqual(json.loads(journal_path.read_bytes())["status"], "in_progress")

    def test_done_matching_closed_result_leaves_journal_bytes_unchanged(self):
        claimed = self.claim()
        self.call("done", "--task", claimed["taskId"],
                  "--close-result", "verified", expect=0)
        journal_path = (self.root / "data" / "agent-handoff" / "codex-autonomy"
                        / claimed["taskId"] / "journal.json")
        before = journal_path.read_bytes()
        _, row = self.call("done", "--task", claimed["taskId"],
                           "--close-result", "verified", expect=0)
        self.assertTrue(row.get("journalClose", {}).get("closed"))
        self.assertTrue(row["journalClose"]["alreadyClosed"])
        self.assertEqual(row["journalClose"]["requestedResult"], "verified")
        self.assertEqual(journal_path.read_bytes(), before)

    def test_done_topic_close_waits_for_all_own_leases(self):
        # Check both a release+close and a close retry after a default topic release.
        for release_first in (False, True):
            claimed = self.claim()
            _, remaining = self.call("claim", "--agent", "devin",
                                     "--task", claimed["taskId"], "--topic", "remaining-scope",
                                     "--path", "other.txt", expect=0)
            journal_path = (self.root / "data" / "agent-handoff" / "codex-autonomy"
                            / claimed["taskId"] / "journal.json")
            remaining_lease = (self.root / "__patch_drop__" / "source-edit-locks"
                               / "remaining-scope.lock" / "lease.json")
            remaining_claim = journal_path.with_name("scope-claim-remaining-scope.json")
            lease_bytes, claim_bytes = remaining_lease.read_bytes(), remaining_claim.read_bytes()
            if release_first:
                _, released = self.call("done", "--task", claimed["taskId"],
                                        "--topic", claimed["topic"], expect=0)
                self.assertTrue(released["released"])
                self.assertEqual(json.loads(journal_path.read_bytes())["status"], "in_progress")
                self.assertFalse(json.loads(remaining_claim.read_bytes())["released"])
            for topic in (claimed["topic"], "unknown-topic"):
                with self.subTest(releaseFirst=release_first, topic=topic):
                    proc, row = self.call("done", "--task", claimed["taskId"],
                                          "--topic", topic, "--close-result", "verified")
                    self.assertEqual(proc.returncode, 6)
                    self.assertTrue(row["released"])
                    self.assertFalse(row["journalClose"]["closed"])
                    self.assertEqual(row["journalClose"]["reason"], "own-leases-remain")
                    self.assertIsNone(row["journalClose"]["exitCode"])
                    self.assertEqual(json.loads(journal_path.read_bytes())["status"], "in_progress")
            self.assertEqual(remaining_lease.read_bytes(), lease_bytes)
            self.assertEqual(remaining_claim.read_bytes(), claim_bytes)
            _, closed = self.call("done", "--task", claimed["taskId"],
                                  "--topic", remaining["topic"], "--close-result", "verified",
                                  expect=0)
            self.assertTrue(closed["journalClose"]["closed"])
            self.assertEqual(json.loads(journal_path.read_bytes())["status"], "closed")
            self.assertFalse(remaining_lease.exists())

    def test_done_journal_close_has_guard_sized_subprocess_deadline(self):
        import importlib.util
        from types import SimpleNamespace
        from unittest.mock import patch

        claimed = self.claim()
        self.call("done", "--task", claimed["taskId"], expect=0)
        spec = importlib.util.spec_from_file_location("scope_deadline", TOOL)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        real_run = subprocess.run
        close_timeouts = []

        def guard_sized_run(command, **kwargs):
            if "close" in command:
                close_timeouts.append(kwargs["timeout"])
                if kwargs["timeout"] < 120:
                    raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            return real_run(command, **kwargs)

        args = SimpleNamespace(task=claimed["taskId"], close_result="verified",
                               summary=None, check_evidence=False)
        with patch.object(module.subprocess, "run", side_effect=guard_sized_run):
            receipt = module.release_journal_close(self.root, args, False, "done")
        self.assertTrue(receipt["closed"])
        self.assertEqual(close_timeouts, [120])
        journal_path = (self.root / "data" / "agent-handoff" / "codex-autonomy"
                        / claimed["taskId"] / "journal.json")
        self.assertEqual(json.loads(journal_path.read_bytes())["status"], "closed")

    def test_help_cp949_does_not_crash(self):
        env = os.environ.copy()
        env['PYTHONIOENCODING'] = 'cp949'
        env.pop('PYTHONUTF8', None)
        proc = subprocess.run(
            [sys.executable, '-B', str(TOOL), '--help'],
            env=env, capture_output=True, timeout=60)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertNotIn(b'UnicodeEncodeError', proc.stderr)
        self.assertNotIn(b'UnicodeEncodeError', proc.stdout)


if __name__ == "__main__":
    unittest.main()
