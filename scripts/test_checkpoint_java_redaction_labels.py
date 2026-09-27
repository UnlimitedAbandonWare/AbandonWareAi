"""Name-only Java redaction predicates contain no credential values."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class RedactionLabelCheckpointTest(unittest.TestCase):
    def test_name_only_predicates_and_adversarial_neighbours(self):
        names = ("to" + "ken", "api" + "_key", "api" + "key",
                 "pass" + "word")
        for name in names:
            expression = 'lower.contains("' + name + '=")'
            code = 'class E { boolean f(String lower) { return ' + expression + '; } }'
            with self.subTest(name=name):
                CP.secret_free(code.encode(), "main/java/E.java")
                for bad in (code.replace('="', '=synthetic-value"'),
                            '// ' + code, '/* ' + code + ' */',
                            '"""' + code + '"""',
                            code + '\nAuthorization: synthetic header',
                            r'\u0022' + code):
                    with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                        CP.secret_free(bad.encode(), "main/java/E.java")
                with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                    CP.secret_free(code.encode(), "docs/example.md")


if __name__ == "__main__":
    unittest.main()
