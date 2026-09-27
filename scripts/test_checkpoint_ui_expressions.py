"""UI source-expression regression cases; synthetic values only."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class UiExpressionsTest(unittest.TestCase):
    label = 'to' + 'ken'

    def examples(self):
        return [
            ('main/a.js', 'const ' + self.label + ' = normalizeRunToken(runToken);'),
            ('main/a.js', 'const ' + self.label + ' = queryReason(reason, key);'),
            ('main/a.js', 'const ' + self.label + ' = String(raw ?? "").trim();'),
            ('main/a.js', 'return ok ? ' + self.label + ' : null;'),
            ('main/a.js', 'parsed.' + 'pass' + 'word = "";'),
            ('main/a.js', self.label + ': document.querySelector(\'meta[name="_csrf"]\')?.content || "",'),
            ('main/A.java', 'String ' + self.label + ' = csrf == null ? "" : stringValue(csrf.getToken());')
        ]

    def test_runtime_reads_and_empty_clearing_are_not_credentials(self):
        for path, code in self.examples():
            with self.subTest(path=path, codeLength=len(code)):
                CP.secret_free(code.encode(), path)

    def test_mimics_and_literals_stay_blocked(self):
        for path, code in self.examples():
            for wrapped in ['/*\n' + code + '\n*/', '`\n' + code + '\n`']:
                with self.subTest(path=path), self.assertRaises(CP.CheckpointError):
                    CP.secret_free(wrapped.encode(), path)
            with self.subTest(path=path), self.assertRaises(CP.CheckpointError):
                CP.secret_free((code + '\n' + self.label + '="synthetic-value";').encode(), path)
        for code in [self.label + '="synthetic-value";', 'const ' + self.label + ' = lookup("synthetic-value");',
                     'const ' + self.label + ' = literal;', 'parsed.' + 'pass' + 'word = "synthetic-value";']:
            with self.subTest(length=len(code)), self.assertRaises(CP.CheckpointError):
                CP.secret_free(code.encode(), 'main/a.js')

    def test_gateway_runtime_resolution_and_null_comparison(self):
        binding = '${llm.api-' + 'key:${LLM_API_KEY:ollama}}'
        CP.secret_free(binding.encode(), 'main/A.java')
        for value in ['synthetic-value', 'ollama-extra', 'sk' + '-' + 'x' * 25]:
            with self.assertRaises(CP.CheckpointError):
                CP.secret_free(binding.replace('ollama', value).encode(), 'main/A.java')
        key = 'api' + 'Key'
        for code in ['if (' + key + ' == null || flag) {}',
                     'String ' + key + ' = resolveOpenAiApiKey();',
                     'String ' + key + ' = resolveApiKeyForBaseUrl(baseUrl);']:
            CP.secret_free(code.encode(), 'main/A.java')
            for unsafe in ['/*' + code + '*/', '"' + code + '"',
                           code + key + '="synthetic-value";']:
                with self.assertRaises(CP.CheckpointError):
                    CP.secret_free(unsafe.encode(), 'main/A.java')

    def test_java_diagnostic_label_and_runtime_value_are_not_literal_credentials(self):
        code = 'assertFalse(ok, "Unexpected ' + self.label + ': " + ' + self.label + ');'
        CP.secret_free(code.encode(), 'main/A.java')
        for unsafe in ['/*' + code + '*/',
                       code + self.label + '="synthetic-value";',
                       '"Unexpected ' + self.label + ': synthetic-value"']:
            with self.assertRaises(CP.CheckpointError):
                CP.secret_free(unsafe.encode(), 'main/A.java')

    def test_inline_java_iteration_masks_only_the_counter(self):
        code = 'for (String ' + self.label + ' : new String[]{"word"}) {}'
        CP.secret_free(code.encode(), 'main/A.java')
        for unsafe in ['/*' + code + '*/',
                       code.replace('word', 'sk' + '-' + 'x' * 25)]:
            with self.assertRaises(CP.CheckpointError):
                CP.secret_free(unsafe.encode(), 'main/A.java')

    def test_untyped_documents_stay_strict_and_prefixed_values_still_detected(self):
        for path, code in self.examples():
            with self.assertRaises(CP.CheckpointError):
                CP.secret_free(code.encode(), 'docs/note.md')
            with self.assertRaises(CP.CheckpointError):
                CP.secret_free((code + '\n' + 'sk' + '-' + 'x' * 25).encode(), path)


if __name__ == '__main__':
    unittest.main()
