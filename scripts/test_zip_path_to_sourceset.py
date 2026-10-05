#!/usr/bin/env python3
"""Unit tests for scripts/zip_path_to_sourceset.py — synthetic trees only."""
from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts import zip_path_to_sourceset as z  # noqa: E402

JAVA = """package com.example.lms.service.chat;

public class ChatRunRegistry {
    void start() {}

    boolean runTerminalSideEffect(Runnable action) {
        return true;
    }

    void cancelRun() {}
}
"""

DIRECTIVE = """# fake directive
| FQCN | ZIP path |
|---|---|
| com.example.lms.service.chat.ChatRunRegistry | main/java/com/example/lms/service/chat/ChatRunRegistry.java, runTerminalSideEffect(), 806~818 |
| same | cancelRun(), 1088~1145 |

- main/java/com/example/lms/assist/JevGatewayClient.java
  - exchange(EvalRequest,List): 59~128행.
- main/java/com/example/lms/missing/Gone.java, nope(), 1~9
"""


def make_tree():
    tmp = tempfile.TemporaryDirectory()
    root = Path(tmp.name)
    target = root / "main/java/com/example/lms/service/chat/ChatRunRegistry.java"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(JAVA, encoding="utf-8")
    return tmp, root


class ParseAnchorTests(unittest.TestCase):
    def test_table_and_inherited_path(self):
        anchors = z.parse_anchors(DIRECTIVE)
        by_sym = {a["symbol"]: a for a in anchors if a["symbol"]}
        self.assertIn("runTerminalSideEffect", by_sym)
        self.assertEqual(806, by_sym["runTerminalSideEffect"]["zipStart"])
        self.assertEqual(818, by_sym["runTerminalSideEffect"]["zipEnd"])
        # cancelRun inherits the same-table path? No: same row has no path ->
        # binds to most recent path = ChatRunRegistry.
        self.assertEqual("main/java/com/example/lms/service/chat/ChatRunRegistry.java",
                         by_sym["cancelRun"]["zipPath"])
        # Jev-style: bare symbol+행 range binds to last-seen JevGatewayClient path.
        self.assertEqual("main/java/com/example/lms/assist/JevGatewayClient.java",
                         by_sym["exchange"]["zipPath"])
        self.assertEqual(59, by_sym["exchange"]["zipStart"])
        self.assertIn("Gone.java", by_sym["nope"]["zipPath"])

    def test_dedup(self):
        dup = DIRECTIVE + "\n" + DIRECTIVE
        a1, a2 = z.parse_anchors(DIRECTIVE), z.parse_anchors(dup)
        # Identical anchors inside one document collapse to one entry.
        self.assertEqual(len(a1), len(a2))
        self.assertGreater(len(a1), 0)


