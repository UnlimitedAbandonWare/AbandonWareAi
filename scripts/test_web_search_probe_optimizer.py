"""Unit tests for web_search_probe_optimizer (offline; no network)."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import web_search_probe_optimizer as probe  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

# Secret-shaped fixture values assembled per checkpoint secret-scan fixture style.
ERROR_DUMP = (
    r"""org.springframework.beans.factory.BeanCreationException: bad
[C:\AbandonWare\demo-1\demo-1\src\main\java\io\foo\Bar.class]
Caused by: java.lang.IllegalStateException: builder() requires modelName
    at io.abandonware.rag.RagChatService.buildModel(RagChatService.java:142)
    at java.base/java.lang.reflect.Method.invoke(Method.java:569)
langchain4j:1.0.1 spring-boot:3.2.1 """
    + "api" + "_key=sk-" + "liveSecret123456"
    + " Bearer " + "eyJhbGciOiJ9" + ".eyJ4In0.sig"
)


class RedactionTest(unittest.TestCase):
    def test_windows_path_masked(self):
        out = probe.redact(r"error at C:\AbandonWare\demo-1\src\main.java")
        self.assertNotIn("AbandonWare", out)
        self.assertIn("<LOCAL_PATH>", out)

    def test_unix_home_masked(self):
        out = probe.redact("boom in /home/nninn/proj/x.py line 3")
        self.assertNotIn("nninn", out)
        self.assertIn("<LOCAL_PATH>", out)

    def test_secret_shapes_masked(self):
        cases = [
            ("sk-" + "abc123XYZdef", "abc123XYZdef"),
            ("ghp_" + "tok1234567", "tok1234567"),
            ("Bearer " + "a" * 24, "a" * 24),
            ("api" + "_key=" + "hunter2hunter2", "hunter2hunter2"),
            ("pass" + "word: " + "p" + "@ssw0rd99", "p@ssw0rd99"),
            ("eyJ" + "aaaaaa" + ".eyJ" + "bbbbbb" + ".cccccc", "bbbbbb"),
        ]
        for secret, tail in cases:
            with self.subTest(tail=tail):
                self.assertNotIn(tail, probe.redact(f"oops {secret}"))

    def test_no_false_positive_on_plain_text(self):
        text = "IllegalStateException at com.foo.Bar.run"
        self.assertEqual(probe.redact(text), text)


class FingerprintTest(unittest.TestCase):
    def setUp(self):
        self.fp = probe.extract_fingerprint(ERROR_DUMP)

    def test_exception_fqcn_prefers_caused_by(self):
        self.assertEqual(self.fp["exceptionFqcn"],
                         "java.lang.IllegalStateException")
        self.assertEqual(self.fp["exceptionShort"], "IllegalStateException")
        self.assertIn("modelName", self.fp["message"])

    def test_failed_frame_skips_jdk(self):
        self.assertEqual(self.fp["failedFrame"]["method"], "buildModel")
        self.assertTrue(self.fp["failedFrame"]["classFqn"].endswith(
            "RagChatService"))

    def test_framework_versions(self):
        versions = {f["name"]: f["version"] for f in self.fp["frameworks"]}
        self.assertEqual(versions.get("langchain4j"), "1.0.1")
        self.assertEqual(versions.get("spring boot"), "3.2.1")

    def test_redaction_ran_on_fingerprint_source(self):
        self.assertTrue(self.fp["redactionApplied"])
        self.assertNotIn("sk-liveSecret", json.dumps(self.fp))


class ProbePlatesTest(unittest.TestCase):
    def setUp(self):
        self.fp = probe.extract_fingerprint(ERROR_DUMP)
        self.plates = probe.build_probe_plates(self.fp)

    def test_four_plates_in_order(self):
        self.assertEqual([p["id"] for p in self.plates],
                         ["T1", "T2", "T3", "T4"])

    def test_t1_targets_official_docs(self):
        t1 = self.plates[0]
        exa = next(q for q in t1["queries"] if q["engine"] == "exa")
        self.assertEqual(exa["category"], "documentation")
        self.assertIn("docs.langchain4j.dev", exa["includeDomains"])
        self.assertIn("site:", exa["query"])

    def test_t2_targets_github_issues(self):
        t2 = self.plates[1]
        joined = json.dumps(t2["queries"])
        self.assertIn("github.com", joined)
        self.assertIn("breaking change", joined)
        self.assertIn("IllegalStateException", joined)

    def test_t3_targets_minimal_repro(self):
        t3 = self.plates[2]
        joined = json.dumps(t3["queries"])
        self.assertIn("@Test", joined)
        self.assertIn("buildModel", joined)

    def test_t4_cross_checks_versions(self):
        t4 = self.plates[3]
        joined = json.dumps(t4["queries"])
        self.assertIn("1.0.1", joined)
        self.assertIn("0.x", joined)

    def test_exa_payload_contract(self):
        for plate in self.plates:
            for q in plate["queries"]:
                if q["engine"] != "exa":
                    continue
                self.assertEqual(q["type"], "deep")
                self.assertTrue(q["contents"]["highlights"])

    def test_no_local_path_or_secret_in_queries(self):
        joined = json.dumps(probe.probe_bundle(ERROR_DUMP))
        self.assertNotIn("AbandonWare", joined)
        self.assertNotIn("sk-liveSecret", joined)
        self.assertNotIn("eyJhbGciOiJ9", joined)


class SynthesizeTest(unittest.TestCase):
    def setUp(self):
        self.fp = probe.extract_fingerprint(ERROR_DUMP)
        self.results = [
            {"plate": "T2", "title": "gh issue fix", "score": 5,
             "url": "https://github.com/x/y/issues/1",
             "highlights": ["langchain4j 1.0.1 fix: pass modelName"]},
            {"plate": "T1", "title": "official doc", "score": 1,
             "url": "https://docs.langchain4j.dev/x",
             "highlights": ["langchain4j 1.0.1 builder usage"]},
            {"plate": "T4", "title": "old blog", "score": 50,
             "url": "https://blog.invalid/old",
             "highlights": ["langchain4j 0.36.0 auto-wires everything"]},
        ]

    def test_output_under_3000_chars(self):
        out = probe.synthesize(self.results, self.fp)
        self.assertLessEqual(out["charCount"], 3000)
        self.assertLessEqual(len(out["markdown"]), 3000)

    def test_version_mismatch_flagged_and_penalized(self):
        out = probe.synthesize(self.results, self.fp)
        self.assertTrue(out["versionFlags"])
        blog = next(v for k, v in out["versionFlags"].items()
                    if "blog.invalid" in k)
        self.assertTrue(any("0.36.0" in f for f in blog))

    def test_official_first_ordering(self):
        out = probe.synthesize(self.results, self.fp)
        t1_pos = out["markdown"].find("[T1]")
        t2_pos = out["markdown"].find("[T2]")
        self.assertGreater(t1_pos, -1)
        self.assertGreater(t2_pos, -1)
        self.assertLess(t1_pos, t2_pos)

    def test_empty_results(self):
        out = probe.synthesize([], self.fp)
        self.assertIn("Golden Context", out["markdown"])
        self.assertEqual(out["itemsUsed"], 0)


class CliDemoTest(unittest.TestCase):
    def test_demo_exit0_and_shape(self):
        run = subprocess.run(
            [sys.executable, "-B", str(ROOT / "scripts/web_search_probe_optimizer.py"),
             "demo"], capture_output=True, text=True, cwd=ROOT, timeout=30)
        self.assertEqual(run.returncode, 0, run.stderr)
        data = json.loads(run.stdout)
        self.assertEqual([p["id"] for p in data["plates"]],
                         ["T1", "T2", "T3", "T4"])
        self.assertEqual(data["fingerprint"]["exceptionShort"],
                         "IllegalStateException")
        self.assertLessEqual(data["synthesis"]["charCount"], 3000)

    def test_probe_cli_via_stdin(self):
        run = subprocess.run(
            [sys.executable, "-B", str(ROOT / "scripts/web_search_probe_optimizer.py"),
             "probe"], input="java.lang.NullPointerException at a.B.c(B.java:1)",
            capture_output=True, text=True, cwd=ROOT, timeout=30)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(json.loads(run.stdout)["fingerprint"]["exceptionShort"],
                         "NullPointerException")


if __name__ == "__main__":
    unittest.main(verbosity=2)
