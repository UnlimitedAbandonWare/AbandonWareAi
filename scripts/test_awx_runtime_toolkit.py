"""Bounded composition contracts; use a real owned STDIO pipe, mocked HTTP only."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import awx_mcp_toolbox as toolbox
import awx_mcp_stdio_server as bridge


class RuntimeToolkitTest(unittest.TestCase):
    def test_status_and_cached_transport_do_not_renew_actual_smoke_timestamp(self):
        client = self.client()
        client.command("smoke")
        verified = client.proof.get("smokeVerifiedAtEpochMs", 0)
        self.assertGreater(verified, 0)
        client.command("smoke")
        self.assertEqual(verified, client.proof["smokeVerifiedAtEpochMs"])
        with tempfile.TemporaryDirectory() as directory:
            client.root = Path(directory)
            client.publish = toolbox.RuntimeToolkit.publish.__get__(client)
            path = client.root / "var/codex-runtime/toolkit-current.json"
            client.status()
            first = json.loads(path.read_text())
            with patch.object(toolbox.time, "time", return_value=verified / 1000 + 120):
                client.status()
            second = json.loads(path.read_text())
            self.assertGreater(second["observedAtEpochMs"], first["observedAtEpochMs"])
            self.assertEqual(verified, second["stdio"]["smokeVerifiedAtEpochMs"])
            client.close_pipe()
            client.published = False

    def test_smoke_refreshes_changed_manifest_before_comparing_real_catalog(self):
        import sys
        client = self.client()
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.json"
            manifest = json.loads(client.manifest_path.read_text(encoding="utf8"))
            tool = next(row for row in manifest["tools"] if row["name"] == "boot_verify")
            tool["input_schema"]["properties"]["runtime_probe_extension"] = {"type": "boolean"}
            manifest_path.write_text(json.dumps(manifest), encoding="utf8")
            client.manifest_path = manifest_path
            code = (f"import sys\nsys.path.insert(0, {str(Path(__file__).resolve().parent)!r})\n"
                    "from pathlib import Path\nimport awx_mcp_stdio_server as live\n"
                    f"live.MANIFEST_PATH = Path({str(manifest_path)!r})\nraise SystemExit(live.main())\n")
            real_worker = bridge.OwnedWorker
            with patch.object(bridge, "OwnedWorker", side_effect=lambda command, **kwargs:
                              real_worker([sys.executable, "-u", "-c", code], **kwargs)):
                result = client.command("smoke")
            try:
                self.assertTrue(result["stdio"]["catalogValidated"])
                self.assertEqual(manifest, client.manifest)
            finally:
                client.close_pipe()

    def faulting_workers(self, method, action, faults=1):
        """Inject one transport fault into the real bridge, never a replacement server."""
        import sys
        real_worker, workers = bridge.OwnedWorker, []
        scripts = str(Path(__file__).resolve().parent)
        code = (f"import os, sys\nsys.path.insert(0, {scripts!r})\n"
                "import awx_mcp_stdio_server as live\noriginal = live.StdioSession.receive\n"
                "def receive(self, request):\n"
                f"    if request.get('method') == {method!r}:\n"
                f"        {action}\n        return\n"
                "    return original(self, request)\n"
                "live.StdioSession.receive = receive\nraise SystemExit(live.main())\n")
        def create(command, **kwargs):
            worker = real_worker([sys.executable, "-u", "-c", code] if len(workers) < faults else command, **kwargs)
            workers.append(worker)
            self.addCleanup(worker.close)
            return worker
        return create, workers

    def test_smoke_recovers_real_inflight_exit_with_one_new_owned_session(self):
        client = self.client()
        factory, workers = self.faulting_workers("tools/list", "os._exit(17)")
        with patch.object(bridge, "OwnedWorker", side_effect=factory):
            result = client.command("smoke")
        self.assertEqual(2, len(workers))
        self.assertEqual(17, workers[0].process.poll())
        self.assertTrue(workers[0].process.stdout.closed)
        self.assertTrue(workers[0].process.stderr.closed)
        self.assertIs(workers[1], client.owned)
        self.assertTrue(result["stdio"]["catalogValidated"])
        self.assertTrue(result["stdio"]["smokeVerified"])
        self.assertEqual(1, client.telemetry["processRestarts"])
        self.assertFalse(result["FULL_LOAD_READY"])
        client.command("stop")
        self.assertTrue(all(worker.process.poll() is not None for worker in workers))
        self.assertFalse(client.reader.is_alive())
        self.assertFalse(client.stderr_reader.is_alive())

    def test_repeated_initialize_exit_has_per_command_and_existing_window_limits(self):
        client = self.client()
        factory, workers = self.faulting_workers("initialize", "os._exit(17)", faults=3)
        with patch.object(bridge, "OwnedWorker", side_effect=factory):
            with self.assertRaisesRegex(RuntimeError, "STDIO_PROCESS_EXITED"):
                client.command("smoke")
            self.assertEqual(2, len(workers), "one command gets at most one recovery attempt")
            self.assertIsNone(client.owned)
            with self.assertRaisesRegex(RuntimeError, "RESTART_RATE_LIMITED"):
                client.command("smoke")
        self.assertEqual(3, len(workers), "the existing three-start/300s budget still applies")
        self.assertTrue(all(worker.process.poll() == 17 for worker in workers))
        self.assertEqual(2, client.telemetry["processRestarts"])
        self.assertEqual(1, client.telemetry["restartLimited"])
        self.assertFalse(client.proof.get("smokeVerified", False))

    def test_malformed_bridge_output_is_not_retried_as_process_exit(self):
        for output in ("not-json", "[]", '{"workerExit":true}'):
            with self.subTest(output=output):
                client = self.client()
                factory, workers = self.faulting_workers("tools/list", f"print({output!r}, flush=True)")
                with patch.object(bridge, "OwnedWorker", side_effect=factory):
                    with self.assertRaisesRegex(RuntimeError, "STDIO_PROTOCOL_MISMATCH"):
                        client.command("smoke")
                self.assertEqual(1, len(workers))
                self.assertEqual(0, client.telemetry["processRestarts"])
                self.assertFalse(client.proof.get("smokeVerified", False))
                client.stop()
                self.assertIsNotNone(workers[0].process.poll())

    def test_generic_tool_rpc_is_not_replayed_after_bridge_exit(self):
        client = self.client()
        factory, workers = self.faulting_workers("tools/call", "os._exit(17)")
        with patch.object(bridge, "OwnedWorker", side_effect=factory):
            client.ensure_session()
            with self.assertRaisesRegex(RuntimeError, "STDIO_PROCESS_EXITED"):
                client.rpc("tools/call", {"name": "boot_verify", "arguments": {"nodeRole": "desktop"}})
        self.assertEqual(1, len(workers))
        self.assertEqual(0, client.telemetry["processRestarts"])
        client.stop()
        self.assertEqual(17, workers[0].process.poll())

    def test_exited_real_owned_worker_with_buffered_stdin_stops_successfully(self):
        import sys
        client = self.client()
        owned = bridge.OwnedWorker([sys.executable, "-c", "pass"])
        client.owned = owned
        self.addCleanup(owned.close)
        owned.process.wait(timeout=5)
        owned.process.stdin.write("pending")  # Buffered until close: receiver has exited.
        result = client.stop()
        self.assertTrue(result["ok"])
        self.assertIsNone(client.owned)
        self.assertIsNotNone(owned.process.poll())
        self.assertTrue(client.stop()["ok"])

    def test_broken_stdin_does_not_hide_unproven_worker_termination(self):
        client = self.client()
        owned = Mock()
        client.owned = owned
        owned.process.stdin.close.side_effect = BrokenPipeError
        owned.close.return_value = False
        try:
            with self.assertRaisesRegex(RuntimeError, "BRIDGE_STOP_FAILED"):
                client.stop()
            self.assertIs(owned, client.owned)
            owned.close.assert_called_once()
        finally:
            client.owned = None  # Mock owns no OS resource.

    @unittest.skipUnless(os.name == "nt", "Windows PowerShell transport contract")
    def test_powershell_session_preserves_json_and_command_stdin(self):
        root = Path(__file__).resolve().parents[1]
        process = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                  str(root / "scripts/awx_mcp_toolbox.ps1"), "-Tool", "status", "-RuntimeSession",
                                  "-InputJson", '{"baseUrl":"http://127.0.0.1:9"}'],
                                 input='{"command":"doctor"}\n', text=True, capture_output=True, timeout=15)
        self.assertEqual(0, process.returncode)
        self.assertEqual("", process.stderr)
        result = json.loads(process.stdout)
        self.assertEqual("doctor", result["command"])
        self.assertFalse(result["FULL_LOAD_READY"])
    def client(self):
        client = toolbox.RuntimeToolkit()
        client.http_snapshot = Mock(return_value={"springPid": 1, "FULL_LOAD_READY": False, "requiredTotal": 3, "requiredReady": 1})
        client.publish = Mock(return_value=True)
        self.addCleanup(client.stop)
        return client

    def test_start_status_smoke_stop_doctor_reuse_one_owned_pipe(self):
        client = self.client()
        first = client.command("start")
        pid = client.owned.process.pid
        self.assertTrue(first["stdio"]["catalogValidated"])
        self.assertTrue(first["stdio"]["smokeVerified"])
        self.assertFalse(first["FULL_LOAD_READY"], "mocked HTTP must never be promoted by pipe success")
        client.command("start")
        self.assertEqual(pid, client.owned.process.pid)
        self.assertEqual(1, len(client.starts))
        self.assertEqual("status", client.command("status")["command"])
        self.assertEqual("doctor", client.command("doctor")["command"])
        client.command("smoke")
        process = client.owned.process
        self.assertFalse(client.command("stop")["FULL_LOAD_READY"])
        self.assertIsNotNone(process.poll())
        self.assertIsNone(client.owned)
        client.command("stop")

    def test_protocol_family_drift_cannot_validate_catalog(self):
        client = self.client()
        original = client.rpc
        def rpc(method, *args, **kwargs):
            value = original(method, *args, **kwargs)
            if method == "awx/catalog":
                value["protocolFamily"] = "UNKNOWN"
            return value
        client.rpc = rpc
        result = client.command("smoke")
        self.assertFalse(result["stdio"]["catalogValidated"])

    def test_smoke_observes_real_handler_cancel_timeout_and_reuses_fresh_proof(self):
        client = self.client()
        result = client.command("smoke")
        for key in ("cancelVerified", "timeoutVerified", "probeResourcesClosed"):
            self.assertIs(result["stdio"].get(key), True, key)
        self.assertEqual("controlled_loopback_dependency_actual_handler", result["stdio"]["transportVerificationScope"])
        counts = dict(client.telemetry)
        client.command("smoke")
        self.assertEqual(counts["toolCancelled"], client.telemetry["toolCancelled"])
        self.assertEqual(counts["toolTimeout"], client.telemetry["toolTimeout"])
        self.assertEqual(1, counts["toolCancelled"])
        self.assertEqual(1, counts["toolTimeout"])

    def test_transport_probe_failure_cleans_loopback_thread_and_cannot_promote(self):
        import threading
        client = self.client()
        client.ensure_session()
        with patch.object(client, "await_lifecycle", side_effect=RuntimeError("fixture_lifecycle_missing")):
            with self.assertRaisesRegex(RuntimeError, "fixture_lifecycle_missing"):
                client.transport_smoke()
        self.assertFalse(client.proof.get("smokeVerified", False))
        self.assertFalse(client.proof["cancelVerified"])
        self.assertEqual(0, client.transport_verified_at)
        self.assertFalse(any(t.name == "awx-toolkit-probe" for t in threading.enumerate()))
        client.close_pipe()

    def test_transport_extension_absent_does_not_claim_verification(self):
        client = self.client()
        client.ensure_session()
        client.timeout_extension = False
        with self.assertRaisesRegex(RuntimeError, "STDIO_TIMEOUT_EXTENSION_UNAVAILABLE"):
            client.transport_smoke()
        self.assertFalse(client.proof["timeoutVerified"])

    def test_output_schema_drift_invalidates_actual_pipe_catalog(self):
        client = self.client()
        original = client.rpc
        def rpc(method, *args, **kwargs):
            value = original(method, *args, **kwargs)
            if method == "tools/list" and "tools" in value:
                value["tools"][0]["outputSchema"] = {"type": "null"}
            return value
        client.rpc = rpc
        self.assertFalse(client.command("smoke")["stdio"]["catalogValidated"])

    def test_shared_readiness_does_not_claim_this_invocations_pipe(self):
        client = self.client()
        client.http_snapshot.return_value = {"FULL_LOAD_READY": True, "clientProofReason": "client_proof_verified"}
        client.published = True
        client.publish.return_value = False
        result = client.status()
        self.assertTrue(result["FULL_LOAD_READY"], "another live client's valid shared proof remains valid")
        self.assertEqual("shared_runtime", result["readinessScope"])
        self.assertEqual("current_invocation", result["stdio"]["evidenceScope"])
        self.assertFalse(result["invocationFullLoadReady"])
        self.assertEqual("rejected", result["proofPublication"])
        self.assertFalse(result["stdio"]["sessionAlive"])

    def test_observer_status_has_no_publication_or_ownership_claim(self):
        client = self.client()
        client.http_snapshot.return_value = {"FULL_LOAD_READY": True}
        result = client.status()
        client.publish.assert_not_called()
        self.assertEqual("not_attempted", result["proofPublication"])
        self.assertFalse(result["invocationFullLoadReady"])
        client.http_snapshot.assert_called_once()

    def test_one_shot_observer_does_not_relabel_foreign_proof_as_invocation_success(self):
        import io
        client = Mock()
        client.command.return_value = {"FULL_LOAD_READY": True, "invocationFullLoadReady": False}
        output = io.StringIO()
        with patch.object(toolbox, "RuntimeToolkit", return_value=client), \
             patch.object(toolbox.sys, "argv", ["awx_mcp_toolbox.py", "status", "--input-json", "{}"]), \
             patch.object(toolbox.sys, "stdout", output):
            self.assertEqual(0, toolbox.main())
        result = json.loads(output.getvalue())
        self.assertFalse(result["readyDuringInvocation"])
        self.assertTrue(result["sessionClosed"])

    def test_invocation_ready_requires_its_live_verified_published_pipe(self):
        client = self.client()
        client.http_snapshot.return_value = {"FULL_LOAD_READY": True}
        client.command("smoke")
        self.assertTrue(client.status()["invocationFullLoadReady"])
        client.publish.return_value = False
        self.assertFalse(client.status()["invocationFullLoadReady"])
        stopped = client.stop()
        self.assertEqual("current_invocation", stopped["readinessScope"])
        self.assertFalse(stopped["invocationFullLoadReady"])

    def test_doctor_checks_versions_without_exposing_command_output(self):
        client = self.client()
        outputs = {"java": 'openjdk version "17.0.13"', "python": "Python 3.11.9", "node": "v22.1.0"}
        with patch.object(toolbox.shutil, "which", side_effect=lambda name: name), \
             patch.object(toolbox.subprocess, "run", side_effect=lambda args, **kw: Mock(returncode=0, stdout=outputs[args[0]], stderr="")) as run:
            result = client.command("doctor")
        self.assertEqual(3, run.call_count)
        self.assertEqual("17.0.13", result["runtimeVersions"]["java"]["version"])
        self.assertEqual("3.11.9", result["runtimeVersions"]["python"]["version"])
        self.assertEqual("22.1.0", result["runtimeVersions"]["node"]["version"])
        self.assertNotIn("openjdk version", json.dumps(result))

    def test_doctor_missing_incompatible_timeout_and_bad_output_are_distinct(self):
        client = self.client()
        cases = [(None, None, "EXECUTABLE_NOT_FOUND"),
                 ("java", Mock(returncode=0, stdout='openjdk version "21.0.1"', stderr=""), "VERSION_INCOMPATIBLE"),
                 ("java", subprocess.TimeoutExpired("java", 2), "VERSION_TIMEOUT"),
                 ("java", Mock(returncode=2, stdout="private-output-sentinel", stderr="private-output-sentinel"), "VERSION_UNAVAILABLE"),
                 ("java", Mock(returncode=0, stdout="private-output-sentinel", stderr=""), "VERSION_UNAVAILABLE")]
        for executable, outcome, reason in cases:
            with self.subTest(reason=reason), \
                 patch.object(toolbox.shutil, "which", return_value=executable), \
                 patch.object(toolbox.subprocess, "run", side_effect=outcome if isinstance(outcome, Exception) else None, return_value=outcome):
                result = client.command("doctor")
            issue = next(row for row in result["issues"] if row["evidence"].get("executable") == "java")
            self.assertEqual(reason, issue["reasonCode"])
            self.assertTrue(issue["safeNextAction"])
            self.assertNotIn("private-output-sentinel", json.dumps(result))

    def test_doctor_preserves_required_failure_optional_and_unknown_evidence(self):
        client = self.client()
        client.http_snapshot.return_value = {"requiredTotal": 2, "clientProofReason": "client_proof_invalid",
            "services": [{"serviceId": "ollama-local", "required": True, "status": "COOLDOWN", "functionalStatus": "FALLBACK_ACTIVE", "reasonCode": "MODEL_LOAD_FAILED"},
                         {"serviceId": "awx-stdio", "required": False, "status": "STOPPED"}]}
        with patch.object(toolbox.shutil, "which", return_value=None):
            result = client.command("doctor")
        failure = next(row for row in result["issues"] if row["reasonCode"] == "REQUIRED_SERVICE_NOT_READY")
        self.assertEqual("FALLBACK_ACTIVE", failure["evidence"]["functionalStatus"])
        self.assertFalse(any(row["evidence"].get("serviceId") == "awx-stdio" and row["reasonCode"] == "REQUIRED_SERVICE_NOT_READY" for row in result["issues"]))
        self.assertTrue(any(row["reasonCode"] == "CLIENT_PROOF_UNVERIFIED" for row in result["issues"]))
        self.assertEqual("NOT_OBSERVED", result["verificationCoverage"]["externalProcessHandles"])
        for issue in result["issues"]:
            self.assertTrue(all(key in issue for key in ("reasonCode", "evidence", "safeNextAction")))

    def test_existing_spring_is_adopted_without_launcher(self):
        client = self.client()
        client.config["startSpring"] = True
        with patch.object(toolbox.subprocess, "run") as run:
            client.start_spring()
        run.assert_not_called()
        self.assertIsNone(client.spring_state)

    def test_missing_ownership_manifest_closes_only_owned_launcher_job(self):
        client = self.client()
        client.config["startSpring"] = True
        client.http_snapshot.return_value = {}
        with tempfile.TemporaryDirectory() as directory:
            client.root = Path(directory)
            worker = Mock()
            worker.process.returncode = 0
            with patch.object(bridge, "OwnedWorker", return_value=worker):
                with self.assertRaisesRegex(RuntimeError, "SPRING_OWNERSHIP_EVIDENCE_MISSING"):
                    client.start_spring()
            worker.close.assert_called_once()
            self.assertIsNone(client.spring_job)

    def test_bridge_close_error_does_not_skip_spring_cleanup(self):
        client = self.client()
        client.close_pipe = Mock(side_effect=RuntimeError("fixture"))
        client.spring_state = Path("var/codex-runtime/fixture-owned.json")
        with patch.object(toolbox.subprocess, "run", return_value=Mock(returncode=0)) as run:
            with self.assertRaisesRegex(RuntimeError, "BRIDGE_STOP_FAILED"):
                client.stop()
            run.assert_called_once()
        client.close_pipe = Mock()

    def test_restart_budget_and_remote_targets_fail_closed(self):
        client = self.client()
        import time
        client.starts = [time.monotonic()] * 3
        with self.assertRaisesRegex(RuntimeError, "RESTART_RATE_LIMITED"):
            client.ensure_session()
        for address in ("http://0.0.0.0:8080", "http://example.com", "http://127.0.0.1:8080/private"):
            with self.assertRaises(ValueError):
                toolbox.RuntimeToolkit({"baseUrl": address})

    def test_timed_out_spring_guard_still_closes_owned_job(self):
        client = self.client()
        client.spring_state = Path("var/codex-runtime/fixture-owned.json")
        job = client.spring_job = Mock()
        with patch.object(toolbox.subprocess, "run", side_effect=subprocess.TimeoutExpired("guard", 40)):
            with self.assertRaises(subprocess.TimeoutExpired):
                client.stop()
        job.close.assert_called_once()
        self.assertIsNone(client.spring_job)
        client.spring_state = None


if __name__ == "__main__":
    unittest.main()
