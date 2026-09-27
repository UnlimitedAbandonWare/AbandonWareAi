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
