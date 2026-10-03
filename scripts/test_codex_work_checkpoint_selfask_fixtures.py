"""SelfAsk synthetic redaction-fixture exemption regression; no real credentials.

Regression for the two fixed secret-shaped fixture literals inside
src/test/java/com/example/lms/service/rag/SelfAskWebSearchRetrieverTest.java:
the checkpoint scanner must accept exactly those bytes on exactly that path,
while mutated values, other paths, and co-resident secrets stay blocked.
All literals below are token-split so this file itself stays secret_free.
"""
from pathlib import Path
import unittest

from scripts.test_codex_work_checkpoint import CP

SELFASK = "src/test/java/com/example/lms/service/rag/selfaskwebsearchretrievertest.java"


def fixture_line(prefix, tail=None):
    # Rebuilds the exact Java bytes '"<prefix> <api-key label>=sk-" + "<tail>"'
    # without ever writing a contiguous secret literal in this file.
    tail = tail or ('abcdefghijklm' + 'nopqrstuvwxyz' + '123456')
    return '"' + prefix + ' api' + '_key=sk-" + "' + tail + '"'


class SelfAskFixtureCheckpointTest(unittest.TestCase):
    def test_both_fixed_fixture_literals_pass_on_the_selfask_path(self):
        for prefix in ("retry branch", "raw timeout query with"):
            with self.subTest(prefix=prefix):
                CP.secret_free(
                    ('class T { String s = ' + fixture_line(prefix) + '; }').encode(),
                    SELFASK)

    def test_real_selfask_test_file_passes_secret_free(self):
        source = (Path(__file__).resolve().parent.parent
                  / "src/test/java/com/example/lms/service/rag/SelfAskWebSearchRetrieverTest.java")
        self.assertTrue(source.is_file(), "SelfAsk test file must exist for this regression")
        CP.secret_free(source.read_bytes(),
                       "src/test/java/com/example/lms/service/rag/SelfAskWebSearchRetrieverTest.java")

    def test_same_fixture_bytes_stay_blocked_off_path(self):
        body = 'class T { String s = ' + fixture_line("retry branch") + '; }'
        for path in ("src/test/java/com/example/lms/service/rag/OtherSelfAskTest.java",
                     "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java",
                     "scripts/example.py", "docs/e.md", ""):
            with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(body.encode(), path)

    def test_mutated_token_stays_blocked_on_the_selfask_path(self):
        for tail in ('abcdefghijklm' + 'nopqrstuvwxyz' + '123457', 'differenttail'):
            with self.subTest(tail=tail[-6:]), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(
                    ('class T { String s = ' + fixture_line("retry branch", tail) + '; }').encode(),
                    SELFASK)
            with self.subTest(tail=tail[-6:], prefix="raw timeout query with"), \
                    self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(
                    ('class T { String s = ' + fixture_line("raw timeout query with", tail) + '; }').encode(),
                    SELFASK)

    def test_single_literal_form_without_concat_stays_blocked(self):
        joined = '"retry branch api' + '_key=sk-' + 'abcdefghijklm' + 'nopqrstuvwxyz' + '123456' + '"'
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(('class T { String s = ' + joined + '; }').encode(), SELFASK)

    def test_co_resident_secret_next_to_fixture_stays_blocked(self):
        body = ('class T { String s = ' + fixture_line("retry branch") + ';'
                + ' String real = "api' + '_key=other-real-value"; }')
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(body.encode(), SELFASK)


if __name__ == "__main__":
    unittest.main()
