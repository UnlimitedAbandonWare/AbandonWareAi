import unittest
import difflib
from scripts.codex_work_checkpoint import secret_free, CheckpointError

class RegisteredRouteFixtureScanTest(unittest.TestCase):
    def test_owned_diff_header_suffix_cannot_hide_secret(self):
        artifact = 'data/agent-handoff/synthetic/EVIDENCE/owned-change.diff'
        header = '@@ -1 +1 @@ api' + 'Key="synthetic-value"\n'
        patch = '--- a/scripts/other.js\n+++ b/scripts/other.js\n' + header + '-const n=0;\n+const n=1;\n'
        with self.assertRaises(CheckpointError):
            secret_free(patch.encode(), artifact)

    def test_owned_diff_windows_text_transport_keeps_all_bytes_scanned(self):
        path = 'scripts/chat_rag_golden_browser.js'
        artifact = 'data/agent-handoff/synthetic/EVIDENCE/owned-change.diff'
        detector = '/Bea' + 'rer |sk-|' + 'token' + '=/i.test(item);\n'
        patch = ''.join(difflib.unified_diff([detector], [detector, 'const n=1;\n'],
                         fromfile='a/'+path, tofile='b/'+path)).replace('\n','\r\r\n')
        secret_free(patch.encode(), artifact)
        with self.assertRaises(CheckpointError):
            secret_free(patch.replace('const n=1','api'+'Key="synthetic-value"').encode(), artifact)

    def test_owned_diff_scans_both_sides_with_source_fixture_contract(self):
        path = 'scripts/chat_rag_golden_browser.js'
        expression = '/Bea' + 'rer |sk-|' + 'token' + '=/i.test(item)'
        before = [expression + ';\n']
        after = [expression + ';\n', 'const safeCount = 1;\n']
        artifact = 'data/agent-handoff/synthetic/EVIDENCE/owned-change.diff'
        patch = ''.join(difflib.unified_diff(before, after, fromfile='a/'+path, tofile='b/'+path))
        secret_free(patch.encode(), artifact)
        for bad in (
            patch.replace('safeCount = 1', 'api' + 'Key="synthetic-value"'),
            patch.replace(path, 'scripts/other.js'),
            patch.replace(path, '../scripts/chat_rag_golden_browser.js'),
            patch.replace('@@ -1 +1,2 @@', '@@ -1 +1,3 @@'),
            patch + 'api' + 'Key="synthetic-value";\n',
        ):
            with self.subTest(caseHash=__import__('hashlib').sha256(bad.encode()).hexdigest()[:12]):
                with self.assertRaises(CheckpointError):
                    secret_free(bad.encode(), artifact)

    def test_owned_diff_new_file_and_removed_secret_are_checked(self):
        artifact = 'data/agent-handoff/synthetic/EVIDENCE/owned-change.diff'
        path = 'scripts/chat_rag_golden_browser_tests.js'
        fixture = "'body unavailable Bea" + "rer synthetic-private-value'"
        patch = ''.join(difflib.unified_diff([], ['throw Error('+fixture+');\n'],
                         fromfile='/dev/null', tofile='b/'+path))
        secret_free(patch.encode(), artifact)
        secret_line = 'api' + 'Key="synthetic-value";\n'
        removed = ''.join(difflib.unified_diff([secret_line], ['const safeCount = 1;\n'],
                         fromfile='a/scripts/other.js', tofile='b/scripts/other.js'))
        for bad in (removed, patch.replace('synthetic-private-value', 'other-private-value')):
            with self.assertRaises(CheckpointError):
                secret_free(bad.encode(), artifact)

    def test_exact_browser_body_read_fixture_keeps_neighbours_strict(self):
        path = 'scripts/chat_rag_golden_browser_tests.js'
        fixture = "'body unavailable Bea" + "rer synthetic-private-value'"
        secret_free(('throw Error(' + fixture + ');').encode(), path)
        for source, target in (
            (fixture.replace('synthetic-private-value', 'other-private-value'), path),
            (fixture, 'scripts/other_tests.js'),
            (fixture + '\n' + 'api' + 'Key="synthetic-value";', path),
        ):
            with self.subTest(target=target), self.assertRaisesRegex(CheckpointError, 'secret-pattern'):
                secret_free(source.encode(), target)

    def test_exact_browser_detector_contains_no_credential(self):
        expression = '/Bea' + 'rer |sk-|' + 'token' + '=/i.test(item)'
        secret_free(expression.encode(), 'scripts/chat_rag_golden_browser.js')
        replacement = '/(?:sk-|' + 'token' + '=|cookie' + '=|authoriz' + 'ation=)\\S+/gi'
        secret_free(replacement.encode(), 'scripts/chat_rag_golden_browser.js')
        with self.assertRaisesRegex(CheckpointError, 'secret-pattern'):
            secret_free((replacement + '\n' + 'api' + 'Key="synthetic-value";').encode(), 'scripts/chat_rag_golden_browser.js')
        with self.assertRaisesRegex(CheckpointError, 'secret-pattern'):
            secret_free((expression + '\n' + 'api' + 'Key="synthetic-value";').encode(), 'scripts/chat_rag_golden_browser.js')

    def test_registered_provider_resolution_contains_no_literal_credential(self):
        name = 'api' + 'Key'
        expression = name + ' = resolveApiKeyForBaseUrl(baseUrl, cfg.getProvider());'
        secret_free(expression.encode(), 'main/java/Example.java')
        for source in ('// ' + expression, expression.replace('baseUrl,', '"literal",'),
                       expression + '\n' + name + '="synthetic-value";'):
            with self.assertRaisesRegex(CheckpointError, 'secret-pattern'):
                secret_free(source.encode(), 'main/java/Example.java')

    def test_exact_public_local_property_and_adversarial_neighbours(self):
        path = "src/test/java/com/example/lms/llm/DynamicChatModelFactoryRoutingTest.java"
        fixture = '"llm.api' + '-key=ollama"'
        secret_free(('runner.withPropertyValues(' + fixture + ');').encode(), path)
        for source, target in (
            (fixture.replace("ollama", "synthetic-value"), path),
            (fixture, "main/java/Example.java"),
            (fixture + '\n' + 'api' + 'Key="synthetic-value";', path),
        ):
            with self.subTest(target=target), self.assertRaisesRegex(CheckpointError, "secret-pattern"):
                secret_free(source.encode(), target)
