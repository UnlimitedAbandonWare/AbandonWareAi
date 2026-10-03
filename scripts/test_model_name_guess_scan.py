#!/usr/bin/env python3
"""Fixture-only contract tests for scripts/model_name_guess_scan.py.
Zero live calls; all inputs are synthetic temp files.

Run: python -B scripts/test_model_name_guess_scan.py
Exit 0 when every case passes.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "model_name_guess_scan.py"

JAVA_SAMPLE = """package x;
public class F {
    private boolean isLocalModel(String model) {
        String m = model.toLowerCase();
        if (m.contains(":")) return true;
        if (m.contains("qwen") || m.contains("gemma")) return true;
        if (m.startsWith("gpt-")) return false;
        return true;
    }
    private String selectLocalBaseUrl(String model) {
        String m = model.toLowerCase();
        if (m.contains("qwen3-coder")) return coderLocalBaseUrl;
        return localBaseUrl;
    }
}
"""

JS_SAMPLE = """const isRemoteLookingId = (id) => {
  const s = id.toLowerCase();
  return s.startsWith("gpt-") || /^o\\d/.test(s);
};
const LOCAL_PREFERRED = ["gemma4:26b", "qwen3:8b"];
function optionLabel(model) {
  return model.id + " - " + (model.provider === "gpt-5.5" ? "x" : "y");
}
"""

CLEAN = """package x;
public class C { int add(int a, int b) { return a + b; } }
"""


def run_tool(*argv):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *argv],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )


def make_tree(base: Path):
    (base / "main/java/x").mkdir(parents=True)
    (base / "main/java/x/F.java").write_text(JAVA_SAMPLE, encoding="utf-8")
    (base / "main/resources/static/js").mkdir(parents=True)
    (base / "main/resources/static/js/m.js").write_text(JS_SAMPLE, encoding="utf-8")
    (base / "main/java/x/C.java").write_text(CLEAN, encoding="utf-8")
    # excluded dir must never be scanned
    (base / "main/java/build").mkdir(parents=True)
    (base / "main/java/build/G.java").write_text(JAVA_SAMPLE, encoding="utf-8")


class ScanTest(unittest.TestCase):
    def test_finds_java_and_js_patterns(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            out = base / "out.json"
            r = run_tool("--root", str(base), "--out-json", str(out))
            self.assertEqual(r.returncode, 0, r.stderr)
            data = json.loads(out.read_text(encoding="utf-8"))
            keys = {(h["file"], h["line"], h["rule"]) for h in data["hits"]}
            self.assertIn(("main/java/x/F.java", 5, "COLON_MEANS_LOCAL"), keys)
            self.assertIn(("main/java/x/F.java", 6, "NAME_CONTAINS_LOCAL"), keys)
            self.assertIn(("main/java/x/F.java", 7, "NAME_PREFIX_REMOTE"), keys)
            self.assertIn(("main/resources/static/js/m.js", 3, "NAME_PREFIX_REMOTE"), keys)
            self.assertTrue(any(h["rule"] == "HARDCODED_MODEL_ID" for h in data["hits"]
                                if h["file"].endswith("m.js")), "js hardcoded ids")

    def test_classification_endpoint_and_display(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            out = base / "out.json"
            r = run_tool("--root", str(base), "--out-json", str(out))
            self.assertEqual(r.returncode, 0, r.stderr)
            data = json.loads(out.read_text(encoding="utf-8"))
            ep = [h for h in data["hits"]
                  if h["file"].endswith("F.java") and h["method"] == "selectLocalBaseUrl"]
            self.assertTrue(ep and all(h["class"] == "endpoint_select" for h in ep))
            disp = [h for h in data["hits"] if h["method"] == "optionLabel"]
            self.assertTrue(disp and all(h["class"] == "display_only" for h in disp))

    def test_excluded_dirs_not_scanned(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            out = base / "out.json"
            run_tool("--root", str(base), "--out-json", str(out))
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertFalse(any("build" in h["file"] for h in data["hits"]))

    def test_baseline_added_routing_decision_exits_2(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            (base / "main/java/x").mkdir(parents=True)
            (base / "main/java/x/C.java").write_text(CLEAN, encoding="utf-8")
            b = base / "baseline.json"
            r = run_tool("--root", str(base), "--out-json", str(b))
            self.assertEqual(r.returncode, 0, r.stderr)
            # now introduce a new name-guess site
            (base / "main/java/x/F.java").write_text(JAVA_SAMPLE, encoding="utf-8")
            r2 = run_tool("--root", str(base), "--baseline", str(b))
            self.assertEqual(r2.returncode, 2, r2.stdout + r2.stderr)
            payload = json.loads(r2.stdout.strip().splitlines()[0])
            self.assertGreaterEqual(payload["addedRoutingDecision"], 1)

    def test_baseline_removed_only_exits_0(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            b = base / "baseline.json"
            run_tool("--root", str(base), "--out-json", str(b))
            # remove the pattern files -> only removals, no additions
            (base / "main/java/x/F.java").write_text(CLEAN, encoding="utf-8")
            (base / "main/resources/static/js/m.js").write_text("// empty\n", encoding="utf-8")
            r = run_tool("--root", str(base), "--baseline", str(b))
            self.assertEqual(r.returncode, 0, r.stdout + r2_msg(r))


def r2_msg(r):
    return getattr(r, "stderr", "")


if __name__ == "__main__":
    unittest.main()
