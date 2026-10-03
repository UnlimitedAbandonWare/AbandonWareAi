#!/usr/bin/env python3
"""Tests for agy_websearch_hook.py and agy_websearch_install.py.

Contract: DEMO1-DEVIN-AGY-WEBSEARCH-DEFAULT-20261002.
Uses temp dirs and fake transcripts only; real ~/.gemini is never touched.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOOK = ROOT / "scripts" / "agy_websearch_hook.py"
INSTALLER = ROOT / "scripts" / "agy_websearch_install.py"
PY = sys.executable

MARK_BEGIN = "<!-- AWX-WEBSEARCH-DEFAULT:BEGIN"
UAW_MARK_BEGIN = "<!-- AWX-UAW-WEB:BEGIN"
UAW_HOOK_NAME = "awx-uaw-citation-gate"
ALLOW_ENTRY = "read_url(*)"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_hook(payload: bytes, timeout: float = 10.0) -> subprocess.CompletedProcess:
    return subprocess.run(
        [PY, "-B", str(HOOK)], input=payload,
        capture_output=True, timeout=timeout,
    )


def run_stop_hook(payload: bytes, state_dir: Path,
                  timeout: float = 10.0) -> subprocess.CompletedProcess:
    return subprocess.run(
        [PY, "-B", str(HOOK), "--event", "stop",
         "--state-dir", str(state_dir)],
        input=payload, capture_output=True, timeout=timeout,
    )


def write_transcript(tmpdir, steps) -> Path:
    path = Path(tmpdir) / "transcript.jsonl"
    path.write_text(
        "".join(json.dumps(s, ensure_ascii=False) + "\n" for s in steps),
        encoding="utf-8",
    )
    return path


def user(text):
    return {"type": "USER_INPUT", "content": text, "step_index": 0}


SEARCH = {"type": "GENERIC", "step_index": 1,
          "tool_calls": [{"name": "search_web", "args": {"query": "q"}}]}
SEARCH_STEP = {"type": "SEARCH_WEB", "step_index": 1, "content": "results"}
PLAN = {"type": "PLANNER_RESPONSE", "step_index": 2, "content": "draft"}


def answer(text, idx=3):
    return {"type": "PLANNER_RESPONSE", "step_index": idx, "content": text}


GOOD_TAIL = ("근거 문단 [1].\n출처: [1] 공식 문서 (2026-01-01, T1, 본문확인)\n"
             "웹: W1_AUTH · 검색 3회 · 본문확인 1 · 출처 2(T1 1) · 모순 없음")


def make_home(tmpdir, hooks_json=None) -> Path:
    home = Path(tmpdir) / "home"
    gem = home / ".gemini"
    (gem / "antigravity-cli").mkdir(parents=True)
    (gem / "config").mkdir(parents=True)
    (gem / "GEMINI.md").write_text("# global rules\n\n- existing line\n",
                                  encoding="utf-8")
    (gem / "antigravity-cli" / "settings.json").write_text(
        json.dumps({
            "agentMode": "accept-edits",
            "permissions": {
                "allow": ["command(regex:.*)"],
                "deny": ["write_file(/Users/nninn/.gemini/antigravity-cli/settings.json)"],
            },
            "trustedWorkspaces": ["C:\\AbandonWare\\demo-1\\demo-1\\src"],
        }, indent=2),
        encoding="utf-8",
    )
    if hooks_json is not None:
        (gem / "config" / "hooks.json").write_text(hooks_json, encoding="utf-8")
    return home


def run_installer(home: Path, *args) -> subprocess.CompletedProcess:
    return subprocess.run(
        [PY, "-B", str(INSTALLER), "--home", str(home), "--repo-root", str(ROOT),
         *args],
        capture_output=True, timeout=60,
    )


class HookDecisionTests(unittest.TestCase):
    """DV6 items 1-7: hook behaviour contract."""

    def test_1_inject_when_no_search_after_user(self):
        with tempfile.TemporaryDirectory() as td:
            t = write_transcript(td, [user("최신 버전 알려줘")])
            out = run_hook(json.dumps({"transcriptPath": str(t),
                                       "invocationNum": 1}).encode("utf-8"))
            self.assertEqual(0, out.returncode)
            body = json.loads(out.stdout.decode("utf-8"))
            steps = body.get("injectSteps", [])
            self.assertEqual(1, len(steps))
            self.assertIn("ephemeralMessage", steps[0])

    def test_2_silent_when_search_done_after_user(self):
        with tempfile.TemporaryDirectory() as td:
            t = write_transcript(td, [user("알려줘"), SEARCH, PLAN])
            out = run_hook(json.dumps({"transcriptPath": str(t)}).encode("utf-8"))
            self.assertEqual(0, out.returncode)
            self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))

    def test_2b_new_turn_reinjects_after_older_search(self):
        with tempfile.TemporaryDirectory() as td:
            t = write_transcript(td, [user("먼저"), SEARCH, user("다른 질문")])
            out = run_hook(json.dumps({"transcriptPath": str(t)}).encode("utf-8"))
            body = json.loads(out.stdout.decode("utf-8"))
            self.assertEqual(1, len(body.get("injectSteps", [])))

    def test_3_no_web_flag(self):
        with tempfile.TemporaryDirectory() as td:
            t = write_transcript(td, [user("[no-web] 1+1은?")])
            out = run_hook(json.dumps({"transcriptPath": str(t)}).encode("utf-8"))
            self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))

    def test_4_korean_no_web_flag(self):
        with tempfile.TemporaryDirectory() as td:
            t = write_transcript(td, [user("웹서치 없이 답해줘")])
            out = run_hook(json.dumps({"transcriptPath": str(t)}).encode("utf-8"))
            self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))

    def test_5_broken_stdin_still_empty_ok(self):
        out = run_hook(b"{not valid json")
        self.assertEqual(0, out.returncode)
        self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))

    def test_6_missing_transcript_injects(self):
        out = run_hook(json.dumps({"conversationId": "x"}).encode("utf-8"))
        self.assertEqual(
            1, len(json.loads(out.stdout.decode("utf-8")).get("injectSteps", [])))
        with tempfile.TemporaryDirectory() as td:
            t = Path(td) / "absent.jsonl"
            out = run_hook(
                json.dumps({"transcriptPath": str(t)}).encode("utf-8"))
            self.assertEqual(
                1, len(json.loads(out.stdout.decode("utf-8"))
                       .get("injectSteps", [])))

    def test_7_fast_and_utf8_output(self):
        sys.path.insert(0, str(ROOT / "scripts"))
        try:
            import agy_websearch_hook as hook
        finally:
            sys.path.pop(0)
        with tempfile.TemporaryDirectory() as td:
            t = write_transcript(td, [user("질문")] * 3 + [PLAN])
            payload = {"transcriptPath": str(t)}
            start = time.monotonic()
            decision = hook.decide(payload)
            elapsed_ms = (time.monotonic() - start) * 1000
        self.assertEqual("inject", decision)
        self.assertLess(elapsed_ms, 250.0)
        out = run_hook(json.dumps(payload).encode("utf-8"))
        text = out.stdout.decode("utf-8")  # Korean must not be mojibake
        self.assertIn("웹서치", text)
        self.assertIn("출처:", text)


class InstallerTests(unittest.TestCase):
    """DV6 items 8-10: installer idempotency and uninstall round-trip."""

    def test_8_double_apply_idempotent(self):
        with tempfile.TemporaryDirectory() as td:
            home = make_home(td)
            for _ in range(2):
                out = run_installer(home, "--apply")
                self.assertEqual(0, out.returncode, out.stderr.decode("utf-8", "replace"))
            gemini = (home / ".gemini" / "GEMINI.md").read_text(encoding="utf-8")
            self.assertEqual(1, gemini.count(MARK_BEGIN))
            self.assertTrue(gemini.startswith("# global rules"))
            hooks = json.loads(
                (home / ".gemini" / "config" / "hooks.json").read_text(encoding="utf-8"))
            self.assertEqual(["awx-websearch-default"], sorted(hooks))
            settings_text = (
                home / ".gemini" / "antigravity-cli" / "settings.json"
            ).read_text(encoding="utf-8")
            self.assertEqual(1, settings_text.count(ALLOW_ENTRY))
            deploy = home / ".gemini" / "config" / "hooks" / "agy_websearch_hook.py"
            self.assertTrue(deploy.is_file())
            self.assertEqual(sha256(HOOK), sha256(deploy))
            # second apply must not stack backups
            baks = list((home / ".gemini").glob("*.bak-20261002-websearch*"))
            self.assertEqual(1, len(baks))

    def test_9_uninstall_restores_preinstall_state(self):
        with tempfile.TemporaryDirectory() as td:
            home = make_home(td)
            gemini = home / ".gemini" / "GEMINI.md"
            settings = home / ".gemini" / "antigravity-cli" / "settings.json"
            hooks_json = home / ".gemini" / "config" / "hooks.json"
            deploy = home / ".gemini" / "config" / "hooks" / "agy_websearch_hook.py"
            before = {p: sha256(p) for p in (gemini, settings)}
            self.assertFalse(hooks_json.exists())
            self.assertEqual(0, run_installer(home, "--apply").returncode)
            out = run_installer(home, "--uninstall")
            self.assertEqual(0, out.returncode, out.stderr.decode("utf-8", "replace"))
            for path, sha in before.items():
                self.assertEqual(sha, sha256(path), path.name)
            self.assertFalse(hooks_json.exists())
            self.assertFalse(deploy.exists())

    def test_10_foreign_hook_keys_preserved(self):
        foreign = {"other-hook": {"PostToolUse": [{"matcher": "run_command",
                                                 "hooks": [{"command": "x"}]}]}}
        with tempfile.TemporaryDirectory() as td:
            home = make_home(td, hooks_json=json.dumps(foreign, indent=2) + "\n")
            hooks_json = home / ".gemini" / "config" / "hooks.json"
            original_sha = sha256(hooks_json)
            self.assertEqual(0, run_installer(home, "--apply").returncode)
            merged = json.loads(hooks_json.read_text(encoding="utf-8"))
            self.assertIn("other-hook", merged)
            self.assertIn("awx-websearch-default", merged)
            self.assertEqual(0, run_installer(home, "--uninstall").returncode)
            self.assertEqual(original_sha, sha256(hooks_json))

    def test_check_reports_three_layers(self):
        with tempfile.TemporaryDirectory() as td:
            home = make_home(td)
            out = run_installer(home, "--check")
            self.assertEqual(0, out.returncode)
            text = out.stdout.decode("utf-8")
            self.assertIn("RULE OFF", text)
            self.assertIn("HOOK OFF", text)
            self.assertIn("ALLOW OFF", text)
            run_installer(home, "--apply")
            text = run_installer(home, "--check").stdout.decode("utf-8")
            self.assertIn("RULE ON", text)
            self.assertIn("HOOK ON", text)
            self.assertIn("ALLOW ON", text)


class StopGateTests(unittest.TestCase):
    """DV5 items 11-15: Stop 이벤트 인용 게이트 (턴당 continue ≤1)."""

    def test_11_search_without_citation_continues_once(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "state"
            t = write_transcript(td, [user("질문"), SEARCH_STEP,
                                      answer("근거 없는 답")])
            payload = json.dumps({"transcriptPath": str(t),
                                  "conversationId": "c1",
                                  "terminationReason": "model_stop",
                                  "executionNum": 1}).encode("utf-8")
            out = run_stop_hook(payload, state)
            self.assertEqual(0, out.returncode)
            body = json.loads(out.stdout.decode("utf-8"))
            self.assertEqual("continue", body.get("decision"))
            self.assertIn("출처:", body.get("reason", ""))

    def test_12_circuit_breaker_same_turn_silent(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "state"
            t = write_transcript(td, [user("질문"), SEARCH_STEP,
                                      answer("아직 출처 없음")])
            payload = json.dumps({"transcriptPath": str(t),
                                  "conversationId": "c1",
                                  "terminationReason": "model_stop"}
                                 ).encode("utf-8")
            first = json.loads(run_stop_hook(payload, state).stdout)
            self.assertEqual("continue", first.get("decision"))
            second = json.loads(run_stop_hook(payload, state).stdout)
            self.assertEqual({}, second)

    def test_13_citation_and_breadcrumb_silent(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "state"
            t = write_transcript(td, [user("질문"), SEARCH_STEP,
                                      answer(GOOD_TAIL)])
            out = run_stop_hook(json.dumps(
                {"transcriptPath": str(t), "conversationId": "c2",
                 "terminationReason": "model_stop"}).encode("utf-8"), state)
            self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))

    def test_14_no_web_turn_silent(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "state"
            t = write_transcript(td, [user("[no-web] 질문"),
                                      answer("답")])
            out = run_stop_hook(json.dumps(
                {"transcriptPath": str(t), "conversationId": "c3"}).encode(),
                state)
            self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))

    def test_15_broken_stdin_still_empty_ok(self):
        with tempfile.TemporaryDirectory() as td:
            out = run_stop_hook(b"{broken", Path(td) / "state")
            self.assertEqual(0, out.returncode)
            self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))

    def test_15b_no_search_turn_silent(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "state"
            t = write_transcript(td, [user("질문"), answer("로컬 답")])
            out = run_stop_hook(json.dumps(
                {"transcriptPath": str(t), "conversationId": "c4"}).encode(),
                state)
            self.assertEqual({}, json.loads(out.stdout.decode("utf-8")))


class UawProfileTests(unittest.TestCase):
    """DV5 items 16-17: --profile uaw 적용/제거 왕복."""

    def test_16_uaw_apply_twice_and_uninstall_keeps_base(self):
        with tempfile.TemporaryDirectory() as td:
            home = make_home(td)
            self.assertEqual(0, run_installer(home, "--apply").returncode)
            gemini = home / ".gemini" / "GEMINI.md"
            base_sha = sha256(gemini)
            skill_src = (ROOT / ".agents" / "skills" /
                         "awx-uaw-web-research" / "SKILL.md")
            for _ in range(2):
                out = run_installer(home, "--apply", "--profile", "uaw")
                self.assertEqual(0, out.returncode,
                                 out.stderr.decode("utf-8", "replace"))
            text = gemini.read_text(encoding="utf-8")
            self.assertEqual(1, text.count(UAW_MARK_BEGIN))
            self.assertEqual(1, text.count(MARK_BEGIN))
            hooks = json.loads(
                (home / ".gemini" / "config" / "hooks.json")
                .read_text(encoding="utf-8"))
            self.assertIn(UAW_HOOK_NAME, hooks)
            self.assertIn("awx-websearch-default", hooks)
            self.assertIn("--event stop",
                          hooks[UAW_HOOK_NAME]["Stop"][0]["command"])
            # 6층 check 표
            layers = run_installer(home, "--check").stdout.decode("utf-8")
            for layer in ("RULE", "HOOK", "ALLOW", "UAW_RULE",
                          "HOOK_STOP", "SKILL"):
                self.assertIn(layer, layers)
            # uaw uninstall → base만 남는다
            out = run_installer(home, "--uninstall", "--profile", "uaw")
            self.assertEqual(0, out.returncode,
                             out.stderr.decode("utf-8", "replace"))
            text = gemini.read_text(encoding="utf-8")
            self.assertNotIn(UAW_MARK_BEGIN, text)
            self.assertIn(MARK_BEGIN, text)
            self.assertEqual(base_sha, sha256(gemini))
            hooks = json.loads(
                (home / ".gemini" / "config" / "hooks.json")
                .read_text(encoding="utf-8"))
            self.assertNotIn(UAW_HOOK_NAME, hooks)
            self.assertIn("awx-websearch-default", hooks)
            self.assertFalse(
                (home / ".gemini" / "antigravity-cli" / "skills"
                 / "awx-uaw-web-research" / "SKILL.md").exists())

    def test_17_foreign_hooks_keys_preserved_uaw(self):
        foreign = {"other-hook": {"PostToolUse": [{"matcher": "run_command",
                                                 "hooks": [{"command": "x"}]}]}}
        with tempfile.TemporaryDirectory() as td:
            home = make_home(td, hooks_json=json.dumps(foreign, indent=2) + "\n")
            hooks_json = home / ".gemini" / "config" / "hooks.json"
            run_installer(home, "--apply", "--profile", "uaw")
            merged = json.loads(hooks_json.read_text(encoding="utf-8"))
            self.assertIn("other-hook", merged)
            self.assertIn(UAW_HOOK_NAME, merged)
            run_installer(home, "--uninstall", "--profile", "uaw")
            merged = json.loads(hooks_json.read_text(encoding="utf-8"))
            self.assertIn("other-hook", merged)
            self.assertNotIn(UAW_HOOK_NAME, merged)
            # --apply --profile uaw 는 base를 보장하므로 base 키는 남는다
            self.assertIn("awx-websearch-default", merged)


if __name__ == "__main__":
    unittest.main(verbosity=2)
