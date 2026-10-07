#!/usr/bin/env python3
"""Tests for scripts/grokbot_bot_import.py — synthetic fixtures only."""
import contextlib
import hashlib
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest
import zipfile

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


def make_export_v2(tmp: str, profile_extra: str = "") -> str:
    """Build a minimal v2 migrate folder (MEMORY_PROFILE, no MANIFEST)."""
    src = os.path.join(tmp, "export-v2")
    profile = ("# Grok Bot 상시 규칙 — demo-1\n"
               "# 출처: profile.\n"
               "## A. 번호 규칙 (원문 그대로)\n"
               "R1 말투: 한국어.\n"
               "R2 그룹방: 3개까지.\n"
               "## B. 기타 profile 사실\n"
               "P1 한국어 선호.\n"
               "## C. 폐기된 규칙 (쓰지 말 것)\n"
               "- \"무료·로컬 우선\" → 폐기.\n"
               "## D. 새 규칙\n"
               "N1 [demo-1 도구] 규칙.\n" + profile_extra)
    log = ("# log\n## 2026-10-06\n"
           "★ 20:2x PASTE_DEVIN_x_20261006.txt (1,234 B, sha12 aaaa1111bbbb) 첫.\n"
           "- 19:0x not a star line.\n"
           "★ 방 abc123: sha 없는 둘째.\n")
    prev = ("# old log\n## 2026-10-04\n"
            "★ 12:0x PASTE_CODEX_y_20261004.txt (100B, cccc2222dddd) 셋.\n")
    shared = "# shared\nU1 [profile] one.\nU2 [log] two.\n"
    files = {
        "memory/MEMORY_PROFILE.md": profile,
        "memory/MEMORY_LOG.md": log,
        "memory/SHARED_USER_MEMORY.md": shared,
        "prev/handoff-20261004/memory/MEMORY_LOG.md": prev,
        "skills/demo-a/SKILL.md": "---\nname: demo-a\n---\n# a\n",
        "skills/demo-b/SKILL.md": "# b\n",
    }
    for rel, text in files.items():
        fp = os.path.join(src, *rel.split("/"))
        os.makedirs(os.path.dirname(fp), exist_ok=True)
        with open(fp, "w", encoding="utf-8") as fh:
            fh.write(text)
    return src


def run_plan(argv):
    """Run main() and parse the last JSON line of stdout."""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = g.main(argv)
    lines = [l for l in buf.getvalue().splitlines() if l.strip()]
    return code, json.loads(lines[-1])


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
            text = pathlib.Path(ptr_out).read_text(encoding="utf-8")
            # pointer contract is <=5 content lines; yaml frontmatter is
            # structural and not counted
            lines = [l for l in text.splitlines()
                     if l.startswith("# ") or l.startswith("- ")]
            self.assertLessEqual(len(lines), 5)
            self.assertIn("docs/GROKBOT_BOT_RULES.md", lines[0])


class ImportV2Test(unittest.TestCase):
    """T1~T5: v2 migrate bundle (MEMORY_PROFILE, no MANIFEST)."""

    def test_v2_t1_format_detect_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export_v2(tmp)
            out = os.path.join(tmp, "out")
            code, plan = run_plan(["--src", src, "--dry-run", "--out-root", out])
            self.assertEqual(code, 0)
            self.assertTrue(plan["ok"])
            self.assertEqual(plan["format"], "v2")
            self.assertEqual(plan["rules"], 4)
            self.assertEqual(plan["retired"], 1)
            self.assertEqual(plan["sharedUser"], 2)
            self.assertEqual(plan["skills"], 2)
            self.assertEqual(plan["episodes"], 3)
            # same fixture zipped with a single top folder also reads as v2
            zpath = os.path.join(tmp, "v2.zip")
            with zipfile.ZipFile(zpath, "w") as zf:
                for base, _dirs, names in os.walk(src):
                    for name in names:
                        fp = os.path.join(base, name)
                        rel = os.path.relpath(fp, src).replace(os.sep, "/")
                        zf.write(fp, "grokbot-migrate-x/" + rel)
            code, plan = run_plan(["--src", zpath, "--dry-run", "--out-root", out])
            self.assertEqual(code, 0)
            self.assertEqual(plan["format"], "v2")
            self.assertEqual(plan["episodes"], 3)

    def test_v2_t2_benign_words_not_redacted(self):
        extra = ("R3 max-output-tokens는 글자수가 아님.\n"
                 "P2 token-cap 승격은 KEEP.\n"
                 "N2 API 키 진단과 401/403/429 재시도 금지.\n")
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export_v2(tmp, profile_extra=extra)
            out = os.path.join(tmp, "out")
            code, plan = run_plan(["--src", src, "--apply", "--out-root", out])
            self.assertEqual(code, 0)
            self.assertEqual(plan["redacted"], 0)
            doc = pathlib.Path(out_paths(out)[0]).read_text(encoding="utf-8")
            for word in ("max-output-tokens", "token-cap",
                         "API 키 진단", "401/403/429"):
                self.assertIn(word, doc)
            self.assertNotIn("[REDACTED]", doc)

    def test_v2_t3_secret_shapes_still_redacted(self):
        extra = ("R9 sk-" + "a" * 25 + "\n"
                 "P9 AIza" + "b" * 35 + "\n"
                 "N9 ghp_" + "c" * 35 + "\n"
                 "R10 vck_" + "d" * 25 + "\n"
                 "P10 password: hunter2\n"
                 "N10 Bearer " + "e" * 20 + "\n")
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export_v2(tmp, profile_extra=extra)
            out = os.path.join(tmp, "out")
            code, plan = run_plan(["--src", src, "--apply", "--out-root", out])
            self.assertEqual(code, 0)
            self.assertEqual(plan["redacted"], 6)
            doc = pathlib.Path(out_paths(out)[0]).read_text(encoding="utf-8")
            self.assertNotIn("hunter2", doc)
            self.assertNotIn("a" * 25, doc)
            self.assertNotIn("b" * 35, doc)
            self.assertNotIn("c" * 35, doc)
            self.assertNotIn("d" * 25, doc)
            self.assertNotIn("e" * 20, doc)
            self.assertIn("[REDACTED]", doc)

    def test_v2_t5_double_apply_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_export_v2(tmp)
            out = os.path.join(tmp, "out")
            for _ in range(2):
                code, _plan = run_plan(["--src", src, "--apply", "--out-root", out])
                self.assertEqual(code, 0)
            ep_out = out_paths(out)[1]
            with open(ep_out, encoding="utf-8") as fh:
                self.assertEqual(len(fh.read().splitlines()), 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
