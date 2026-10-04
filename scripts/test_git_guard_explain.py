#!/usr/bin/env python3
"""Sandbox tests for git_guard_explain.py — temp git repos only; fake key
material is assembled at runtime and must never appear in any output."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import git_guard_fast as ggf  # noqa: E402
import git_guard_explain as gge  # noqa: E402

GIT = (os.environ.get("GIT_GUARD_GIT") or shutil.which("git")
       or r"F:\git\cmd\git.exe")
FAKE_PROVIDER = "sk-" + "Z" * 24
FAKE_ASSIGN = "Q" * 30


def git(root, *args):
    r = subprocess.run([GIT, "--no-optional-locks", *args], cwd=root,
                       capture_output=True, timeout=30)
    if r.returncode:
        raise AssertionError(f"git {args} failed: {r.stderr[:400]!r}")
    return r.stdout


def make_repo():
    d = Path(tempfile.mkdtemp(prefix="gge-test-"))
    git(d, "init", "-q")
    hooks = d / "empty-hooks"
    hooks.mkdir()
    git(d, "config", "core.hooksPath", str(hooks))
    git(d, "config", "user.email", "test@example.invalid")
    git(d, "config", "user.name", "gge-test")
    git(d, "config", "commit.gpgsign", "false")
    return d


def w(root, rel, data):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))


def fixture_repo():
    root = make_repo()
    w(root, "scripts/test_key.py", 'K = "' + FAKE_PROVIDER + '"\n')
    w(root, "config/placeholder.yml", 'key = "' + "sk-" + "changeme" + "0" * 20 + '"\n')
    w(root, "main/resources/application-secrets.yml", "placeholder: true\n")
    w(root, "binary.bin", b"A\x00B")
    w(root, "big.bin", b"a" * (2 * 1024 * 1024 + 16))
    w(root, "config/app.yml", "api_key = " + FAKE_ASSIGN + "\n")
    git(root, "add", "-A")
    return root


def explain_repo(root):
    scan = ggf.scan_staged(root)
    candidates = gge.build_candidates_staged(root)
    return gge.explain(scan, candidates, root)


class ExplainTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ["GIT_GUARD_GIT"] = GIT
        ggf._prep_git_path()

    def test_e1_mapping_and_classification(self):
        root = fixture_repo()
        try:
            report = explain_repo(root)
            cls_by_path = {f["path"]: f["class"] for f in report["findings"]}
            self.assertEqual(cls_by_path["scripts/test_key.py"], "fake")
            self.assertEqual(cls_by_path["config/placeholder.yml"], "placeholder")
            self.assertEqual(cls_by_path["main/resources/application-secrets.yml"], "path-rule")
            self.assertEqual(cls_by_path["binary.bin"], "binary")
            self.assertEqual(cls_by_path["big.bin"], "size")
            self.assertEqual(cls_by_path["config/app.yml"], "suspect")
            # line numbers present for key rules, values never
            row = next(f for f in report["findings"] if f["path"] == "config/app.yml")
            self.assertTrue(row["lines"])
            # unmapped hash is reported, not dropped
            scan = ggf.scan_staged(root)
            scan["findings"].append({"pathHash": "0" * 64, "rule": "provider-key"})
            rep2 = gge.explain(scan, gge.build_candidates_staged(root), root)
            self.assertTrue(any(f["class"] == "unmapped" for f in rep2["findings"]))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_e2_no_secret_fragment_in_output(self):
        root = fixture_repo()
        try:
            report = explain_repo(root)
            md = gge.render_md(report)
            blob = json.dumps(report, ensure_ascii=False) + md
            for frag in (FAKE_PROVIDER[:6], FAKE_PROVIDER, FAKE_ASSIGN[:6], FAKE_ASSIGN):
                self.assertNotIn(frag, blob)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_e3_suggest_allow_does_not_write_write_allow_needs_reason(self):
        root = fixture_repo()
        try:
            report = explain_repo(root)
            cands = gge.suggest_allow(report)
            paths = {c["path"] for c in cands}
            self.assertIn("scripts/test_key.py", paths)
            self.assertNotIn("config/app.yml", paths)
            # --suggest-allow via CLI writes nothing
            scan = ggf.scan_staged(root)
            sj = root / "scan.json"
            sj.write_text(json.dumps(scan), encoding="utf-8")
            af = root / "configs/git-guard-allow.json"
            r = subprocess.run(
                [sys.executable, "-B",
                 str(Path(__file__).parent / "git_guard_explain.py"),
                 str(sj), "--staged", "--root", str(root), "--suggest-allow"],
                capture_output=True, timeout=60)
            self.assertEqual(r.returncode, 0, r.stderr[:400])
            self.assertFalse(af.exists())
            r2 = subprocess.run(
                [sys.executable, "-B",
                 str(Path(__file__).parent / "git_guard_explain.py"),
                 str(sj), "--staged", "--root", str(root), "--write-allow"],
                capture_output=True, timeout=60)
            self.assertEqual(r2.returncode, 2)
            self.assertFalse(af.exists())
        finally:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
