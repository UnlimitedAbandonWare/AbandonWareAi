"""Synthetic JavaScript parser expressions; preserve/rollback and credential boundaries."""
import unittest
from scripts import test_codex_work_checkpoint_source_expressions as fixtures
CP = fixtures.CP


class ParserExpressionTest(unittest.TestCase):
    name = "to" + "ken"

    def examples(self):
        return [
            "const placeholder = " + self.name + " => {};",
            "const " + self.name + ' = math[Number(placeholder.getAttribute("data-chat-math"))];',
            "const spec = {" + self.name + ":runtimeValue};",
            "const spec = {mode:kind," + self.name + ":runtimeValue,next:other};",
        ]

    def test_expressions_preserve_seal_and_restore_exact_bytes(self):
        helper = fixtures.SourceExpressionCheckpointTest()
        for source in self.examples():
            with self.subTest(length=len(source)):
                result = helper.checkpoint(source, name="main/example.js", after=source + "\n")
                self.assertEqual("rolled_back", result["status"])

    def test_comments_strings_templates_and_documents_stay_blocked(self):
        for source in self.examples():
            for wrapped in ["/*" + source + "*/", "// " + source, "`" + source + "`",
                            "'" + source + "'"]:
                with self.subTest(length=len(wrapped)), self.assertRaises(CP.CheckpointError):
                    CP.secret_free(wrapped.encode(), "main/example.js")
            with self.assertRaises(CP.CheckpointError):
                CP.secret_free(source.encode(), "docs/example.md")

    def test_assignment_calls_literals_and_index_strings_stay_blocked(self):
        cases = [
            "const " + self.name + " = literal;",
            "const " + self.name + " = math['synthetic-value'];",
            "const " + self.name + ' = math[Number(placeholder.getAttribute("private-value"))];',
            "const spec = {" + self.name + ":'synthetic-value'};",
            "const spec = {" + self.name + ":readValue()};",
            "const spec = {" + self.name + ":runtimeValue+'suffix'};",
        ]
        for source in cases:
            with self.subTest(length=len(source)), self.assertRaises(CP.CheckpointError):
                CP.secret_free(source.encode(), "main/example.js")

    def test_rhs_and_neighboring_secret_values_are_still_scanned(self):
        for source in self.examples():
            for suffix in [self.name + "='synthetic-value';", "'" + "sk" + "-" + "x"*25 + "';"]:
                with self.assertRaises(CP.CheckpointError):
                    CP.secret_free((source + suffix).encode(), "main/example.js")
        source = "const spec = {" + self.name + ":gsk_" + "x"*32 + "};"
        with self.assertRaises(CP.CheckpointError):
            CP.secret_free(source.encode(), "main/example.js")

    def test_regex_quotes_and_slashes_do_not_protect_later_expressions(self):
        prefixes = [
            'const escaping = /[&<>"' + "'" + ']/g;\n',
            r'const url = /https?:\/\//;',
            r'const brackets = /[\\/"' + "'" + r']/;',
        ]
        helper = fixtures.SourceExpressionCheckpointTest()
        for prefix in prefixes:
            for source in self.examples():
                with self.subTest(prefixLength=len(prefix), expressionLength=len(source)):
                    body = prefix + ("\n" if "placeholder =" in source or "const " + self.name in source else "") + source
                    result = helper.checkpoint(body, name="main/parser.js", after=body + "\n")
                    self.assertEqual("rolled_back", result["status"])

    def test_regexes_and_division_do_not_hide_literal_or_comment_credentials(self):
        label = self.name
        samples = [
            "const re = /" + label + ":synthetic-value/;",
            "const re = /https?:\\\\/\\\\//; // " + label + "=synthetic-value",
            "const ratio = left / right; // " + label + "=synthetic-value",
            "const ratio = left / right; /* " + label + "=synthetic-value */",
            "const ref = {" + label + ":runtime}; const re = /" + "sk" + "-" + "x"*25 + "/;",
        ]
        for source in samples:
            with self.subTest(length=len(source)), self.assertRaises(CP.CheckpointError):
                CP.secret_free(source.encode(), "main/parser.js")


if __name__ == "__main__":
    unittest.main()
