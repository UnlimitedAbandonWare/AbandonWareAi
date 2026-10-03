"""Exact synthetic Java redaction fixtures; neighbouring credentials remain blocked."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class FallbackRedactionFixtureTest(unittest.TestCase):
    def test_exact_stream_signal_redaction_fixtures_are_scannable(self):
        path = "src/test/java/com/example/lms/api/ChatStreamSignalBuilderTest.java"
        fixtures = [
            '"Bea' + 'rer private-secret PRIVATE_PROMPT"',
            '"https://private.invalid/?api_' + 'key=PRIVATE_KEY"',
            '"Author' + 'ization=secret-token"',
            '"api_' + 'key=secret-value"',
            '"Author' + 'ization=private-token"',
            '"Author' + 'ization=secret-not-a-number"',
        ]
        for fixture in fixtures:
            with self.subTest(length=len(fixture)):
                CP.secret_free(fixture.encode(), path)
        from pathlib import Path
        CP.secret_free(Path(path).read_bytes(), path)

    def test_stream_signal_variants_and_neighbouring_credentials_stay_blocked(self):
        path = "src/test/java/com/example/lms/api/ChatStreamSignalBuilderTest.java"
        fixture = '"Author' + 'ization=secret-token"'
        for text, other_path in (
                (fixture, "main/java/Example.java"),
                (fixture, "src/test/java/OtherTest.java"),
                (fixture.replace("secret-token", "changed-value"), path),
                (fixture + '; "api_' + 'key=REALVALUE123456789"', path)):
            with self.subTest(path=other_path, length=len(text)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(text.encode(), other_path)

    def test_redteam_dynamic_header_mask_assertion_is_scannable(self):
        path = "src/test/java/ai/abandonware/nova/orch/llm/ChatGptOAuthRedTeamContractTest.java"
        expression = 'PromptMasker.mask("Author' + 'ization: " + bearer)'
        CP.secret_free(expression.encode(), path)
        for text, other_path in (
                (expression, "main/java/Example.java"),
                (expression, "src/test/java/OtherTest.java"),
                (expression.replace("PromptMasker.mask", "otherCall"), path),
                (expression.replace("bearer", '"private-value"'), path),
                (expression + "\n" + "Author" + "ization=private-value", path)):
            with self.subTest(path=other_path, length=len(text)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(text.encode(), other_path)

    def test_exact_loopback_inputs_remain_scannable(self):
        path = "src/test/java/com/example/lms/llm/gateway/FallbackAwareChatModelTest.java"
        label = "to" + "ken"
        fixture = 'String url = "http://user:' + 'pass' + 'word@127.0.0.1:11435/v1?' + label + '=secret";'
        CP.secret_free(fixture.encode(), path)
        CP.secret_free(('"' + label + '=secret"').encode(), path)
        for text, other_path in (
                (fixture, "main/java/Example.java"),
                (fixture, "src/test/java/OtherTest.java"),
                (fixture.replace("11435", "19101"), path),
                (fixture.replace("=secret", "=other-value"), path),
                (fixture + "\n" + "Author" + "ization=private-value", path)):
            with self.subTest(path=other_path, length=len(text)), self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(text.encode(), other_path)
