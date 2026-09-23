"""Synthetic source fixtures; no real credentials or checkout writes."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest

from scripts.test_codex_work_checkpoint import CP, decision


class SourceExpressionCheckpointTest(unittest.TestCase):
    setting = "api" + "Key"

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
