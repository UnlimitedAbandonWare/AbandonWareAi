"""Scanner regression: a Python ``name = call(...)`` binds runtime bytes, not a
credential literal. Synthetic fixtures only; key-shaped strings are split so
this file itself stays a non-secret under the checkpoint scan."""
import unittest

from scripts.test_codex_work_checkpoint import CP


def held(text, path="scripts/example.py"):
    try:
        CP.secret_free(text.encode(), path)
        return False
    except CP.CheckpointError:
        return True


class PythonCallRhsCheckpointTest(unittest.TestCase):

    key = "api" + "_key"
    tok = "to" + "ken"

    def test_call_result_assignments_hold_no_credential(self):
        cases = [
            self.tok + ' = encoded({"pid": 1})',
            self.tok + " = make_lock_token()",
            self.key + " = self._resolve(name)",
            "pass" + "word" + " = cfg.get_password_ref(user)",
            self.tok + ' = encoded({\n    "pid": 1,\n    "nonce": n\n})',
        ]
        for text in cases:
            with self.subTest(text=text):
                self.assertFalse(held(text), "call-RHS false positive: " + text.splitlines()[0])

    def test_literal_and_env_rhs_stay_blocked(self):
        cases = [
            self.tok + ' = "sk-" + "A" * 24',
            self.key + ' = os.environ["X"]',
            self.key + ' = os.getenv("X")',
            self.key + ' = os.environ.get("X")',
            self.tok + ' = f"pre{x}post"',
            self.tok + ' = b"raw"',
            self.tok + " = 'abc' if x else y",
        ]
        for text in cases:
            with self.subTest(text=text):
                self.assertTrue(held(text), "must stay blocked: " + text)

    def test_secret_shaped_literal_inside_call_stays_blocked(self):
        inside = self.tok + ' = encoded("sk-' + 'B' * 24 + '")'
        spread = self.tok + ' = enc("a", "sk-' + 'C' * 24 + '")'
        for text in (inside, spread):
            with self.subTest(text=text):
                self.assertTrue(held(text), "literal in call args must block: " + text[:40])

    def test_call_rule_is_scoped_to_python_paths(self):
        text = self.tok + " = make_lock_token()"
        for path in ("docs/note.md", "main/java/E.java", "src/e.js", ""):
            with self.subTest(path=path):
                self.assertTrue(held(text, path), "non-python path must stay strict")


if __name__ == "__main__":
    unittest.main()
