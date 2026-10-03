#!/usr/bin/env python3
"""Fixture-only contract tests for scripts/test_model_policy.py and the two
runner --model-purpose options. Zero live server calls; catalogs come from
JSON fixtures written under a temp dir.

Run: python -B scripts/test_test_model_policy.py
Exit 0 when every case passes.
"""
from __future__ import annotations

import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "test_model_policy.py"
PROBE = ROOT / "scripts" / "main_chat_target_probe.py"
PHASE2 = ROOT / "scripts" / "phase2_full_app_browser_tests.js"

PRE = "2026-10-02T12:00:00+09:00"      # before the 2026-12-30 KST cutoff
EDGE_IN = "2026-12-30T23:59:00+09:00"  # still api-first
POST = "2026-12-31T00:00:00+09:00"    # dynamic mode


def entry(mid, provider, status, selectable, model_id=None, reason=""):
    return {
        "id": mid,
        "provider": provider,
        "endpointId": "local-default" if provider == "Ollama" else mid.split(":")[0],
        "modelId": model_id or mid.split(":")[-1],
        "status": status,
        "selectable": selectable,
        "reason": reason,
        "release": "unknown",
        "evidence": "fixture",
    }


def catalog(*, astra_ok=True, oauth_ok=True, api3_ok=True, local_ok=True):
    rows = []
    if local_ok:
        rows += [
            entry("gemma4:26b", "Ollama", "installed", True),
            entry("qwen3.5:9b", "Ollama", "installed", True),
        ]
    rows.append(entry("llmrouter.api3", "groq",
                      "configured" if api3_ok else "unavailable",
                      api3_ok, model_id="openai/gpt-oss-120b",
                      reason="" if api3_ok else "remote_selection_disabled"))
    for mid in ("gpt-6-astra", "gpt-reserve", "gpt-5.6-sol", "gpt-5.6-terra",
                "gpt-5.6-luna", "gpt-5.5", "codex-auto-review"):
        ok = oauth_ok and (mid != "gpt-6-astra" or astra_ok)
        rows.append(entry(f"chatgpt-oauth:{mid}", "chatgpt_oauth",
                          "configured" if ok else "unavailable", ok,
                          model_id=mid, reason="" if ok else "provider_error"))
    return rows


class Cli:
    """Subprocess wrapper: one JSON object on stdout; exit code captured."""

    def __init__(self, tmp: Path):
        self.tmp = tmp
        self.catalog_path = tmp / "catalog.json"
        self.usage = tmp / "usage.jsonl"

    def write_catalog(self, rows):
        self.catalog_path.write_text(json.dumps(rows), encoding="utf-8")
        return self.catalog_path

    def run(self, *args, now=PRE):
        cmd = [sys.executable, "-B", str(TOOL), *args,
               "--catalog", str(self.catalog_path),
               "--usage-log", str(self.usage)]
        if now is not None and args[0] in ("resolve", "check", "plan"):
            cmd += ["--now", now]
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        out = subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT,
                             encoding="utf-8", errors="replace", env=env)
        line = out.stdout.strip().splitlines()
        payload = json.loads(line[-1]) if line else {}
        return out.returncode, payload, out


