"""Scanner regression: .ps1 $PWD / Get-Location working-directory references.

PowerShell's $PWD automatic variable and Get-Location evaluate to the process
working directory — a path, never a credential. secret_free must clear label
fragments that bind the identifier `pwd` to `$PWD`/`Get-Location` in .ps1
sources while still blocking real
literal values, env reads, calls, and other identifiers. Synthetic fixtures
only; the identifier is assembled so this file itself stays scanner-clean.
"""
import unittest

from scripts.test_codex_work_checkpoint import CP


class Ps1WorkingDirectoryScannerTest(unittest.TestCase):
    pwid = "p" + "wd"           # builds the real identifier at runtime
    setting = "api" + "Key"

    def test_ps1_working_directory_references_pass(self):
        pwid = self.pwid
        for statement in (pwid + " = $PWD.Path",
                          'detail = "' + pwid.upper() + '=$($PWD.Path) PS=5.1"',
                          pwid + " = Get-Location",
                          pwid + "=(Get-Location).Path"):
            with self.subTest(statement=statement):
                CP.secret_free((statement + "\n").encode(), "scripts/example.ps1")

    def test_ps1_non_working_directory_values_remain_blocked(self):
        pwid = self.pwid
        for statement in (pwid + ' = "synthetic-value"',
                          pwid + " = $env:STAY_BLOCKED",
                          pwid + " = Read-Host",
                          self.setting + " = $PWD.Path",
                          pwid + " = $PWD.Path\n" + self.setting + ' = "synthetic"'):
            with self.subTest(statement=statement), self.assertRaisesRegex(
                    CP.CheckpointError, "secret-pattern"):
                CP.secret_free((statement + "\n").encode(), "scripts/example.ps1")

    def test_other_languages_still_strict(self):
        pwid = self.pwid
        for path in ("scripts/example.py", "main/resources/static/x.js",
                     "docs/note.md", ""):
            with self.subTest(path=path), self.assertRaisesRegex(
                    CP.CheckpointError, "secret-pattern"):
                CP.secret_free((pwid + " = $PWD.Path\n").encode(), path)


if __name__ == "__main__":
    unittest.main()
