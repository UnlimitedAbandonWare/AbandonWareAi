"""Keep fixed synthetic log-header fixtures distinct from credential literals."""
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("checkpoint", ROOT / "scripts/codex_work_checkpoint.py")
CP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CP)
FIXTURE_PATH = "tools/test_build_error_secret_mask.py"
FIXTURES = (
    '("header", "Author' + 'ization: " + "D" * 24, "<secret>"),',
    '("cookie", "Coo' + 'kie: session=" + "E" * 24, "<secret>"),',
    '("multiline header", "Coo' + 'kie: value\\nBUILD SUCCESSFUL", "<secret>\\nBUILD SUCCESSFUL"),',
)


class SyntheticMaskFixtureTest(unittest.TestCase):
    def test_exact_synthetic_fixtures_scan(self):
        for fixture in FIXTURES:
            with self.subTest(fixture=fixture.split(",", 1)[0]):
                CP.secret_free(fixture.encode(), FIXTURE_PATH)

    def test_changed_fixture_values_remain_blocked(self):
        for fixture in FIXTURES:
            changed = fixture.replace('"D"', '"F"').replace('"E"', '"F"').replace("value", "opaque")
            with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(changed.encode(), FIXTURE_PATH)

    def test_other_paths_remain_blocked(self):
        for path in ("tools/other_test.py", "tools/build_error_miner.py", "docs/example.md"):
            with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                CP.secret_free(FIXTURES[0].encode(), path)

    def test_adjacent_secret_shaped_value_remains_blocked(self):
        source = FIXTURES[0] + '\n"' + "sk-" + "Z" * 24 + '"'
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(source.encode(), FIXTURE_PATH)

    def test_current_redaction_tests_scan(self):
        CP.secret_free((ROOT / FIXTURE_PATH).read_bytes(), FIXTURE_PATH)


if __name__ == "__main__":
    unittest.main()
