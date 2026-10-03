"""Exact synthetic fixture/detector literals; changed values remain strict."""
import unittest
from scripts import codex_work_checkpoint as CP


class GoldenBrowserLiteralTest(unittest.TestCase):
    def test_exact_literals_have_no_credential_bytes(self):
        detector = "/Bearer |sk-|" + "token" + "=/i.test(String(item))"
        fixture = "authorization" + ":'synthetic-value'"
        CP.secret_free(detector.encode(), "scripts/chat_rag_golden_browser.js")
        CP.secret_free(fixture.encode(), "scripts/chat_rag_golden_browser_tests.js")

    def test_other_paths_altered_values_and_adjacent_credentials_remain_blocked(self):
        detector = "/Bearer |sk-|" + "token" + "=/i.test(String(item))"
        fixture = "authorization" + ":'synthetic-value'"
        cases = [(detector, "main/resources/static/example.js"),
                 (fixture, "scripts/other_test.js"),
                 (fixture.replace("synthetic-value", "actual-value"), "scripts/chat_rag_golden_browser_tests.js"),
                 (fixture + "\n" + "password" + "=actual-value", "scripts/chat_rag_golden_browser_tests.js")]
        for value, target in cases:
            with self.subTest(target=target), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(value.encode(), target)
