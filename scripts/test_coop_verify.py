"""Fake-contention acceptance for coop_verify deferred-verification rails.

Contract DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928 — temporary roots and
fake writer/build commands only; no Gradle, no product code, no live leases.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("coop_verify.py")
SPEC = importlib.util.spec_from_file_location("coop_verify", SCRIPT) if SCRIPT.exists() else None
CV = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("coop_verify", CV)
    SPEC.loader.exec_module(CV)


class CoopVerifyTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(CV, "coop_verify helper is not implemented")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "src").mkdir()
        (self.root / "src" / "watched.py").write_text("x = 1\n", encoding="utf-8")
        self.store = str(self.root / "coop-store")
        self.cfg = dict(CV.DEFAULTS)
        self.cfg["source_quiet_seconds"] = 0
        self.counter = self.root / "build-count.txt"

    # -- helpers -----------------------------------------------------------
    def args(self, **kw):
        class A:
            pass
        a = A()
        for key, default in {
            "agent": None, "ticket": None, "task": "t-task", "session": None,
            "batch_id": None, "lease_id": None, "pid": None, "path": [],
            "token": None, "source_changed": False, "profile": "compile",
            "scope": ["src"], "command": None, "command_json": None,
            "stage": [], "target_mode": "latest", "target_identity": None,
            "timeout_seconds": None, "runner_id": None, "iterations": None,
            "poll": None, "release": False,
        }.items():
            setattr(a, key, kw.get(key, default))
        return a

    def store_dir(self) -> Path:
        return CV.store_dir(self.root, self.store)

    def _call(self, func, args):
        capture = io.StringIO()
        with contextlib.redirect_stdout(capture):
            code = func(self.root, self.store_dir(), args, self.cfg)
        out = capture.getvalue().strip()
        return code, json.loads(out) if out else {}

    def writer_begin(self, agent="foreign", pid=None, paths=("src/watched.py",)):
        code, res = self._call(CV.cmd_writer_begin, self.args(
            agent=agent, pid=pid or os.getpid(), path=list(paths)))
        self.assertEqual(code, 0, res)
        return res

    def counter_cmd(self, exit_code=0):
        """Fake build: bumps a counter file, exits exit_code."""
        return [
            sys.executable, "-c",
            "import pathlib,sys; p=pathlib.Path(sys.argv[1]); "
            "p.write_text(str(int(p.read_text() or '0')+1) if p.exists() else '1'); "
            "sys.exit(" + str(exit_code) + ")", str(self.counter)]

    def request(self, agent="me", command=None, scope=("src",), **kw):
        code, res = self._call(CV.cmd_request, self.args(
            agent=agent, scope=list(scope),
            command_json=json.dumps(command or self.counter_cmd()), **kw))
        self.assertEqual(code, 0, res)
        return res

    def run_once(self):
        return self._call(CV.cmd_run_once, self.args())

    def status(self, agent=None):
        return self._call(CV.cmd_status, self.args(agent=agent))[1]

    def writer_end(self, w):
        return self._call(CV.cmd_writer_end, self.args(
            batch_id=w["editBatchId"], **{"token": w["writerToken"]}))

    def state(self):
        return CV.load_state(self.store_dir())

    def test_released_writer_history_is_archived_before_reusing_bounded_slot(self):
        state = CV.empty_state()
        state["writers"] = {
            f"closed-{i:02d}": {"editBatchId": f"closed-{i:02d}", "state": "RELEASED",
                                 "endedAtUtc": "2026-10-01T00:00:00+00:00"}
            for i in range(CV.MAX_WRITERS)
        }
        CV.save_state(self.store_dir(), state)
        self.writer_begin(agent="new-owner")
        current = self.state()
        self.assertEqual(len(current["writers"]), CV.MAX_WRITERS)
        archive = self.store_dir() / "released-writers/closed-00.json"
        self.assertEqual(json.loads(archive.read_text(encoding="utf-8")), state["writers"]["closed-00"])
        self.assertNotIn("closed-00", current["writers"])
        # An archived identity cannot be reused to impersonate its old owner.
        with self.assertRaisesRegex(Exception, "edit-batch-id-taken"):
            self._call(CV.cmd_writer_begin, self.args(agent="new-owner", batch_id="closed-00"))

    def test_writer_capacity_still_refuses_live_records_without_removing_them(self):
        state = CV.empty_state()
        state["writers"] = {
            f"live-{i:02d}": {"editBatchId": f"live-{i:02d}", "state": "EDITING"}
            for i in range(CV.MAX_WRITERS)
        }
        CV.save_state(self.store_dir(), state)
        with self.assertRaisesRegex(Exception, "writer-cap"):
            self.writer_begin(agent="new-owner")
        self.assertEqual(self.state(), state)
        self.assertFalse((self.store_dir() / "released-writers").exists())

    def test_archive_failure_keeps_released_records_in_live_state(self):
        from unittest.mock import patch
        state = CV.empty_state()
        state["writers"] = {
            f"closed-{i:02d}": {"editBatchId": f"closed-{i:02d}", "state": "RELEASED",
                                 "endedAtUtc": "2026-10-01T00:00:00+00:00"}
            for i in range(CV.MAX_WRITERS)
        }
        CV.save_state(self.store_dir(), state)
        with patch.object(CV.ck, "write_json", side_effect=OSError("synthetic archive failure")):
            with self.assertRaisesRegex(OSError, "synthetic archive failure"):
                self.writer_begin(agent="new-owner")
        self.assertEqual(self.state(), state)

    # -- T1: foreign EDITING writer → zero builds, DEFERRED ticket ------------
    def test_t1_foreign_editing_defers_and_never_builds(self):
        w = self.writer_begin(agent="codex-other")
        req = self.request()
        self.assertEqual(req["state"], "DEFERRED")
        self.assertIn(w["editBatchId"], req["waitingOn"])
        code, res = self.run_once()
        self.assertEqual(code, CV.EXIT_DEFERRED)
        self.assertEqual(res["state"], "DEFERRED")
        self.assertFalse(self.counter.exists(),
                         "verify must not run while a foreign writer edits")
        self.assertEqual(self.state()["tickets"][req["ticketId"]]["state"], "DEFERRED")

    # -- happy path: VERIFIED_PASS + receipt + overlapping pending SUPERSEDED --
    def test_pass_receipt_and_supersede(self):
        first = self.request(scope=("src",))
        self.assertEqual(first["state"], "WAITING_FOR_RUNNER")
        overlapping = self.request(agent="other", scope=("src/watched.py",))
        self.assertFalse(overlapping.get("merged"), "different scope must not merge")
        code, res = self.run_once()
        self.assertEqual(code, 0)
        self.assertEqual(res["state"], "VERIFIED_PASS")
        self.assertEqual(res["ticketId"], first["ticketId"])
        self.assertTrue(self.counter.exists())
        receipt = self.store_dir() / "receipts" / (first["ticketId"] + ".json")
        data = json.loads(receipt.read_text(encoding="utf-8"))
        self.assertEqual(data["verdict"], "VERIFIED_PASS")
        self.assertEqual(data["exitCode"], 0)
        self.assertEqual(self.state()["tickets"][overlapping["ticketId"]]["state"],
                         "SUPERSEDED")

    # -- T2: single heavy verifier -------------------------------------------
    def test_t2_second_verifier_is_deferred(self):
        self.request()
        lock = self.store_dir() / ".verify.lock"
        self.store_dir().mkdir(parents=True, exist_ok=True)
        lock.write_text(json.dumps({"pid": os.getpid()}), encoding="utf-8")
        try:
            code, res = self.run_once()
        finally:
            lock.unlink()
        self.assertEqual(code, CV.EXIT_DEFERRED)
        self.assertEqual(res["deferReason"], "verifier-busy")
        self.assertFalse(self.counter.exists())

    # -- T3: duplicate requests merge, oldest requestedAtUtc kept ------------
    def test_t3_duplicate_requests_merge_keep_oldest(self):
        first = self.request(agent="a")
        state = self.state()
        state["tickets"][first["ticketId"]]["requestedAtUtc"] = "2000-01-01T00:00:00+00:00"
        CV.save_state(self.store_dir(), state)
        second = self.request(agent="b")
        self.assertTrue(second["merged"])
        self.assertEqual(second["ticketId"], first["ticketId"])
        ticket = self.state()["tickets"][first["ticketId"]]
        self.assertEqual(ticket["requestedAtUtc"], "2000-01-01T00:00:00+00:00")
        self.assertEqual(len(ticket["coveredRequests"]), 1)
        self.assertEqual(len(self.state()["tickets"]), 1)

    # -- T4: Stop/turn-end may end on APPLIED_PENDING; DEFERRED != PASS --------
    def test_t4_turn_end_pending_is_not_pass(self):
        w = self.writer_begin(agent="me")
        self.request(agent="me")
        st = self.status(agent="me")
        self.assertFalse(st["turnEnd"]["allowedToEnd"],
                         "open writer must checkpoint/end first")
        self.writer_end(w)
        st = self.status(agent="me")
        self.assertTrue(st["turnEnd"]["allowedToEnd"])
        self.assertEqual(st["turnEnd"]["reportState"], "APPLIED_PENDING_VERIFICATION")
        self.assertNotEqual(st["turnEnd"]["reportState"], "VERIFIED_PASS")
        self.assertEqual(len(st["turnEnd"]["pendingTickets"]), 1)

    # -- T5: orphan writer → BLOCKED_UNKNOWN_OWNER, still not PASS -------------
    def test_t5_orphan_writer_blocks_never_passes(self):
        w = self.writer_begin(agent="ghost", pid=999999, paths=["src/watched.py"])
        state = self.state()
        state["writers"][w["editBatchId"]]["heartbeatAtUtc"] = "2000-01-01T00:00:00+00:00"
        CV.save_state(self.store_dir(), state)
        self.request()
        code, res = self.run_once()
        self.assertEqual(code, CV.EXIT_DEFERRED)
        _, rep = self._call(CV.cmd_recover, self.args())
        self.assertIn(w["editBatchId"], rep["blockedUnknown"])
        self.assertEqual(self.state()["writers"][w["editBatchId"]]["state"],
                         "BLOCKED_UNKNOWN_OWNER")
        code, res = self.run_once()
        self.assertEqual(code, CV.EXIT_DEFERRED)
        self.assertFalse(self.counter.exists(),
                         "unknown-owner tree must not verify as PASS")
        _, rep = self._call(CV.cmd_recover, self.args(release=True))
        self.assertIn(w["editBatchId"], rep["releasedDead"])
        code, res = self.run_once()
        self.assertEqual(code, 0)
        self.assertEqual(res["state"], "VERIFIED_PASS")

    # -- T6: scoped edit during VERIFYING → INVALIDATED (A→B→A, epoch check) ---
    def test_t6_mid_verify_edit_invalidates(self):
        script = self.root / "src" / "watched.py"
        inner = (
            "import subprocess,sys,json,pathlib\n"
            "cv=[sys.executable,r'" + str(SCRIPT) + "','--root',r'" + str(self.root)
            + "','--store',r'" + str(self.store_dir()) + "']\n"
            "scr=r'" + str(script) + "'\n"
            "w=subprocess.run(cv+['writer-begin','--agent','sneaky',"
            "'--path','src/watched.py'],"
            "capture_output=True,text=True)\n"
            "tok=json.loads(w.stdout)\n"
            "subprocess.run(cv+['writer-heartbeat','--batch-id',tok['editBatchId'],"
            "'--token',tok['writerToken'],'--source-changed'])\n"
            "original=pathlib.Path(scr).read_text()\n"
            "pathlib.Path(scr).write_text(original+'# touched\\n')\n"
            "pathlib.Path(scr).write_text(original)\n"
            "subprocess.run(cv+['writer-end','--batch-id',tok['editBatchId'],"
            "'--token',tok['writerToken']])\n")
        self.request(command=[sys.executable, "-c", inner])
        code, res = self.run_once()
        self.assertEqual(code, CV.EXIT_INVALIDATED)
        self.assertEqual(res["state"], "INVALIDATED")
        self.assertTrue(res["interferingWriters"],
                        "mid-verify writer epoch must be recorded")
        receipt = self.store_dir() / "receipts" / (res["ticketId"] + ".json")
        self.assertEqual(json.loads(receipt.read_text(encoding="utf-8"))["verdict"],
                         "INVALIDATED")

    # -- T7: stable input, real failure → FAILED (no PASS disguise) -------------
    def test_t7_stable_failure_records_failed(self):
        self.request(command=self.counter_cmd(exit_code=3))
        code, res = self.run_once()
        self.assertEqual(code, CV.EXIT_FAILED)
        self.assertEqual(res["state"], "FAILED")
        self.assertEqual(res["exitCode"], 3)
        st = self.status(agent="me")
        self.assertEqual(st["turnEnd"]["reportState"], "NOT_VERIFIED")

    # -- stale heartbeat never equals done -------------------------------------
    def test_stale_heartbeat_still_defers(self):
        w = self.writer_begin(agent="stale-one")
        state = self.state()
        state["writers"][w["editBatchId"]]["heartbeatAtUtc"] = "2000-01-01T00:00:00+00:00"
        CV.save_state(self.store_dir(), state)
        req = self.request()
        self.assertEqual(req["state"], "DEFERRED")
        self.assertIn(w["editBatchId"], req["waitingOn"])

    # -- quiet window holds verification until the source settles --------------
    def test_quiet_window_blocks_then_clears(self):
        w = self.writer_begin(agent="me")
        self.writer_end(w)
        self.cfg["source_quiet_seconds"] = 900
        req = self.request()
        self.assertEqual(req["state"], "QUIESCING")
        self.cfg["source_quiet_seconds"] = 0
        code, res = self.run_once()
        self.assertEqual(code, 0)
        self.assertEqual(res["state"], "VERIFIED_PASS")

    # -- writer token fences heartbeat/checkpoint/end ---------------------------
    def test_writer_token_mismatch_refused(self):
        w = self.writer_begin(agent="me")
        with self.assertRaises(Exception) as caught:
            self._call(CV.cmd_writer_heartbeat, self.args(
                batch_id=w["editBatchId"], **{"token": "wrong-token"}))
        self.assertIn("writer-token-mismatch", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
