"""Interrupted run ownership, using temporary leases and no real processes."""
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "port_lease_cleanup", Path(__file__).with_name("agent_port_lease.py"))
APL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(APL)


class RunCleanupPendingTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.rt = APL.AgentPortLease(Path(temporary.name))
        for name, kwargs in (
            ("port_bindable", {"return_value": True}),
            ("listen_owner", {"return_value": None}),
            ("process_identity", {"side_effect": AssertionError("identity lookup forbidden")}),
            ("kill_process", {"side_effect": AssertionError("termination forbidden")}),
        ):
            patcher = mock.patch.object(self.rt, name, **kwargs)
            setattr(self, name, patcher.start())
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(APL.subprocess, "Popen",
                                    side_effect=AssertionError("real child forbidden"))
        self.popen = patcher.start()
        self.addCleanup(patcher.stop)
        self.argv = ["fixture-command"]
        self.run_args = {"port_range": (25000, 25003), "attempts": 1}

    def fake_start(self, owner, session, lease_id, argv, **kwargs):
        lease = self.rt._require_owned(lease_id, owner, session)
        lease.update(state="running", pid=98765, processCreateTime=123456,
                     killAllowed=True)
        self.rt.save_lease(lease)
        return APL.result(True, lease=self.rt._public_lease(lease))

    def interrupted(self, error=None, callback=None):
        error = error or KeyboardInterrupt()

        def verify(*args, **kwargs):
            if callback:
                callback()
            raise error

        with mock.patch.object(self.rt, "start", side_effect=self.fake_start), \
                mock.patch.object(self.rt, "verify", side_effect=verify), \
                mock.patch.object(self.rt, "close") as close, \
                mock.patch.object(self.rt, "release") as release, \
                mock.patch("sys.stderr", new_callable=io.StringIO) as stderr:
            with self.assertRaises(type(error)) as raised:
                self.rt.run("owner", "session", "service", self.argv, **self.run_args)
            self.assertIs(raised.exception, error)
            close.assert_not_called()
            release.assert_not_called()
        self.popen.assert_not_called()
        self.kill_process.assert_not_called()
        self.process_identity.assert_not_called()
        return self.rt.list_leases()[0], stderr.getvalue()

    def test_interrupts_preserve_owned_identity_without_stopping(self):
        for error in (KeyboardInterrupt(), SystemExit(7), RuntimeError("private message")):
            with self.subTest(exception=type(error).__name__):
                # Each exception gets a fresh owner-scoped fixture store.
                self.rt.store = self.rt.root / type(error).__name__
                lease, diagnostic = self.interrupted(error)
                self.assertEqual("cleanup_pending", lease["state"])
                self.assertEqual("verify-interrupted", lease["cause"])
                self.assertEqual((98765, 123456, True),
                                 (lease["pid"], lease["processCreateTime"], lease["killAllowed"]))
                self.assertEqual(("owner", "session", "service"),
                                 (lease["owner"], lease["session"], lease["service"]))
                self.assertIsNone(lease["endedAt"])
                self.assertEqual("", diagnostic)
                trace = self.rt.read_traces()[-1]
                self.assertEqual("cleanup_pending", trace["endStatus"])
                self.assertEqual(type(error).__name__, trace["exceptionType"])
                self.assertNotIn("private message", str(trace))

    def test_pending_reservation_survives_ttl_and_reap(self):
        lease, _ = self.interrupted()
        lease["expiresAt"] = "2000-01-01T00:00:00+00:00"
        self.rt.save_lease(lease)
        self.assertTrue(self.rt.holds_port(lease))
        self.assertEqual("cleanup_pending", self.rt.status()["leases"][0]["state"])
        with mock.patch.object(self.rt, "close") as close:
            self.assertEqual([], self.rt.reap("owner", "session")["closed"])
            close.assert_not_called()
        other = self.rt.acquire("other", "session", "service", port_range=(25000, 25003))
        self.assertTrue(other["ok"])
        self.assertNotEqual(lease["port"], other["port"])

    def test_pending_stop_close_release_refuse_even_without_pid(self):
        lease, _ = self.interrupted()
        for pid in (98765, None):
            with self.subTest(pid=pid):
                lease["pid"] = pid
                self.rt.save_lease(lease)
                for action in (self.rt.stop, self.rt.close, self.rt.release):
                    outcome = action("owner", "session", lease["leaseId"])
                    self.assertFalse(outcome["ok"])
                    self.assertEqual("cleanup-pending", outcome["cause"])
                    self.assertEqual(lease, self.rt.load_lease(lease["leaseId"]))
        self.kill_process.assert_not_called()
        self.process_identity.assert_not_called()

    def test_same_identity_cannot_allocate_or_start_again(self):
        lease, _ = self.interrupted()
        with mock.patch.object(self.rt, "start") as start, \
                mock.patch.object(self.rt, "verify") as verify:
            outcome = self.rt.run("owner", "session", "service", self.argv,
                                  port_range=(25000, 25003), attempts=3)
            self.assertFalse(outcome["ok"])
            self.assertEqual("cleanup-pending", outcome["cause"])
            self.assertEqual(1, len(outcome["attempts"]))
            start.assert_not_called()
            verify.assert_not_called()
        self.assertEqual([lease], self.rt.list_leases())

    def test_other_owner_session_service_remain_independent(self):
        pending, _ = self.interrupted()
        for owner, session, service in (("other", "session", "service"),
                                         ("owner", "other", "service"),
                                         ("owner", "session", "other")):
            outcome = self.rt.acquire(owner, session, service, port_range=(25000, 25003))
            self.assertTrue(outcome["ok"])
            self.assertNotEqual(pending["port"], outcome["port"])
        self.assertEqual(pending, self.rt.load_lease(pending["leaseId"]))

    def test_changed_identity_or_terminal_state_is_not_overwritten(self):
        for field, value in (("pid", 98766), ("processCreateTime", 654321),
                             ("owner", "foreign"), ("state", "released")):
            with self.subTest(field=field):
                self.rt.store = self.rt.root / field

                def change():
                    lease = self.rt.list_leases()[0]
                    lease[field] = value
                    self.rt.save_lease(lease)

                lease, diagnostic = self.interrupted(callback=change)
                self.assertEqual(value, lease[field])
                self.assertNotEqual("cleanup_pending", lease["state"])
                self.assertIn("cleanup-pending-record-failed", diagnostic)

    def test_save_failure_does_not_mask_original_exception(self):
        def fail_save():
            self.rt.save_lease = mock.Mock(side_effect=OSError("private failure"))

        lease, diagnostic = self.interrupted(RuntimeError("original"), fail_save)
        self.assertEqual("running", lease["state"])
        self.assertIn("OSError", diagnostic)
        self.assertNotIn("private failure", diagnostic)

    def test_health_transition_can_preserve_pending_identity(self):
        def mark_unhealthy():
            lease = self.rt.list_leases()[0]
            lease["state"] = "unhealthy"
            self.rt.save_lease(lease)

        lease, _ = self.interrupted(callback=mark_unhealthy)
        self.assertEqual("cleanup_pending", lease["state"])

    def test_initially_terminal_snapshot_is_not_resurrected(self):
        start = self.fake_start

        def terminal_start(*args, **kwargs):
            outcome = start(*args, **kwargs)
            lease = self.rt.list_leases()[0]
            lease["state"] = "released"
            self.rt.save_lease(lease)
            return outcome

        self.fake_start = terminal_start
        lease, diagnostic = self.interrupted()
        self.assertEqual("released", lease["state"])
        self.assertIn("cleanup-pending-record-failed", diagnostic)

    def test_diagnostic_output_failure_does_not_mask_original_exception(self):
        error = RuntimeError("original")
        broken_stderr = mock.Mock()
        broken_stderr.write.side_effect = BrokenPipeError("closed output")

        def fail_save(*args, **kwargs):
            self.rt.save_lease = mock.Mock(side_effect=OSError("private failure"))
            raise error

        with mock.patch.object(self.rt, "start", side_effect=self.fake_start), \
                mock.patch.object(self.rt, "verify", side_effect=fail_save), \
                mock.patch("sys.stderr", broken_stderr), \
                mock.patch.object(self.rt, "close") as close:
            with self.assertRaises(RuntimeError) as raised:
                self.rt.run("owner", "session", "service", self.argv, **self.run_args)
            self.assertIs(raised.exception, error)
            close.assert_not_called()
        self.popen.assert_not_called()
        self.kill_process.assert_not_called()

    def test_trace_failure_keeps_persisted_pending_and_original_exception(self):
        def fail_trace():
            self.rt.append_trace = mock.Mock(side_effect=OSError("private failure"))

        lease, diagnostic = self.interrupted(KeyboardInterrupt(), fail_trace)
        self.assertEqual("cleanup_pending", lease["state"])
        self.assertIn("OSError", diagnostic)
        self.assertNotIn("private failure", diagnostic)

    def test_normal_keep_success_retains_running_contract(self):
        with mock.patch.object(self.rt, "start", side_effect=self.fake_start), \
                mock.patch.object(self.rt, "verify", return_value=APL.result(True)), \
                mock.patch.object(self.rt, "close") as close:
            outcome = self.rt.run("owner", "session", "service", self.argv,
                                  keep=True, **self.run_args)
        self.assertTrue(outcome["ok"])
        self.assertTrue(outcome["kept"])
        self.assertEqual("running", outcome["lease"]["state"])
        close.assert_not_called()

    def test_normal_success_and_verify_false_keep_existing_close_contract(self):
        for ok in (True, False):
            with self.subTest(verify_ok=ok):
                self.rt.store = self.rt.root / str(ok)
                with mock.patch.object(self.rt, "start", side_effect=self.fake_start), \
                        mock.patch.object(self.rt, "verify", return_value=APL.result(ok, "fixture")), \
                        mock.patch.object(self.rt, "close", return_value=APL.result(True)) as close:
                    outcome = self.rt.run("owner", "session", "service", self.argv,
                                          **self.run_args)
                self.assertEqual(ok, outcome["ok"])
                self.assertFalse(outcome["kept"])
                close.assert_called_once()
        self.kill_process.assert_not_called()
        self.popen.assert_not_called()


    def failed_verify_run(self, close_effect, verify_results=None):
        verify_results = verify_results or [APL.result(False, "verify-failed")] * 3
        with mock.patch.object(self.rt, "start", side_effect=self.fake_start) as start, \
                mock.patch.object(self.rt, "verify", side_effect=verify_results) as verify, \
                mock.patch.object(self.rt, "close", side_effect=close_effect) as close, \
                mock.patch("sys.stderr", new_callable=io.StringIO) as stderr:
            outcome = self.rt.run("owner", "session", "service", self.argv,
                                  port_range=(25000, 25003), attempts=3, keep=True)
        self.popen.assert_not_called()
        self.kill_process.assert_not_called()
        self.process_identity.assert_not_called()
        return outcome, start.call_count, verify.call_count, close.call_count, stderr.getvalue()

    def released_close(self, owner, session, lease_id):
        lease = self.rt._require_owned(lease_id, owner, session)
        lease.update(state="released", pid=None, killAllowed=False, endedAt=APL.iso(APL.utc_now()))
        self.rt.save_lease(lease)
        return APL.result(True, lease=self.rt._public_lease(lease))

    def test_close_success_allows_normal_retry(self):
        outcome, starts, verifies, closes, _ = self.failed_verify_run(
            self.released_close, [APL.result(False, "verify-failed"), APL.result(True)])
        self.assertTrue(outcome["ok"])
        self.assertTrue(outcome["kept"])
        self.assertEqual((2, 2, 1), (starts, verifies, closes))
        self.assertEqual({"released", "running"}, {l["state"] for l in self.rt.list_leases()})

    def test_close_false_blocks_same_run_and_future_duplicate(self):
        outcome, starts, verifies, closes, _ = self.failed_verify_run(
            lambda *args: APL.result(False, "stop-failed"))
        self.assertFalse(outcome["ok"])
        self.assertEqual("cleanup-pending", outcome["cause"])
        self.assertEqual((1, 1, 1), (starts, verifies, closes))
        lease = self.rt.list_leases()[0]
        self.assertEqual("cleanup_pending", lease["state"])
        self.assertEqual("stop-failed", lease["cause"])
        self.assertEqual((98765, 123456), (lease["pid"], lease["processCreateTime"]))
        with mock.patch.object(self.rt, "start") as start:
            blocked = self.rt.run("owner", "session", "service", self.argv, **self.run_args)
            self.assertEqual("cleanup-pending", blocked["cause"])
            start.assert_not_called()

    def test_unconfirmed_close_blocks_retry(self):
        results = [None, {}, {"ok": "true"}, APL.result(True)]
        for index, closed in enumerate(results):
            with self.subTest(result=index):
                self.rt.store = self.rt.root / str(index)
                outcome, starts, verifies, closes, _ = self.failed_verify_run(lambda *args: closed)
                self.assertEqual((1, 1, 1), (starts, verifies, closes))
                self.assertFalse(outcome["ok"])
                self.assertEqual("cleanup_pending", self.rt.list_leases()[0]["state"])
        self.rt.store = self.rt.root / "incoherent"

        def incoherent(*args):
            public = self.rt._public_lease(self.rt.list_leases()[0])
            public.update(state="released", pid=None)
            return APL.result(True, lease=public)

        outcome, starts, _, _, _ = self.failed_verify_run(incoherent)
        self.assertEqual(1, starts)
        self.assertFalse(outcome["ok"])
        self.assertEqual("cleanup_pending", self.rt.list_leases()[0]["state"])

    def test_close_exception_preserves_original_and_pending(self):
        for error in (KeyboardInterrupt(), RuntimeError("private close message")):
            with self.subTest(exception=type(error).__name__):
                self.rt.store = self.rt.root / type(error).__name__
                with mock.patch.object(self.rt, "start", side_effect=self.fake_start) as start, \
                        mock.patch.object(self.rt, "verify", return_value=APL.result(False, "verify-failed")), \
                        mock.patch.object(self.rt, "close", side_effect=error), \
                        mock.patch("sys.stderr", new_callable=io.StringIO):
                    with self.assertRaises(type(error)) as raised:
                        self.rt.run("owner", "session", "service", self.argv,
                                    attempts=3, port_range=(25000, 25003))
                    self.assertIs(error, raised.exception)
                    self.assertEqual(1, start.call_count)
                lease = self.rt.list_leases()[0]
                self.assertEqual("cleanup_pending", lease["state"])
                self.assertEqual("cleanup-exception", lease["cause"])
                self.assertNotIn("private close message", str(self.rt.read_traces()))

    def test_partial_close_failure_never_restores_pid_or_retries(self):
        for state in ("stopped", "released"):
            for raises in (False, True):
                with self.subTest(state=state, raises=raises):
                    self.rt.store = self.rt.root / (state + str(raises))
                    error = RuntimeError("private partial close")

                    def partial(owner, session, lease_id):
                        lease = self.rt._require_owned(lease_id, owner, session)
                        lease.update(state=state, pid=None, killAllowed=False,
                                     endedAt=APL.iso(APL.utc_now()))
                        self.rt.save_lease(lease)
                        if raises:
                            raise error
                        return APL.result(False, "stop-failed")

                    with mock.patch.object(self.rt, "start", side_effect=self.fake_start) as start, \
                            mock.patch.object(self.rt, "verify", return_value=APL.result(False, "verify-failed")), \
                            mock.patch.object(self.rt, "close", side_effect=partial), \
                            mock.patch("sys.stderr", new_callable=io.StringIO):
                        if raises:
                            with self.assertRaises(RuntimeError) as raised:
                                self.rt.run("owner", "session", "service", self.argv,
                                            attempts=3, port_range=(25000, 25003))
                            self.assertIs(error, raised.exception)
                        else:
                            outcome = self.rt.run("owner", "session", "service", self.argv,
                                                  attempts=3, port_range=(25000, 25003))
                            self.assertFalse(outcome["ok"])
                        self.assertEqual(1, start.call_count)
                    lease = self.rt.list_leases()[0]
                    self.assertEqual("cleanup_pending", lease["state"])
                    self.assertIsNone(lease["pid"])
                    self.assertFalse(lease["killAllowed"])
                    self.assertEqual({"pid": 98765, "processCreateTime": 123456}, lease["cleanupSnapshot"])
                    self.assertTrue(self.rt.holds_port(lease))
                    blocked = self.rt.acquire("owner", "session", "service", port_range=(25000, 25003))
                    self.assertEqual("cleanup-pending", blocked["cause"])

    def test_save_failure_after_close_false_still_blocks_this_run(self):
        def fail_record(*args):
            self.rt.save_lease = mock.Mock(side_effect=OSError("private write failure"))
            return APL.result(False, "stop-failed")

        outcome, starts, verifies, closes, diagnostic = self.failed_verify_run(fail_record)
        self.assertEqual((1, 1, 1), (starts, verifies, closes))
        self.assertFalse(outcome["ok"])
        self.assertFalse(outcome["cleanupRecorded"])
        self.assertIn("OSError", diagnostic)
        self.assertNotIn("private write failure", diagnostic)
        self.assertEqual("running", self.rt.list_leases()[0]["state"])

    def test_record_failure_does_not_mask_close_exception(self):
        for mode in ("save", "trace", "stderr", "second-interrupt"):
            with self.subTest(mode=mode):
                self.rt.store = self.rt.root / mode
                error = RuntimeError("original close exception")
                original_save, original_trace = self.rt.save_lease, self.rt.append_trace

                def fail_close(*args):
                    if mode == "trace":
                        self.rt.append_trace = mock.Mock(side_effect=OSError("private trace failure"))
                    else:
                        secondary = KeyboardInterrupt() if mode == "second-interrupt" else OSError("private save failure")
                        self.rt.save_lease = mock.Mock(side_effect=secondary)
                    raise error

                stderr = mock.Mock() if mode == "stderr" else io.StringIO()
                if mode == "stderr":
                    stderr.write.side_effect = BrokenPipeError()
                try:
                    with mock.patch.object(self.rt, "start", side_effect=self.fake_start) as start, \
                            mock.patch.object(self.rt, "verify", return_value=APL.result(False, "verify-failed")), \
                            mock.patch.object(self.rt, "close", side_effect=fail_close), \
                            mock.patch("sys.stderr", stderr):
                        with self.assertRaises(RuntimeError) as raised:
                            self.rt.run("owner", "session", "service", self.argv,
                                        attempts=3, port_range=(25000, 25003))
                        self.assertIs(error, raised.exception)
                        self.assertEqual(1, start.call_count)
                    if mode == "trace":
                        self.assertEqual("cleanup_pending", self.rt.list_leases()[0]["state"])
                finally:
                    self.rt.save_lease, self.rt.append_trace = original_save, original_trace

    def test_changed_identity_during_close_is_not_overwritten(self):
        for field, value in (("owner", "foreign"), ("pid", 98766), ("processCreateTime", 654321)):
            with self.subTest(field=field):
                self.rt.store = self.rt.root / field
                error = RuntimeError("close failed")

                def changed(*args):
                    lease = self.rt.list_leases()[0]
                    lease[field] = value
                    self.rt.save_lease(lease)
                    raise error

                with mock.patch.object(self.rt, "start", side_effect=self.fake_start) as start, \
                        mock.patch.object(self.rt, "verify", return_value=APL.result(False, "verify-failed")), \
                        mock.patch.object(self.rt, "close", side_effect=changed), \
                        mock.patch("sys.stderr", new_callable=io.StringIO):
                    with self.assertRaises(RuntimeError) as raised:
                        self.rt.run("owner", "session", "service", self.argv,
                                    attempts=3, port_range=(25000, 25003))
                    self.assertIs(error, raised.exception)
                    self.assertEqual(1, start.call_count)
                lease = self.rt.list_leases()[0]
                self.assertEqual(value, lease[field])
                self.assertEqual("running", lease["state"])

    def test_close_success_requires_explicit_pid_fields(self):
        for missing in ("returned", "current"):
            with self.subTest(missing=missing):
                self.rt.store = self.rt.root / missing

                def ambiguous(owner, session, lease_id):
                    closed = self.released_close(owner, session, lease_id)
                    if missing == "returned":
                        closed["lease"].pop("pid")
                    else:
                        lease = self.rt._require_owned(lease_id, owner, session)
                        lease.pop("pid")
                        self.rt.save_lease(lease)
                    return closed

                outcome, starts, verifies, closes, _ = self.failed_verify_run(
                    ambiguous, [APL.result(False, "verify-failed"), APL.result(True)])
                self.assertEqual((1, 1, 1), (starts, verifies, closes))
                self.assertFalse(outcome["ok"])
                self.assertEqual("cleanup_pending", self.rt.list_leases()[0]["state"])

    def test_malformed_close_reason_returns_failure_without_retry(self):
        outcome, starts, verifies, closes, diagnostic = self.failed_verify_run(
            lambda *args: APL.result(False, {"unknown": "fixture"}))
        self.assertFalse(outcome["ok"])
        self.assertEqual((1, 1, 1), (starts, verifies, closes))
        self.assertEqual("cleanup-failed", outcome["cleanupCause"])
        self.assertEqual("", diagnostic)

if __name__ == "__main__":
    unittest.main()
