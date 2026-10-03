"""Java Cookie method references must not look like credential headers."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class JavaCookieReferenceCheckpointTest(unittest.TestCase):
    reference = "Cookie" + "::getValue"

    def test_method_reference_is_source_not_a_cookie_header(self):
        CP.secret_free(("cookies.stream().map(" + self.reference + ");").encode(),
                       "main/java/E.java")

    def test_strings_comments_and_other_files_stay_strict(self):
        for value, path in (
            ('String value = "' + self.reference + '";', "main/java/E.java"),
            ("// " + self.reference, "main/java/E.java"),
            ("/* " + self.reference + " */", "main/java/E.java"),
            ("cookies.map(" + self.reference + ");", "main/resources/e.txt"),
            ("cookies.map(" + self.reference + '); String x = "\\u0022";', "main/java/E.java"),
        ):
            with self.subTest(path=path, length=len(value)):
                with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                    CP.secret_free(value.encode(), path)

    def test_other_cookie_values_and_adjacent_credentials_remain_blocked(self):
        for value in (
            "Cookie" + ": synthetic-session",
            "Cookie" + "::otherValue",
            "cookies.map(" + self.reference + "); Author" + "ization: synthetic-header",
        ):
            with self.subTest(length=len(value)):
                with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
                    CP.secret_free(value.encode(), "main/java/E.java")


if __name__ == "__main__":
    unittest.main()
