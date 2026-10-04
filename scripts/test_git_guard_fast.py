#!/usr/bin/env python3
"""Sandbox tests for git_guard_fast.py — temp git repos only; the real repo is
never touched. Fake key material is assembled at runtime, never stored."""
from __future__ import annotations

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
import git_staged_guard as gsg  # noqa: E402

GIT = (os.environ.get("GIT_GUARD_GIT") or shutil.which("git")
       or r"F:\git\cmd\git.exe")
FAKE_PROVIDER = "sk-" + "F" * 24
FAKE_SLACK = "xox" + "b-" + "1" * 12


def git(root, *args):
    r = subprocess.run([GIT, "--no-optional-locks", *args], cwd=root,
                       capture_output=True, timeout=30)
    if r.returncode:
        raise AssertionError(f"git {args} failed: {r.stderr[:400]!r}")
    return r.stdout


def make_repo():
    d = Path(tempfile.mkdtemp(prefix="ggf-test-"))
    git(d, "init", "-q")
    hooks = d / "empty-hooks"
    hooks.mkdir()
    git(d, "config", "core.hooksPath", str(hooks))
    git(d, "config", "user.email", "test@example.invalid")
    git(d, "config", "user.name", "ggf-test")
    git(d, "config", "commit.gpgsign", "false")
    return d


def w(root, rel, data):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))


def stage_all(root):
    git(root, "add", "-A")


def findings_set(result):
    return {(f["pathHash"], f["rule"]) for f in result["findings"]}


def fixture_repo():
    root = make_repo()
    w(root, "scripts/test_fake_keys.py", 'KEY = "' + FAKE_PROVIDER + '"\n')
    w(root, "scripts/fixtures/goal_block/rollout_secret.jsonl",
      json.dumps({"t": FAKE_SLACK}) + "\n")
    w(root, "main/resources/application-secrets.yml", "placeholder: true\n")
    w(root, ".env", "OPAQUE=x\n")
    w(root, "binary.bin", b"A\x00B\x00C")
    w(root, "big.bin", b"a" * (2 * 1024 * 1024 + 16))
    for i in range(50):
        w(root, f"normal/f{i:02d}.txt", f"clean file {i}\nline two\n")
    stage_all(root)
    return root


class FastGuardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ["GIT_GUARD_GIT"] = GIT
        ggf._prep_git_path()

    def test_g1_parity_with_reference(self):
        root = fixture_repo()
        try:
            fast = ggf.scan_staged(root)
            ref = gsg.scan(root)
            self.assertEqual(findings_set(fast), findings_set(ref))
            self.assertTrue(fast["findings"])
            self.assertFalse(fast["ok"])
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_g2_cache_hit_second_run(self):
        root = fixture_repo()
        try:
            ggf.scan_staged(root)
            second = ggf.scan_staged(root)
            self.assertGreaterEqual(second["fast"]["cacheHit"], 50)
            first = ggf.scan_staged(root)
            self.assertEqual(findings_set(second), findings_set(first))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_g3_changed_oid_rescanned_and_key_caught(self):
        root = fixture_repo()
        try:
            ggf.scan_staged(root)
            w(root, "normal/f00.txt", "changed content\n")
            w(root, "normal/f01.txt", 'k = "' + FAKE_PROVIDER + '"\n')
            stage_all(root)
            res = ggf.scan_staged(root)
            self.assertGreaterEqual(res["fast"]["cacheMiss"], 2)
            bad = "normal/f01.txt"
            self.assertIn((ggf.hashlib.sha256(bad.encode()).hexdigest(), "provider-key"),
                          findings_set(res))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_g4_scanner_version_invalidates_cache(self):
        root = fixture_repo()
        try:
            ggf.scan_staged(root)
            os.environ["GIT_GUARD_FAST_SCANNER_VERSION"] = "bogus-version"
            try:
                res = ggf.scan_staged(root)
                self.assertEqual(res["fast"]["cacheHit"], 0)
            finally:
                del os.environ["GIT_GUARD_FAST_SCANNER_VERSION"]
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_g5_corrupt_cache_ignored(self):
        root = fixture_repo()
        try:
            ggf.scan_staged(root)
            cache = root / "var/git-guard-cache/clean-oids.jsonl"
            cache.write_bytes(b"\x00\xffnot-json{{{")
            res = ggf.scan_staged(root)
            ref = gsg.scan(root)
            self.assertEqual(findings_set(res), findings_set(ref))
            self.assertFalse(res["ok"])
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_g6_allow_list(self):
        root = fixture_repo()
        try:
            base = ggf.scan_staged(root)
            by_path = {}
            entries = []
            index = {}
            raw = git(root, "ls-files", "--stage", "-z")
            for row in raw.split(b"\0"):
                if not row:
                    continue
                head, p = row.split(b"\t", 1)
                index[p.decode()] = head.decode().split()[1]
            allow = {"entries": [{
                "path": "scripts/test_fake_keys.py", "rule": "provider-key",
                "oid": index["scripts/test_fake_keys.py"],
                "reason": "assembled fake key in test"}]}
            af = root / "configs/git-guard-allow.json"
            af.parent.mkdir(parents=True, exist_ok=True)
            af.write_text(json.dumps(allow), encoding="utf-8")
            res = ggf.scan_staged(root, ggf._load_allow(root))
            allowed = {(f["pathHash"], f["rule"]) for f in res["allowed"]}
            self.assertIn((ggf.hashlib.sha256(b"scripts/test_fake_keys.py").hexdigest(),
                           "provider-key"), allowed)
            self.assertNotIn((ggf.hashlib.sha256(b"scripts/test_fake_keys.py").hexdigest(),
                              "provider-key"), findings_set(res))
            # same path, changed content -> new oid -> blocked again
            w(root, "scripts/test_fake_keys.py", 'KEY = "' + FAKE_PROVIDER + 'x"\n')
            stage_all(root)
            res2 = ggf.scan_staged(root, ggf._load_allow(root))
            self.assertIn((ggf.hashlib.sha256(b"scripts/test_fake_keys.py").hexdigest(),
                           "provider-key"), findings_set(res2))
            # non-test path with allow entry still blocked
            w(root, "src/app.py", 'K = "' + FAKE_PROVIDER + '"\n')
            stage_all(root)
            allow["entries"].append({
                "path": "src/app.py", "rule": "provider-key",
                "oid": git(root, "hash-object", "src/app.py").decode().strip(),
                "reason": "should never apply"})
            af.write_text(json.dumps(allow), encoding="utf-8")
            res3 = ggf.scan_staged(root, ggf._load_allow(root))
            self.assertIn((ggf.hashlib.sha256(b"src/app.py").hexdigest(), "provider-key"),
                          findings_set(res3))
            # empty reason ignored
            allow["entries"] = [{"path": "src/app.py", "rule": "provider-key",
                                 "oid": git(root, "hash-object", "src/app.py").decode().strip(),
                                 "reason": "  "}]
            af.write_text(json.dumps(allow), encoding="utf-8")
            res4 = ggf.scan_staged(root, ggf._load_allow(root))
            self.assertIn((ggf.hashlib.sha256(b"src/app.py").hexdigest(), "provider-key"),
                          findings_set(res4))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_g7_index_change_mid_scan_fails(self):
        root = fixture_repo()
        try:
            def mutate():
                w(root, "normal/late.txt", "late staged\n")
                git(root, "add", "normal/late.txt")
            with self.assertRaises(gsg.GuardFailure):
                ggf.scan_staged(root, _before_second_snapshot=mutate)
            r = subprocess.run([sys.executable, "-B",
                                str(Path(__file__).parent / "git_guard_fast.py"),
                                "--root", str(root / "not-a-repo")],
                               capture_output=True, timeout=30)
            self.assertEqual(r.returncode, 2)
            self.assertIn(b'"ok": false', r.stdout)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_g8_constant_git_processes(self):
        root = fixture_repo()
        try:
            res = ggf.scan_staged(root)
            self.assertLessEqual(res["fast"]["gitProcesses"], 5)
            res2 = ggf.scan_staged(root)
            self.assertLessEqual(res2["fast"]["gitProcesses"], 5)
        finally:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
