import json
import tempfile
import unittest
from pathlib import Path

import test_tree_contamination_report as report


class TestTreeContaminationReportTest(unittest.TestCase):
    def test_reports_missing_test_imports_against_active_main_roots(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live").mkdir(parents=True)
            (root / "app/src/main/java_clean/com/example/app").mkdir(parents=True)

            (root / "main/java/com/example/live/AliveService.java").write_text(
                "package com.example.live;\npublic class AliveService {}\n",
                encoding="utf-8",
            )
            (root / "app/src/main/java_clean/com/example/app/AppBridge.java").write_text(
                "package com.example.app;\npublic class AppBridge {}\n",
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/LegacyTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "import com.example.live.AliveService;",
                        "import com.example.app.AppBridge;",
                        "import ai.abandonware.nova.missing.LegacyAspect;",
                        "import static com.example.missing.LegacyUtil.run;",
                        "class LegacyTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

            self.assertEqual(data["activeClassCount"], 2)
            self.assertEqual(data["testJavaFileCount"], 1)
            self.assertEqual(data["missingImportCount"], 2)
            self.assertEqual(data["affectedTestFileCount"], 1)
            self.assertEqual(
                data["topAffectedTestFiles"][0]["file"],
                "src/test/java/com/example/live/LegacyTest.java",
            )
            self.assertIn(
                "ai.abandonware.nova.missing.LegacyAspect",
                data["topAffectedTestFiles"][0]["missingImports"],
            )
            self.assertIn(
                "com.example.missing.LegacyUtil",
                data["topAffectedTestFiles"][0]["missingImports"],
            )
            self.assertEqual(data["missingByNamespace"]["ai.abandonware.nova"], 1)
            self.assertEqual(data["missingByNamespace"]["com.example"], 1)
            self.assertGreater(data["riskScore"], 0)
            json.dumps(data)

    def test_counts_nested_active_types_as_resolved_imports(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live").mkdir(parents=True)

            (root / "main/java/com/example/live/OuterService.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "public class OuterService {",
                        "  public static class NestedRequest {}",
                        "  public enum NestedMode { SAFE }",
                        "}",
                    ]
                ),
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/NestedTypeTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "import com.example.live.OuterService;",
                        "import com.example.live.OuterService.NestedRequest;",
                        "import com.example.live.OuterService.NestedMode;",
                        "class NestedTypeTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

        self.assertEqual(data["activeClassCount"], 3)
        self.assertEqual(data["missingImportCount"], 0)
        self.assertEqual(data["affectedTestFileCount"], 0)

    def test_counts_test_support_imports_as_resolved_not_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live/support").mkdir(parents=True)

            (root / "main/java/com/example/live/AliveService.java").write_text(
                "package com.example.live;\npublic class AliveService {}\n",
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/support/TestFixtures.java").write_text(
                "package com.example.live.support;\npublic final class TestFixtures {}\n",
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/UsesFixtureTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "import com.example.live.AliveService;",
                        "import com.example.live.support.TestFixtures;",
                        "class UsesFixtureTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

        self.assertEqual(data["activeClassCount"], 1)
        self.assertEqual(data["testSupportClassCount"], 2)
        self.assertEqual(data["testSupportImportCount"], 1)
        self.assertEqual(data["missingImportCount"], 0)
        self.assertEqual(data["affectedTestFileCount"], 0)

    def test_reports_app_test_tree_imports_that_broad_gradle_test_compiles(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "app/src/main/java_clean/com/example/app").mkdir(parents=True)
            (root / "app/src/test/java/com/example/app").mkdir(parents=True)

            (root / "main/java/com/example/live/AliveService.java").write_text(
                "package com.example.live;\npublic class AliveService {}\n",
                encoding="utf-8",
            )
            (root / "app/src/main/java_clean/com/example/app/AppBridge.java").write_text(
                "package com.example.app;\npublic class AppBridge {}\n",
                encoding="utf-8",
            )
            (root / "app/src/test/java/com/example/app/AppLegacyTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.app;",
                        "import com.example.app.AppBridge;",
                        "import com.abandonware.ai.agent.consent.ConsentCardRenderer;",
                        "class AppLegacyTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

        self.assertIn("app/src/test/java", data["testRoots"])
        self.assertEqual(data["testJavaFileCount"], 1)
        self.assertEqual(data["missingImportCount"], 1)
        self.assertEqual(data["affectedTestFileCount"], 1)
        self.assertEqual(
            "app/src/test/java/com/example/app/AppLegacyTest.java",
            data["topAffectedTestFiles"][0]["file"],
        )
        self.assertIn(
            "com.abandonware.ai.agent.consent.ConsentCardRenderer",
            data["topAffectedTestFiles"][0]["missingImports"],
        )

    def test_skips_app_test_tree_when_build_quarantines_legacy_tests(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "app/src/main/java_clean/com/example/app").mkdir(parents=True)
            (root / "app/src/test/java/com/example/app").mkdir(parents=True)
            (root / "app").mkdir(exist_ok=True)

            (root / "app/build.gradle.kts").write_text(
                "\n".join(
                    [
                        "sourceSets {",
                        "  val main by getting {",
                        '    java.setSrcDirs(listOf("src/main/java_clean"))',
                        '    resources.setSrcDirs(listOf("src/main/resources"))',
                        "  }",
                        "  val test by getting {",
                        "    java.setSrcDirs(emptyList<String>())",
                        "    resources.setSrcDirs(emptyList<String>())",
                        "  }",
                        "}",
                    ]
                ),
                encoding="utf-8",
            )
            (root / "main/java/com/example/live/AliveService.java").write_text(
                "package com.example.live;\npublic class AliveService {}\n",
                encoding="utf-8",
            )
            (root / "app/src/main/java_clean/com/example/app/AppBridge.java").write_text(
                "package com.example.app;\npublic class AppBridge {}\n",
                encoding="utf-8",
            )
            (root / "app/src/test/java/com/example/app/AppLegacyTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.app;",
                        "import com.abandonware.ai.agent.consent.ConsentCardRenderer;",
                        "class AppLegacyTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

        self.assertNotIn("app/src/test/java", data["testRoots"])
        self.assertIn("app/src/test/java", data["quarantinedTestRoots"])
        self.assertEqual(data["testJavaFileCount"], 0)
        self.assertEqual(data["missingImportCount"], 0)
        self.assertEqual(data["affectedTestFileCount"], 0)

    def test_gradle_task_is_registered_for_repo_owned_refresh(self):
        root = Path(__file__).resolve().parents[1]
        build = (root / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")

        self.assertIn('tasks.register<Exec>("testTreeContaminationReport")', build)
        self.assertIn("scripts/test_tree_contamination_report.py", build)
        self.assertIn("verification/test-tree-contamination-metrics.json", build)


if __name__ == "__main__":
    unittest.main()
