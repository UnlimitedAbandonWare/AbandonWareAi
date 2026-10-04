#!/usr/bin/env python3
"""Tests for scripts/grokbot_bot_import.py — synthetic fixtures only."""
import hashlib
import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import grokbot_bot_import as g


def sha12(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


def make_export(tmp: str, rules_body: str = None, tamper: bool = False) -> str:
    """Build a minimal export folder and return its path."""
    src = os.path.join(tmp, "export")
    mem = os.path.join(src, "memory")
    os.makedirs(mem, exist_ok=True)
    rules = (rules_body if rules_body is not None
             else "# RULES\nR1 말투: 한국어.\nR13 비용: Codex 크레딧 먼저.\n")
    eps = ("# Episodes\n"
           "- 13:02 PASTE_CODEX_t_20261003.txt / 34bc4228bf85 — chat run cap.\n"
           "- 오전 PASTE_DEVIN_x_20261003.txt / 5070161ee92c — git lock.\n")
    sk = os.path.join(src, "skills", "demo-x")
    os.makedirs(sk, exist_ok=True)
    skill = "---\nname: demo-x\n---\n# x\n"
    files = {
        "memory/RULES.md": rules.encode("utf-8"),
        "memory/EPISODES_20261003.md": eps.encode("utf-8"),
        "skills/demo-x/SKILL.md": skill.encode("utf-8"),
    }
    manifest = {"files": [{"path": rel, "bytes": len(blob), "sha12": sha12(blob)}
                          for rel, blob in files.items()]}
    if tamper:
        files["memory/RULES.md"] = b"# corrupted\n"
    for rel, blob in files.items():
        fp = os.path.join(src, *rel.split("/"))
        with open(fp, "wb") as fh:
            fh.write(blob)
    with open(os.path.join(src, "MANIFEST.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh)
    return src


def out_paths(out_root: str):
    return (os.path.join(out_root, "docs", "GROKBOT_BOT_RULES.md"),
            os.path.join(out_root, "data", "agent-handoff", "grokbot",
                         "bot_episodes.jsonl"),
            os.path.join(out_root, ".agents", "rules",
                         "grokbot-bot-memory.md"))


class ImportTest(unittest.TestCase):
    def test_t1_manifest_mismatch_aborts(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export(tmp, tamper=True)
            out = os.path.join(tmp, "out")
            code = g.main(["--src", src, "--apply", "--out-root", out])
            self.assertEqual(code, 3)
            rules_out, ep_out, ptr_out = out_paths(out)
            self.assertFalse(os.path.exists(rules_out))
            self.assertFalse(os.path.exists(ep_out))
            self.assertFalse(os.path.exists(ptr_out))

    def test_t2_idempotent_episode_append(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export(tmp)
            out = os.path.join(tmp, "out")
            self.assertEqual(g.main(["--src", src, "--apply", "--out-root", out]), 0)
            ep_out = out_paths(out)[1]
            with open(ep_out, encoding="utf-8") as fh:
                first = fh.read().splitlines()
            self.assertEqual(len(first), 2)
            self.assertEqual(g.main(["--src", src, "--apply", "--out-root", out]), 0)
            with open(ep_out, encoding="utf-8") as fh:
                second = fh.read().splitlines()
            self.assertEqual(first, second)

    def test_t3_secret_line_redacted(self):
        secret_rules = "# RULES\nR1 ok.\nR99 token = hunter2abc\n"
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export(tmp, rules_body=secret_rules)
            out = os.path.join(tmp, "out")
            self.assertEqual(g.main(["--src", src, "--apply", "--out-root", out]), 0)
            rules_out = out_paths(out)[0]
            doc = pathlib.Path(rules_out).read_text(encoding="utf-8")
            self.assertIn("[REDACTED]", doc)
            self.assertNotIn("hunter2abc", doc)

    def test_t4_pointer_rule_five_lines_or_less(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export(tmp)
            out = os.path.join(tmp, "out")
            self.assertEqual(g.main(["--src", src, "--apply", "--out-root", out]), 0)
            ptr_out = out_paths(out)[2]
            lines = pathlib.Path(ptr_out).read_text(encoding="utf-8").splitlines()
            self.assertLessEqual(len(lines), 5)
            self.assertIn("docs/GROKBOT_BOT_RULES.md", lines[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
