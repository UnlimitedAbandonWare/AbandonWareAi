#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "build_error_pattern_scanner.py"


def load_module():
    spec = importlib.util.spec_from_file_location("build_error_pattern_scanner", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildErrorPatternScannerRedactionTest(unittest.TestCase):
    def test_samples_redact_secret_shaped_log_fragments(self):
        scanner = load_module()
        secret = "sk-" + "D" * 24

        _, samples = scanner.scan_text(f"error: cannot find symbol token={secret}")

        rendered = str(samples)
        self.assertNotIn(secret, rendered)
        self.assertNotIn("token=sk-", rendered)
        self.assertIn("<secret>", rendered)


if __name__ == "__main__":
    unittest.main()