class ResolveTests(unittest.TestCase):
    def test_ok_with_drift(self):
        tmp, root = make_tree()
        with tmp:
            d = root / "d.md"
            d.write_text(DIRECTIVE, encoding="utf-8")
            report = z.translate(root, [d])
            row = next(a for a in report["anchors"] if a["directive"]["symbol"] == "runTerminalSideEffect")
            self.assertEqual("OK", row["status"])
            # live decl of runTerminalSideEffect is at line 6 of JAVA fixture.
            self.assertEqual(6, row["live"]["line"])
            self.assertEqual(6 - 806, row["driftFromZipStart"])
            self.assertEqual(":", row["module"])
            self.assertEqual("src/test/java", row["testSourceRoot"])
            self.assertEqual("com.example.lms.service.chat.ChatRunRegistry", row["fqcn"])
            self.assertIn("--tests", row["testTask"])

    def test_anchor_missing(self):
        tmp, root = make_tree()
        with tmp:
            d = root / "d.md"
            d.write_text(DIRECTIVE, encoding="utf-8")
            report = z.translate(root, [d])
            row = next(a for a in report["anchors"] if "Gone.java" in a["directive"]["zipPath"])
            self.assertEqual("ANCHOR_MISSING", row["status"])

    def test_symbol_not_found(self):
        tmp, root = make_tree()
        with tmp:
            d = root / "d.md"
            d.write_text(
                "main/java/com/example/lms/service/chat/ChatRunRegistry.java, imaginary(), 1~9\n",
                encoding="utf-8")
            report = z.translate(root, [d])
            self.assertEqual("SYMBOL_NOT_FOUND", report["anchors"][0]["status"])

    def test_zip_member_check(self):
        tmp, root = make_tree()
        with tmp:
            zip_path = root / "src.zip"
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.writestr("main/java/com/example/lms/service/chat/ChatRunRegistry.java", JAVA)
            d = root / "d.md"
            d.write_text(DIRECTIVE, encoding="utf-8")
            report = z.translate(root, [d], zip_path)
            row = next(a for a in report["anchors"] if a["directive"]["symbol"] == "runTerminalSideEffect")
            self.assertTrue(row["zipMemberFound"])
            self.assertEqual(6, row["zipSymbolLine"])
            missing = next(a for a in report["anchors"] if "Gone.java" in a["directive"]["zipPath"])
            self.assertEqual("ANCHOR_MISSING+ZIP_MEMBER_MISSING", missing["status"])

    def test_stale_app_java_is_not_an_active_anchor(self):
        tmp = tempfile.TemporaryDirectory()
        with tmp:
            root = Path(tmp.name)
            f = root / "app/src/main/java_clean/x/Y.java"
            f.parent.mkdir(parents=True)
            f.write_text("package x;\npublic class Y { void go() {} }\n", encoding="utf-8")
            d = root / "d.md"
            d.write_text("main/java/x/Y.java, go(), 1~1\n", encoding="utf-8")
            report = z.translate(root, [d])
            row = report["anchors"][0]
            self.assertEqual("ANCHOR_MISSING", row["status"])
            self.assertFalse(row["live"]["exists"])
            self.assertIsNone(row["module"])
            self.assertIsNone(row["testSourceRoot"])
            self.assertIsNone(row["testTask"])

    def test_stale_app_resource_is_not_an_active_anchor(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f = root / "app/src/main/resources/static/demo.js"
            f.parent.mkdir(parents=True)
            f.write_text("function go() {}\n", encoding="utf-8")
            d = root / "d.md"
            d.write_text("main/resources/static/demo.js\n", encoding="utf-8")
            row = z.translate(root, [d])["anchors"][0]
            self.assertEqual("ANCHOR_MISSING", row["status"])
            self.assertFalse(row["live"]["exists"])
            self.assertIsNone(row["module"])
            self.assertIsNone(row["testSourceRoot"])
            self.assertIsNone(row["testTask"])

    def test_canonical_root_wins_over_stale_app_files(self):
        cases = (
            ("main/java/x/Y.java", "app/src/main/java_clean/x/Y.java",
             "package x;\npublic class Y {}\n", "test --tests 'x.Y'"),
            ("main/resources/static/demo.js", "app/src/main/resources/static/demo.js",
             "function go() {}\n", None),
        )
        for canonical, stale, body, expected_task in cases:
            with self.subTest(path=canonical), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                for rel in (canonical, stale):
                    f = root / rel
                    f.parent.mkdir(parents=True)
                    f.write_text(body, encoding="utf-8")
                d = root / "d.md"
                d.write_text(canonical + "\n", encoding="utf-8")
                row = z.translate(root, [d])["anchors"][0]
                self.assertEqual("OK_FILE_ONLY", row["status"])
                self.assertTrue(row["live"]["exists"])
                self.assertEqual(canonical, row["live"]["path"])
                self.assertEqual(":", row["module"])
                self.assertEqual("src/test/java", row["testSourceRoot"])
                self.assertEqual(expected_task, row["testTask"])


if __name__ == "__main__":
    unittest.main()
