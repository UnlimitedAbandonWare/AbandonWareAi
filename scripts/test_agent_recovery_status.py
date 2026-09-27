import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "agent_recovery_status.py"


def run(*args):
    proc = subprocess.run(
        [sys.executable, "-B", str(SCRIPT), *args],
        capture_output=True, text=True, timeout=120)
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


class AgentRecoveryStatusTest(unittest.TestCase):
    def test_guide_known_reason(self):
        code, out = run("guide", "--reason", "source-lease-scope")
        self.assertEqual(code, 0)
        self.assertTrue(out["known"])
        self.assertTrue(any("artifact" in s or "TargetManifest" in s
                            for s in out["nextActions"]))

    def test_guide_unknown_reason_lists_map(self):
        code, out = run("guide", "--reason", "bogus-rule")
        self.assertEqual(code, 0)
        self.assertFalse(out["known"])
        self.assertIn("source-lease-scope", out["knownReasons"])

    def test_status_skip_lease_scan_hermetic(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run("status", "--root", tmp, "--skip-lease-scan")
            self.assertEqual(code, 0, out)
            self.assertEqual(out["schemaVersion"],
                             "awx.agent-recovery-status.v1")
            self.assertEqual(out["leases"]["status"], "skipped")
            self.assertIn(out["journals"]["status"], ("ok", "unavailable"))
            self.assertIn(out["quarantineCandidates"]["status"],
                          ("ok", "unavailable"))
            self.assertTrue(out["nextActions"])

    def test_status_real_root_readonly(self):
        # Real checkout: journals section must parse; lease scan may be
        # unavailable on hosts without PowerShell, never an error here.
        code, out = run("status", "--root", str(ROOT))
        self.assertEqual(code, 0, out)
        self.assertEqual(out["journals"]["status"], "ok")
        self.assertIn("inProgressCount", out["journals"])
        self.assertIn(out["leases"]["status"], ("ok", "unavailable"))
        self.assertTrue(out["nextActions"])


if __name__ == "__main__":
    unittest.main()
