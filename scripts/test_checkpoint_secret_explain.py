"""checkpoint_secret_explain: verdict parity and hit fields; matched bytes
must never appear in any output. Synthetic fixtures only."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("checkpoint_secret_explain.py")
SPEC = importlib.util.spec_from_file_location("checkpoint_secret_explain", SCRIPT)
EX = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EX)

from scripts.test_codex_work_checkpoint import CP

kw_token = "to" + "ken"
kw_apikey = "api" + "-key"
fake_value = "zebra9Quartz7-synthetic-value"
java_path = "main/java/Example.java"
real_path = "main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java"
repo_root = Path(__file__).resolve().parents[1]


def scanner_verdict(text, path):
    try:
        CP.secret_free(text.encode(), path)
    except CP.CheckpointError:
        return "hold"
    return "pass"


class SecretExplainTest(unittest.TestCase):
    def explain(self, text, path=java_path):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root.joinpath(*path.split("/"))
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(text.encode())
            return EX.explain_file(root, path)

    def test_clean_java_file_passes_with_no_hits(self):
        result = self.explain("class E { int count = 1; }\n")
        self.assertEqual("pass", result["verdict"])
        self.assertEqual([], result["hits"])

    def test_literal_assignment_holds_with_line_identifier_kind(self):
        text = "class E {\n  String " + kw_token + ' = "' + fake_value + '";\n}\n'
        result = self.explain(text)
        self.assertEqual("hold", result["verdict"])
        self.assertEqual(1, len(result["hits"]))
        hit = result["hits"][0]
        self.assertEqual(2, hit["line"])
        self.assertEqual("token", hit["identifier"])
        self.assertEqual("literal", hit["rhsKind"])
        self.assertIs(True, hit["blocking"])

    def test_untyped_lambda_assignment_reports_lambda_kind(self):
        # A bare keyword assignment (no type) is never an exempt declaration,
        # so the hit survives in every scanner version.
        text = "class E {\n  void f() { " + kw_token + " = () -> source.get(); }\n}\n"
        result = self.explain(text)
        self.assertEqual("hold", result["verdict"])
        kinds = {hit["rhsKind"] for hit in result["hits"]}
        self.assertIn("lambda", kinds)

    def test_comment_hit_reports_comment_kind(self):
        text = "// " + kw_apikey + ": " + fake_value + "\nclass E {}\n"
        result = self.explain(text)
        self.assertEqual("hold", result["verdict"])
        self.assertEqual("comment", result["hits"][0]["rhsKind"])

    def test_bearer_and_private_key_material_stay_blocking(self):
        bearer = 'String s = "Bearer ' + ("x" * 16) + '";'
        result = self.explain("class E { " + bearer + " }\n")
        self.assertEqual("hold", result["verdict"])
        self.assertEqual("bearer", result["hits"][0]["identifier"].lower())
        self.assertIs(True, result["hits"][0]["blocking"])
        key = "-----BEGIN " + "PRIVATE KEY-----"
        self.assertEqual("hold", self.explain(key + "\n")["verdict"])

    def test_output_never_contains_matched_bytes_or_line_text(self):
        text = ("class E {\n  String " + kw_token + ' = "' + fake_value + '";\n'
                "  // neighbour " + fake_value + "-comment\n}\n")
        result = self.explain(text)
        rendered = json.dumps(result)
        self.assertNotIn(fake_value, rendered)
        self.assertNotIn("neighbour", rendered)
        self.assertNotIn("synthetic-value", rendered)
        for hit in result["hits"]:
            self.assertEqual({"line", "identifier", "rhsKind", "blocking"},
                             set(hit.keys()))

    def test_verdict_parity_with_scanner(self):
        fixtures = [
            "class E { int count = 1; }\n",
            "class E { String " + kw_token + ' = "' + fake_value + '"; }\n',
            "class E { void f() { " + kw_token + " = () -> source.get(); } }\n",
        ]
        for text in fixtures:
            with self.subTest(length=len(text)):
                self.assertEqual(scanner_verdict(text, java_path),
                                 self.explain(text)["verdict"])

    def test_cli_json_shape_and_exit_codes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root.joinpath(*java_path.split("/"))
            target.parent.mkdir(parents=True)
            target.write_text("class E { String " + kw_token + ' = "' + fake_value + '"; }\n')
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = EX.main(["--root", str(root), "--path", java_path, "--json"])
            result = json.loads(out.getvalue())
            self.assertEqual(6, code)
            self.assertEqual("hold", result["verdict"])
            self.assertNotIn(fake_value, out.getvalue())
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = EX.main(["--root", str(root), "--path", "main/java/Missing.java"])
            self.assertEqual(2, code)
            self.assertIn("error", out.getvalue())

    def test_real_orchestrator_file_verdict_matches_scanner(self):
        if not (repo_root / real_path).exists():
            self.skipTest("checkout file absent")
        data = (repo_root / real_path).read_bytes()
        expected = "pass"
        try:
            CP.secret_free(data, real_path)
        except CP.CheckpointError:
            expected = "hold"
        result = EX.explain_file(repo_root, real_path)
        self.assertEqual(expected, result["verdict"])
        if expected == "hold":
            self.assertTrue(result["hits"])
        rendered = json.dumps(result)
        for hit in result["hits"]:
            self.assertEqual({"line", "identifier", "rhsKind", "blocking"},
                             set(hit.keys()))
        self.assertNotIn("userAbsenceGate", rendered)


if __name__ == "__main__":
    unittest.main()
