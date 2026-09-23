#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "build_error_mitigator.py"


def load_module():
    spec = importlib.util.spec_from_file_location("build_error_mitigator", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildErrorMitigatorRedactionTest(unittest.TestCase):
    def test_exception_summary_is_hash_only(self):
        mitigator = load_module()
        secret = "sk-" + "B" * 24

        summary = mitigator.safe_error(RuntimeError(f"failed with token {secret}"))

        self.assertEqual("RuntimeError", summary["errorType"])
        self.assertTrue(summary["errorHash"].startswith("hash:"))
        self.assertGreater(summary["errorLength"], 0)
        self.assertNotIn(secret, str(summary))

    def test_partial_dependency_injection_adds_only_missing_snippets(self):
        mitigator = load_module()
        cases = (
            (
                "groovy",
                mitigator.patch_groovy,
                "compileOnly 'example:annotations:1'",
                "annotationProcessor 'example:annotations:1'",
                "tasks.withType(JavaCompile).configureEach",
            ),
            (
                "kotlin",
                mitigator.patch_kts,
                'compileOnly("example:annotations:1")',
                'annotationProcessor("example:annotations:1")',
                "tasks.withType<JavaCompile>().configureEach",
            ),
        )

        for name, patcher, existing, missing, compile_hook in cases:
            with self.subTest(name=name):
                original = "\n".join(
                    (
                        "plugins {}",
                        "dependencies {",
                        "  // injected by build_error_mitigator.py",
                        f"  {existing}",
                        "}",
                        "",
                    )
                )

                requested = [existing, missing, missing]
                patched = patcher(original, requested)

                self.assertEqual(1, patched.count(existing))
                self.assertEqual(1, patched.count(missing))
                self.assertEqual(1, patched.count("// injected by build_error_mitigator.py"))
                self.assertEqual(1, patched.count(compile_hook))
                self.assertEqual(patched, patcher(patched, requested))


if __name__ == "__main__":
    unittest.main()
