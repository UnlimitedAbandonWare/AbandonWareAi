"""Only the fixed local-provider dummy comment is a noncredential example."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class PropertyCommentCheckpointTest(unittest.TestCase):
    path = "main/resources/application.properties"
    example = "#   api-" + "key: dummy    # [PATCH]"

    def test_exact_example_can_be_preserved(self):
        for newline in ("\n", "\r\n"):
            CP.secret_free((self.example + newline + "ordinary.setting=false" + newline).encode(), self.path)

    def test_empty_setting_does_not_consume_next_comment(self):
        text = "vector.admin." + "token" + "=\n\n# another section\n"
        CP.secret_free(text.encode(), self.path)

    def test_empty_setting_does_not_hide_following_secret(self):
        text = "vector.admin." + "token" + "=\nAuthorization: synthetic-header"
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(text.encode(), self.path)

    def test_changed_values_and_appended_material_stay_blocked(self):
        for text in (self.example.replace("dummy", "synthetic-credential"),
                     self.example + " unexpected",
                     self.example + "\nAuthorization: synthetic-header"):
            with self.subTest(textLength=len(text)):
                with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                    CP.secret_free(text.encode(), self.path)

    def test_other_source_paths_and_active_settings_stay_blocked(self):
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(self.example.encode(), "main/resources/other.properties")
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(self.example[2:].encode(), self.path)


if __name__ == "__main__":
    unittest.main()
