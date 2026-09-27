"""Owner-scoped port lease: skip foreign listeners, stop only the recorded pid."""
import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("agent_port_lease.py")
SPEC = importlib.util.spec_from_file_location("agent_port_lease", SCRIPT)
APL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(APL)

OK_SERVER = (
    "import os\n"
    "from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n"
    "class H(BaseHTTPRequestHandler):\n"
    "    def do_GET(self):\n"
    "        self.send_response(200); self.end_headers(); self.wfile.write(b'ok')\n"
    "    def log_message(self, fmt, *args):\n"
    "        return\n"
    "port = int(os.environ['AWX_AGENT_PORT'])\n"
    "ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()\n"
)
FAIL_ONCE = (
    "import os, sys\n"
    "from pathlib import Path\n"
    "marker = Path(os.environ['AWX_FAIL_MARKER'])\n"
    "if not marker.exists():\n"
    "    marker.write_text('1', encoding='utf-8')\n"
    "    sys.stderr.write('Address already in use\\n')\n"
    "    sys.stderr.flush()\n"
    "    raise SystemExit(1)\n"
    + OK_SERVER
)
HTTP_500 = (
    "import os\n"
    "from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n"
    "class H(BaseHTTPRequestHandler):\n"
    "    def do_GET(self):\n"
    "        self.send_response(500); self.end_headers()\n"
    "    def log_message(self, fmt, *args):\n"
    "        return\n"
    "port = int(os.environ['AWX_AGENT_PORT'])\n"
    "ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()\n"
)
SLOW = (
    "import os, time\n"
    "from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n"
    "class H(BaseHTTPRequestHandler):\n"
    "    def do_GET(self):\n"
    "        time.sleep(5)\n"
    "        self.send_response(200); self.end_headers()\n"
    "    def log_message(self, fmt, *args):\n"
    "        return\n"
    "port = int(os.environ['AWX_AGENT_PORT'])\n"
    "ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()\n"
)


class AgentPortLeaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.work = self.root / "work"
        self.work.mkdir()
        self.rt = APL.AgentPortLease(self.root)
        self.killed = []
        real_kill = self.rt.kill_process

        def tracking(pid):
            self.killed.append(int(pid))
            return real_kill(pid)

        self.rt.kill_process = tracking

    def server(self, code):
        return [sys.executable, "-u", "-c", code]

    def sleeper(self):
        proc = subprocess.Popen([sys.executable, "-u", "-c", "import time; time.sleep(40)"])
        def _stop_sleeper():
            if proc.poll() is None:
                proc.kill()
            proc.wait(timeout=5)
        self.addCleanup(_stop_sleeper)
        return proc

    def test_contract_and_protected_ports_are_never_chosen(self):
        self.rt.port_bindable = lambda port: True
        self.rt.listen_owner = lambda port: None
        body = self.rt.acquire("agent-a", "session-a", service="svc", port_range=(18180, 18184))
        self.assertTrue(body["ok"])
        self.assertEqual(body["lease"]["port"], 18183)
        self.assertNotIn(body["lease"]["port"], APL.PROTECTED_PORTS)
        refused = self.rt.acquire("agent-a", "session-a", port=18180)
        self.assertFalse(refused["ok"])
        self.assertEqual(refused["cause"], "protected-port")
        self.assertEqual(self.killed, [])
        contract = json.loads(subprocess.check_output(
            [sys.executable, "-B", str(SCRIPT), "contract", "--root", str(self.root)],
            text=True,
        ))
        self.assertEqual(contract["contract"], list(APL.CONTRACT))
        self.assertEqual(set(contract["protectedPorts"]), set(APL.PROTECTED_PORTS))

    def test_occupied_port_is_skipped_without_a_kill(self):
        held = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        held.bind(("127.0.0.1", 0))
        held.listen(1)
        self.addCleanup(held.close)
        port = held.getsockname()[1]
        body = self.rt.acquire("agent-a", "session-a", service="svc", port_range=(port, port + 4))
        self.assertTrue(body["ok"], body)
        self.assertNotEqual(body["lease"]["port"], port)
        self.assertTrue(any(
            row["port"] == port and row["cause"] == "occupied-foreign" for row in body["skipped"]
        ))
        explicit = self.rt.acquire("agent-a", "session-b", service="svc", port=port)
        self.assertEqual(explicit["cause"], "occupied-foreign")
        self.assertEqual(self.killed, [])

    def test_other_owner_cannot_release_or_stop(self):
        body = self.rt.acquire("agent-a", "session-a", service="svc", port_range=(49000, 49010))
        lease_id = body["lease"]["leaseId"]
        foreign = self.rt.release("agent-b", "session-a", lease_id)
        self.assertEqual(foreign["cause"], "owner-mismatch")
        same_owner = self.rt.release("agent-a", "session-b", lease_id)
        self.assertEqual(same_owner["cause"], "owner-mismatch")
        self.assertEqual(self.rt.load_lease(lease_id)["state"], "reserved")
        self.assertTrue(self.rt.release("agent-a", "session-a", lease_id)["ok"])

    def test_parallel_acquires_get_distinct_ports(self):
        self.rt.port_bindable = lambda port: True
        self.rt.listen_owner = lambda port: None
        results = []

        def work(index):
            results.append(self.rt.acquire(
                "agent-" + str(index), "session-p", service="svc", port_range=(49100, 49120),
            ))

        threads = [threading.Thread(target=work, args=(i,)) for i in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        ports = [row["lease"]["port"] for row in results if row["ok"]]
        self.assertEqual(len(ports), 4)
        self.assertEqual(len(set(ports)), 4)

    def test_pid_reuse_and_unproven_identity_are_not_killed(self):
        proc = self.sleeper()
        body = self.rt.acquire("agent-a", "session-a", service="svc", port_range=(49200, 49210))
        lease = self.rt.load_lease(body["lease"]["leaseId"])
        lease.update(pid=proc.pid, processCreateTime=1, killAllowed=True, state="running")
        self.rt.save_lease(lease)
        refused = self.rt.stop("agent-a", "session-a", lease["leaseId"])
        self.assertEqual(refused["cause"], "pid-reused")
        self.assertIsNone(proc.poll())
        lease["killAllowed"] = False
        lease["processCreateTime"] = None
        self.rt.save_lease(lease)
        unproven = self.rt.stop("agent-a", "session-a", lease["leaseId"])
        self.assertEqual(unproven["cause"], "identity-unproven")
        self.assertIsNone(proc.poll())
        self.assertEqual(self.killed, [])

    def test_run_retries_then_stops_only_its_server(self):
        proc = self.sleeper()
        marker = self.root / "fail-once.txt"
        code = FAIL_ONCE
        wrapped = (
            "import os, pathlib, sys\n"
            "os.environ['AWX_FAIL_MARKER'] = sys.argv[1]\n"
            "exec(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))\n"
        )
        script = self.root / "server.py"
        script.write_text(code, encoding="utf-8")
        body = self.rt.run(
            "agent-a", "session-run", "demo",
            [sys.executable, "-u", "-c", wrapped, str(marker), str(script)],
            port_range=(49300, 49340), attempts=3, health_timeout=4, cwd=self.work,
        )
        self.assertTrue(body["ok"], body)
        self.assertFalse(body["kept"])
        causes = [row.get("cause") for row in body["attempts"]]
        self.assertIn("port-conflict", causes)
        traces = self.rt.trace_query(owner="agent-a", session="session-run")["traces"]
        self.assertTrue(traces)
        for key in APL.TRACE_KEYS:
            self.assertIn(key, traces[-1])
        failed = [row for row in traces if row.get("cause") == "port-conflict"]
        self.assertTrue(failed)
        around = self.rt.diagnose(
            "around", "agent-a", "session-run", trace_id=failed[0]["traceId"],
        )
        self.assertTrue(around["ok"], around)
        self.assertIn("Address already in use", around["excerpt"])
        self.assertIsNone(proc.poll())

    def test_http_failure_and_timeout_are_classified_and_cleaned(self):
        http_failed = self.rt.run(
            "agent-a", "session-http", "demo", self.server(HTTP_500),
            port_range=(49400, 49420), attempts=1, health_timeout=3, cwd=self.work,
        )
        self.assertEqual(http_failed["cause"], "http-failed")
        self.assertTrue(any(row.get("httpStatus") == 500 for row in http_failed["attempts"]))
        timed = self.rt.run(
            "agent-a", "session-slow", "demo", self.server(SLOW),
            port_range=(49430, 49450), attempts=1, health_timeout=0.8, cwd=self.work,
        )
        self.assertEqual(timed["cause"], "timeout")
        self.assertTrue(any(row.get("exceptionType") for row in timed["attempts"]))

    def test_kept_server_close_releases_port_and_records_request_trace(self):
        body = self.rt.run(
            "agent-a", "session-keep", "demo", self.server(OK_SERVER),
            port_range=(49500, 49520), attempts=1, health_timeout=4, keep=True, cwd=self.work,
        )
        self.assertTrue(body["ok"], body)
        lease = body["lease"]
        added = self.rt.trace_add(
            "agent-a", "session-keep", lease["leaseId"], http_status=201,
            end_status="ok", exception_type=None, cause=None, phase="request",
        )
        self.assertTrue(added["ok"])
        queried = self.rt.trace_query(trace_id=added["traceId"])
        row = queried["traces"][0]
        self.assertEqual(row["port"], lease["port"])
        self.assertEqual(row["pid"], lease["pid"])
        self.assertEqual(row["httpStatus"], 201)
        self.assertEqual(row["owner"], "agent-a")
        log = self.rt.log_path(lease["leaseId"])
        sample = "tok" + "en=" + ("x" * 8)
        with log.open("ab") as handle:
            handle.write((sample + "\n").encode("utf-8"))
        tail = self.rt.diagnose(
            "tail", "agent-a", "session-keep", lease_id=lease["leaseId"], follow_seconds=0.3,
        )
        self.assertTrue(any("<redacted>" in line for line in tail["lines"]))
        closed = self.rt.close("agent-a", "session-keep", lease["leaseId"])
        self.assertTrue(closed["ok"], closed)
        self.assertEqual(self.rt.load_lease(lease["leaseId"])["state"], "released")
        deadline = time.time() + 3
        last_error = None
        while time.time() < deadline:
            probe = socket.socket()
            try:
                probe.bind(("127.0.0.1", int(lease["port"])))
                last_error = None
                break
            except OSError as exc:
                last_error = exc
                time.sleep(0.1)
            finally:
                probe.close()
        self.assertIsNone(last_error)

    def test_cli_ps1_and_bat_round_trip(self):
        base = [sys.executable, "-B", str(SCRIPT), "--root", str(self.root)]
        acquired = json.loads(subprocess.check_output(
            base + ["acquire", "--owner", "cli-owner", "--session", "cli-session",
                    "--service", "cli", "--range", "49600-49610"],
            text=True,
        ))
        self.assertTrue(acquired["ok"], acquired)
        released = json.loads(subprocess.check_output(
            base + ["release", "--owner", "cli-owner", "--session", "cli-session",
                    "--lease", acquired["lease"]["leaseId"]],
            text=True,
        ))
        self.assertTrue(released["ok"], released)
        usage = subprocess.run(
            base + ["acquire", "--service", "cli"], capture_output=True, text=True,
        )
        self.assertEqual(usage.returncode, 2)
        ps1 = SCRIPT.with_name("agent_port_lease.ps1")
        bat = SCRIPT.parents[1] / "Agent-Port.bat"
        if ps1.is_file():
            completed = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1),
                 "-Action", "acquire", "-Owner", "ps-owner", "-Session", "ps-session",
                 "-Service", "ps", "-Range", "49620-49630", "-Root", str(self.root)],
                capture_output=True, text=True, timeout=40,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            payload = json.loads(completed.stdout)
            self.assertTrue(payload["ok"], payload)
            subprocess.check_call(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1),
                 "-Action", "release", "-Owner", "ps-owner", "-Session", "ps-session",
                 "-Lease", payload["lease"]["leaseId"], "-Root", str(self.root)],
                timeout=40,
            )
        if bat.is_file():
            env = os.environ.copy()
            env["AWX_RAG_NO_PAUSE"] = "1"
            completed = subprocess.run(
                ["cmd", "/c", str(bat), "acquire", "--root", str(self.root),
                 "--owner", "bat-owner", "--session", "bat-session", "--range", "49640-49650"],
                capture_output=True, text=True, timeout=40, env=env,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            payload = json.loads(completed.stdout.strip().splitlines()[-1])
            self.assertEqual(payload["owner"], "bat-owner")


if __name__ == "__main__":
    unittest.main()
