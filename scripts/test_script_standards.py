"""Standards for the script-hardening rail.

Covers masking, argparse exit codes, root launcher hardcodes, the strict-mode
PowerShell facade, and Doctor-Scripts.bat. It does not retrofit every legacy
script. Stdlib only.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from script_mask import find_secrets, mask_text  # noqa: E402


def _bearer(body: str) -> str:
    return ("Bea" + "rer ") + body


def _assigned(value: str) -> str:
    return ("api_" + "key") + " = '" + value + "'"


def _openai(body: str) -> str:
    return ("sk" + "-") + body


class MaskTests(unittest.TestCase):
    def test_mask_hides_constructed_values(self):
        bearer = _bearer("Z" * 32)
        assigned = _assigned("c" * 16)
        openai = _openai("b" * 24)
        masked = mask_text("\n".join((bearer, assigned, openai)))
        self.assertNotIn("Z" * 16, masked)
        self.assertNotIn("c" * 16, masked)
        self.assertNotIn("b" * 16, masked)
        self.assertIn("[REDACTED:bearer]", masked)
        self.assertIn("[REDACTED:assigned_secret]", masked)
        self.assertIn("[REDACTED:openai_sk]", masked)

    def test_plain_text_unchanged(self):
        self.assertEqual(mask_text("hello inventory"), "hello inventory")

    def test_find_secrets_omits_values(self):
        hits = find_secrets(_bearer("Z" * 32) + "\n" + _assigned("changeme-placeholder-value"))
        encoded = json.dumps(hits)
        self.assertNotIn("Z" * 8, encoded)
        self.assertEqual(hits[0]["pattern"], "bearer")
        self.assertEqual(hits[0]["severity"], "review")
        self.assertEqual(hits[1]["severity"], "advisory")
        self.assertNotIn("value", hits[0])

    def test_owned_sources_have_no_review_hits(self):
        for rel in (
            "scripts/script_mask.py",
            "scripts/scripts_inventory.py",
            "scripts/script_doctor.py",
            "scripts/test_script_standards.py",
            "scripts/script_doctor.ps1",
            "Doctor-Scripts.bat",
        ):
            text = (ROOT / rel).read_text(encoding="utf-8")
            review = [hit for hit in find_secrets(text) if hit["severity"] == "review"]
            self.assertEqual(review, [], rel)


class CliTests(unittest.TestCase):
    def run_tool(self, args):
        return subprocess.run(
            [sys.executable, "-B", *args],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
            check=False,
        )

    def test_help_exits_zero(self):
        for script in ("scripts/scripts_inventory.py", "scripts/script_doctor.py"):
            completed = self.run_tool([script, "--help"])
            self.assertEqual(completed.returncode, 0, script)
            self.assertIn("usage", completed.stdout.lower())

    def test_missing_manifest_check_is_nonzero(self):
        missing = Path(tempfile.gettempdir()) / "awx-scripts-manifest-missing.json"
        if missing.exists():
            missing.unlink()
        completed = self.run_tool([
            "scripts/scripts_inventory.py", "--check", "--manifest", str(missing),
        ])
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("manifest-missing", completed.stderr)

    def test_inventory_json_then_check(self):
        handle = tempfile.NamedTemporaryFile("w", delete=False, suffix=".json")
        handle.close()
        path = handle.name
        try:
            written = self.run_tool(["scripts/scripts_inventory.py", "--json", path])
            self.assertEqual(written.returncode, 0, written.stderr)
            payload = json.loads(Path(path).read_text(encoding="utf-8"))
            self.assertGreaterEqual(payload["total"], 1500)
            self.assertEqual(set(payload["byExtension"]), {
                ".bat", ".cmd", ".js", ".mjs", ".ps1", ".py", ".sh", ".sql",
            })
            self.assertIn("sha12", payload["files"][0])
            self.assertIn("category", payload["files"][0])
            self.assertEqual(payload["projectRoot"], ".")
            self.assertEqual(payload["orphanPolicy"], "STALE_FLAG_ONLY")
            checked = self.run_tool([
                "scripts/scripts_inventory.py", "--check", "--manifest", path,
            ])
            self.assertEqual(checked.returncode, 0, checked.stderr)
            self.assertIn("manifest-ok", checked.stdout)
        finally:
            try:
                os.remove(path)
            except OSError:
                pass

    def test_root_launchers_have_no_project_hardcode(self):
        completed = self.run_tool(["scripts/scripts_inventory.py", "--check-hardcoded-paths"])
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("count=0", completed.stdout)

    def test_new_python_tools_use_argparse_and_explicit_exit(self):
        mask = (ROOT / "scripts" / "script_mask.py").read_text(encoding="utf-8")
        self.assertIn("import argparse", mask)
        self.assertIn("ArgumentParser", mask)
        for rel in ("scripts/scripts_inventory.py", "scripts/script_doctor.py"):
            text = (ROOT / rel).read_text(encoding="utf-8")
            self.assertIn("parse_args", text)
            self.assertIn("build_parser", text)
            self.assertIn("sys.exit", text)
            self.assertIn("mask_text", text)

    def test_powershell_facade_is_strict(self):
        text = (ROOT / "scripts" / "script_doctor.ps1").read_text(encoding="utf-8")
        self.assertIn("[CmdletBinding(", text)
        self.assertIn("SupportsShouldProcess", text)
        self.assertIn("Set-StrictMode -Version Latest", text)
        self.assertIn("$ErrorActionPreference = 'Stop'", text)
        completed = subprocess.run(
            ["powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-File", str(ROOT / "scripts" / "script_doctor.ps1"), "-WhatIf"],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("WhatIf", completed.stdout)

    def test_doctor_bat_is_a_dynamic_facade(self):
        text = (ROOT / "Doctor-Scripts.bat").read_text(encoding="utf-8")
        self.assertIn("chcp 65001", text)
        self.assertIn("%~dp0", text)
        self.assertIn("python -B", text)
        self.assertIn("exit /b", text)
        self.assertNotIn("C:\\AbandonWare", text)
        self.assertNotIn("C:/AbandonWare", text)

    def test_doctor_summary_is_healthy(self):
        handle = tempfile.NamedTemporaryFile("w", delete=False, suffix=".json")
        handle.close()
        path = handle.name
        try:
            completed = self.run_tool([
                "scripts/script_doctor.py", "--summary", "--json", path,
            ])
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertIn("healthy=true", completed.stdout)
            report = json.loads(Path(path).read_text(encoding="utf-8"))
            self.assertTrue(report["healthy"])
            self.assertEqual(report["gate"]["hardcodedProjectRoot"], 0)
            self.assertGreaterEqual(report["inventory"]["total"], 1500)
            encoded = json.dumps(report)
            self.assertNotIn("Z" * 24, encoded)
            self.assertNotRegex(encoded, r"sk-[A-Za-z0-9]{20,}")
        finally:
            try:
                os.remove(path)
            except OSError:
                pass


if __name__ == "__main__":
    program = unittest.main(exit=False)
    sys.exit(0 if program.result.wasSuccessful() else 1)
