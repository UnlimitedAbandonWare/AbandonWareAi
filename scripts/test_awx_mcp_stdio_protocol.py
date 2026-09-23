"""Client-owned pipe contracts; synchronization uses handler-entered events."""
import json
import queue
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import awx_mcp_stdio_server as server


class ProtocolTest(unittest.TestCase):

    def test_direct_subscription_review_fails_closed_with_a_valid_closed_result(self):
        payload = {'mode':'review','requestId':'contract-1','reviewReason':'Find a counterexample',
                   'changeSummary':'Review supplied material','evidence':[]}
        reply = server.handle_request(dict(jsonrpc='2.0', id=91, method='tools/call',
                    params={'name':'codex_review_change','arguments':payload}))
        self.assertNotIn('error', reply)
        result = reply['result']['structuredContent']
        self.assertEqual('worker-ownership-required', result['reason'])
        self.assertEqual(0, result['attemptCount'])
        schema = next(row['outputSchema'] for row in server.list_tools() if row['name']=='codex_review_change')
        self.assertTrue(server.schema_matches(result, schema))
        self.assertNotIn('sessionId', result)

    def test_subscription_review_rejects_command_overrides_before_the_handler(self):
        with patch.dict(server.HANDLERS, codex_review_change=lambda _: self.fail('handler entered')):
            reply = server.handle_request(dict(jsonrpc='2.0', id=92, method='tools/call', params={
                'name':'codex_review_change','arguments':{'mode':'status','requestId':'contract-2','model':'other'}}))
        self.assertEqual(-32602,reply['error']['code'])

    def test_subscription_economy_profile_reaches_owned_worker_gate(self):
        reply = server.handle_request(dict(jsonrpc='2.0', id=93, method='tools/call', params={
            'name':'codex_review_change','arguments':{'mode':'review','requestId':'economy-1',
            'reviewProfile':'economy','reviewReason':'Check a public example',
            'changeSummary':'Bounded review','evidence':[]}}))
        self.assertNotIn('error', reply)
        self.assertEqual('worker-ownership-required', reply['result']['structuredContent']['reason'])


    def test_large_finite_integers_do_not_overflow_validation(self):
        for value in (10 ** 400, -(10 ** 400)):
            self.assertTrue(server.valid_id(value))
            self.assertTrue(server.schema_matches(value, {"type": "integer"}))
            self.assertTrue(server.schema_matches(value, {"type": "number"}))
        self.assertFalse(server.schema_matches(10 ** 400, {"type": "integer", "maximum": 9}))
        self.assertFalse(server.schema_matches(-(10 ** 400), {"type": "integer", "minimum": -9}))
        for value in (True, float("inf"), float("nan")):
            self.assertFalse(server.valid_id(value))
            self.assertFalse(server.schema_matches(value, {"type": "number"}))

    def test_enum_equality_uses_json_types_at_every_depth(self):
        cases = [(1.0, 1, True), (1, 1.0, True), (True, 1, False), (False, 0, False),
                 ({"n": True}, {"n": 1}, False), ([False], [0], False),
                 ([1.0, {"n": 2}], [1, {"n": 2.0}], True),
                 ({"a": 1, "b": 2}, {"b": 2.0, "a": 1.0}, True),
                 ([1], [1, 2], False), ({"a": 1}, {"b": 1}, False), ("1", 1, False)]
        for value, choice, expected in cases:
            with self.subTest(value=value, choice=choice):
                self.assertIs(expected, server.schema_matches(value, {"enum": [choice]}))

    def test_execution_timeout_extension_is_bounded_and_optional(self):
        params = {"name": "boot_verify", "arguments": {"nodeRole": "desktop"}}
        for meta in ([], {"awx/executionTimeoutMs": True}, {"awx/executionTimeoutMs": 0},
                     {"awx/executionTimeoutMs": "100"}, {"awx/executionTimeoutMs": 600001}):
            with self.subTest(meta=meta):
                reply = server.handle_request(dict(jsonrpc="2.0", id=0, method="tools/call", params={**params, "_meta": meta}))
                self.assertEqual(-32602, reply.get("error", {}).get("code"))
        for meta in ({}, {"vendor/extension": True}, {"awx/executionTimeoutMs": 500}):
            self.assertEqual("boot_verify", server.validate_tool_call({**params, "_meta": meta})[0])
        for requested, effective in ((600000, .7), (100, .1)):
            session = server.StdioSession(request_timeout=.7)
            session.receive(dict(jsonrpc="2.0", id=0, method="tools/call",
                params={**params, "_meta": {"awx/executionTimeoutMs": requested}}))
            self.assertEqual(effective, session.queue.get_nowait()[1]["timeout"])

    def test_error_stages_preserve_id_and_type(self):
        for request_id in (0, 1, "1", "", None):
            for method, params, code in (("missing", {}, -32601),
                                         ("tools/list", [], -32602),
                                         ("tools/call", {"name": "missing"}, -32602),
                                         ("resources/read", {"uri": "missing"}, -32002)):
                reply = server.handle_request(dict(jsonrpc="2.0", id=request_id, method=method, params=params))
                self.assertEqual(code, reply["error"]["code"])
                self.assertIs(type(request_id), type(reply["id"]))
                self.assertEqual(request_id, reply["id"])

    def test_notifications_do_not_reply_even_on_method_error(self):
        for method in ("notifications/initialized", "missing", "tools/list"):
            self.assertIsNone(server.handle_request(dict(jsonrpc="2.0", method=method)))

    def test_envelope_invalid_is_not_parse_error(self):
        for request in ([], {}, {"jsonrpc": "1.0", "id": 0, "method": "ping"},
                        {"jsonrpc": "2.0", "id": True, "method": "ping"}):
            self.assertEqual(-32600, server.handle_request(request)["error"]["code"])

    def test_schema_constraints_and_extensions(self):
        schema = {"type": "object", "required": ["n"], "properties": {"n": {"type": "integer", "minimum": 1, "maximum": 2}}}
        self.assertTrue(server.schema_matches({"n": 1, "extension": "allowed"}, schema))
        for value in ({}, {"n": True}, {"n": 0}, {"n": 3}, {"n": "1"}):
            self.assertFalse(server.schema_matches(value, schema))
        schema["additionalProperties"] = False
        self.assertFalse(server.schema_matches({"n": 1, "extension": 1}, schema))
        schema["additionalProperties"] = {"type": "string"}
        self.assertTrue(server.schema_matches({"n": 1, "extension": "yes"}, schema))

    def test_business_error_is_tool_result_internal_error_is_rpc(self):
        request = dict(jsonrpc="2.0", id=0, method="tools/call", params={"name": "boot_verify", "arguments": {"nodeRole": "desktop"}})
        with patch.dict(server.HANDLERS, boot_verify=lambda _: (_ for _ in ()).throw(RuntimeError("private fixture"))):
            reply = server.handle_request(request)
        self.assertTrue(reply["result"]["isError"])
        self.assertNotIn("private fixture", json.dumps(reply))
        with patch.object(server, "list_tools", side_effect=RuntimeError("private fixture")):
            self.assertEqual(-32603, server.handle_request(dict(jsonrpc="2.0", id=0, method="tools/list"))["error"]["code"])


class PipeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.fixture = Path(self.directory.name) / "pipe_fixture.py"
        self.fixture.write_text('''import sys, time
sys.path.insert(0, %r)
import awx_mcp_stdio_server as server
def handler(args):
    mode = args.get("fixture", "complete")
    if mode in ("exit_without_reply", "clean_without_reply"):
        import os
        os._exit(17 if mode == "exit_without_reply" else 0)
    if mode == "invalid_output":
        return {}
    if mode == "child":
        import subprocess
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        from pathlib import Path
        Path(args["fixturePidPath"]).write_text(str(child.pid))
    if mode in ("wait", "child"):
        while True:
            time.sleep(.01)
    if mode == "native":
        # Simulate a native call with no cooperative Python checkpoints. The
        # file handshake happens after tracing is disabled, before blocking.
        sys.settrace(None)
        from pathlib import Path
        Path(args["fixturePidPath"]).write_text("native_entered")
        time.sleep(60)
    print("fixture noise must not reach protocol stdout")
    return {"ok": True, "commands": [], "executeSupported": False}
server.HANDLERS["boot_verify"] = handler
if "--tool-worker" in sys.argv:
    raise SystemExit(server.tool_worker())
raise SystemExit(server.StdioSession(worker_command=[sys.executable, __file__, "--tool-worker"], request_timeout=.7).run())
''' % str(server.ROOT / "scripts"), encoding="utf8")
        self.process = subprocess.Popen([sys.executable, str(self.fixture)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, bufsize=1)
        self.addCleanup(self.cleanup_process)
        self.output, self.events = queue.Queue(), queue.Queue()
        self.readers = []
        for stream, destination in ((self.process.stdout, self.output), (self.process.stderr, self.events)):
            reader = threading.Thread(target=self.collect, args=(stream, destination), daemon=True)
            reader.start()
            self.readers.append(reader)

    @staticmethod
    def collect(stream, destination):
        for line in stream:
            try:
                destination.put(json.loads(line))
            except ValueError:
                destination.put({"invalidJson": True})

    def cleanup_process(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        for reader in self.readers:
            reader.join(timeout=2)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()

    def send(self, value):
        self.process.stdin.write(json.dumps(value) + "\n")
        self.process.stdin.flush()

    def call(self, request_id, mode="complete"):
        self.send(dict(jsonrpc="2.0", id=request_id, method="tools/call", params={"name": "boot_verify", "arguments": {"nodeRole": "desktop", "fixture": mode, "fixturePidPath": str(Path(self.directory.name) / "owned-child.pid")}}))

    def event(self, name):
        for _ in range(20):
            event = self.events.get(timeout=5)
            if event.get("event") == name:
                return event
        self.fail("expected lifecycle event missing")

    def barrier(self):
        self.send(dict(jsonrpc="2.0", id="barrier", method="ping"))
        return self.output.get(timeout=5)

    def test_parse_error_and_notification_stdout(self):
        self.process.stdin.write("{\n")
        self.process.stdin.flush()
        self.assertEqual(-32700, self.output.get(timeout=5)["error"]["code"])
        self.send(dict(jsonrpc="2.0", method="notifications/initialized"))
        self.send(dict(jsonrpc="2.0", id=0, method="tools/call", params={"name": "missing"}))
        reply = self.output.get(timeout=5)
        self.assertEqual(0, reply["id"])
        self.assertEqual(-32602, reply["error"]["code"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_worker_exit_without_reply_terminates_original_request_once(self):
        for request_id, mode in ((0, "exit_without_reply"), ("0", "clean_without_reply")):
            with self.subTest(mode=mode):
                self.call(request_id, mode)
                finished = self.event("request_finished")
                reply = self.barrier()
                self.assertEqual(request_id, reply["id"])
                self.assertIs(type(request_id), type(reply["id"]))
                self.assertEqual(-32603, reply["error"]["code"])
                self.assertEqual("worker_response_missing", reply["error"]["message"])
                self.assertEqual("internal_error", finished["outcome"])
                self.assertTrue(finished["workerExited"])
                self.assertEqual("barrier", self.output.get(timeout=5)["id"])
        self.call("after-exit")
        self.assertEqual("after-exit", self.output.get(timeout=5)["id"])

    def test_notification_worker_exit_does_not_create_error_response(self):
        self.send(dict(jsonrpc="2.0", method="tools/call", params={"name": "boot_verify",
            "arguments": {"nodeRole": "desktop", "fixture": "exit_without_reply"}}))
        self.assertTrue(self.event("request_finished")["workerExited"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_large_integer_ids_preserve_success_error_and_session(self):
        for request_id, method, error in ((10 ** 400, "ping", None), (-(10 ** 400), "missing", -32601)):
            self.send(dict(jsonrpc="2.0", id=request_id, method=method))
            reply = self.output.get(timeout=5)
            self.assertIs(int, type(reply["id"]))
            self.assertEqual(request_id, reply["id"])
            self.assertEqual(error, reply.get("error", {}).get("code"))
        self.assertEqual("barrier", self.barrier()["id"])

    def test_large_integer_id_can_cancel_owned_work(self):
        request_id = 10 ** 400
        self.call(request_id, "wait")
        self.event("handler_entered")
        self.send(dict(jsonrpc="2.0", method="notifications/cancelled", params={"requestId": request_id}))
        finished = self.event("request_finished")
        self.assertEqual("cancelled", finished["outcome"])
        self.assertTrue(finished["workerExited"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_output_schema_failure_preserves_id_and_session(self):
        self.call(0, "invalid_output")
        reply = self.output.get(timeout=5)
        self.assertEqual(0, reply["id"])
        self.assertTrue(reply["result"]["isError"])
        self.assertEqual("output_schema_validation_failed", reply["result"]["structuredContent"]["reason"])
        self.call("0")
        reply = self.output.get(timeout=5)
        self.assertEqual("0", reply["id"])
        self.assertFalse(reply["result"]["isError"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_cancel_running_and_continue_session(self):
        self.call(1, "wait")
        self.event("handler_entered")
        self.send(dict(jsonrpc="2.0", method="notifications/cancelled", params={"requestId": 1}))
        finished = self.event("request_finished")
        self.assertEqual("cancelled", finished["outcome"])
        self.assertTrue(finished["workerExited"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_request_timeout_cannot_extend_server_limit(self):
        self.send(dict(jsonrpc="2.0", id="0", method="tools/call", params={"name": "boot_verify",
            "arguments": {"nodeRole": "desktop", "fixture": "wait"}, "_meta": {"awx/executionTimeoutMs": 600000}}))
        self.event("handler_entered")
        reply = self.output.get(timeout=5)
        self.assertEqual("0", reply["id"])
        self.assertEqual(-32001, reply["error"]["code"])
        self.assertTrue(self.event("request_finished")["workerExited"])
        self.assertEqual("barrier", self.barrier()["id"])
        self.call("1")
        self.assertEqual("1", self.output.get(timeout=5)["id"])

    def test_completion_before_cancel_and_unknown_cancel(self):
        self.call(0)
        self.assertEqual(0, self.output.get(timeout=5)["id"])
        for request_id in (0, "absent"):
            self.send(dict(jsonrpc="2.0", method="notifications/cancelled", params={"requestId": request_id}))
        self.assertEqual("barrier", self.barrier()["id"])

    def test_cancel_queued_typed_id_does_not_cancel_numeric_running_id(self):
        self.call(1, "wait")
        self.event("handler_entered")
        self.call("1")
        self.send(dict(jsonrpc="2.0", method="notifications/cancelled", params={"requestId": "1"}))
        self.send(dict(jsonrpc="2.0", method="notifications/cancelled", params={"requestId": 1}))
        running = self.event("request_finished")
        queued = self.event("request_finished")
        self.assertTrue(running["workerStarted"])
        self.assertFalse(queued["workerStarted"])
        self.assertFalse(queued["workerExited"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_queue_capacity_fails_without_unbounded_workers(self):
        self.call(1, "wait")
        self.event("handler_entered")
        for request_id in range(10, 20):
            self.call(request_id)
        for _ in range(2):
            self.assertEqual(-32000, self.output.get(timeout=5)["error"]["code"])
        for request_id in [1] + list(range(10, 20)):
            self.send(dict(jsonrpc="2.0", method="notifications/cancelled", params={"requestId": request_id}))
        self.assertEqual("barrier", self.barrier()["id"])

    def test_native_blocking_work_uses_owned_bounded_cleanup(self):
        self.call(9, "native")
        self.event("handler_entered")
        marker = Path(self.directory.name) / "owned-child.pid"
        deadline = time.monotonic() + 3
        while not marker.exists() and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual("native_entered", marker.read_text())
        self.send(dict(jsonrpc="2.0", method="notifications/cancelled", params={"requestId": 9}))
        self.assertEqual("native_operation_blocked", self.event("cancellation_escalated")["reason"])
        self.assertTrue(self.event("request_finished")["workerExited"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_timeout_has_one_terminal_response(self):
        self.call(3, "wait")
        self.event("handler_entered")
        reply = self.output.get(timeout=5)
        self.assertEqual(3, reply["id"])
        self.assertEqual(-32001, reply["error"]["code"])
        self.assertEqual("timeout", self.event("request_finished")["outcome"])
        self.assertEqual("barrier", self.barrier()["id"])

    def test_eof_drains_queued_complete_request_before_shutdown(self):
        self.call(4)
        self.process.stdin.close()
        self.assertEqual(0, self.process.wait(timeout=5))
        reply = self.output.get(timeout=5)
        self.assertEqual(4, reply["id"])
        self.assertTrue(reply["result"]["structuredContent"]["ok"])
        self.assertEqual("completed", self.event("request_finished")["outcome"])
        closed = self.event("session_closed")
        self.assertEqual(0, closed["activeRequests"])
        self.assertFalse(closed["workerAlive"])

    def test_eof_cleans_running_worker_and_owned_child(self):
        self.call(5, "child")
        self.event("handler_entered")
        marker = Path(self.directory.name) / "owned-child.pid"
        deadline = time.monotonic() + 3
        while not marker.exists() and time.monotonic() < deadline:
            time.sleep(.01)
        child = int(marker.read_text())
        self.process.stdin.close()
        self.assertEqual(0, self.process.wait(timeout=5))
        self.assertEqual(0, self.event("session_closed")["activeRequests"])
        if sys.platform == "win32":
            import ctypes
            handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, child)
            if handle:
                code = ctypes.c_ulong()
                ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
                ctypes.windll.kernel32.CloseHandle(handle)
                self.assertNotEqual(259, code.value)


if __name__ == "__main__":
    unittest.main()
