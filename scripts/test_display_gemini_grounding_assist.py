"""Fixture tests for display_gemini_grounding_assist. No product source and no Gradle."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "display_gemini_grounding_assist.py"


def load_module():
    spec = importlib.util.spec_from_file_location("display_gemini_grounding_assist_under_test", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def write_spec(root: Path, body: dict) -> Path:
    path = root / "spec.json"
    path.write_text(json.dumps(body), encoding="utf-8")
    return path


class DisplayGeminiGroundingAssistTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-gemini-grounding-")
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def spec(self, **extra):
        body = {
            "schemaVersion": self.mod.SCHEMA,
            "contract": "DEMO",
            "sources": [{
                "path": "Answer.java",
                "expectSha12": extra.get("expect"),
                "needles": [{"id": "fixed", "regex": "Mode\\.FIXED"}],
                "forbidden": [{"id": "interactions-copy", "regex": "\"type\"\\s*[,:]\\s*\"google_search\""}],
            }],
            "gaps": [{
                "path": "Gateway.java",
                "tokens": [
                    {"id": "meta", "regex": "groundingMetadata"},
                    {"id": "switch-name", "regex": "webSearchEnabled", "optional": True},
                ],
            }],
            "coverage": [{
                "path": "GatewayTest.java",
                "tokens": [{"id": "meta", "regex": "groundingMetadata"}],
            }],
            "seam": {
                "nativeOptIn": {"path": "Gateway.java", "regex": "google_search"},
                "displayLink": {"path": "Answer.java", "regex": "webGrounding"},
                "clientCalls": {"path": "Client.java", "regex": "\\.generate\\("},
            },
            "diffRules": [
                {
                    "id": "interactions-copy",
                    "pathRegex": "\\.java$",
                    "lineRegex": "\"type\"\\s*[,:]\\s*\"google_search\"",
                    "allowPathRegex": "fixtures/",
                },
                {
                    "id": "display-model-default",
                    "pathRegex": "application-meta-display\\.yml$",
                    "lineRegex": "^(?=.*(?:web-model|default-model):)(?!.*llmrouter\\.gemini-pro).+",
                },
                {
                    "id": "assert-removed",
                    "side": "removed",
                    "pathRegex": "Test\\.java$",
                    "lineRegex": "\\bassert(?:[A-Z]\\w*)?\\s*\\(",
                },
            ],
        }
        return write_spec(self.root, body)

    def test_pin_fresh(self):
        source = self.root / "Answer.java"
        source.write_text("Mode.FIXED\n", encoding="utf-8")
        spec = self.spec(expect=self.mod.sha12_of(source.read_bytes()))
        self.assertEqual(0, self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)]))

    def test_pin_stale(self):
        (self.root / "Answer.java").write_text("Mode.FIXED\n", encoding="utf-8")
        spec = self.spec(expect="000000000000")
        self.assertEqual(4, self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)]))

    def test_pin_missing_needle(self):
        (self.root / "Answer.java").write_text("other\n", encoding="utf-8")
        spec = self.spec()
        self.assertEqual(3, self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)]))

    def test_pin_forbidden(self):
        (self.root / "Answer.java").write_text('Mode.FIXED\n"type": "google_search"\n', encoding="utf-8")
        spec = self.spec(expect=self.mod.sha12_of((self.root / "Answer.java").read_bytes()))
        self.assertEqual(3, self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)]))

    def test_gaps_open_and_closed(self):
        spec = self.spec()
        (self.root / "Gateway.java").write_text("google_search\n", encoding="utf-8")
        self.assertEqual(4, self.mod.main(["gaps", "--root", str(self.root), "--spec", str(spec)]))
        (self.root / "Gateway.java").write_text("groundingMetadata\n", encoding="utf-8")
        self.assertEqual(0, self.mod.main(["gaps", "--root", str(self.root), "--spec", str(spec)]))

    def test_cover_search_gap(self):
        spec = self.spec()
        (self.root / "GatewayTest.java").write_text("google_search\n", encoding="utf-8")
        self.assertEqual(4, self.mod.main(["cover", "--root", str(self.root), "--spec", str(spec)]))

    def test_seam_open_linked_and_broken(self):
        spec = self.spec()
        (self.root / "Gateway.java").write_text("google_search\n", encoding="utf-8")
        (self.root / "Answer.java").write_text("Mode.FIXED\n", encoding="utf-8")
        (self.root / "Client.java").write_text("gateway.generate(prompt, purpose)\n", encoding="utf-8")
        self.assertEqual(4, self.mod.main(["seam", "--root", str(self.root), "--spec", str(spec)]))
        (self.root / "Answer.java").write_text("webGrounding\n", encoding="utf-8")
        self.assertEqual(0, self.mod.main(["seam", "--root", str(self.root), "--spec", str(spec)]))
        (self.root / "Gateway.java").write_text("plain\n", encoding="utf-8")
        self.assertEqual(3, self.mod.main(["seam", "--root", str(self.root), "--spec", str(spec)]))

    def test_body_shapes(self):
        spec = self.spec()
        cases = {
            "off": ({"contents": [{"parts": [{"text": "synthetic"}]}]}, "OFF"),
            "snake": ({"contents": [], "tools": [{"google_search": {}}]}, "ON_NATIVE_SNAKE"),
            "camel": ({"tools": [{"googleSearch": {}}]}, "ON_JS_CAMEL"),
            "bad": ({"tools": [{"type": "google_search"}]}, "INTERACTIONS_COPY"),
            "plain": ({"candidates": [{"content": {"parts": [{"text": "synthetic answer"}]}}]}, "UNOBSERVED"),
            "query": ({"candidates": [{"content": {"parts": [{"text": "synthetic"}]}, "groundingMetadata": {"webSearchQueries": ["q"]}}]}, "QUERY_ONLY"),
            "linked": ({"candidates": [{"groundingMetadata": {"groundingChunks": [{"web": {"title": "Example"}}], "groundingSupports": [{}]}}]}, "LINKED"),
        }
        for name, (body, expect) in cases.items():
            path = self.root / (name + ".json")
            path.write_text(json.dumps(body), encoding="utf-8")
            code = self.mod.main([
                "body", "--root", str(self.root), "--spec", str(spec),
                "--file", str(path), "--expect", expect,
            ])
            self.assertEqual(0, code, name)

    def test_body_expect_mismatch(self):
        spec = self.spec()
        path = self.root / "body.json"
        path.write_text(json.dumps({"tools": [{"type": "google_search"}]}), encoding="utf-8")
        code = self.mod.main([
            "body", "--root", str(self.root), "--spec", str(spec),
            "--file", str(path), "--expect", "ON_NATIVE_SNAKE",
        ])
        self.assertEqual(3, code)

    def test_secret_body_refused(self):
        spec = self.spec()
        path = self.root / "body.json"
        path.write_text('{"contents":[],"x-goog-api-key":"synthetic-value-not-real"}', encoding="utf-8")
        code = self.mod.main([
            "body", "--root", str(self.root), "--spec", str(spec), "--file", str(path),
        ])
        self.assertEqual(2, code)

    def write_diff(self, text: str) -> Path:
        path = self.root / "owned.diff"
        path.write_text(text, encoding="utf-8")
        return path

    def test_diff_hits_and_allows(self):
        spec = self.spec()
        hit = self.write_diff(
            "--- a/Answer.java\n+++ b/Answer.java\n@@\n+\"type\": \"google_search\"\n"
        )
        self.assertEqual(3, self.mod.main([
            "diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(hit),
        ]))
        kept = self.write_diff(
            "--- a/main/resources/application-meta-display.yml\n"
            "+++ b/main/resources/application-meta-display.yml\n@@\n"
            "+    web-model: ${CONVERSATE_FOCUS_WEB_MODEL:llmrouter.gemini-pro}\n"
        )
        self.assertEqual(0, self.mod.main([
            "diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(kept),
        ]))
        swapped = self.write_diff(
            "--- a/main/resources/application-meta-display.yml\n"
            "+++ b/main/resources/application-meta-display.yml\n@@\n"
            "+    web-model: ${CONVERSATE_FOCUS_WEB_MODEL:other-model}\n"
        )
        self.assertEqual(3, self.mod.main([
            "diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(swapped),
        ]))
        removed = self.write_diff(
            "--- a/GatewayTest.java\n+++ b/GatewayTest.java\n@@\n-        assertEquals(1, calls);\n"
        )
        self.assertEqual(3, self.mod.main([
            "diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(removed),
        ]))
        allowed = self.write_diff(
            "--- a/var/fixtures/bad.json\n+++ b/var/fixtures/bad.json\n@@\n+\"type\": \"google_search\"\n"
        )
        self.assertEqual(0, self.mod.main([
            "diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(allowed),
        ]))

    def test_invalid_diff_and_escape(self):
        spec = self.spec()
        bad = self.write_diff("not a diff\n")
        self.assertEqual(2, self.mod.main([
            "diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(bad),
        ]))
        self.assertEqual(2, self.mod.main([
            "pin", "--root", str(self.root), "--spec", "../spec.json",
        ]))

    def test_shipped_spec_and_fixtures(self):
        spec_path = ROOT / self.mod.DEFAULT_SPEC
        spec = self.mod.load_spec(spec_path)
        self.assertEqual(self.mod.SCHEMA, spec["schemaVersion"])
        shipped = {
            "off-no-tools": "OFF",
            "on-native-snake": "ON_NATIVE_SNAKE",
            "on-js-camel": "ON_JS_CAMEL",
            "bad-interactions-type": "INTERACTIONS_COPY",
            "response-unobserved": "UNOBSERVED",
            "response-query-only": "QUERY_ONLY",
            "response-linked": "LINKED",
            "response-suggestions": "SUGGESTIONS",
        }
        for name, expect in shipped.items():
            code = self.mod.main([
                "body", "--root", str(ROOT), "--spec", str(spec_path),
                "--fixture", name, "--expect", expect,
            ])
            self.assertEqual(0, code, name)


if __name__ == "__main__":
    unittest.main()
