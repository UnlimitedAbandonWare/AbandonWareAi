"""Synthetic-config/env tests for glm_route_preflight. No real secrets used."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import glm_route_preflight as preflight  # noqa: E402


def write_config(tmpdir, body):
    path = Path(tmpdir) / "config.toml"
    path.write_text(body, encoding="utf-8")
    return path


GLM_BLOCK = """
[mcp_servers."glm_agent"]
"enabled" = true
"enabled_tools" = ["glm_delegate_task", "glm_agent_status"]
"env_vars" = ["AI_GATEWAY_API_KEY", "AGENT_SUBAGENT_GLM_ENABLED", "GLM_EXTERNAL_READY"]
"command" = "python"
"args" = ["-B", "run.py"]

[model_providers.vercel]
name = "Vercel AI Gateway"
base_url = "https://ai-gateway.vercel.sh/codex/v1"
env_key = "AI_GATEWAY_API_KEY"
wire_api = "responses"
"""

FULL_ENV = {
    "AI_GATEWAY_API_KEY": "x",
    "AGENT_SUBAGENT_GLM_ENABLED": "true",
    "GLM_EXTERNAL_READY": "true",
}
KEY_ONLY_ENV = {"AI_GATEWAY_API_KEY": "x"}


class InspectTests(unittest.TestCase):
    def test_glm_block_parsed(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = preflight.load_codex_config(write_config(tmp, 'model = "gpt-x"\n' + GLM_BLOCK))
            facts = preflight.inspect(config, FULL_ENV)
            self.assertTrue(facts["glmAgentPresent"])
            self.assertTrue(facts["glmAgentEnabled"])
            self.assertEqual(facts["glmAgentEnabledTools"],
                             ["glm_delegate_task", "glm_agent_status"])
            self.assertTrue(facts["vercelProviderPresent"])
            self.assertTrue(facts["nativeUnsupportedChatgptLogin"])

    def test_secret_values_never_in_facts(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = preflight.load_codex_config(write_config(
                tmp, 'model = "gpt-x"\n' + GLM_BLOCK))
            facts = preflight.inspect(config, FULL_ENV)
            blob = repr(facts)
            self.assertNotIn("ai-gateway.vercel.sh", blob)
            self.assertNotIn("env_key", blob)

    def test_unreadable_config(self):
        facts = preflight.inspect(None, FULL_ENV)
        self.assertFalse(facts["configReadable"])
        self.assertFalse(facts["glmAgentPresent"])


class ClassifyTests(unittest.TestCase):
    def facts(self, **overrides):
        base = {
            "keyPresent": True,
            "flags": {"AGENT_SUBAGENT_GLM_ENABLED": True, "GLM_EXTERNAL_READY": True},
            "configReadable": True,
            "topLevelModelProviderPresent": False,
            "glmAgentPresent": True,
            "glmAgentEnabled": True,
            "glmAgentEnabledTools": ["glm_agent_status"],
            "vercelProviderPresent": True,
            "nativeUnsupportedChatgptLogin": True,
        }
        base.update(overrides)
        return base

    def test_mcp_ready(self):
        self.assertEqual(preflight.classify(self.facts()), "MCP_READY")
        self.assertEqual(preflight.ROUTES["MCP_READY"], 0)

    def test_key_missing_beats_everything(self):
        self.assertEqual(preflight.classify(self.facts(keyPresent=False)), "KEY_MISSING")

    def test_disabled_flags(self):
        flags = {"AGENT_SUBAGENT_GLM_ENABLED": False, "GLM_EXTERNAL_READY": False}
        self.assertEqual(preflight.classify(self.facts(flags=flags)), "MCP_DISABLED_FLAGS")

    def test_native_unsupported_when_no_mcp_and_chatgpt_login(self):
        self.assertEqual(preflight.classify(self.facts(glmAgentPresent=False)),
                         "NATIVE_UNSUPPORTED_CHATGPT_LOGIN")

    def test_mcp_not_configured_under_api_key_login(self):
        self.assertEqual(preflight.classify(self.facts(
            glmAgentPresent=False, nativeUnsupportedChatgptLogin=False,
            topLevelModelProviderPresent=True)), "MCP_NOT_CONFIGURED")

    def test_flags_in_glm_agent_env_map_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            body = 'model = "gpt-x"\n' + GLM_BLOCK.replace(
                '"command" = "python"',
                '"env" = {"AGENT_SUBAGENT_GLM_ENABLED" = "true", "GLM_EXTERNAL_READY" = "true"}\n"command" = "python"')
            config = preflight.load_codex_config(write_config(tmp, body))
            facts = preflight.inspect(config, KEY_ONLY_ENV)
            self.assertTrue(all(facts["flags"].values()))
            self.assertEqual(len(facts["flagsFromGlmAgentEnv"]), 2)
            self.assertEqual(preflight.classify(facts), "MCP_READY")

    def test_disabled_server_is_not_configured(self):
        self.assertEqual(preflight.classify(self.facts(
            glmAgentEnabled=False, nativeUnsupportedChatgptLogin=False,
            topLevelModelProviderPresent=True)), "MCP_NOT_CONFIGURED")


class MainTests(unittest.TestCase):
    def test_main_exit_code_and_no_key_value_leak(self):
        import io
        import contextlib
        with tempfile.TemporaryDirectory() as tmp:
            cfg = write_config(tmp, 'model = "gpt-x"\n' + GLM_BLOCK)
            old = {k: os.environ.get(k) for k in
                   ("AI_GATEWAY_API_KEY", "AGENT_SUBAGENT_GLM_ENABLED", "GLM_EXTERNAL_READY")}
            try:
                os.environ["AI_GATEWAY_API_KEY"] = "SUPERSECRET-VALUE-123"
                os.environ.pop("AGENT_SUBAGENT_GLM_ENABLED", None)
                os.environ.pop("GLM_EXTERNAL_READY", None)
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    code = preflight.main(["--config", str(cfg)])
                self.assertEqual(code, preflight.ROUTES["MCP_DISABLED_FLAGS"])
                self.assertNotIn("SUPERSECRET-VALUE-123", out.getvalue())
                self.assertIn("route=MCP_DISABLED_FLAGS", out.getvalue())
            finally:
                for key, value in old.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value


if __name__ == "__main__":
    unittest.main()
