"""Synthetic Java-only false-positive coverage; no credential values or source writes."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class WorkflowExpressionsTest(unittest.TestCase):
    key = 'api' + 'Key'
    word = 'to' + 'ken'

    def scan(self, text, path='main/java/Example.java'):
        CP.secret_free(text.encode(), path)

    def conditional(self):
        return self.key + ' = local ? resolver.local() : resolver.remote();'

    def test_literal_free_conditional_comparison_and_iteration(self):
        self.scan(self.conditional())
        self.scan('if (' + self.key + ' == null || ' + self.key + '.isBlank()) {}')
        self.scan('for (String ' + self.word + ' : safe.toLowerCase().split("\\\\s+")) {}')
        self.scan('for (String ' + self.word + ' : words) {}')

    def test_non_ascii_unicode_does_not_disable_safe_expression_checks(self):
        for code in ('AC00', '201C', '00d7'):
            self.scan('String label="\\u' + code + '"; ' + self.conditional())

    def test_nested_placeholder_already_supported(self):
        self.scan('@Value("${openai.api.key:${OPENAI_API_KEY:}}")')

    def test_literal_rhs_unqualified_and_compound_calls_remain_strict(self):
        for rhs in ('local ? resolver.local() : "synthetic";',
                    'local ? local() : remote();', 'local ? resolver.local("synthetic") : resolver.remote();',
                    'local ? resolver.local() : resolver.remote()+suffix;', '"synthetic";'):
            with self.subTest(rhs=rhs), self.assertRaises(CP.CheckpointError):
                self.scan(self.key + ' = ' + rhs)

    def test_mimics_ambiguous_escapes_and_other_languages_remain_strict(self):
        for text in ('// ' + self.conditional(), '/* ' + self.conditional() + ' */',
                     '"' + self.conditional() + '"', '\\u0022 ' + self.conditional(),
                     '\\u2028 ' + self.conditional(), self.word + ' = value;'):
            with self.subTest(length=len(text)), self.assertRaises(CP.CheckpointError):
                self.scan(text)
        with self.assertRaises(CP.CheckpointError):
            self.scan(self.conditional(), 'docs/note.md')

    def test_remaining_rhs_and_following_literals_still_scanned(self):
        for suffix in (' ' + self.key + '="synthetic";', '\nAuthorization: synthetic header',
                       '\n' + 'sk' + '-' + 'x'*24):
            with self.subTest(length=len(suffix)), self.assertRaises(CP.CheckpointError):
                self.scan(self.conditional() + suffix)
        with self.assertRaises(CP.CheckpointError):
            self.scan(self.conditional().replace('resolver.remote()', 'gsk' + '_' + 'x'*32 + '.remote()'))


if __name__ == '__main__':
    unittest.main()
