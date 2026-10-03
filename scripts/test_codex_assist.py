"""Contract tests for scripts/codex_assist.py (delegation packet + collect + status).

Fixtures use tempdirs only; no real ledger, no git, no network.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
ASSIST = SCRIPTS / "codex_assist.py"

ROLES = ["assist_browser_verifier", "assist_rag_trace_analyst",
         "assist_routing_mapper", "assist_patch_reviewer"]

RESULT_TEMPLATE = """finding
task_received={marker}
scenario failed at retrieval stage.
evidence
main/java/com/example/lms/Foo.java:12 returns empty list.
uncertainty
not re-checked against a live server.
recommended next check
run focused test FooTest.
files_written={files_written}
"""


def run_assist(*args, cwd=None):
    return subprocess.run(
        [sys.executable, "-B", str(ASSIST), *args],
        capture_output=True, text=True, cwd=cwd or str(SCRIPTS.parent))


def make_packet(ledger, role, objective="check C2", extra=()):
    proc = run_assist("packet", "--role", role, "--objective", objective,
                      "--ledger", str(ledger), *extra)
    assert proc.returncode == 0, proc.stderr + proc.stdout
    packets = list((Path(ledger) / "assist" / "packets").glob("*.json"))
    assert packets, "no packet file written"
    return json.loads(packets[-1].read_text(encoding="utf-8")), packets[-1]


class PacketTests(unittest.TestCase):
    def test_packet_all_roles(self):
        # DV2-1: each role emits a packet JSON with an 8-hex marker; unknown role refused.
        with tempfile.TemporaryDirectory() as td:
            for role in ROLES:
                ledger = Path(td) / role
                packet, path = make_packet(ledger, role)
                self.assertEqual(packet["role"], role)
                self.assertRegex(packet["deliveryMarker"], r"^[0-9a-f]{8}$")
                self.assertIsInstance(packet["allowedWritePaths"], list)
                self.assertIn("delegateText", packet)
            bad = run_assist("packet", "--role", "assist_nope", "--objective", "x",
                             "--ledger", str(Path(td) / "bad"))
            self.assertNotEqual(bad.returncode, 0)


class CollectTests(unittest.TestCase):
    def _collect(self, ledger, packet_path, text):
        result_file = Path(ledger) / "result.txt"
        result_file.write_text(text, encoding="utf-8")
        return run_assist("collect", "--packet", str(packet_path),
                          "--result", str(result_file))

    def test_collect_accept(self):
        # DV2-2: 4 sections + matching marker + files_written=none -> ACCEPT.
        with tempfile.TemporaryDirectory() as td:
            packet, ppath = make_packet(td, "assist_rag_trace_analyst")
            proc = self._collect(td, ppath, RESULT_TEMPLATE.format(
                marker=packet["deliveryMarker"], files_written="none"))
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            out = json.loads(proc.stdout)
            self.assertEqual(out["verdict"], "ACCEPT")
            lines = (Path(td) / "assist" / "results.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 1)
            self.assertEqual(json.loads(lines[0])["verdict"], "ACCEPT")

    def test_collect_marker_mismatch_and_missing(self):
        # DV2-3: wrong marker or absent marker -> REJECT.
        with tempfile.TemporaryDirectory() as td:
            packet, ppath = make_packet(td, "assist_rag_trace_analyst")
            wrong = self._collect(td, ppath, RESULT_TEMPLATE.format(
                marker="00000000", files_written="none"))
            self.assertEqual(wrong.returncode, 3)
            self.assertEqual(json.loads(wrong.stdout)["verdict"], "REJECT")
            nomarker = RESULT_TEMPLATE.format(marker="", files_written="none").replace(
                "task_received=\n", "")
            missing = self._collect(td, ppath, nomarker)
            self.assertEqual(missing.returncode, 3)

    def test_collect_missing_section(self):
        # DV2-4: a missing required section -> REJECT.
        with tempfile.TemporaryDirectory() as td:
            packet, ppath = make_packet(td, "assist_patch_reviewer")
            text = RESULT_TEMPLATE.format(
                marker=packet["deliveryMarker"], files_written="none")
            text = text.replace("uncertainty\nnot re-checked against a live server.\n", "")
            proc = self._collect(td, ppath, text)
            self.assertEqual(proc.returncode, 3)
            self.assertIn("uncertainty", json.dumps(json.loads(proc.stdout)["reasons"]))

    def test_collect_files_written_outside_allowed(self):
        # DV2-5: files_written outside packet allowedWritePaths -> REJECT.
        with tempfile.TemporaryDirectory() as td:
            packet, ppath = make_packet(td, "assist_browser_verifier")
            text = RESULT_TEMPLATE.format(
                marker=packet["deliveryMarker"],
                files_written="main/java/com/example/lms/Foo.java")
            proc = self._collect(td, ppath, text)
            self.assertEqual(proc.returncode, 3)
            self.assertEqual(json.loads(proc.stdout)["verdict"], "REJECT")

    def test_collect_secret_pattern_no_body_stored(self):
        # DV2-6: secret-like content -> REJECT; results.jsonl keeps hash+summary only.
        # Fixture uses a slack-style credential string; exercises the secret
        # gate without embedding a credential-shaped literal in this source.
        with tempfile.TemporaryDirectory() as td:
            packet, ppath = make_packet(td, "assist_rag_trace_analyst")
            text = RESULT_TEMPLATE.format(
                marker=packet["deliveryMarker"], files_written="none")
            text += "note: leaked " + "xox" + "b-123456789012\n"
            proc = self._collect(td, ppath, text)
            self.assertEqual(proc.returncode, 3)
            blob = (Path(td) / "assist" / "results.jsonl").read_text(encoding="utf-8")
            # pattern name may be logged; the leaked token bytes must not be.
            self.assertNotIn("xoxb-123456789012", blob)
            rec = json.loads(blob.splitlines()[0])
            self.assertEqual(rec["verdict"], "REJECT")
            self.assertIn("resultSha256", rec)


class StatusTests(unittest.TestCase):
    def test_status_budget_accounting(self):
        # DV2-7: status reports declared vs observed browser-send budget.
        with tempfile.TemporaryDirectory() as td:
            packet, ppath = make_packet(td, "assist_browser_verifier",
                                        extra=("--budget-sends", "10"))
            text = RESULT_TEMPLATE.format(
                marker=packet["deliveryMarker"], files_written="none")
            text += "browser_sends=4\n"
            result_file = Path(td) / "r.txt"
            result_file.write_text(text, encoding="utf-8")
            run_assist("collect", "--packet", str(ppath), "--result", str(result_file))
            proc = run_assist("status", "--ledger", str(td))
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            out = json.loads(proc.stdout)
            self.assertEqual(out["delegations"], 1)
            self.assertEqual(out["browserSends"]["declared"], 10)
            self.assertEqual(out["browserSends"]["observed"], 4)
            self.assertEqual(out["browserSends"]["remaining"], 6)


if __name__ == "__main__":
    unittest.main()
