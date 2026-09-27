"""Regression tests for the shared multi-agent protection layer.

Every scenario uses synthetic fixtures under temp dirs — never the active
checkout, real Codex home, or live services. These verify COMMON LOGIC only;
they are not proof of any real Codex/Grok/Devin/Cline client connection.
"""
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPTS = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


CP = load("codex_work_checkpoint")
SD = load("status_doc")
RQ = load("codex_home_quarantine")
RV = load("run_verified_command")
SE = load("awx_session_evidence")
WJ = load("work_journal")
PF = load("agent_preflight")


def decision(**gates):
    return {
        "goalId": "synthetic-guard-test", "reasonCode": "minimal-verified-fix",
        "risk": dict(recovery=1, blastRadius=1, regression=2, uncertainty=1, cost=0),
        "gates": {key: gates.get(key, False) for key in (
            "bulkDelete", "unrecoverableOverwrite", "credentialChange", "externalRealData",
            "paidBulkCalls", "productionMutation", "permissionChange", "irreversibleLoss")},
    }


def sha(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()


class TempRoot(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "docs").mkdir(parents=True)
        self.file = self.root / "docs" / "notes.md"
        self.file.write_bytes(b"original\n")
        self.run = "data/agent-handoff/codex-autonomy/test-cycle"


class CheckpointApplyTest(TempRoot):
    """Stale-hash / two-writer protection at apply time."""

    def test_apply_replaces_only_when_preimage_matches(self):
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        new = self.root / "new.bin"
        new.write_bytes(b"changed by this cycle\n")
        out = CP.apply(self.root, self.run, "docs/notes.md", str(new))
        self.assertEqual("applied", out["status"])
        self.assertEqual(b"changed by this cycle\n", self.file.read_bytes())

    def test_apply_refuses_file_changed_after_begin(self):
        # Agent A begins (records preimage), agent B writes first.
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        self.file.write_bytes(b"foreign change\n")
        new = self.root / "new.bin"
        new.write_bytes(b"my change\n")
        with self.assertRaises(CP.CheckpointError) as ctx:
            CP.apply(self.root, self.run, "docs/notes.md", str(new))
        self.assertEqual("apply-precondition-conflict", str(ctx.exception))
        self.assertEqual(b"foreign change\n", self.file.read_bytes())

    def test_apply_allows_iteration_on_own_change(self):
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        for i in (1, 2):
            new = self.root / f"v{i}.bin"
            new.write_bytes(f"v{i}\n".encode())
            CP.apply(self.root, self.run, "docs/notes.md", str(new))
        self.assertEqual(b"v2\n", self.file.read_bytes())

    def test_apply_refuses_undeclared_target(self):
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        new = self.root / "new.bin"
        new.write_bytes(b"x")
        with self.assertRaises(CP.CheckpointError) as ctx:
            CP.apply(self.root, self.run, "docs/other.md", str(new))
        self.assertEqual("apply-target-not-declared", str(ctx.exception))

    def test_apply_refuses_unprepared_cycle(self):
        new = self.root / "new.bin"
        new.write_bytes(b"x")
        with self.assertRaises(CP.CheckpointError):
            CP.apply(self.root, self.run, "docs/notes.md", str(new))


class CheckpointRestoreTest(TempRoot):
    """Restore must never overwrite a foreign postimage."""

    def _sealed_cycle(self):
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        new = self.root / "new.bin"
        new.write_bytes(b"cycle postimage\n")
        CP.apply(self.root, self.run, "docs/notes.md", str(new))
        CP.seal(self.root, self.run)

    def test_restore_reverts_clean_postimage(self):
        self._sealed_cycle()
        out = CP.restore(self.root, self.run)
        self.assertEqual("restored", out["status"])
        self.assertEqual(b"original\n", self.file.read_bytes())

    def test_restore_reports_foreign_change_without_overwrite(self):
        self._sealed_cycle()
        self.file.write_bytes(b"someone else's later change\n")
        out = CP.restore(self.root, self.run)
        self.assertEqual("hold", out["status"])
        self.assertEqual(1, len(out["conflicts"]))
        self.assertEqual(b"someone else's later change\n", self.file.read_bytes())

    def test_restore_staging_verifies_without_touching_live(self):
        self._sealed_cycle()
        staging = self.root / "staging-out"
        out = CP.restore(self.root, self.run, staging=str(staging))
        self.assertEqual("restore_staged", out["status"])
        self.assertEqual(b"cycle postimage\n", self.file.read_bytes())
        self.assertTrue(out["staged"])
        self.assertEqual(b"original\n", (staging / "0.bin").read_bytes())

    def test_restore_resumes_partial_rollback_idempotently(self):
        # Simulate a crash mid-rollback: status left "restoring", target already
        # back at preimage -> rerun must report unchanged, not duplicate work.
        self._sealed_cycle()
        run_dir = self.root / self.run
        state = json.loads((run_dir / "checkpoint.json").read_bytes())
        state["status"] = "restoring"
        (run_dir / "checkpoint.json").write_text(json.dumps(state))
        self.file.write_bytes(b"original\n")
        out = CP.restore(self.root, self.run)
        self.assertEqual("restored", out["status"])
        self.assertEqual(1, out["unchanged"])
        self.assertEqual(0, out["restored"])


class OperationLockTest(TempRoot):
    def test_dead_holder_lock_is_reclaimed(self):
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        dead = subprocess.Popen([sys.executable, "-c", "pass"])
        dead.wait()
        lock = self.root / self.run / ".operation.lock"
        lock.write_text(json.dumps({"pid": dead.pid}))
        CP.seal(self.root, self.run)  # must not stay blocked by a dead holder

    def test_live_holder_lock_blocks(self):
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        live = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(30)"])
        self.addCleanup(live.kill)
        lock = self.root / self.run / ".operation.lock"
        lock.write_text(json.dumps({"pid": live.pid}))
        with self.assertRaises(CP.CheckpointError) as ctx:
            CP.seal(self.root, self.run)
        self.assertEqual("checkpoint-operation-active", str(ctx.exception))


class StatusDocTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.doc = Path(self.tmp.name) / "PROJECT_STATUS.md"
        self.doc.write_text(
            "# Status\n\n| taskId | state | note |\n|---|---|---|\n"
            "| task-aaa | in_progress | first |\n"
            "| task-bbb | verified | second |\n", encoding="utf-8")

    def test_update_row_replaces_only_matching_row(self):
        before = sha(self.doc.read_bytes())
        out = SD.update_row(str(self.doc), "task-aaa",
                            "| task-aaa | verified | done |", before)
        self.assertEqual("applied", out["status"])
        text = self.doc.read_text()
        self.assertIn("| task-aaa | verified | done |", text)
        self.assertIn("| task-bbb | verified | second |", text)

    def test_update_refuses_stale_file_hash(self):
        SD.update_row(str(self.doc), "task-aaa", "| task-aaa | x | y |",
                      sha(self.doc.read_bytes()))
        out = SD.update_row(str(self.doc), "task-aaa", "| task-aaa | z | w |",
                            sha(b"old hash"))
        self.assertEqual("conflict", out["status"])
        self.assertIn("| task-aaa | x | y |", self.doc.read_text())

    def test_update_missing_key_never_autofills(self):
        with self.assertRaises(ValueError) as ctx:
            SD.update_row(str(self.doc), "task-zzz", "| task-zzz | n | n |",
                          sha(self.doc.read_bytes()))
        self.assertEqual("row-key-missing", str(ctx.exception))

    def test_ambiguous_key_refused(self):
        self.doc.write_text(
            self.doc.read_text() + "| task-aaa | dup | dup |\n", encoding="utf-8")
        with self.assertRaises(ValueError) as ctx:
            SD.update_row(str(self.doc), "task-aaa", "| task-aaa | q | q |",
                          sha(self.doc.read_bytes()))
        self.assertEqual("row-key-ambiguous", str(ctx.exception))

    def test_non_row_replacement_refused(self):
        with self.assertRaises(ValueError):
            SD.update_row(str(self.doc), "task-aaa", "task-aaa done",
                          sha(self.doc.read_bytes()))

    def test_append_row_inserts_after_anchor(self):
        out = SD.append_row(str(self.doc), "task-bbb",
                            "| task-ccc | new | row |",
                            sha(self.doc.read_bytes()))
        self.assertEqual("applied", out["status"])
        lines = self.doc.read_text().splitlines()
        self.assertEqual("| task-ccc | new | row |", lines[-1])


class QuarantineTest(unittest.TestCase):
    """Manifest-run lifecycle: unique manifests, hash-bound apply, idempotent
    restore, conflict reporting. Synthetic codex-home only."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name) / "codex-home"
        self.rescue = Path(self.tmp.name) / "rescue"
        self.home.mkdir(parents=True)
        self.old_home, self.old_rescue = RQ.CODEX_HOME, RQ.RESCUE
        RQ.CODEX_HOME, RQ.RESCUE = self.home, self.rescue
        self.addCleanup(setattr, RQ, "CODEX_HOME", self.old_home)
        self.addCleanup(setattr, RQ, "RESCUE", self.old_rescue)
        self._make_state()

    def _make_state(self):
        # state_5.sqlite: one stale child rollout + edges
        db = sqlite3.connect(self.home / "state_5.sqlite")
        db.executescript(
            "CREATE TABLE threads(id TEXT, rollout_path TEXT, updated_at_ms INTEGER,"
            " archived INTEGER, is_pinned INTEGER, title TEXT, thread_source TEXT);"
            "CREATE TABLE thread_spawn_edges(parent_thread_id TEXT, child_thread_id TEXT);")
        sess = self.home / "sessions" / "2026" / "child.jsonl"
        sess.parent.mkdir(parents=True)
        sess.write_text('{"type":"response_item","payload":{"type":"message"}}\n')
        stale_ms = int((time.time() - 30 * 86400) * 1000)
        db.execute("INSERT INTO threads VALUES(?,?,?,?,?,?,?)",
                   ("parent-1", str(sess), stale_ms, 0, 0, "parent", ""))
        db.execute("INSERT INTO threads VALUES(?,?,?,?,?,?,?)",
                   ("child-1", str(sess), stale_ms, 0, 0, "child", ""))
        db.execute("INSERT INTO thread_spawn_edges VALUES(?,?)", ("parent-1", "child-1"))
        db.commit(); db.close()
        hist = sqlite3.connect(self.home / "thread_history_1.sqlite")
        hist.executescript(
            "CREATE TABLE thread_items(thread_id TEXT, item_type TEXT);"
            "CREATE TABLE thread_history_projection_state(thread_id TEXT,"
            " next_rollout_byte_offset INTEGER);")
        hist.commit(); hist.close()
        (self.home / "codex-global-state.json.tmp-1").write_text("{}")

    def test_candidates_twice_preserves_both_manifests(self):
        self.assertEqual(0, RQ.cmd_candidates())
        first = RQ.latest_pointer()
        time.sleep(0.01)
        self.assertEqual(0, RQ.cmd_candidates())
        second = json.loads((self.rescue / "latest-run.json").read_text())
        self.assertNotEqual(first["runId"], second["runId"])
        self.assertTrue(RQ.manifest_path(first["runId"]).exists())
        self.assertTrue(RQ.manifest_path(second["runId"]).exists())
        self.assertEqual(first["manifestSha256"],
                         RQ.sha256(RQ.manifest_path(first["runId"])))

    def _run(self):
        RQ.cmd_candidates()
        return json.loads((self.rescue / "latest-run.json").read_text())

    def test_apply_requires_manifest_hash_approval(self):
        ptr = self._run()
        self.assertEqual(2, RQ.cmd_apply(expect_sha256="0" * 64))
        self.assertFalse((self.rescue / "child-stale-no-evidence").exists())
        self.assertEqual(0, RQ.cmd_apply(expect_sha256=ptr["manifestSha256"]))

    def test_apply_records_hashes_and_second_run_is_idempotent(self):
        ptr = self._run()
        RQ.cmd_apply(run_id=ptr["runId"])
        log = RQ.applylog_path(ptr["runId"]).read_text()
        rec = json.loads(log.splitlines()[0])
        self.assertEqual("moved", rec["result"])
        self.assertEqual(rec["preSha256"], rec["postSha256"])
        # A second apply must not re-move or double-count. (Fixture has two
        # manifest entries: the stale child rollout + the tmp leftover.)
        RQ.cmd_apply(run_id=ptr["runId"])
        results = [json.loads(x)["result"]
                   for x in RQ.applylog_path(ptr["runId"]).read_text().splitlines()]
        self.assertEqual(2, results.count("moved"))
        self.assertEqual(2, results.count("already-moved"))

    def test_restore_roundtrip_and_dst_conflict(self):
        ptr = self._run()
        src = self.home / "codex-global-state.json.tmp-1"
        RQ.cmd_apply(run_id=ptr["runId"])
        self.assertFalse(src.exists())
        # Foreign modification at quarantine dst -> conflict, preserved.
        dst = self.rescue / "tmp-globalstate" / src.name
        self.assertTrue(dst.exists())
        dst.write_text("foreign")
        RQ.cmd_restore(run_id=ptr["runId"])
        rlog = [json.loads(x) for x in
                RQ.restorelog_path(ptr["runId"]).read_text().splitlines()]
        self.assertEqual(1, len([r for r in rlog
                                 if r["result"] == "conflict-dst-changed"]))
        self.assertFalse(src.exists())  # conflict -> nothing moved back
        # The untouched entry still restored; put the tampered dst back to its
        # recorded postimage bytes and rerun -> now restored.
        dst.write_text("{}")
        RQ.cmd_restore(run_id=ptr["runId"])
        self.assertTrue(src.exists())
        self.assertEqual("{}", src.read_text())
        # Idempotent: third restore adds nothing.
        RQ.cmd_restore(run_id=ptr["runId"])
        rlog = [json.loads(x) for x in
                RQ.restorelog_path(ptr["runId"]).read_text().splitlines()]
        self.assertEqual(2, [r["result"] for r in rlog].count("restored"))

    def test_restore_refuses_recreated_source(self):
        ptr = self._run()
        src = self.home / "codex-global-state.json.tmp-1"
        RQ.cmd_apply(run_id=ptr["runId"])
        src.write_text("recreated by someone else")
        RQ.cmd_restore(run_id=ptr["runId"])
        rlog = [json.loads(x) for x in
                RQ.restorelog_path(ptr["runId"]).read_text().splitlines()]
        self.assertIn("conflict-src-exists", [r["result"] for r in rlog])
        self.assertEqual("recreated by someone else", src.read_text())


class RunVerifiedCommandTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "run"

    def test_run_records_cwd_argv_and_exit(self):
        report = RV.run([sys.executable, "-c", "print(1)"], self.tmp.name, self.out)
        self.assertEqual("passed", report["status"])
        self.assertEqual(0, report["exitCode"])
        self.assertEqual(str(Path(self.tmp.name).resolve()), report["cwd"])
        self.assertIn("-c", report["commandArgv"])

    def test_status_distinguishes_orphaned_from_finalized(self):
        RV.run([sys.executable, "-c", "print(1)"], self.tmp.name, self.out)
        self.assertEqual("finalized", RV.run_status(self.out)["observation"])
        # Fabricate an interrupted run: recorder died, pid long gone.
        dead = subprocess.Popen([sys.executable, "-c", "pass"]); dead.wait()
        rec = json.loads((self.out / "run.json").read_text())
        rec.update(status="running", pid=dead.pid, endedAt=None)
        (self.out / "run.json").write_text(json.dumps(rec))
        self.assertEqual("orphaned_unconfirmed", RV.run_status(self.out)["observation"])

    def test_status_and_stop_on_live_run(self):
        live = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(60)"])
        self.addCleanup(lambda: live.poll() is None and live.kill())
        self.out.mkdir()
        (self.out / "run.json").write_text(json.dumps(
            {"runId": "x", "status": "running", "pid": live.pid,
             "startedAt": "2026-01-01T00:00:00Z"}))
        self.assertEqual("still_running", RV.run_status(self.out)["observation"])
        result = RV.run_stop(self.out)
        self.assertEqual("stopped", result["observation"])
        live.wait(timeout=15)
        self.assertIsNotNone(live.poll())

    def test_timeout_marks_interrupted_not_passed(self):
        report = RV.run([sys.executable, "-c", "import time;time.sleep(60)"],
                        self.tmp.name, self.out, timeout=0.5)
        self.assertEqual("interrupted", report["status"])
        self.assertNotEqual(0, report["verificationExitCode"])

    def test_stale_xml_is_not_accepted_as_fresh_pass(self):
        xml_dir = Path(self.tmp.name) / "xml"; xml_dir.mkdir()
        (xml_dir / "TEST-suite.A.xml").write_text(
            '<testsuite name="suite.A" tests="1" failures="0" errors="0" skipped="0">'
            "<testcase/></testsuite>")
        # Sleep so the pre-scan fingerprint predates a would-be fresh write.
        report = RV.run([sys.executable, "-c", "pass"], self.tmp.name, self.out,
                        suites=["suite.A"], xml_dir=xml_dir)
        self.assertIn("stale_xml:suite.A", report["failures"])
        self.assertNotEqual("passed", report["status"])


class SessionEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "repo"; self.root.mkdir()
        self.home = Path(self.tmp.name) / "codex-home"; self.home.mkdir()
        db = sqlite3.connect(self.home / "state_5.sqlite")
        db.executescript(
            "CREATE TABLE threads(id TEXT, rollout_path TEXT, updated_at_ms INTEGER,"
            " archived INTEGER, is_pinned INTEGER, title TEXT, thread_source TEXT);"
            "CREATE TABLE thread_spawn_edges(parent_thread_id TEXT, child_thread_id TEXT);")
        r1 = self.home / "sessions" / "a.jsonl"; r1.parent.mkdir()
        r1.write_text('{"payload":"apply_patch docs/notes.md"}\n{"x":1}\n')
        r2 = self.home / "sessions" / "b.jsonl"
        r2.write_text('{"payload":"unrelated"}\n')
        db.execute("INSERT INTO threads VALUES(?,?,?,?,?,?,?)",
                   ("p1", str(r1), 100, 0, 0, "parent sess", ""))
        db.execute("INSERT INTO threads VALUES(?,?,?,?,?,?,?)",
                   ("c1", str(r2), 200, 0, 0, "child sess", ""))
        db.execute("INSERT INTO thread_spawn_edges VALUES(?,?)", ("p1", "c1"))
        db.commit(); db.close()

    def test_index_parents_and_search(self):
        self.assertEqual(0, SE.cmd_index(self.root, self.home))
        con = SE.open_index(self.root)
        self.assertEqual(2, con.execute("SELECT COUNT(*) FROM sessions").fetchone()[0])
        self.assertEqual(2, con.execute("SELECT COUNT(*) FROM sessions WHERE present=1")
                         .fetchone()[0])
        con.close()
        # parents
        import io, contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            SE.cmd_parents(self.root, "c1")
        chain = json.loads(buf.getvalue())
        self.assertEqual("p1", chain["parents"][0]["id"])
        # search records hits at byte offsets
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            SE.cmd_search(self.root, "apply_patch")
        res = json.loads(buf.getvalue())
        self.assertEqual(1, res["hits"])
        self.assertEqual("p1", res["sample"][0]["session"])
        self.assertGreaterEqual(res["sample"][0]["offset"], 0)

    def test_search_bounds_and_records_query(self):
        SE.cmd_index(self.root, self.home)
        import io, contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            SE.cmd_search(self.root, "apply_patch", max_files=0)
        res = json.loads(buf.getvalue())
        self.assertEqual(1, res["bounded"])
        self.assertEqual(0, res["hits"])
        con = SE.open_index(self.root)
        self.assertEqual(1, con.execute("SELECT COUNT(*) FROM queries").fetchone()[0])
        con.close()


class HandoffTest(TempRoot):
    def test_handoff_packet_lists_files_runs_and_holds(self):
        WJ.open_journal(self.root, "task-handoff-1", "devin", "test", ["docs/notes.md"])
        self.run = "data/agent-handoff/codex-autonomy/task-handoff-1/cycle-1"
        CP.begin(self.root, self.run, ["docs/notes.md"], decision())
        new = self.root / "n.bin"; new.write_bytes(b"v2\n")
        CP.apply(self.root, self.run, "docs/notes.md", str(new))
        CP.seal(self.root, self.run)
        packet = WJ.handoff(self.root, "task-handoff-1")
        self.assertEqual("awx.handoff.v1", packet["schemaVersion"])
        self.assertEqual("docs/notes.md", packet["files"][0]["path"])
        self.assertEqual(sha(b"v2\n"), packet["files"][0]["currentSha256"])
        self.assertTrue((self.root / "data/agent-handoff/codex-autonomy/task-handoff-1"
                         / "handoff.json").exists())


class PreflightTest(unittest.TestCase):
    def test_preflight_reports_observed_surfaces(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "scripts").mkdir()
            (root / "docs").mkdir()
            (root / ".codex").mkdir()
            (root / ".codex" / "hooks.json").write_text("{}")
            (root / "docs" / "PROJECT_STATUS.md").write_text("# s\n")
            report = PF.collect(root)
            self.assertEqual("awx.agent-preflight.v1", report["schemaVersion"])
            self.assertTrue(report["protections"]["codexHook"]["present"])
            self.assertFalse(report["protections"]["devinHook"]["present"])
            self.assertTrue(report["statusDoc"]["present"])
            self.assertIn("status", report["journals"])  # scripts absent -> degraded field
            self.assertIn("status", report["deviceBus"])
            self.assertEqual("unavailable", report["projectRoot"]["status"])
            self.assertFalse(report["tools"]["agent_work_guard.py"])
            guide = report["leaseGuidance"]
            self.assertEqual("awx.agent-preflight.lease-guidance.v1", guide["schemaVersion"])
            self.assertEqual(
                ["begin", "end", "status", "verify", "bind-scope", "heartbeat", "recover"],
                guide["actions"])
            self.assertIn("open", guide["unsupportedActions"])
            self.assertEqual("TargetManifest", guide["targetManifest"]["param"])
            self.assertTrue(guide["targetManifest"]["inlineJsonNotAccepted"])
            self.assertIn("TargetPath", guide["targetManifest"]["unsupportedParams"])
            self.assertFalse(guide["blockingCount"]["repositoryWideHold"])
            self.assertTrue(guide["expiredUnknown"]["stillBlocksOverlap"])
            self.assertTrue(guide["statusWithoutManifest"]["exit7IfAnyBlockingLease"])
            self.assertTrue(guide["statusWithoutManifest"]["doesNotMeanRepositoryWideHold"])

    def test_lease_guidance_does_not_treat_global_blocking_count_as_a_repo_hold(self):
        guide = PF.lease_guidance({
            "status": "ok",
            "result": {"sourceLeaseBlockingCount": 4, "sourceLeaseExpiredCount": 3},
        })
        self.assertEqual(4, guide["blockingCount"]["value"])
        self.assertEqual("active+corrupt+expired", guide["blockingCount"]["formula"])
        self.assertFalse(guide["blockingCount"]["repositoryWideHold"])
        self.assertIn("TargetManifest", guide["overlap"]["how"])


if __name__ == "__main__":
    unittest.main()
