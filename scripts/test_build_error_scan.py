#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "build_error_scan.py"


def load_module():
    spec = importlib.util.spec_from_file_location("build_error_scan", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildErrorScanRedactionTest(unittest.TestCase):
    def test_regex_capture_group_is_hash_only(self):
        scanner = load_module()
        secret = "sk-" + "A" * 24
        patterns = [{
            "id": "secret-shaped-error",
            "regex": r"failed token=(sk-[A-Za-z0-9_-]{20,})",
            "severity": "warn",
            "explain": "token-shaped failure"
        }]

        hits = scanner.scan_log(f"compile failed token={secret}", patterns)

        self.assertEqual(1, len(hits))
        self.assertNotIn(secret, str(hits))
        self.assertTrue(hits[0]["groupHash"].startswith("hash:"))
        self.assertEqual(len(secret), hits[0]["groupLength"])
        self.assertNotIn("group", hits[0])


if __name__ == "__main__":
    unittest.main()
