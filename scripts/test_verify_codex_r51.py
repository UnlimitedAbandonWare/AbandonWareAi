#!/usr/bin/env python3
"""Tests for scripts/verify_codex_r51.py — synthetic fixture roots only."""
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify_codex_r51.py")
FALLBACK = "main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java"
LIFECYCLE = "src/test/java/com/example/lms/service/RoutingBenefitLifecycleTest.java"
REDTEAM = "src/test/java/ai/abandonware/nova/orch/llm/ChatGptOAuthRedTeamContractTest.java"
TASK = "oauth-benefit-r51-1001-deadbeef"

METHODS = """
    @Test void blankAccountRefUsesCatalogFingerprintAndPersistsExhaustion() throws Exception {
        var r = registration(); assertTrue(r.available("chatgpt-oauth:fixture-model"));
    }
    @Test void exhaustionSurvivesRestartWithDerivedKey() throws Exception { helper(); }
    void helper() {}
    @Test void noAccountKeyIsExplicitUnboundNotSilent() throws Exception { assertTrue(true); }
    @Test void explicitAccountRefWinsOverCatalog() throws Exception { assertTrue(true); }
"""


def xml(suite, tests=5, failures=0, errors=0, skipped=0):
    return (f'<testsuite name="{suite}" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}"></testsuite>')


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def build_root(tmp, *, redteam_skipped=0, injection=False, disabled=False,
               out_of_scope=None, fallback_changed=False, xmls=True):
    root = Path(tmp)
    write(root / FALLBACK,
          "public final class FallbackAwareChatModel { /* fixture */ }\n")
    baseline_sha = hashlib.sha256((root / FALLBACK).read_bytes()).hexdigest()
    if fallback_changed:
        with open(root / FALLBACK, "ab") as fh:
            fh.write(b"// drifted\n")
    write(root / "data/agent-handoff/codex-autonomy/oauth-adaptive-r5-1001-9f522366"
          / "final-owned-changes.json",
          json.dumps([{"path": FALLBACK, "sha256": baseline_sha}]))
    td = root / "data/agent-handoff/codex-autonomy" / TASK
    write(td / "journal.json", json.dumps({"startedAtUtc": "2026-10-01T05:38:56Z"}))
    if xmls:
        write(td / "final-focused-test" / "TEST-com.example.SuiteA.xml",
              xml("com.example.SuiteA", tests=20))
        write(td / "final-focused-test"
              / "TEST-ai.abandonware.nova.orch.llm.ChatGptOAuthRedTeamContractTest.xml",
              xml("ai.abandonware.nova.orch.llm.ChatGptOAuthRedTeamContractTest",
                  tests=12, skipped=redteam_skipped))
    targets = [{"path": "main/java/com/example/lms/llm/ChatGptOAuthRegistration.java",
                "sha256": "0" * 64},
               {"path": LIFECYCLE, "sha256": "1" * 64}]
    for p in out_of_scope or []:
        targets.append({"path": p, "sha256": "2" * 64})
    write(td / "wp1-green-targets.json", json.dumps({"targets": targets}))
    body = METHODS
    if injection:
        body = body.replace("assertTrue(true); }",
                            'ReflectionTestUtils.setField(r, "accountRef", "a"); }', 1)
    write(root / LIFECYCLE,
          "class RoutingBenefitLifecycleTest {\n" + body + "}\n")
    rt = ("class ChatGptOAuthRedTeamContractTest {\n"
          + ('    @Test @Disabled("x")\n' if disabled else "    @Test\n")
          + "    void bareJwtNeverSurvivesRedaction() {}\n"
          + "    @Test\n    void bareRefreshTokenNeverSurvivesRedaction() {}\n}\n")
    write(root / REDTEAM, rt)
    write(root / "main/resources/static/js/chat.js", "// fixture\n")
    write(root / "src/test/js/chat-stream-boundaries.test.cjs", "// fixture\n")
    return root


def run_verifier(root, task=None):
    cmd = [sys.executable, "-B", str(SCRIPT), "--root", str(root)]
    if task:
        cmd += ["--task", task]
    cp = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    return cp.returncode, (json.loads(cp.stdout) if cp.stdout.strip().startswith("{") else cp.stdout)


class VerifyCodexR51Test(unittest.TestCase):
    def test_pass(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(build_root(t))
            self.assertEqual(0, code, out)
            self.assertTrue(out["allPass"])
            self.assertEqual("PASS", out["checks"]["junit_totals"]["verdict"])

    def test_fail_injection(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(build_root(t, injection=True))
            self.assertEqual(1, code)
            self.assertEqual("FAIL", out["checks"]["a1_no_reflection_injection"]["verdict"])

    def test_fail_disabled_remains(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(build_root(t, disabled=True))
            self.assertEqual(1, code)
            self.assertEqual("FAIL", out["checks"]["a4_redteam_unpinned"]["verdict"])

    def test_fail_out_of_scope_and_schema(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(build_root(
                t, out_of_scope=["main/resources/static/js/chat.js",
                                 "db/migration/V9__oauth.sql"]))
            self.assertEqual(1, code)
            self.assertEqual("FAIL", out["checks"]["scope_containment"]["verdict"])
            self.assertEqual("FAIL", out["checks"]["protected_files"]["verdict"])

    def test_fail_fallback_drift(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(build_root(t, fallback_changed=True))
            self.assertEqual(1, code)
            self.assertEqual("FAIL", out["checks"]["f4_fallback_unchanged"]["verdict"])

    def test_fail_redteam_skipped(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(build_root(t, redteam_skipped=2))
            self.assertEqual(1, code)
            self.assertEqual("FAIL", out["checks"]["junit_totals"]["verdict"])

    def test_no_input(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(Path(t))
            self.assertEqual(2, code)

    def test_notrun_no_xml(self):
        with tempfile.TemporaryDirectory() as t:
            code, out = run_verifier(build_root(t, xmls=False))
            self.assertEqual(1, code)
            self.assertEqual("NOT_RUN", out["checks"]["junit_totals"]["verdict"])


if __name__ == "__main__":
    unittest.main()
