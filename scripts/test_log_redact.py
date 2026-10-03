#!/usr/bin/env python3
"""log_redact fixtures: secret samples must survive zero leakage; Gradle
error spine must stay readable for AWX classification."""
from __future__ import annotations

import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from scripts.log_redact import keep_gradle_spine, redact_text

# Fixture secrets are built by concatenation so the file itself carries no
# literal credential bytes (checkpoint secret-scan convention).
SECRETS = {
    "bearer": "Authoriz" + "ation: Bea" + "rer abcdefghijklmnopqrstuvwxyz123456",
    "jwt": "tok" + "en=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sigpart",
    "sk": "key is sk-" + "abcdefghij1234567890abcd",
    "kv": "pass" + "word=hunter2 api_k" + "ey=ZZZ1234567890ABCDEFG",
    "jdbc": "jdbc:h2:file:C:/data/lmsdb;user=sa;" + "pass" + "word=dbsecret9",
    "url": "https://deploy:tok3n9@vercel.example.com/hook",
    "env": "AI_GATEWAY_API" + "_KEY=vk-live-abc123def456",
    "cookie": "Cook" + "ie: AWXSESS=deadbeefsession42; XSRF-TO" + "KEN=tok",
    "home": "C:\\Users\\nninn\\Downloads\\mfwasainx.zip",
    "ghp": "ghp_" + "abcdefghij1234567890ABCD",
}
SECRETS_LIST = list(SECRETS.values())

ERROR_SPINE = """
Configuration on demand is an incubating feature.
""" + "\n".join(f"resolve dep {i} from repo.maven.example" for i in range(12)) + """
> Task :app:compileJava
C:\\x\\ChatRunRegistry.java:535: error: cannot find symbol
  symbol:   method lock()
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:compileJava'.
> Compilation failed; see the compiler error output for details.
* Try:
> Run with --stacktrace option to get the stack trace.
BUILD FAILED in 1m 2s
"""


class RedactText(unittest.TestCase):
    def test_no_secret_leaks(self):
        blob = "\n".join(SECRETS.values())
        out, counts = redact_text(blob)
        for leak in ("abcdefghijklmnopqrstuvwxyz123456", "hunter2",
                     "sk-" + "abcdefghij1234567890abcd", "dbsecret9", "tok3n9",
                     "vk-live-abc123def456", "deadbeefsession42", "nninn",
                     "ghp_abcdefghij1234567890ABCD", "eyJhbGciOiJIUzI1NiJ9"):
            self.assertNotIn(leak, out)
        self.assertGreater(sum(counts.values()), 0)

    def test_names_not_values(self):
        out, _ = redact_text(SECRETS["env"])
        self.assertIn("AI_GATEWAY_API_KEY", out)  # name stays, value goes
        self.assertNotIn("vk-live", out)

    def test_url_cred(self):
        out, _ = redact_text(SECRETS["url"])
        self.assertIn("vercel.example.com", out)
        self.assertNotIn("deploy:tok3n9", out)

    def test_jdbc_keeps_path(self):
        out, _ = redact_text(SECRETS["jdbc"])
        self.assertIn("lmsdb", out)
        self.assertNotIn("dbsecret9", out)


class Spine(unittest.TestCase):
    def test_error_lines_survive(self):
        redacted, _ = redact_text(ERROR_SPINE)
        compact = keep_gradle_spine(redacted)
        for needle in ("FAILURE:", "What went wrong", "error:", "BUILD FAILED",
                       "Execution failed for task"):
            self.assertIn(needle, compact)
        self.assertIn("[elided", compact)

    def test_redact_then_spine_preserves_mask(self):
        blob = ERROR_SPINE + "\npassword=hunter2\n" + SECRETS["bearer"]
        redacted, _ = redact_text(blob)
        compact = keep_gradle_spine(redacted)
        self.assertNotIn("hunter2", compact)


class Cli(unittest.TestCase):
    def test_end_to_end_file(self):
        from scripts.log_redact import main
        with tempfile.TemporaryDirectory() as td:
            src, dst = Path(td) / "in.log", Path(td) / "out.log"
            src.write_text("\n".join(SECRETS.values()) + ERROR_SPINE,
                           encoding="utf-8")
            rc = main(["--in", str(src), "--out", str(dst), "--spine-only"])
            self.assertEqual(rc, 0)
            out = dst.read_text(encoding="utf-8")
            self.assertNotIn("hunter2", out)
            self.assertNotIn("nninn", out)
            self.assertIn("FAILURE:", out)


if __name__ == "__main__":
    unittest.main()
