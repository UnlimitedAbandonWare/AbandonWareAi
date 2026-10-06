"""Synthetic source fixtures; no real credentials or checkout writes."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest

from scripts.test_codex_work_checkpoint import CP, decision


class SourceExpressionCheckpointTest(unittest.TestCase):
    setting = "api" + "Key"

    def test_java_empty_query_label_can_precede_more_runtime_concatenation(self):
        label = "api" + "_key="
        body = ('String body = "' + label + '" + value + " Author' + 'ization: "'
                + ' + "Bea' + 'rer " + "raw-owner-to' + 'ken-123456";')
        CP.secret_free(body.encode(), "src/test/java/Example.java")
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(('String body = "' + label + '" + value + "' + 'x' * 32 + '";').encode(),
                           "src/test/java/Example.java")
        for extra in (' "' + 'sk-' + 'x' * 24 + '";',
                      '\n' + self.setting + '="opaque-value";'):
            with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free((body + extra).encode(), "src/test/java/Example.java")

    def test_split_public_header_marker_keeps_opaque_credentials_strict(self):
        header = "Author" + "ization"
        prefix = 'String body = "' + header + ': " + "Bea' + 'rer " + "'
        body = prefix + 'raw-owner-to' + 'ken-123456";'
        CP.secret_free(body.encode(), "src/test/java/Example.java")
        for changed in (prefix + 'x' * 32 + '";',
                        body.replace("123456", "123457"),
                        body.replace("123456", "123456-extra"),
                        body.replace('123456";', '123456" + "' + 'x' * 32 + '";'),
                        'String body = (' + body[len('String body = '):-1] + ') + "' + 'x' * 32 + '";',
                        'String body = ((' + body[len('String body = '):-1] + ')) + "' + 'x' * 32 + '";',
                        body + '\n"' + 'sk-' + 'x' * 24 + '";',
                        "// " + body, "/* " + body + " */",
                        '"""\n' + body + '\n"""', '\\u0022' + body):
            with self.subTest(length=len(changed)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(changed.encode(), "src/test/java/Example.java")
        for path in ("main/java/Example.java", "docs/example.md"):
            with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(body.encode(), path)

    def test_existing_embedding_redaction_fixture_passes_without_path_bypass(self):
        path = "src/test/java/com/example/lms/service/embedding/OllamaEmbeddingModelTest.java"
        source = Path(path).read_bytes()
        CP.secret_free(source, path)
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(source + ('\n' + self.setting + '="opaque-value";').encode(), path)

    def test_display_continuity_fixture_is_bounded_to_its_exact_synthetic_rhs(self):
        path = "src/test/js/display-continuity.test.cjs"
        name = "to" + "ken"
        for rhs in ("'b'.repeat(64)", "(++issued===1?'a':'b').repeat(64)"):
            fixture = "const fixture={" + name + ":" + rhs + ",expiresAt:1};"
            CP.secret_free(fixture.encode(), path)
            for altered in (fixture.replace("(64)", "(63)"), fixture.replace("'b'", "'actual-value'"),
                            "// " + fixture, "/* " + fixture + " */", '"' + fixture + '"',
                            fixture + "\n" + name + ":'actual-value'"):
                with self.subTest(rhs=rhs), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                    CP.secret_free(altered.encode(), path)
            with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(fixture.encode(), "main/resources/static/example.js")

    def test_fixed_synthetic_browser_fixture_is_checkpointed_and_restored(self):
        name = "to" + "ken"
        before = "const fixture={" + name + ":'a'.repeat(64),expiresAt:1};"
        result = self.checkpoint(before, "src/test/js/example.cjs", before.replace("expiresAt:1", "expiresAt:2"))
        self.assertEqual("rolled_back", result["status"])

    def test_synthetic_fixture_exception_keeps_literals_mimics_and_production_strict(self):
        name = "to" + "ken"
        fixture = "const fixture={" + name + ":'a'.repeat(64),expiresAt:1};"
        samples = [fixture.replace("'a'", "'b'"), fixture.replace("(64)", "(63)"),
                   fixture.replace("'a'.repeat(64)", "'synthetic-value'"),
                   "// " + fixture, "/* " + fixture + " */", '"' + fixture + '"',
                   fixture + "\nAuthorization: synthetic header"]
        for value in samples:
            with self.subTest(length=len(value)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(value.encode(), "src/test/js/example.cjs")
        for path in ("main/resources/static/example.js", "docs/example.md", "scripts/example.cjs"):
            with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(fixture.encode(), path)

    def statement(self, value="resolver.valueOrNull();"):
        return self.setting + " = " + value

    def checkpoint(self, text, name="main/java/Example.java", after=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / name
            source.parent.mkdir(parents=True)
            before = text.encode()
            source.write_bytes(before)
            lease_name = "__patch_drop__/source-edit-locks/toy.lock/lease.json"
            lease = root / lease_name
            lease.parent.mkdir(parents=True)
            lease.write_text(json.dumps(dict(
                root=str(root), ownerId="synthetic-owner", mutationAllowed=True,
                coordinationMode="target-scoped", targetPaths=[name],
                expiresAtUtc=(datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat())))
            run = "data/agent-handoff/codex-autonomy/source-expression-test"
            result = CP.begin(root, run, [name], decision(), lease_name)
            if after is not None:
                source.write_bytes(after.encode())
                CP.seal(root, run)
                result = CP.finish(root, run, 1, "synthetic-rollback-proof")
                self.assertEqual(before, source.read_bytes())
            return result

    def test_java_method_assignment_can_be_checkpointed_sealed_and_restored(self):
        before = "class Example { void f() { " + self.statement() + " } }\n"
        after = before.replace("void f()", "void g()")
        try:
            result = self.checkpoint(before, after=after)
        except CP.CheckpointError as error:
            self.fail("nonliteral Java assignment was rejected: " + str(error))
        self.assertEqual("rolled_back", result["status"])
        self.assertEqual(1, result["verificationExitCode"])

    def test_other_file_types_and_missing_language_context_remain_strict(self):
        for name in ("docs/notes.md", "scripts/example.py", "config/example.txt"):
            with self.subTest(name=name), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                self.checkpoint(self.statement(), name=name)
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(self.statement().encode())

    def test_literal_arguments_and_compound_expressions_remain_blocked(self):
        for value in ('"synthetic-value";', 'synthetic_value;', 'resolver.read("synthetic");',
                      'resolver.read()+suffix;', 'read();',
                      'resolver.read();suffix', 'resolver.read('):
            with self.subTest(value=value), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                self.checkpoint(self.statement(value))

    def test_string_comment_and_text_block_mimics_remain_blocked(self):
        expression = self.statement()
        for text in ('"' + expression + ' "', "'" + expression + " '",
                     '// ' + expression, '/*\n' + expression + '\n*/',
                     '/* unterminated\n' + expression,
                     '"""\n' + expression + '\n"""',
                     '"unterminated\n' + expression):
            with self.subTest(kind=text[:3]), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                self.checkpoint(text)

    def test_other_secret_material_on_same_or_following_line_remains_blocked(self):
        for suffix in (' ' + self.statement('"synthetic-value";'),
                       '\n' + self.statement('"synthetic-value";'),
                       '\nAuthorization: synthetic header', '\nCookie: synthetic value',
                       '\n' + 'sk' + '-' + 'x' * 24,
                       '\n-----BEGIN ' + 'PRIVATE KEY-----'):
            with self.subTest(length=len(suffix)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                self.checkpoint(self.statement() + suffix)

    def test_method_receiver_is_still_scanned_for_prefixed_material(self):
        value = 'gsk' + '_' + 'x' * 32 + '.read();'
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            self.checkpoint(self.statement(value))

    def test_unicode_escape_context_is_not_exempted(self):
        for marker in ('\\u0022', '\\uu0022', '\\uuuu0022'):
            with self.subTest(marker=marker), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                self.checkpoint(marker + ' ' + self.statement() + ' ' + marker)

    def test_postimage_secret_is_rejected_before_diff_is_written(self):
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            self.checkpoint('class Example {}', after=self.statement('"synthetic-value";'))

    counter = "to" + "ken"

    def lexical_statements(self):
        name = self.counter
        return [
            'String ' + name + ' = normalizeAnchorToken(raw);',
            'String ' + name + r' = raw.replaceAll("[^\\p{IsHangul}\\p{L}\\p{Nd}_-]+", "").strip();',
            '? ' + name + ' : ' + name + '.toLowerCase(Locale.ROOT);',
            'String ' + name + ' = normalizeUserQueryKeyword(matcher.group());',
            'Pattern ' + name.upper() + r' = Pattern.compile("[\\p{L}\\p{Nd}]{2,}");',
        ]

    def test_lexical_normalizers_preserve_checkpoint_and_restore(self):
        before = 'class E {void f(){' + '\n'.join(self.lexical_statements()) + '}}'
        result = self.checkpoint(before, after=before.replace('void f()', 'void g()'))
        self.assertEqual('rolled_back', result['status'])

    def test_lexical_normalizers_remain_strict_for_literals_and_mimics(self):
        name = self.counter
        samples = [
            'String ' + name + ' = normalizeAnchorToken("synthetic-value");',
            'String ' + name + ' = anotherFunction(raw);',
            'String ' + name + ' = normalizeUserQueryKeyword("synthetic-value");',
            'String ' + name + ' = normalizeUserQueryKeyword(matcher.group("synthetic-value"));',
            'String ' + name + ' = anotherFunction(matcher.group());',
            'String ' + name + ' = normalizeUserQueryKeyword(gsk_' + 'x' * 32 + '.group());',
            '"' + 'String ' + name + ' = normalizeUserQueryKeyword(matcher.group());' + '"',
            'String ' + name + ' = raw.replaceAll("synthetic-value", "").strip();',
            self.lexical_statements()[1].replace(', ""', ', "synthetic-value"'),
            '? ' + name + ' : "synthetic-value";',
            'Pattern ' + name.upper() + ' = Pattern.compile("synthetic-value");',
            'Pattern ' + name.upper() + ' = anotherCall' + self.lexical_statements()[4][len('Pattern ' + name.upper() + ' = Pattern.compile'):],
            'String ' + name + r' = Pattern.compile("[\\p{L}\\p{Nd}]{2,}");',
        ]
        for statement in self.lexical_statements():
            samples += ['// ' + statement, '/*\n' + statement + '\n*/',
                        '"""\n' + statement + '\n"""', '/* unterminated\n' + statement,
                        '\\u0022 ' + statement, statement + '\n' + name + '="synthetic";']
        for statement in samples:
            with self.subTest(length=len(statement)), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(statement.encode(), 'main/java/E.java')

    def test_lexical_normalizers_do_not_exempt_other_languages(self):
        # Java-specific exemptions never leak: every statement stays strict on
        # non-Java paths. A fragment that reads as a plain Python call
        # assignment (the replaceAll line truncates at `", `) clears only under
        # .py — via the generic Python call-RHS rule, not a leaked Java rule.
        for path in ('scripts/e.py', 'docs/e.md', ''):
            for statement in self.lexical_statements():
                if path == 'scripts/e.py' and 'replaceAll' in statement:
                    with self.subTest(path=path):
                        CP.secret_free(statement.encode(), path)
                    continue
                with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                    CP.secret_free(statement.encode(), path)

    def test_anti_leak_fixture_sentinel_scoped_to_test_sources(self):
        marker = "Authorization" + "=private-token should not surface"
        body = 'class T { String rawSecret = "' + marker + '"; }'
        CP.secret_free(body.encode(), "src/chatUiTest/java/com/example/T.java")
        CP.secret_free(body.encode(), "src/test/java/com/example/T.java")
        for path in ("main/java/com/example/T.java", "docs/e.md", ""):
            with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(body.encode(), path)
        for evil in ('"Authorization' + '=real-credential-value";',
                     '"Authorization' + '=private-token" + suffix;',
                     '"Authorization' + '=private-token should not surface-ish";'):
            with self.subTest(value=evil), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(('class T { String s = ' + evil + ' }').encode(),
                               "src/chatUiTest/java/com/example/T.java")

    def test_synthetic_bearer_fixture_scoped_to_test_sources(self):
        fixture = "Bearer " + "synthetic-fixture-secret"
        body = 'class T { String expected = "' + fixture + '"; }'
        CP.secret_free(body.encode(), "src/test/java/com/example/T.java")
        CP.secret_free(body.encode(), "src/chatUiTest/java/com/example/T.java")
        for path in ("main/java/com/example/T.java", "docs/e.md", ""):
            with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(body.encode(), path)
        for evil in ('"Bearer ' + 'synthetic-fixture-secret-ish";',
                     '"Bearer ' + 'real-token-value-12345";',
                     '"' + fixture + '"; String real = "Bearer ' + 'real-token-value-12345";'):
            with self.subTest(value=evil), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(('class T { String s = ' + evil + ' }').encode(),
                               "src/test/java/com/example/T.java")

    def test_public_locator_invalid_query_fixture_stays_exact_and_test_scoped(self):
        path = "src/test/java/com/example/lms/service/PublicEvidenceLocatorIdentityTest.java"
        fixture = '"https://example.org/profile?id=alpha&to' + 'ken=synthetic"'
        opening = 'for(String url:List.of("https://user@example.org/profile?id=alpha",'
        body = 'class T { void test() {\n' + opening + '\n' + fixture + ',\n"safe")) {} }}'
        CP.secret_free(body.encode(), path)
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(body.replace(opening + '\n', opening + '\nprefix +\n').encode(), path)
        for other in ("main/java/com/example/T.java", "src/test/java/com/example/T.java", "docs/e.md"):
            with self.subTest(path=other), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(body.encode(), other)
        for changed in (fixture + ' + "-ish"', fixture + ' + credentialSuffix',
                        'prefix + ' + fixture,
                        fixture.replace("synthetic", "synthetic-ish"),
                        fixture.replace("synthetic", "opaque-credential-value"),
                        fixture + '; String real = "api' + 'Key=opaque-value"',
                        fixture.replace('synthetic"', 'synthetic&extra=value"')):
            with self.subTest(length=len(changed)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(('class T { String s = ' + changed + '; }').encode(), path)

    def zero_loop(self):
        name = self.counter
        return 'for(int ' + name + '=0;' + name + '<n;' + name + '++){}'

    def test_zero_loop_checkpoint_seal_failure_and_restore(self):
        before = 'class Example {void f(){' + self.zero_loop() + '}}'
        result = self.checkpoint(before, after=before.replace('void f()', 'void g()'))
        self.assertEqual('rolled_back', result['status'])
        self.assertEqual(1, result['verificationExitCode'])

    def test_zero_loop_spacing_variants(self):
        name = self.counter
        for expression in (self.zero_loop(), 'for ( int ' + name + ' = 0 ; ' + name + '<n; ' + name + '++){}',
                           'for\n(\nint ' + name + '\n=\n0\n;' + name + '<n;' + name + '++){}'):
            with self.subTest(length=len(expression)):
                CP.secret_free(('class E {void f(){' + expression + '}}').encode(), 'main/java/E.java')

    def test_nonzero_nonint_and_nonloop_assignments_remain_blocked(self):
        loop = self.zero_loop()
        samples = [loop.replace('=0;', '=' + value + ';') for value in ('1', '00', '0L', '0x0', '+0', '"synthetic"', 'read()')]
        samples += [loop.replace('int ', value + ' ') for value in ('long', 'String', 'var')]
        samples += ['int ' + self.counter + '=0;', 'while(' + self.counter + '=0){}', loop.replace(self.counter, self.setting)]
        for expression in samples:
            with self.subTest(length=len(expression)), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(('class E {void f(){' + expression + '}}').encode(), 'main/java/E.java')

    def test_zero_loop_mimics_remain_blocked(self):
        loop = self.zero_loop()
        for expression in ('"' + loop + '"', '// ' + loop, '/* ' + loop + ' */',
                           '"""\n' + loop + '\n"""', '\\u0022' + loop, '/* unterminated ' + loop):
            with self.subTest(length=len(expression)), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(expression.encode(), 'main/java/E.java')

    def test_zero_loop_keeps_following_secrets_and_other_languages_blocked(self):
        loop = self.zero_loop()
        for expression in (loop + ' ' + self.counter + '="synthetic";', loop + '\nAuthorization: synthetic header'):
            with self.subTest(length=len(expression)), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(expression.encode(), 'main/java/E.java')
        for path in ('docs/note.md', 'scripts/example.py', ''):
            with self.subTest(path=path), self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
                CP.secret_free(loop.encode(), path)


if __name__ == "__main__":
    unittest.main()
