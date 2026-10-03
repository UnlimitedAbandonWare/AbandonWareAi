#!/usr/bin/env python3
"""Fixture-only contract tests for scripts/api_first_order_preview.py.
Zero live calls; all YAML inputs are synthetic temp files.

Run: python -B scripts/test_api_first_order_preview.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "api_first_order_preview.py"

LLM_YAML = """\
llmrouter:
  models:
    light:
      provider: local
      stage: chat
      name: "${FAKE_LIGHT_NAME:qwen3.5:9b}"
      weight: 0.45
    gemma:
      provider: local
      stage: chat
      name: "${FAKE_GEMMA_NAME:gemma4:26b}"
      weight: 0.55
    api3:
      provider: groq
      stage: chat
      name: "${FAKE_API3_NAME:openai/gpt-oss-120b}"
      weight: 0.0
      fallback-only: "${FAKE_API3_FB:true}"
      credential-env: FAKE_GROQ_KEY
    premium:
      provider: openai
      stage: chat
      name: gpt-5.5
      weight: 0.0
      fallback-only: true
      credential-env: FAKE_OPENAI_KEY
    judge:
      provider: local
      stage: judge
      name: judge-model
      weight: 0.0
"""

ROUTING_YAML = """\
version: test
policy:
  order: [free_local, low_cost, paid_quality]
"""


def run_tool(*argv, env=None):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *argv],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=env,
    )


def make_tree(base: Path):
    (base / "main/resources/configs").mkdir(parents=True)
    (base / "main/resources/application-llm.yaml").write_text(LLM_YAML, encoding="utf-8")
    (base / "main/resources/configs/api-routing.yaml").write_text(ROUTING_YAML, encoding="utf-8")


class PreviewTest(unittest.TestCase):
    def test_legacy_order_weight_desc(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            r = run_tool("--root", str(base), "--json")
            self.assertEqual(r.returncode, 0, r.stderr)
            payload = json.loads(r.stdout)
            ids = [x["id"] for x in payload["legacy"]]
            # weight order: gemma 0.55 > light 0.45; fallback-only clouds trail
            self.assertEqual(ids[:2], ["gemma", "light"])
            self.assertNotIn("judge", ids)  # stage != chat excluded
            self.assertEqual(payload["policyOrderLegacy"],
                             ["free_local", "low_cost", "paid_quality"])

    def test_api_first_clouds_then_local(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            r = run_tool("--root", str(base), "--json")
            self.assertEqual(r.returncode, 0, r.stderr)
            payload = json.loads(r.stdout)
            ids = [x["id"] for x in payload["apiFirst"]]
            self.assertEqual(ids[:2], ["api3", "premium"])
            local_rows = [x for x in payload["apiFirst"] if x["provider"] == "local"]
            self.assertTrue(local_rows)
            for row in local_rows:
                self.assertTrue(row["effectiveFallbackOnly"])
                self.assertEqual(row["effectiveWeight"], 0.0)
            self.assertIn("chatgpt-oauth", payload["oauthNote"])

    def test_env_presence_names_only(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            env = dict(os.environ)
            env["FAKE_OPENAI_KEY"] = "sk-should-never-appear"
            env.pop("FAKE_GROQ_KEY", None)
            r = run_tool("--root", str(base), "--json", env=env)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertNotIn("sk-should-never-appear", r.stdout)
            payload = json.loads(r.stdout)
            self.assertEqual(payload["envPresence"].get("FAKE_OPENAI_KEY"), "set")
            self.assertEqual(payload["envPresence"].get("FAKE_GROQ_KEY"), "unset")
            api3 = [x for x in payload["legacy"] if x["id"] == "api3"][0]
            self.assertEqual(api3["credential"], "credential_unset(FAKE_GROQ_KEY)")
            prem = [x for x in payload["legacy"] if x["id"] == "premium"][0]
            self.assertEqual(prem["credential"], "set")

    def test_role_flag_marks_absent(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            r = run_tool("--root", str(base), "--json", "--role", "MAIN_DEFAULT")
            self.assertEqual(r.returncode, 0, r.stderr)
            payload = json.loads(r.stdout)
            self.assertIn("absent", payload["roleInfo"])

    def test_defaults_resolve(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            make_tree(base)
            r = run_tool("--root", str(base), "--json")
            payload = json.loads(r.stdout)
            light = [x for x in payload["legacy"] if x["id"] == "light"][0]
            self.assertEqual(light["name"], "qwen3.5:9b")
            api3 = [x for x in payload["legacy"] if x["id"] == "api3"][0]
            self.assertTrue(api3["fallbackOnly"])

    def test_missing_yaml_exit_2(self):
        with tempfile.TemporaryDirectory() as td:
            r = run_tool("--root", td, "--json")
            self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main()
