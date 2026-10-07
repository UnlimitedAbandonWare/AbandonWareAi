"""Synthetic source references are not secrets; literal credentials still are."""
import unittest
from scripts.codex_work_checkpoint import secret_free, CheckpointError

FIELD = "to" + "ken"

class JavascriptMemberReferenceTest(unittest.TestCase):
    def test_bounded_object_member_references_are_source_only(self):
        for path in ("main/resources/static/receiver.js", "src/test/js/client.cjs"):
            for body in ("JSON.stringify({" + FIELD + ":options." + FIELD + "});",
                         "post('lens/text',{" + FIELD + ":saved." + FIELD + "});"):
                secret_free(body.encode(), path)
                with self.assertRaises(CheckpointError):
                    secret_free(body.encode(), "docs/example.md")

    def test_literals_expressions_comments_strings_templates_stay_rejected(self):
        bodies = ["({" + FIELD + ":'synthetic-value'});",
                  "({" + FIELD + ":options." + FIELD + "+'suffix'});",
                  "// ({" + FIELD + ":options." + FIELD + "});",
                  '"({' + FIELD + ':options.' + FIELD + '});"',
                  "`({" + FIELD + ":options." + FIELD + "});`",
                  "({" + FIELD + ":readSecret()});"]
        for body in bodies:
            with self.subTest(body=body), self.assertRaises(CheckpointError):
                secret_free(body.encode(), "client.js")

    def test_real_prefixed_secret_is_not_hidden_by_reference(self):
        body="({"+FIELD+":options."+FIELD+"}); const value='"+"s"+"k-"+"a"*25+"';"
        with self.assertRaises(CheckpointError):
            secret_free(body.encode(), "client.js")

    def test_unrelated_unicode_string_and_backtick_comment_do_not_block_member(self):
        body="// A `view` property.\n const status='\\u2026'; JSON.stringify({"+FIELD+":options."+FIELD+"});"
        secret_free(body.encode(), "client.js")

    def test_reference_ends_at_object_boundary_before_next_statement(self):
        body="post('lens/text',{"+FIELD+":saved."+FIELD+"});return saved;"
        secret_free(body.encode(),"client.js")
        with self.assertRaises(CheckpointError):
            secret_free((body+" "+FIELD+"='synthetic-value';").encode(),"client.js")

    def test_arrow_function_declaration_is_source_only_but_body_stays_scanned(self):
        declaration = "const " + FIELD + " = (target, data) => context.renderChatEvent({type:'event', data}, target);"
        for path in ("client.js", "scripts/fixture.cjs", "client.mjs"):
            with self.subTest(path=path):
                secret_free(declaration.encode(), path)
        unsafe = [
            "// " + declaration,
            repr(declaration),
            "`" + declaration + "`",
            "const " + FIELD + " = (target = 'synthetic-value') => context.send(target);",
            "const " + FIELD + " = () => 'synthetic-value';",
            declaration + " const " + FIELD + " = 'synthetic-value';",
            declaration + " const value = '" + "s" + "k-" + "a" * 25 + "';",
            "const " + FIELD + " = (target, data) => context.send({" + FIELD + ":'synthetic-value'});",
        ]
        for body in unsafe:
            with self.subTest(body=body), self.assertRaises(CheckpointError):
                secret_free(body.encode(), "client.js")
        with self.assertRaises(CheckpointError):
            secret_free(declaration.encode(), "docs/example.md")

    def test_js_declaration_member_reference_keeps_literal_and_comment_denials(self):
        declaration = "const " + FIELD + " = context.__knownTokenRunToken;"
        secret_free(declaration.encode(), "fixture.js")
        for body in ("// " + declaration, repr(declaration), "`" + declaration + "`",
                     "const " + FIELD + " = 'synthetic-value';",
                     declaration.replace(";", "+'suffix';")):
            with self.subTest(body=body), self.assertRaises(CheckpointError):
                secret_free(body.encode(), "fixture.js")

    def test_existing_stream_redaction_sentinels_remain_exact_and_path_scoped(self):
        from pathlib import Path
        path = "scripts/chat_ui_stream_contract_tests.js"
        source = (Path(__file__).resolve().parents[1] / path).read_bytes()
        secret_free(source, path)
        for changed in (source.replace(b"'A'.repeat(24)", b"'D'.repeat(24)"),
                        source.replace(b"'C'.repeat(24)", b"'C'.repeat(25)"),
                        source + (" const " + FIELD + "='synthetic-value';").encode(),
                        source + (" const value='" + "s" + "k-" + "a"*25 + "';").encode()):
            with self.assertRaises(CheckpointError):
                secret_free(changed, path)
        with self.assertRaises(CheckpointError):
            secret_free(source, "scripts/other_stream_test.js")

    def test_csrf_optional_dom_read_is_not_a_literal_secret(self):
        declaration = "const " + FIELD + " = document.querySelector('meta[name=\"_csrf\"]')?.content;"
        secret_free(declaration.encode(), "client.js")
        for unsafe in ("// " + declaration, repr(declaration), "`" + declaration + "`",
                       declaration.replace(";", "+'synthetic-value';"),
                       declaration.replace("_csrf", "arbitrary-secret"),
                       declaration + " const " + FIELD + "='synthetic-value';"):
            with self.subTest(body=unsafe), self.assertRaises(CheckpointError):
                secret_free(unsafe.encode(), "client.js")
        with self.assertRaises(CheckpointError):
            secret_free(declaration.encode(), "docs/example.md")

    def test_conversation_export_fixture_is_exact_and_test_scoped(self):
        fixture = ('message(1,1,"user","안녕 sec' + 'ret="+"sensitive-fixture"+" Coo'
                   + 'kie: synthetic-cookie\\nownerKey=browser-alice\\nrunId=synthetic-run");')
        path = "src/chatUiTest/java/com/example/lms/api/ChatConversationExportContractTest.java"
        secret_free(fixture.encode(), path)
        for unsafe in (fixture.replace("synthetic-cookie", "altered-fixture"),
                       fixture + " const " + FIELD + "='synthetic-value';"):
            with self.assertRaises(CheckpointError):
                secret_free(unsafe.encode(), path)
        with self.assertRaises(CheckpointError):
            secret_free(fixture.encode(), "main/java/Export.java")

    def test_live_client_contains_only_source_reference(self):
        from pathlib import Path
        path=Path(__file__).resolve().parents[1]/"main/resources/static/assets/display/display-conversate.js"
        secret_free(path.read_bytes(),str(path))

    def test_live_reader_contains_only_source_reference(self):
        from pathlib import Path
        path=Path(__file__).resolve().parents[1]/"main/resources/static/assets/display/meta/receiver.js"
        secret_free(path.read_bytes(), str(path))

if __name__ == '__main__':
    unittest.main()
