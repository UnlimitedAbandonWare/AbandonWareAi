"""Synthetic Java query-name fixtures; never credentials or provider calls."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class JavaUrlLabelTest(unittest.TestCase):
    label = "api" + "_key"

    def expression(self, suffix='" + value;'):
        return 'class E { void f() { String url = "https://example.org/reference?' + self.label + '=' + suffix + ' } }'

    def test_java_query_name_with_runtime_value_has_no_literal_credential(self):
        try:
            CP.secret_free(self.expression().encode(), 'main/java/E.java')
        except CP.CheckpointError as error:
            self.fail('Java query-name label rejected: ' + str(error))

    def test_literal_value_and_concatenated_prefixed_value_remain_blocked(self):
        for text in (self.expression('synthetic-literal";'),
                     self.expression('" + "sk-' + 'A' * 24 + '";')):
            with self.subTest(size=len(text)), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(text.encode(), 'main/java/E.java')

    def test_raw_newlines_in_ordinary_java_string_remain_strict(self):
        for newline in ('\n', '\r', '\r\n', '\\\n'):
            source = self.expression().replace('/reference?', '/reference' + newline + '?')
            with self.subTest(newline=repr(newline)), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(source.encode(), 'main/java/E.java')

    def test_comments_text_blocks_other_languages_and_unicode_remain_strict(self):
        source = self.expression()
        for text in ('// ' + source, '/* ' + source + ' */', '"""\n' + source + '\n"""',
                     '\\u0022' + source):
            with self.subTest(size=len(text)), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(text.encode(), 'main/java/E.java')
        with self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
            CP.secret_free(source.encode(), 'docs/E.md')


if __name__ == '__main__':
    unittest.main()
