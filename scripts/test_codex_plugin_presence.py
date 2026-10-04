#!/usr/bin/env python3
"""Tests for codex_plugin_presence.py — fake fixtures only, no real ~/.codex."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_plugin_presence as cp


def _secretish() -> str:
    return "sk-" + "A" * 24  # built so no literal secret lands in this file


def make_tree(base: Path) -> Path:
    home = base / ".codex"
    (home / "plugins" / "cache" / "openai-curated-remote" / "vercel").mkdir(
        parents=True)
    (home / "plugins" / "cache" / "openai-bundled" / "computer-use").mkdir(
        parents=True)
    (home / "plugins" / "cache" / "created-by-me-remote"
     / "dev-6aa5e6bf1a0881919e7aa85c05fa85f6").mkdir(parents=True)
    cfg = (
        '[plugins."superpowers@openai-curated"]\n'
        "enabled = true\n"
        '[plugins."github@openai-curated"]\n'
        "enabled = false\n"
        '[mcp_servers."glm_agent"]\n'
        "command = \"x\"\n"
        "[mcp_servers.\"glm_agent\".env]\n"
        f"GLM_API_KEY = \"{_secretish()}\"\n"
        '[mcp_servers."awx-control-tower"]\n'
        "command = \"x\"\n"
        "[mcp_servers.\"awx-control-tower\".env]\n"
        f"AWX_TOKEN = \"{_secretish()}\"\n"
    )
    (home / "config.toml").write_text(cfg, encoding="utf-8")
    sess_dir = home / "sessions" / "2026" / "10" / "03"
    sess_dir.mkdir(parents=True)
    body = ("- `r6` = `C:/Users/x/.codex/plugins/cache/openai-curated-remote`\n"
            "- `r15` = `C:/x/plugins/cache/openai-curated-remote/vercel/0.21.4/skills`\n"
            "- app-69ea4ed2cf7c8191b742ef3622479ddd:Search: (file: r6/app-69ea4ed2cf7c8191b742ef3622479ddd/4.0.1/skills/Search/SKILL.md)\n")
    rollout = (
        json.dumps({"type": "session_meta", "payload": {
            "cwd": "C:\\AbandonWare\\demo-1\\demo-1\\src",
            "timestamp": "2026-10-03T00:00:00Z"}}) + "\n"
        + json.dumps({"type": "world_state", "payload": {"state": {
            "host_skills": {"body": body}}}}) + "\n"
        + json.dumps({"type": "event_msg", "payload": {
            "type": "item_completed", "item": {
                "server": "codex_apps", "tool": "github.get_repo",
                "appName": "GitHub"}}}) + "\n"
        + json.dumps({"type": "turn_context", "payload": {
            "disabled_plugin_ids": []}}) + "\n"
    )
    (sess_dir / "rollout-2026-10-03T00-00-00-aaaa.jsonl").write_text(
        rollout, encoding="utf-8")
    other = (
        json.dumps({"type": "session_meta", "payload": {
            "cwd": "C:\\other\\root", "timestamp": "2026-10-03T01:00:00Z"}})
        + "\n")
    (sess_dir / "rollout-2026-10-03T01-00-00-bbbb.jsonl").write_text(
        other, encoding="utf-8")
    os.utime(sess_dir / "rollout-2026-10-03T01-00-00-bbbb.jsonl")
    return home


class PresenceTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="cpp_"))
        self.home = make_tree(self.dir)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def report(self):
        sess = (self.home / "sessions" / "2026" / "10" / "03"
                / "rollout-2026-10-03T00-00-00-aaaa.jsonl")
        return cp.build_report(
            self.dir, self.home, self.home / "config.toml",
            self.dir / "no-skill.md", sess)

    def test_config_parse_names_only(self):
        plugins, mcps = cp.parse_config(self.home / "config.toml")
        self.assertTrue(plugins.get("superpowers@openai-curated"))
        self.assertFalse(plugins.get("github@openai-curated"))
        self.assertIn("glm_agent", mcps)

    def test_lane_statuses(self):
        rep = self.report()
        by = {r["lane"]: r["status"] for r in rep["lanes"]}
        self.assertEqual(by["Vercel"], "VISIBLE_IN_LAST_SESSION")
        self.assertEqual(by["Exa"], "VISIBLE_IN_LAST_SESSION")
        self.assertEqual(by["GitHub"], "VISIBLE_IN_LAST_SESSION")  # app call
        self.assertEqual(by["Superpowers"], "ENABLED")
        self.assertEqual(by["AWX Control Tower"], "ENABLED")  # local MCP lane
        self.assertEqual(by["GLM assist"], "ENABLED")  # mcp glm_agent
        self.assertEqual(by["Supabase"], "NOT_ENABLED")

    def test_latest_session_picks_demo1_cwd(self):
        found = cp.find_latest_session(
            self.home, "C:\\AbandonWare\\demo-1\\demo-1\\src")
        self.assertIsNotNone(found)
        self.assertIn("aaaa", found.name)

    def test_no_secret_values_in_output(self):
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        argv = ["--root", str(self.dir), "--codex-home", str(self.home),
                "--config", str(self.home / "config.toml"), "--json"]
        with redirect_stdout(buf):
            rc = cp.main(argv)
        self.assertEqual(rc, 0)
        self.assertNotIn(_secretish(), buf.getvalue())
        self.assertNotIn("GLM_API_KEY", buf.getvalue())
        self.assertNotIn("AWX_TOKEN", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