class ResolveTest(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.cli = Cli(Path(self._td.name))

    def tearDown(self):
        self._td.cleanup()

    # 1. quality before cutoff -> strongest oauth model
    def test_01_quality_first_rank(self):
        self.cli.write_catalog(catalog())
        rc, out, _ = self.cli.run("resolve", "--purpose", "quality")
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["verdict"], "RESOLVED")
        self.assertEqual(out["mode"], "api_first")
        self.assertEqual(out["selected"], "chatgpt-oauth:gpt-6-astra")

    # 2. first rank not selectable -> next rank
    def test_02_astra_unavailable_falls_to_sol(self):
        self.cli.write_catalog(catalog(astra_ok=False))
        rc, out, _ = self.cli.run("resolve", "--purpose", "quality")
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["selected"], "chatgpt-oauth:gpt-5.6-sol")

    # 3. no API candidate before cutoff -> BLOCKED_API, never a local pick
    def test_03_all_api_down_blocked_not_local(self):
        self.cli.write_catalog(catalog(oauth_ok=False, api3_ok=False))
        rc, out, _ = self.cli.run("resolve", "--purpose", "quality")
        self.assertEqual(rc, 6, out)
        self.assertEqual(out["verdict"], "BLOCKED_API")
        self.assertIsNone(out["selected"])
        chosen_lanes = [c["lane"] for c in out["candidates"] if c.get("chosen")]
        self.assertNotIn("local", chosen_lanes)

    # 4. cutoff edge inclusive
    def test_04_edge_still_api_first(self):
        self.cli.write_catalog(catalog())
        rc, out, _ = self.cli.run("resolve", "--purpose", "smoke", now=EDGE_IN)
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["mode"], "api_first")
        self.assertTrue(out["selected"].startswith("chatgpt-oauth:"))

    # 5. after cutoff -> dynamic, local lane becomes legal
    def test_05_after_cutoff_dynamic_allows_local(self):
        self.cli.write_catalog(catalog(oauth_ok=False, api3_ok=False))
        rc, out, _ = self.cli.run("resolve", "--purpose", "quality", now=POST)
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["mode"], "dynamic")
        self.assertEqual(out["selected"], "gemma4:26b")

    # 6. explicit local_fallback purpose -> local allowed even before cutoff
    def test_06_local_fallback_before_cutoff(self):
        self.cli.write_catalog(catalog())
        rc, out, _ = self.cli.run("resolve", "--purpose", "local_fallback")
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["selected"], "gemma4:26b")


