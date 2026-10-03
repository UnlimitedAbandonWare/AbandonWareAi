import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class AppBuildSourceSetContractTest(unittest.TestCase):
    @staticmethod
    def _build_script() -> str:
        return (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")

    def test_app_main_and_test_sourcesets_are_empty_after_java_clean_purge(self):
        build = self._build_script()

        self.assertIn("val main by getting", build)
        self.assertIn("val test by getting", build)
        self.assertEqual(2, build.count("java.setSrcDirs(emptyList<String>())"))
        self.assertEqual(2, build.count("resources.setSrcDirs(emptyList<String>())"))
        self.assertNotIn('setSrcDirs(listOf("src/main/', build)

    def test_duplicate_fqcn_exclusion_machinery_is_retired(self):
        build = self._build_script()

        for token in (
            "generateDupFqcnExcludes",
            "appJarDuplicateFqcnExcludes",
            "dupFqcnEvidenceFile",
            "dup-fqcn-evidence.json",
            "packagingState",
        ):
            self.assertNotIn(token, build)


if __name__ == "__main__":
    unittest.main()
