"""Checkpoint scanner: typed Java declarations whose RHS is a lambda assign a
functional reference, so the keyword label is exempt; literal and untyped
forms stay blocked. Synthetic fixtures only; no real credentials."""
from pathlib import Path
import unittest

from scripts.test_codex_work_checkpoint import CP

kw_token = "to" + "ken"
kw_apikey = "api" + "Key"
kw_pwd = "pass" + "word"
fake_value = "zebra9Quartz7-synthetic-value"
sk_value = "sk" + "-" + "x" * 24
java_path = "main/java/Example.java"
real_path = "main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java"
repo_root = Path(__file__).resolve().parents[1]


def body(*lines):
    return "class E {\n  void f() {\n" + "\n".join("    " + line for line in lines) + "\n  }\n}\n"


class JavaLambdaDeclarationTest(unittest.TestCase):
    def assert_free(self, text, path=java_path):
        try:
            CP.secret_free(text.encode(), path)
        except CP.CheckpointError as error:
            self.fail("typed lambda declaration was rejected: " + str(error))

    def assert_blocked(self, text, path=java_path):
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(text.encode(), path)

    def test_empty_param_lambda_declaration_is_exempt(self):
        self.assert_free(body("PreemptionToken " + kw_token + " = () -> !gate.isUserAbsentNow();"))

    def test_generic_supplier_lambda_is_exempt(self):
        self.assert_free(body("Supplier<String> " + kw_apikey + " = () -> resolveKey();"))

    def test_single_param_lambda_is_exempt(self):
        self.assert_free(body("Predicate<X> " + kw_token + " = x -> x.ok();"))

    def test_multi_param_lambda_is_exempt(self):
        self.assert_free(body("BiPredicate<A,B> " + kw_token + " = (a, b) -> a.ok(b);"))

    def test_modifier_and_qualified_type_forms_are_exempt(self):
        self.assert_free(body(
            "final PreemptionToken " + kw_token + " = () -> !gate.check();",
            "private java.util.function.Supplier<String> " + kw_pwd + " = () -> resolve();"))

    def test_block_lambda_body_is_exempt(self):
        self.assert_free(body("Handler " + kw_token + " = () -> { work(); };"))

    def test_real_orchestrator_lambda_file_is_free(self):
        if not (repo_root / real_path).exists():
            self.skipTest("checkout file absent")
        CP.secret_free((repo_root / real_path).read_bytes(), real_path)

    def test_string_literal_assignment_stays_blocked(self):
        self.assert_blocked(body("String " + kw_token + ' = "' + fake_value + '";'))

    def test_prefixed_literal_concat_stays_blocked(self):
        self.assert_blocked(body("String " + kw_apikey + ' = "sk-" + "' + "x" * 24 + '";'))

    def test_lambda_returning_literal_stays_blocked(self):
        # The label exemption requires a non-literal body; the value scan still
        # sees every RHS byte, so a quoted body (prefixed or not) holds.
        self.assert_blocked(body("Supplier<String> " + kw_token + ' = () -> "' + sk_value + '";'))
        self.assert_blocked(body("Supplier<String> " + kw_token + ' = () -> "' + fake_value + '";'))

    def test_untyped_lambda_assignment_stays_blocked(self):
        self.assert_blocked(body(kw_token + " = () -> source.get();"))

    def test_private_key_block_stays_blocked(self):
        self.assert_blocked(body("PreemptionToken " + kw_token + " = () -> source.get();")
                            + "-----BEGIN " + "PRIVATE KEY-----")

    def test_properties_credential_stays_blocked(self):
        self.assert_blocked("api-" + "key=" + fake_value, "main/resources/example.properties")

    def test_comment_and_string_mimics_stay_blocked(self):
        decl = "Supplier<String> " + kw_token + " = () -> resolveKey();"
        for mimic in ("// " + decl, '"' + decl + '"', "/* " + decl + " */",
                      '"""\n' + decl + '\n"""'):
            with self.subTest(mimic=mimic[:3]):
                self.assert_blocked(mimic + "\n")

    def test_lambda_declaration_in_other_languages_stays_blocked(self):
        decl = "Supplier<String> " + kw_token + " = () -> resolveKey();"
        for path in ("docs/notes.md", "scripts/example.py", ""):
            with self.subTest(path=path):
                self.assert_blocked(decl, path)

    def test_neighbouring_literal_on_next_line_stays_blocked(self):
        self.assert_blocked(body(
            "Supplier<String> " + kw_token + " = () -> resolveKey();",
            "String " + kw_apikey + ' = "' + fake_value + '";'))

    def test_midline_declaration_after_brace_stays_blocked(self):
        # Exemptions are line-anchored like every other UI-expression pattern.
        self.assert_blocked("class E { void f() { Supplier<String> " + kw_token
                            + " = () -> resolveKey(); } }\n")


if __name__ == "__main__":
    unittest.main()