class CheckTest(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.cli = Cli(Path(self._td.name))
        self.cli.write_catalog(catalog())

    def tearDown(self):
        self._td.cleanup()

    # 7. selected==observed OK; mismatch SILENT_FALLBACK; pre-cutoff local LOCAL_BEFORE_CUTOFF
    def test_07_check_verdicts(self):
        rc, out, _ = self.cli.run("check", "--selected", "chatgpt-oauth:gpt-5.6-luna",
                                  "--observed", "chatgpt-oauth:gpt-5.6-luna")
        self.assertEqual((rc, out["verdict"]), (0, "OK"))
        rc, out, _ = self.cli.run("check", "--selected", "chatgpt-oauth:gpt-5.6-luna",
                                  "--observed", "gpt-5.6-luna")
        self.assertEqual((rc, out["verdict"]), (0, "OK"))
        rc, out, _ = self.cli.run("check", "--selected", "chatgpt-oauth:gpt-5.6-luna",
                                  "--observed", "chatgpt-oauth:gpt-5.5")
        self.assertEqual((rc, out["verdict"]), (1, "SILENT_FALLBACK"))
        rc, out, _ = self.cli.run("check", "--selected", "chatgpt-oauth:gpt-5.6-luna",
                                  "--observed", "gemma4:26b")
        self.assertEqual((rc, out["verdict"]), (1, "LOCAL_BEFORE_CUTOFF"))

    # 7b. no model answered (e.g. send failed) is UNOBSERVED, not a fallback
    def test_07b_unobserved(self):
        for empty in ("", "null", "none", "null"):
            rc, out, _ = self.cli.run("check", "--selected",
                                      "chatgpt-oauth:gpt-5.6-luna",
                                      "--observed", empty)
            self.assertEqual((rc, out["verdict"]), (1, "UNOBSERVED"), empty)

    # 7c. a bare api modelId observed is still the api lane (mismatch =
    #     SILENT_FALLBACK, never LOCAL_BEFORE_CUTOFF)
    def test_07c_bare_api_observed(self):
        rc, out, _ = self.cli.run("check", "--selected",
                                  "chatgpt-oauth:gpt-5.6-luna",
                                  "--observed", "gpt-5.5")
        self.assertEqual((rc, out["verdict"]), (1, "SILENT_FALLBACK"))


class UsageAndBudgetTest(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.cli = Cli(Path(self._td.name))
        self.cli.write_catalog(catalog())

    def tearDown(self):
        self._td.cleanup()

    # 8. a recorded 429 excludes that model on the next resolve (same purpose)
    def test_08_429_record_excludes_next_resolve(self):
        rc, _, _ = self.cli.run("record", "--agent", "devin", "--purpose", "quality",
                                "--model", "chatgpt-oauth:gpt-6-astra", "--code", "429",
                                "--run", "t1")
        self.assertEqual(rc, 0)
        rc, out, _ = self.cli.run("resolve", "--purpose", "quality")
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["selected"], "chatgpt-oauth:gpt-5.6-sol")
        rc, out, _ = self.cli.run("resolve", "--purpose", "smoke")
        self.assertEqual(out["selected"], "chatgpt-oauth:gpt-5.6-luna")

    # 9. record schema carries no prompt/answer/question text fields
    def test_09_record_schema_minimal(self):
        rc, _, _ = self.cli.run("record", "--agent", "devin", "--purpose", "smoke",
                                "--model", "chatgpt-oauth:gpt-5.6-luna", "--code", "200",
                                "--run", "t9")
        self.assertEqual(rc, 0)
        line = self.cli.usage.read_text(encoding="utf-8").strip()
        row = json.loads(line)
        allowed = {"ts", "agent", "purpose", "model", "code", "run", "kind", "tool"}
        self.assertTrue(set(row).issubset(allowed), set(row) - allowed)
        for banned in ("prompt", "question", "answer", "message", "text", "token", "key"):
            self.assertNotIn(banned, row)

    # 10. budget cap 25/run
    def test_10_budget_cap(self):
        for i in range(25):
            self.cli.run("record", "--agent", "devin", "--purpose", "regression",
                         "--model", "chatgpt-oauth:gpt-5.6-terra", "--code", "200",
                         "--run", "t10")
        rc, out, _ = self.cli.run("budget", "--run", "t10")
        self.assertEqual(out["verdict"], "BUDGET_EXCEEDED")
        self.assertEqual(out["used"], 25)
        rc, out, _ = self.cli.run("budget", "--run", "other")
        self.assertEqual(out["verdict"], "OK")


class RunnerContractTest(unittest.TestCase):
    """11. without --model-purpose the runners behave exactly as before."""

    def test_11_probe_default_unchanged(self):
        spec = importlib.util.spec_from_file_location("probe", PROBE)
        probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe)
        calls = []
        probe.probe = lambda name, url: {
            "tool": "main_chat_target_probe", "target": name, "url": url,
            "status": 200, "title": "t", "chatUiMarkersFound": 3,
            "chatJsRef": True, "interviewDemoMarker": False, "verdict": "MAIN_OK"}
        probe.model_policy_step = lambda *a, **k: calls.append((a, k)) or {"purpose": "smoke"}
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = probe.main(["--local"])
        row = json.loads(buf.getvalue().strip())
        self.assertEqual(rc, 0)
        self.assertEqual(row["verdict"], "MAIN_OK")
        self.assertNotIn("modelPolicy", row)
        self.assertEqual(calls, [])

    def test_11b_probe_flag_invokes_step(self):
        spec = importlib.util.spec_from_file_location("probe", PROBE)
        probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe)
        calls = []
        probe.probe = lambda name, url: {
            "tool": "main_chat_target_probe", "target": name, "url": url,
            "status": 200, "verdict": "MAIN_OK"}
        probe.model_policy_step = lambda *a, **k: calls.append((a, k)) or {"verdict": "RESOLVED"}
        buf = io.StringIO()
        with redirect_stdout(buf):
            probe.main(["--local", "--model-purpose", "smoke", "--no-send"])
        row = json.loads(buf.getvalue().strip())
        self.assertIn("modelPolicy", row)
        self.assertEqual(len(calls), 1)

    def test_11c_phase2_argparse_default(self):
        if shutil.which("node") is None:
            self.skipTest("node not on PATH")
        code = (
            "const m=require(process.argv[1]);"
            "const a=m.parseArgs(['http://127.0.0.1:18180']);"
            "const b=m.parseArgs(['http://127.0.0.1:18180','--model-purpose','smoke']);"
            "process.stdout.write(JSON.stringify([a,b]));")
        out = subprocess.run(["node", "-e", code, str(PHASE2)],
                             capture_output=True, text=True, cwd=ROOT)
        self.assertEqual(out.returncode, 0, out.stderr)
        a, b = json.loads(out.stdout)
        self.assertEqual(a["origin"], "http://127.0.0.1:18180")
        self.assertIsNone(a["modelPurpose"])
        self.assertEqual(b["modelPurpose"], "smoke")


