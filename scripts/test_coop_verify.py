"""Fake-contention acceptance for coop_verify deferred-verification rails.

Contract DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928 — temporary roots and
fake writer/build commands only; no Gradle, no product code, no live leases.
"""
import contextlib
from copy import deepcopy
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import subprocess
import time
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
        # 마스킹 이후 호출자는 .coop-token-<batchId> 파일 경로로 토큰을 찾는다
        # (token=None -> 파일 폴백 경로를 그대로 검증한다).
        return self._call(CV.cmd_writer_end, self.args(
            batch_id=w["editBatchId"], token=None))

    def state(self):
        return CV.load_state(self.store_dir())

    def test_request_equivalence_keeps_different_commands(self):
        first = self.request(command=self.counter_cmd(0))
        second = self.request(agent="other", command=self.counter_cmd(7))
        self.assertNotEqual(first["ticketId"], second["ticketId"])
        self.assertFalse(second["merged"])
        code, result = self._call(CV.cmd_run_once, self.args(ticket=first["ticketId"]))
        self.assertEqual((code, result["state"]), (0, "VERIFIED_PASS"))
        self.assertIn(self.state()["tickets"][second["ticketId"]]["state"], CV.TICKET_OPEN)
        code, result = self._call(CV.cmd_run_once, self.args(ticket=second["ticketId"]))
        self.assertEqual((code, result["state"]), (CV.EXIT_FAILED, "FAILED"))
        receipt = json.loads((self.store_dir() / result["receiptPath"]).read_text())
        self.assertEqual(receipt["verifyCommand"], self.counter_cmd(7))
        self.assertEqual((receipt["exitCode"], receipt["verdict"]), (7, "FAILED"))
        self.assertEqual(self.counter.read_text(), "2")

    def test_request_equivalence_keeps_overlapping_scopes(self):
        first = self.request(scope=("src",))
        second = self.request(agent="other", scope=("src/watched.py",),
                              command=self.counter_cmd(7))
        self.assertFalse(second["merged"])
        code, _ = self._call(CV.cmd_run_once, self.args(ticket=first["ticketId"]))
        self.assertEqual(code, 0)
        self.assertIn(self.state()["tickets"][second["ticketId"]]["state"], CV.TICKET_OPEN)
        code, result = self._call(CV.cmd_run_once, self.args(ticket=second["ticketId"]))
        self.assertEqual(code, CV.EXIT_FAILED)
        receipt = json.loads((self.store_dir() / result["receiptPath"]).read_text())
        self.assertEqual(receipt["verifyCommand"], self.counter_cmd(7))
        self.assertEqual((receipt["exitCode"], receipt["verdict"]), (7, "FAILED"))
        self.assertEqual(self.counter.read_text(), "2")

    def test_request_equivalence_keeps_distinct_contracts(self):
        cases = [
            ("task", {"task": "separate-task"}, "taskId", "separate-task"),
            ("profile", {"profile": "separate-profile"}, "profile", "separate-profile"),
            ("stages", {"stage": ["compile", "tests"]}, "requiredStages", ["compile", "tests"]),
            ("timeout", {"timeout_seconds": self.cfg["build_timeout_seconds"] + 1},
             "timeoutSeconds", self.cfg["build_timeout_seconds"] + 1),
            ("identity", {"target_identity": "different-input"}, "targetIdentity", "different-input"),
            ("mode", {"target_mode": "exact"}, "targetMode", "exact"),
        ]
        fields = ("taskId", "profile", "requiredStages", "timeoutSeconds",
                  "targetIdentity", "targetMode", "verifyCommand", "scope", "requester")
        for name, kwargs, field, value in cases:
            with self.subTest(contract=name):
                CV.save_state(self.store_dir(), CV.empty_state())
                first = self.request(agent="original")
                original = deepcopy(self.state()["tickets"][first["ticketId"]])
                second = self.request(agent="other", **kwargs)
                self.assertNotEqual(first["ticketId"], second["ticketId"])
                self.assertFalse(second["merged"])
                current = self.state()["tickets"]
                self.assertEqual(len(current), 2)
                self.assertEqual({f: current[first["ticketId"]][f] for f in fields},
                                 {f: original[f] for f in fields})
                self.assertEqual(current[second["ticketId"]][field], value)
                self.assertEqual(current[second["ticketId"]]["requester"], "other")

    def test_request_equivalence_merges_true_duplicates_once(self):
        first = self.request(agent="original", profile=None,
                             scope=("src/watched.py", "src"))
        state = self.state()
        oldest = "2000-01-01T00:00:00+00:00"
        state["tickets"][first["ticketId"]]["requestedAtUtc"] = oldest
        CV.save_state(self.store_dir(), state)
        second = self.request(agent="other", profile="default",
                              scope=("src", "src/watched.py", "src"),
                              stage=["verifyCommand"], timeout_seconds=0)
        self.assertTrue(second["merged"])
        self.assertEqual(second["ticketId"], first["ticketId"])
        ticket = self.state()["tickets"][first["ticketId"]]
        self.assertEqual(ticket["requestedAtUtc"], oldest)
        self.assertEqual(ticket["requester"], "original")
        self.assertEqual(len(ticket["coveredRequests"]), 1)
        self.assertEqual(ticket["coveredRequests"][0]["requester"], "other")
        self.assertEqual(ticket["coveredRequests"][0]["taskId"], "t-task")
        self.assertEqual(ticket["coveredRequests"][0]["verifyCommand"], self.counter_cmd())
        self.assertEqual(len(self.state()["tickets"]), 1)
        code, result = self.run_once()
        self.assertEqual((code, result["state"]), (0, "VERIFIED_PASS"))
        self.assertEqual(self.counter.read_text(), "1")
        self.assertEqual(len(list((self.store_dir() / "receipts").glob("*.json"))), 1)
        code, replay = self._call(CV.cmd_run_once, self.args(ticket=first["ticketId"]))
        self.assertEqual(code, CV.EXIT_NO_TICKET)
        self.assertEqual(replay["state"], "NO_PENDING_TICKET")
        self.assertEqual(self.counter.read_text(), "1")

    def test_request_equivalence_nonpass_preserves_pending(self):
        for verdict in ("FAILED", "INVALIDATED", "TIMEOUT", "VERIFIED_PASS"):
            with self.subTest(verdict=verdict):
                CV.save_state(self.store_dir(), CV.empty_state())
                self.counter.unlink(missing_ok=True)
                (self.root / "src/watched.py").write_text("x = 1\n")
                command = self.counter_cmd(7 if verdict == "FAILED" else 0)
                if verdict == "INVALIDATED":
                    command[2] = command[2].replace(
                        "sys.exit(0)", "pathlib.Path('src/watched.py').write_text('changed'); sys.exit(0)")
                if verdict == "TIMEOUT":
                    command = [sys.executable, "-c", "import time; time.sleep(5)"]
                first = self.request(command=command, timeout_seconds=1)
                state = self.state()
                pending = deepcopy(state["tickets"][first["ticketId"]])
                pending["ticketId"] = "legacy-pending"
                pending["requester"] = pending["agent"] = "other"
                state["tickets"][pending["ticketId"]] = pending
                CV.save_state(self.store_dir(), state)
                code, result = self._call(CV.cmd_run_once, self.args(ticket=first["ticketId"]))
                expected_code = {"FAILED": CV.EXIT_FAILED, "INVALIDATED": CV.EXIT_INVALIDATED,
                                 "TIMEOUT": CV.EXIT_ENV, "VERIFIED_PASS": 0}[verdict]
                self.assertEqual((code, result["state"]), (expected_code, verdict))
                receipt = json.loads((self.store_dir() / result["receiptPath"]).read_text())
                self.assertEqual(receipt["verdict"], verdict)
                self.assertEqual(receipt["verifyCommand"], command)
                if verdict == "FAILED":
                    self.assertEqual(receipt["exitCode"], 7)
                remaining = self.state()["tickets"][pending["ticketId"]]
                if verdict == "VERIFIED_PASS":
                    self.assertEqual(remaining["state"], "SUPERSEDED")
                    self.assertEqual(remaining["supersededBy"], first["ticketId"])
                else:
                    self.assertIn(remaining["state"], CV.TICKET_OPEN)
                    self.assertIsNone(remaining["supersededBy"])
                self.assertIsNone(remaining["receiptPath"])
                self.assertFalse((self.store_dir() / "receipts/legacy-pending.json").exists())
                if verdict == "TIMEOUT":
                    self.assertFalse(self.counter.exists())
                else:
                    self.assertEqual(self.counter.read_text(), "1")

    def test_request_equivalence_unknown_inputs_do_not_merge(self):
        fields = ("taskId", "profile", "targetMode", "targetIdentity", "scope",
                  "verifyCommand", "requiredStages", "timeoutSeconds")
        malformed = {"taskId": 1, "profile": [], "targetMode": 1,
                     "targetIdentity": [], "scope": "src", "verifyCommand": "python",
                     "requiredStages": "verifyCommand", "timeoutSeconds": True}
        cases = [("unknown", "taskId", "unknown")]
        cases += [("missing-" + f, f, None) for f in fields]
        cases += [("bad-type-" + f, f, malformed[f]) for f in fields]
        cases += [("bad-scope-item", "scope", [1]),
                  ("bad-command-item", "verifyCommand", [1]),
                  ("bad-stage-item", "requiredStages", [1])]
        for name, field, value in cases:
            with self.subTest(legacy=name):
                CV.save_state(self.store_dir(), CV.empty_state())
                first = self.request()
                state = self.state()
                complete = deepcopy(state["tickets"][first["ticketId"]])
                legacy = state["tickets"][first["ticketId"]]
                if name.startswith("missing-"):
                    del legacy[field]
                else:
                    legacy[field] = value
                legacy_fields = {f: deepcopy(legacy[f]) for f in fields if f in legacy}
                CV.save_state(self.store_dir(), state)
                second = self.request(agent="other", task="unknown" if name == "unknown" else "t-task")
                self.assertFalse(second["merged"])
                self.assertNotEqual(second["ticketId"], first["ticketId"])
                current = self.state()
                done = deepcopy(complete)
                done.update(ticketId="finished", state="VERIFIED_PASS")
                CV._supersede_stale_open(current, done, CV.datetime.now(CV.timezone.utc))
                self.assertIn(current["tickets"][first["ticketId"]]["state"], CV.TICKET_OPEN)
                self.assertEqual({f: current["tickets"][first["ticketId"]][f]
                                  for f in fields if f in current["tickets"][first["ticketId"]]},
                                 legacy_fields)
                self.assertEqual(current["tickets"][second["ticketId"]]["state"],
                                 "SUPERSEDED" if name != "unknown" else second["state"])

    def test_recover_invalidates_dead_verifier_without_lock(self):
        from unittest.mock import patch
        first = self.request()
        state = self.state()
        ticket = state["tickets"][first["ticketId"]]
        ticket.update(state="VERIFYING", verifierPid=999999)
        before = deepcopy(ticket)
        CV.save_state(self.store_dir(), state)
        with patch.object(CV.ck, "pid_alive", return_value=False):
            code, _ = self._call(CV.cmd_recover, self.args())
        self.assertEqual(code, 0)
        after = self.state()["tickets"][first["ticketId"]]
        self.assertEqual(after["state"], "INVALIDATED")
        self.assertEqual({k: v for k, v in after.items() if k != "state"},
                         {k: v for k, v in before.items() if k != "state"})
        self.assertFalse(self.counter.exists())
        self.assertFalse((self.store_dir() / "receipts").exists())

    def test_recover_preserves_live_or_locked_verifiers(self):
        from unittest.mock import patch
        cases = [("alive", os.getpid(), True, None),
                 ("live-lock", 999999, False, os.getpid()),
                 ("dead-lock", 999999, False, 999999),
                 ("directory-lock", 999999, False, "directory"),
                 ("unknown-probe", 999999, None, None)]
        cases += [("bad-pid-" + str(i), pid, False, None)
                  for i, pid in enumerate((None, "999999", True, 0, -1))]
        lock = self.store_dir() / ".verify.lock"
        for name, pid, alive, lock_pid in cases:
            with self.subTest(safety=name):
                if lock.is_dir():
                    lock.rmdir()
                else:
                    lock.unlink(missing_ok=True)
                first = self.request(task=name)
                state = self.state()
                state["tickets"][first["ticketId"]].update(state="VERIFYING", verifierPid=pid)
                before = deepcopy(state["tickets"][first["ticketId"]])
                CV.save_state(self.store_dir(), state)
                if lock_pid == "directory":
                    lock.mkdir()
                elif lock_pid is not None:
                    lock.write_text(json.dumps({"pid": lock_pid}))
                with patch.object(CV.ck, "pid_alive", side_effect=lambda p:
                                  True if p == os.getpid() else alive):
                    code, _ = self._call(CV.cmd_recover, self.args())
                self.assertEqual(code, 0)
                self.assertEqual(self.state()["tickets"][first["ticketId"]], before)
                self.assertFalse(self.counter.exists())

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

    def test_closed_ticket_capacity_preserves_evidence_and_historical_lookup(self):
        state = CV.empty_state()
        state["tickets"] = {
            f"cv-old-{i:03d}": {"ticketId": f"cv-old-{i:03d}", "state": "FAILED",
                "updatedAtUtc": "2026-10-01T00:00:00+00:00",
                "coveredRequests": [{"requester": "old-owner", "taskId": "old-task"}],
                "receiptPath": f"receipts/cv-old-{i:03d}.json"}
            for i in range(CV.MAX_TICKETS)
        }
        receipt = self.store_dir() / "receipts/cv-old-000.json"
        receipt.parent.mkdir(parents=True)
        receipt.write_bytes(b'{"verdict":"FAILED","exitCode":7}\n')
        before_receipt = receipt.read_bytes()
        CV.save_state(self.store_dir(), state)
        result = self.request()
        current = self.state()
        self.assertEqual(len(current["tickets"]), CV.MAX_TICKETS)
        self.assertNotEqual(result["state"], "VERIFIED_PASS")
        archives = list((self.store_dir() / "closed-tickets").glob("*.json"))
        self.assertEqual([p.name for p in archives], ["cv-old-000.json"])
        self.assertEqual(json.loads(archives[0].read_text(encoding="utf-8")), state["tickets"]["cv-old-000"])
        self.assertNotIn("cv-old-000", current["tickets"])
        self.assertEqual(receipt.read_bytes(), before_receipt)
        _, status = self._call(CV.cmd_status, self.args(ticket="cv-old-000"))
        self.assertEqual(status["tickets"], {"cv-old-000": state["tickets"]["cv-old-000"]})
        code, replay = self._call(CV.cmd_run_once, self.args(ticket="cv-old-000"))
        self.assertEqual((code, replay["state"], replay["alreadyClosed"]), (CV.EXIT_FAILED, "FAILED", True))
        self.assertEqual(replay["receiptPath"], "receipts/cv-old-000.json")
        self.assertFalse(self.counter.exists())
        current = self.state()
        current["tickets"][result["ticketId"]]["state"] = "SUPERSEDED"
        CV.save_state(self.store_dir(), current)
        code, replay = self._call(CV.cmd_run_once, self.args(ticket="cv-old-000"))
        self.assertEqual((code, replay["state"], replay["alreadyClosed"]), (CV.EXIT_FAILED, "FAILED", True))
        self.assertFalse(self.counter.exists())

    def test_ticket_capacity_keeps_pending_inflight_unknown_and_blocked_records(self):
        for ticket_state in ("WAITING_FOR_RUNNER", "VERIFYING", "BLOCKED_UNKNOWN_OWNER", "UNKNOWN"):
            with self.subTest(state=ticket_state):
                state = CV.empty_state()
                state["tickets"] = {
                    f"cv-open-{i:03d}": {"ticketId": f"cv-open-{i:03d}", "state": ticket_state}
                    for i in range(CV.MAX_TICKETS)
                }
                CV.save_state(self.store_dir(), state)
                with self.assertRaisesRegex(Exception, "ticket-cap"):
                    self.request()
                self.assertEqual(self.state(), state)
                self.assertFalse((self.store_dir() / "closed-tickets").exists())

    def test_ticket_archive_failure_keeps_saved_state_and_receipts(self):
        from unittest.mock import patch
        state = CV.empty_state()
        state["tickets"] = {
            f"cv-old-{i:03d}": {"ticketId": f"cv-old-{i:03d}", "state": "VERIFIED_PASS"}
            for i in range(CV.MAX_TICKETS)
        }
        CV.save_state(self.store_dir(), state)
        with patch.object(CV.ck, "write_json", side_effect=OSError("synthetic archive failure")):
            with self.assertRaisesRegex(OSError, "synthetic archive failure"):
                self.request()
        self.assertEqual(self.state(), state)
        self.assertFalse(self.counter.exists())

    # -- T1: foreign EDITING writer → zero builds, DEFERRED ticket ------------
    def test_status_preserves_writer_registered_before_lock_acquisition(self):
        from unittest.mock import patch

        real_lock = CV.exclusive_lock
        interleaved = {}

        @contextlib.contextmanager
        def delayed_status_lock(directory, name, busy_error):
            if name == ".coop.lock" and not interleaved:
                interleaved["started"] = True
                interleaved["writer"] = self.writer_begin(agent="concurrent")
            with real_lock(directory, name, busy_error) as handle:
                yield handle

        with patch.object(CV, "exclusive_lock", delayed_status_lock):
            self.status()

        self.assertIn(interleaved["writer"]["editBatchId"], self.state()["writers"],
                      "status must preserve a writer committed before its lock acquisition")

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

    # -- happy path: VERIFIED_PASS + receipt + independent overlapping pending --
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
        self.assertIn(self.state()["tickets"][overlapping["ticketId"]]["state"], CV.TICKET_OPEN)
        code, result = self._call(CV.cmd_run_once, self.args(ticket=overlapping["ticketId"]))
        self.assertEqual((code, result["ticketId"]), (0, overlapping["ticketId"]))
        own_receipt = json.loads((self.store_dir() / result["receiptPath"]).read_text())
        self.assertEqual(own_receipt["verifyCommand"], self.counter_cmd())
        self.assertEqual(own_receipt["verdict"], "VERIFIED_PASS")
        self.assertEqual(self.counter.read_text(), "2")

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

    def held_verification_command(self, label, release):
        """Actual child/CLI coordination with isolated synthetic outputs, not Gradle proof."""
        output = self.root / ('output-' + label)
        output.mkdir()
        return [sys.executable, '-B', '-c',
            "import sys,time,json; from pathlib import Path; "
            "out,release=map(Path,sys.argv[1:]); started=time.perf_counter_ns(); "
            "(out/'entered').write_text('ready'); deadline=time.monotonic()+15\n"
            "while not release.exists() and time.monotonic()<deadline: time.sleep(.02)\n"
            "assert release.exists(), 'fixture release deadline'\n"
            "(out/'result.json').write_text(json.dumps({'started':started,'ended':time.perf_counter_ns(),'runs':1}))\n",
            str(output), str(release)]

    def start_verifier_cli(self, ticket, store=None):
        return subprocess.Popen([sys.executable, '-B', str(SCRIPT), '--root', str(self.root),
            '--store', store or self.store, '--set', 'source_quiet_seconds=0',
            'run-once', '--ticket', ticket], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def wait_for_output_entry(self, label, process):
        deadline = time.monotonic() + 10
        entered = self.root / ('output-' + label) / 'entered'
        while not entered.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertTrue(entered.exists(), f'child never entered: exit={process.poll()}')
        self.assertIsNone(process.poll())

    def finish_verifier_cli(self, process):
        stdout, stderr = process.communicate(timeout=20)
        self.assertEqual(process.returncode, 0, stderr.decode('utf-8', 'replace'))
        result = json.loads(stdout.decode('utf-8'))
        self.assertEqual(result['state'], 'VERIFIED_PASS')
        return result

    def test_real_running_verifier_defers_same_shared_output_without_second_command(self):
        release = self.root / 'release'
        first = self.request(command=self.held_verification_command('shared', release))
        process = self.start_verifier_cli(first['ticketId'])
        try:
            self.wait_for_output_entry('shared', process)
            second = self.request(agent='other', command=self.counter_cmd())
            command = [sys.executable, '-B', str(SCRIPT), '--root', str(self.root), '--store', self.store,
                '--set', 'source_quiet_seconds=0', 'run-once', '--ticket', second['ticketId']]
            blocked = subprocess.run(command, capture_output=True, timeout=10)
            report = json.loads(blocked.stdout)
            self.assertEqual(blocked.returncode, CV.EXIT_DEFERRED)
            self.assertEqual(report['state'], 'DEFERRED')
            self.assertNotEqual(report['state'], 'VERIFIED_PASS')
            self.assertFalse(self.counter.exists())
            self.assertIsNone(process.poll())
            release.write_text('release')
            self.finish_verifier_cli(process)
            result = json.loads((self.root / 'output-shared/result.json').read_text())
            self.assertEqual(result['runs'], 1)
        finally:
            release.write_text('release')
            if process.poll() is None:
                process.communicate(timeout=20)

    def test_real_separated_output_resources_verify_in_overlapping_intervals(self):
        release = self.root / 'release-separated'
        processes = []
        try:
            for label in ['left', 'right']:
                self.store = str(self.root / ('coop-' + label))
                request = self.request(agent=label, command=self.held_verification_command(label, release))
                processes.append((label, self.start_verifier_cli(request['ticketId'], self.store)))
            for label, process in processes:
                self.wait_for_output_entry(label, process)
            self.assertTrue(all(p.poll() is None for _, p in processes))
            release.write_text('release')
            intervals = []
            for label, process in processes:
                result = self.finish_verifier_cli(process)
                self.assertEqual(result['state'], 'VERIFIED_PASS')
                intervals.append(json.loads((self.root / ('output-' + label) / 'result.json').read_text()))
            self.assertLess(max(r['started'] for r in intervals), min(r['ended'] for r in intervals))
            self.assertEqual([r['runs'] for r in intervals], [1, 1])
            for label, _ in processes:
                self.assertFalse((self.root / ('coop-' + label) / '.verify.lock').exists())
        finally:
            release.write_text('release')
            for _, process in processes:
                if process.poll() is None:
                    process.communicate(timeout=20)

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
            "tf=pathlib.Path(r'" + str(self.store_dir()) + "')/('.coop-token-'+tok['editBatchId'])\n"
            "realtok=json.loads(tf.read_text())['writerToken']\n"
            "subprocess.run(cv+['writer-heartbeat','--batch-id',tok['editBatchId'],"
            "'--token',realtok,'--source-changed'])\n"
            "original=pathlib.Path(scr).read_text()\n"
            "pathlib.Path(scr).write_text(original+'# touched\\n')\n"
            "pathlib.Path(scr).write_text(original)\n"
            "subprocess.run(cv+['writer-end','--batch-id',tok['editBatchId'],"
            "'--token',realtok])\n")
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

    # -- WP1: writer-begin stdout은 평문 토큰을 노출하지 않는다 ------------------
    def test_writer_begin_stdout_masks_token_but_file_carries_it(self):
        capture = io.StringIO()
        with contextlib.redirect_stdout(capture):
            code = CV.cmd_writer_begin(self.root, self.store_dir(),
                                       self.args(agent="masked"), self.cfg)
        self.assertEqual(code, 0)
        raw = capture.getvalue()
        res = json.loads(raw)
        real = self.state()["writers"][res["editBatchId"]]["writerToken"]
        self.assertEqual(res["writerToken"], real[:4] + "..." + real[-4:])
        self.assertNotIn(real, raw)
        token_path = self.store_dir() / (".coop-token-" + res["editBatchId"])
        doc = json.loads(token_path.read_text(encoding="utf-8"))
        self.assertEqual(doc["writerToken"], real)
        # 파일 폴백 경로로 heartbeat/end가 동작하고 end가 토큰 파일을 정리한다.
        code, _ = self._call(CV.cmd_writer_heartbeat, self.args(
            batch_id=res["editBatchId"], token=None))
        self.assertEqual(code, 0)
        code, _ = self._call(CV.cmd_writer_end, self.args(
            batch_id=res["editBatchId"], token=None))
        self.assertEqual(code, 0)
        self.assertFalse(token_path.exists())


if __name__ == "__main__":
    unittest.main()
