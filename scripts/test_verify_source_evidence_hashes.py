"""Fixture tests for verify_source_evidence_hashes.ps1 markdown and JSON modes."""
from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

PS1 = Path(__file__).with_name("verify_source_evidence_hashes.ps1")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_ps(*args: str) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(PS1), *args],
        capture_output=True)
    raw = proc.stdout or b""
    if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
        text = raw.decode("utf-16")
    else:
        text = raw.decode("utf-8", errors="replace")
    err = (proc.stderr or b"").decode("utf-8", errors="replace")
    return subprocess.CompletedProcess(proc.args, proc.returncode, text, err)


class VerifySourceEvidenceHashesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.main = self.root / "main"
        self.main.mkdir()
        self.keep = self.main / "keep.txt"
        self.keep.write_text("same", encoding="utf-8")
        self.changed = self.main / "changed.txt"
        self.changed.write_text("now", encoding="utf-8")

    def test_evidence_md_match_diff_missing(self) -> None:
        digest = sha(self.keep)
        other = "0" * 64
        doc = self.root / "evidence.md"
        doc.write_text(
            f"`main/keep.txt` SHA-256: `{digest}`\n"
            f"`main/changed.txt` SHA-256: `{other}`\n"
            f"`main/missing.txt` SHA-256: `{other}`\n",
            encoding="utf-8")
        proc = run_ps("-EvidenceMd", str(doc), "-Root", str(self.root), "-Json")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        payload = json.loads(proc.stdout)
        by_path = {row["path"]: row["status"] for row in payload["rows"]}
        self.assertEqual(by_path["main/keep.txt"], "MATCH")
        self.assertEqual(by_path["main/changed.txt"], "DIFF")
        self.assertEqual(by_path["main/missing.txt"], "MISSING")
        self.assertIn("re-anchor", payload["note"])

    def test_hash_json_source_hashes(self) -> None:
        digest = sha(self.keep)
        other = "1" * 64
        doc = self.root / "hashes.json"
        doc.write_text(json.dumps({"sourceHashes": {
            "main/keep.txt": digest,
            "main/changed.txt": other,
            "main/missing.txt": other,
        }}), encoding="utf-8")
        proc = run_ps("-HashJson", str(doc), "-HashKey", "sourceHashes",
                      "-Root", str(self.root), "-Json")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        payload = json.loads(proc.stdout)
        by_path = {row["path"]: row["status"] for row in payload["rows"]}
        self.assertEqual(by_path, {
            "main/keep.txt": "MATCH",
            "main/changed.txt": "DIFF",
            "main/missing.txt": "MISSING",
        })
        self.assertIn("re-anchor", payload["note"])


if __name__ == "__main__":
    unittest.main()