class NeverSelectTest(unittest.TestCase):
    # 12. codex-auto-review is never picked for any purpose
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.cli = Cli(Path(self._td.name))

    def tearDown(self):
        self._td.cleanup()

    def test_12_codex_auto_review_never_selected(self):
        rows = catalog(oauth_ok=False)
        rows = [r if r["id"] != "chatgpt-oauth:codex-auto-review" else
                entry("chatgpt-oauth:codex-auto-review", "chatgpt_oauth",
                      "configured", True, model_id="codex-auto-review")
                for r in rows]
        rows = [r for r in rows if r["provider"] != "Ollama" and r["id"] != "llmrouter.api3"]
        self.cli.write_catalog(rows)
        for purpose in ("quality", "regression", "smoke", "cross_provider"):
            rc, out, _ = self.cli.run("resolve", "--purpose", purpose)
            self.assertNotEqual(out.get("selected"), "chatgpt-oauth:codex-auto-review")
            if purpose != "cross_provider":
                self.assertEqual(rc, 6, (purpose, out))
                self.assertEqual(out["verdict"], "BLOCKED_API")


class PracticePolicyTest(unittest.TestCase):
    """13-18. Correction scope: practice_* purposes, auto classification,
    per-question re-resolve with newConversation flags, dynamic mode, and
    the public-URL guard on the practice runner."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.cli = Cli(Path(self._td.name))
        self.cli.write_catalog(catalog())

    def tearDown(self):
        self._td.cleanup()

    def _write(self, name, text):
        p = self.cli.tmp / name
        p.write_text(text, encoding="utf-8")
        return p

    # 13. short greeting -> practice_chat -> luna
    def test_13_greeting_practice_chat(self):
        f = self._write("hi.txt", "안녕! 잘 지내?")
        rc, out, _ = self.cli.run("resolve", "--purpose", "auto",
                                  "--prompt-file", str(f))
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["classifiedPurpose"], "practice_chat")
        self.assertEqual(out["selected"], "chatgpt-oauth:gpt-5.6-luna")
        self.assertTrue(out["autoReason"].startswith("auto→practice_chat"))

    # 14. code block + long question -> practice_reasoning -> sol
    def test_14_code_long_practice_reasoning(self):
        f = self._write("code.txt", "```python\ndef f(x):\n    return x\n```\n"
                        + "설명해줘 " + "가" * 900)
        rc, out, _ = self.cli.run("resolve", "--purpose", "auto",
                                  "--prompt-file", str(f))
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["classifiedPurpose"], "practice_reasoning")
        self.assertEqual(out["selected"], "chatgpt-oauth:gpt-5.6-sol")

    # 15. evidence keywords + attachment -> quality
    def test_15_evidence_attachment_quality(self):
        f = self._write("ev.txt", "첨부한 문서의 근거와 출처를 정리해줘")
        rc, out, _ = self.cli.run("resolve", "--purpose", "auto",
                                  "--prompt-file", str(f), "--has-attachment")
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["classifiedPurpose"], "quality")
        self.assertEqual(out["selected"], "chatgpt-oauth:gpt-6-astra")

    # 16. per-question re-pick; model change -> newConversation flag
    def test_16_plan_per_question_models(self):
        prompts = [
            {"text": "안녕!", "attachment": False},
            {"text": "오늘 점심 뭐 먹을까?", "attachment": False},
            {"text": "```js\nfunction x(){}\n```\n설명 " + "가" * 900,
             "attachment": False},
            {"text": "이 문서의 출처와 근거", "attachment": True},
        ]
        f = self._write("prompts.json", json.dumps(prompts, ensure_ascii=False))
        rc, out, _ = self.cli.run("plan", "--prompts-file", str(f))
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["verdict"], "PLANNED")
        purposes = [i["purpose"] for i in out["items"]]
        self.assertEqual(purposes, ["practice_chat", "practice_chat",
                                    "practice_reasoning", "quality"])
        models = [i["selected"] for i in out["items"]]
        self.assertEqual(models, ["chatgpt-oauth:gpt-5.6-luna"] * 2 +
                         ["chatgpt-oauth:gpt-5.6-sol", "chatgpt-oauth:gpt-6-astra"])
        flags = [i["newConversation"] for i in out["items"]]
        self.assertEqual(flags, [True, False, True, True])

    # 17. after cutoff + practice prompt -> dynamic mode, local candidates allowed
    def test_17_practice_dynamic_after_cutoff(self):
        f = self._write("hi.txt", "안녕! 잘 지내?")
        rc, out, _ = self.cli.run("resolve", "--purpose", "auto",
                                  "--prompt-file", str(f), now=POST)
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["mode"], "dynamic")
        self.assertEqual(out["classifiedPurpose"], "practice_chat")
        locals_ok = [c for c in out["candidates"]
                     if c["lane"] == "local" and c.get("available")]
        self.assertTrue(locals_ok, out["candidates"])

    # 18. practice runner refuses a non-loopback URL unless --public is given
    def test_18_public_url_requires_flag(self):
        if shutil.which("node") is None:
            self.skipTest("node not on PATH")
        runner = ROOT / "scripts" / "chat_practice_browser.js"
        code = (
            "const m=require(process.argv[1]);"
            "let threw=null;try{m.parseArgs(['--prompts','p.json','--base','https://abandonwareai.kro.kr']);}"
            "catch(e){threw=e.message;}"
            "const ok=m.parseArgs(['--prompts','p.json','--base','https://abandonwareai.kro.kr','--public']);"
            "const loc=m.parseArgs(['--prompts','p.json']);"
            "process.stdout.write(JSON.stringify({threw,pubMax:ok.maxCalls,base:loc.base}));")
        out = subprocess.run(["node", "-e", code, str(runner)],
                             capture_output=True, text=True, cwd=ROOT)
        self.assertEqual(out.returncode, 0, out.stderr)
        res = json.loads(out.stdout)
        self.assertEqual(res["threw"], "PUBLIC_REQUIRES_FLAG")
        self.assertEqual(res["pubMax"], 3)
        self.assertEqual(res["base"], "http://127.0.0.1:18180")


class SpendGateEnvTest(unittest.TestCase):
    """19-21. AWX_AGENT_ALLOW_PAID_MODELS is a kill switch, not an opt-in
    (SSOT configs/agent-api-spend-guard.yaml paid_default_on: true):
    env unset/other -> paid allowed; explicit false values -> blocked."""

    KILL = ROOT / "scripts" / "apikit" / "providers" / "openai.py"
    GUARD = ROOT / "scripts" / "agent_api_spend_guard.ps1"

    def _openai(self):
        apikit_dir = str(self.KILL.parent.parent)
        if apikit_dir not in sys.path:
            sys.path.insert(0, apikit_dir)
        spec = importlib.util.spec_from_file_location("apikit_openai", self.KILL)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def _ctx(self):
        return {"secrets": {"OPENAI_API_KEY": "sk-fixture"},
                "scopes": {"Process": {}, "User": {}, "Machine": {}},
                "timeout": 1, "secret_values": (), "paid": True,
                "openai_models": ["gpt-5.6-mini-x"]}

    # 19. env unset -> paid call proceeds to HTTP (stubbed)
    def test_19_env_unset_paid_allowed(self):
        mod = self._openai()
        saved = os.environ.pop("AWX_AGENT_ALLOW_PAID_MODELS", None)
        calls = []
        orig = mod.common.http_request
        try:
            mod.common.http_request = lambda *a, **k: calls.append(a) or {
                "status": 200, "headers": {}, "ms": 1, "error": None,
                "error_kind": None, "text": json.dumps({"id": "r1"})}
            self.assertFalse(mod._paid_kill_switched())
            row = mod.call(self._ctx())[0]
            self.assertTrue(calls, "paid call must reach HTTP when env unset")
            self.assertEqual(row["cls"], mod.common.OK, row["detail"])
        finally:
            mod.common.http_request = orig
            if saved is not None:
                os.environ["AWX_AGENT_ALLOW_PAID_MODELS"] = saved

    # 20. explicit false values -> refused before any HTTP
    def test_20_env_false_values_paid_blocked(self):
        mod = self._openai()
        saved = os.environ.pop("AWX_AGENT_ALLOW_PAID_MODELS", None)
        calls = []
        orig = mod.common.http_request
        try:
            mod.common.http_request = lambda *a, **k: calls.append(a) or {}
            for val in ("0", "false", "no", "off"):
                os.environ["AWX_AGENT_ALLOW_PAID_MODELS"] = val
                self.assertTrue(mod._paid_kill_switched(), val)
                row = mod.call(self._ctx())[0]
                self.assertIn("kill-switch", row["detail"], val)
            self.assertFalse(calls, "kill-switch must refuse before HTTP")
        finally:
            mod.common.http_request = orig
            if saved is None:
                os.environ.pop("AWX_AGENT_ALLOW_PAID_MODELS", None)
            else:
                os.environ["AWX_AGENT_ALLOW_PAID_MODELS"] = saved

    # 21. ps1 guard: unset -> Assert allows paid provider; =0 -> blocked;
    #     =0 + local provider still allowed (kill switch is paid-only)
    def test_21_ps1_guard_kill_switch(self):
        if shutil.which("powershell") is None:
            self.skipTest("powershell not on PATH")

        def run_guard(env_val):
            lines = [
                "$env:AWX_AGENT_HOST='test'",
                ("$env:AWX_AGENT_ALLOW_PAID_MODELS='{0}'".format(env_val)
                 if env_val is not None else
                 "Remove-Item Env:AWX_AGENT_ALLOW_PAID_MODELS -EA SilentlyContinue"),
                ". '{0}'".format(str(self.GUARD).replace("'", "''")),
                ("Assert-AgentSpendAllow -Purpose t -Provider openai "
                 "-Model gpt-5.6-mini-x -Caller t"),
                ("Assert-AgentSpendAllow -Purpose t -Provider ollama "
                 "-Model qwen3 -Caller t -ProbeId local"),
            ]
            proc = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                 "-Command", "; ".join(lines)],
                capture_output=True, text=True, cwd=ROOT, timeout=120)
            return proc.stdout

        def verdicts(out):
            return [l.strip() for l in out.splitlines()
                    if l.strip() in ("True", "False")]

        self.assertEqual(["True", "True"], verdicts(run_guard(None)))
        out = run_guard("0")
        self.assertIn("paid_blocked_kill_switch", out)
        self.assertEqual(["False", "True"], verdicts(out))


if __name__ == "__main__":
    unittest.main(verbosity=2)
