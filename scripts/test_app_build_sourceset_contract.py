import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class AppBuildSourceSetContractTest(unittest.TestCase):
    @staticmethod
    def _build_script() -> str:
        return (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")

    @staticmethod
    def _duplicate_owner_body(build: str) -> str:
        start_marker = "val generateDupFqcnExcludes by tasks.registering {"
        end_marker = "tasks.withType<Jar>().configureEach {"
        start = build.index(start_marker)
        end = build.index(end_marker, start)
        return build[start:end]

    def test_app_main_uses_java_clean_and_legacy_tests_are_quarantined(self):
        build = self._build_script()

        self.assertIn('java.setSrcDirs(listOf("src/main/java_clean"))', build)
        self.assertIn('resources.setSrcDirs(listOf("src/main/resources"))', build)
        self.assertIn("val test by getting", build)
        self.assertIn("java.setSrcDirs(emptyList<String>())", build)
        self.assertIn("resources.setSrcDirs(emptyList<String>())", build)

    def test_duplicate_fqcn_evidence_contract_is_owned_by_existing_task(self):
        build = self._build_script()
        owner = self._duplicate_owner_body(build)

        for token in (
            "dupFqcnEvidenceFile",
            "reports/dup-fqcn-evidence.json",
        ):
            self.assertIn(token, build)

        for token in (
            "schemaVersion",
            "duplicateFqcnSourceCollisionCount",
            "duplicateFqcnGeneratedExcludeCount",
            "duplicateFqcnHardExcludeCount",
            "duplicateFqcnPackagedActiveCount",
            "duplicateFqcnActiveCount",
            "packagingState",
            "semanticHash",
        ):
            self.assertIn(token, owner)

        self.assertIn("outputs.file(dupFqcnEvidenceFile)", owner)
        self.assertIn("evidenceOut.writeText(", owner)
        self.assertEqual(1, build.count("val generateDupFqcnExcludes by tasks.registering {"))

    def test_duplicate_fqcn_evidence_does_not_add_a_second_java_root_scan(self):
        build = self._build_script()
        owner = self._duplicate_owner_body(build)

        self.assertEqual(1, build.count('rootProject.layout.projectDirectory.dir("main/java")'))
        self.assertEqual(1, build.count('project.layout.projectDirectory.dir("src/main/java_clean")'))
        self.assertEqual(2, owner.count(".walkTopDown()"))


if __name__ == "__main__":
    unittest.main()
