"""Inline CSRF reads have no stored credential; all literal values stay scanned."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class HtmlRuntimeExpressionCheckpointTest(unittest.TestCase):
    statement = "const " + "token" + " = tokenMeta ? String(tokenMeta.content || '') : '';"
    path = "main/resources/templates/debug-events.html"

    def test_inline_runtime_expression_is_allowed(self):
        CP.secret_free(("<script>\n" + self.statement + "\n</script>").encode(), self.path)

    def test_other_html_contexts_and_js_comments_stay_strict(self):
        for value in (
            self.statement,
            '<div title="' + self.statement + '"></div>',
            '<div title="<script>\n' + self.statement + '\n</script>"></div>',
            "<!-- <script>\n" + self.statement + "\n</script> -->",
            "<script>\n// " + self.statement + "\n</script>",
            "<script>\n/*\n" + self.statement + "\n*/</script>",
            '<script>\nconst x = "' + self.statement + '";\n</script>',
            "<script>\nconst x = " + chr(96) + "\n" + self.statement + "\n" + chr(96) + ";\n</script>",
        ):
            with self.subTest(length=len(value)):
                with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                    CP.secret_free(value.encode(), self.path)

    def test_literal_defaults_and_following_headers_stay_strict(self):
        for value in (
            self.statement.replace("''", "'synthetic-secret'", 1),
            self.statement.rsplit("''", 1)[0] + "'synthetic-secret';",
            self.statement + "\nAuthor" + "ization: synthetic-header",
        ):
            with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(("<script>\n" + value + "\n</script>").encode(), self.path)


if __name__ == "__main__":
    unittest.main()
