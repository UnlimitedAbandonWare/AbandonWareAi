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


class DisplayInvalidUrlFixtureScannerTest(unittest.TestCase):
    source = "scripts/meta_display_launcher_tests.ps1"
    query = "to" + "ken=synthetic"
    url = "'https://example.com/?" + query + "'"
    header = ("foreach ($invalid in @('http://example.com',"
              "'https://user:synthetic@example.com'," + url
              + ",'https://example.com/#synthetic')) {")

    def test_exact_invalid_url_header_passes(self):
        for newline in ("\n", "\r\n"):
            CP.secret_free(("    " + self.header + newline).encode(), self.source)

    def test_altered_url_and_source_path_remain_blocked(self):
        for header in (self.header.replace(self.query, self.query + "-changed"),
                       self.header.replace("https://example.com/?", "https://other.example/?"),
                       self.header.replace("https://example.com/?", "https://example.com/other?")):
            with self.subTest(header=header), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(header.encode(), self.source)
        for path in ("scripts/other.ps1", "main/resources/example.ps1"):
            with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(self.header.encode(), path)

    def test_comments_strings_and_adjacent_secret_remain_blocked(self):
        extra = "api" + "Key='synthetic-sensitive-value'"
        for text in ("# " + self.header, '"' + self.header + '"',
                     self.header + " # " + extra, self.header + "\n" + extra):
            with self.subTest(text=text), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(text.encode(), self.source)


if __name__ == "__main__":
    unittest.main()
