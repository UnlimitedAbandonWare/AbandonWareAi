import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "agent_worker_registry.py"


def run(*args, root):
    proc = subprocess.run(
        [sys.executable, "-B", str(SCRIPT), "--root", str(root), *args],
        capture_output=True, text=True, timeout=60)
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


class AgentWorkerRegistryTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_register_heartbeat_list(self):
        code, out = run("register", "--id", "w1", "--role", "glm_worker",
                        "--task", "t1", "--agent", "codex", root=self.root)
        self.assertEqual(code, 0, out)
        self.assertTrue(out["ok"])
        self.assertEqual(out["worker"]["status"], "registered")
        self.assertEqual(out["worker"]["liveness"], "fresh")

        code, out = run("heartbeat", "--id", "w1", root=self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out["worker"]["status"], "active")

        code, out = run("list", root=self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out["count"], 1)
        self.assertEqual(out["workers"][0]["workerId"], "w1")

    def test_classify_requires_cause(self):
        run("register", "--id", "w1", "--role", "r", root=self.root)
        code, out = run("classify", "--id", "w1", "--state", "blocked",
                        root=self.root)
        self.assertEqual(code, 2)
        self.assertEqual(out["error"], "state-and-cause-required")

        code, out = run("classify", "--id", "w1", "--state", "blocked",
                        "--cause", "lease-conflict", root=self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out["worker"]["causeCode"], "lease-conflict")
        self.assertEqual(out["policy"]["decision"], "retry")

    def test_retry_policy_max_once_and_same_cause(self):
        run("register", "--id", "w1", "--role", "r", root=self.root)
        run("classify", "--id", "w1", "--state", "error",
            "--cause", "tool-failure", root=self.root)
        code, out = run("retry", "--id", "w1", root=self.root)
        self.assertEqual(code, 0, out)
        self.assertEqual(out["worker"]["retries"], 1)
        self.assertEqual(out["worker"]["status"], "active")

        # second failure, different cause: retries exhausted -> abort-report
        run("classify", "--id", "w1", "--state", "error",
            "--cause", "no-response", root=self.root)
        code, out = run("retry", "--id", "w1", root=self.root)
        self.assertEqual(code, 4)
        self.assertEqual(out["policy"]["decision"], "abort-report")
        self.assertEqual(out["policy"]["reason"], "max-retries-1")

    def test_same_cause_repeat_aborts_even_before_max(self):
        run("register", "--id", "w1", "--role", "r", root=self.root)
        run("classify", "--id", "w1", "--state", "blocked",
            "--cause", "lease-conflict", root=self.root)
        run("retry", "--id", "w1", root=self.root)
        run("classify", "--id", "w1", "--state", "blocked",
            "--cause", "lease-conflict", root=self.root)
        code, out = run("retry-decision", "--id", "w1", root=self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out["policy"]["decision"], "abort-report")
        self.assertEqual(out["policy"]["reason"], "same-cause-repeated")

    def test_sweep_marks_stale_no_response(self):
        run("register", "--id", "w1", "--role", "r", "--ttl-sec", "1",
            root=self.root)
        import time
        time.sleep(1.1)
        code, out = run("sweep", "--mark", root=self.root)
        self.assertEqual(code, 0)
        self.assertEqual(len(out["staleWorkers"]), 1)
        code, out = run("status", "--id", "w1", root=self.root)
        self.assertEqual(out["worker"]["status"], "blocked")
        self.assertEqual(out["worker"]["causeCode"], "no-response")

    def test_close_terminal(self):
        run("register", "--id", "w1", "--role", "r", root=self.root)
        code, out = run("close", "--id", "w1", "--result", "done",
                        root=self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out["worker"]["status"], "closed")
        code, out = run("register", "--id", "w1", "--role", "r",
                        root=self.root)
        self.assertNotEqual(out.get("ok"), True)

    def test_missing_worker(self):
        code, out = run("heartbeat", "--id", "ghost", root=self.root)
        self.assertEqual(code, 3)
        self.assertEqual(out["error"], "worker-not-found")


if __name__ == "__main__":
    unittest.main()
