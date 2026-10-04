"""Fixed synthetic git_ship_easy fixture stays scoped; other literals still fail."""
import unittest
from scripts.test_codex_work_checkpoint import CP

SOURCE = "scripts/test_git_ship_easy.py"
FIXTURE = 'secret = "sk-' + 'Qm7vX2pL9wK4tR8zN5bH3jF6"'


class GitShipEasyFixtureTest(unittest.TestCase):
    def test_exact_synthetic_fixture(self):
        CP.secret_free(FIXTURE.encode(), SOURCE)

    def test_same_expression_in_other_source_is_rejected(self):
        with self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
            CP.secret_free(FIXTURE.encode(), "scripts/other_tool.py")

    def test_changed_expression_is_rejected(self):
        changed = FIXTURE.replace("Qm7vX2", "DIFFERENTVALUE99")
        with self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
            CP.secret_free(changed.encode(), SOURCE)

    def test_adjacent_credential_is_rejected(self):
        extra = ' to' + 'ken = "sk-' + 'REALVALUE12345678901234"'
        with self.assertRaisesRegex(CP.CheckpointError, 'secret-pattern'):
            CP.secret_free((FIXTURE + "\n" + extra).encode(), SOURCE)


if __name__ == '__main__':
    unittest.main()
