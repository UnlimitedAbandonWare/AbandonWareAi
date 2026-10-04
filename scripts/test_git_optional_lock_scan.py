#!/usr/bin/env python3
"""Unit tests for git_optional_lock_scan.py (fake input strings only)."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import git_optional_lock_scan as sc  # noqa: E402


def py(text: str):
    return sc.scan_python(Path("fake/x.py"), text)


def ps(text: str):
    return sc.scan_generic(Path("fake/x.ps1"), text)


def unprotected(findings):
    return [f for f in findings if f["unprotected"]]


class PyDetectionTest(unittest.TestCase):
    def test_direct_status_flagged(self):
        f = unprotected(py(
            'import subprocess\n'
            'subprocess.run(["git", "-C", str(root), "status", "--short"])\n'))
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]["line"], 2)
        self.assertEqual(f[0]["kind"], "direct")

    def test_flag_in_list_covered(self):
        f = unprotected(py(
            'subprocess.run(["git", "--no-optional-locks", "-C", r, '
            '"status", "--short"])\n'))
        self.assertEqual(f, [])

    def test_env_locks_covered(self):
        f = unprotected(py(
            'subprocess.run(["git", "-C", r, "status"], '
            'env={"GIT_OPTIONAL_LOCKS": "0"})\n'))
        self.assertEqual(f, [])

    def test_exe_var_direct(self):
        f = unprotected(py(
            'git = shutil.which("git")\n'
            'subprocess.run([git, "-C", str(root), "status", "--short"])\n'))
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]["kind"], "direct")

    def test_helper_call_flagged(self):
        src = (
            'def git(repo, args):\n'
            '    return subprocess.run(["git", "-C", str(repo), *args])\n'
            '\n'
            'proc = git(repo, ["status", "--porcelain"])\n')
        f = unprotected(py(src))
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]["kind"], "via-helper")
        self.assertIn("git()", f[0]["note"])

    def test_helper_with_flag_covered(self):
        src = (
            'def run_git(root, args):\n'
            '    return subprocess.run(["git", "--no-optional-locks", "-C", '
            'str(root), *args])\n'
            '\n'
            'proc = run_git(root, ["status", "--porcelain"])\n'
            'proc2 = run_git(root, ["diff", "--cached"])\n')
        self.assertEqual(unprotected(py(src)), [])

    def test_helper_env_covered(self):
        src = (
            'def git(repo, args):\n'
            '    env = dict(os.environ, GIT_OPTIONAL_LOCKS="0")\n'
            '    return subprocess.run(["git", "-C", str(repo), *args], '
            'env=env)\n'
            '\n'
            'proc = git(repo, ["diff", "HEAD"])\n')
        self.assertEqual(unprotected(py(src)), [])

    def test_comment_and_docstring_ignored(self):
        src = (
            '# git status --porcelain is what we scan for\n'
            '"""Module docstring mentions git status and git diff."""\n'
            'x = 1\n')
        self.assertEqual(unprotected(py(src)), [])

    def test_string_literal_not_invocation(self):
        src = (
            'p.add_argument("--no-git", help="git status --porcelain off")\n'
            'out += ["## git status --porcelain", ""]\n'
            'd = {"status": "ok"}\n')
        self.assertEqual(unprotected(py(src)), [])

    def test_injected_callable_is_indirect(self):
        src = (
            'def build_changes(git, root):\n'
            '    porcelain = git("status", "--porcelain") or ""\n'
            '    return porcelain\n')
        fs = py(src)
        self.assertEqual(unprotected(fs), [])
        self.assertEqual(fs[0]["kind"], "indirect")

    def test_non_git_args_ignored(self):
        src = 'subprocess.run(["curl", "-s", "--get", "status"], cwd=x)\n'
        self.assertEqual(unprotected(py(src)), [])

    def test_multiline_call(self):
        src = (
            'proc = subprocess.run(\n'
            '    [\n'
            '        "git",\n'
            '        "-C",\n'
            '        str(root),\n'
            '        "status",\n'
            '        "--porcelain=v1",\n'
            '    ],\n'
            '    capture_output=True,\n'
            ')\n')
        f = unprotected(py(src))
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]["line"], 1)

    def test_diff_parsing_not_invocation(self):
        src = 'for line in t.splitlines():\n    if line.startswith("diff --git "):\n        pass\n'
        self.assertEqual(unprotected(py(src)), [])


class PsDetectionTest(unittest.TestCase):
    def test_ps_direct_flagged(self):
        f = unprotected(ps('$status = git -C $Path status --short 2>$null\n'))
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]["kind"], "direct")

    def test_ps_flag_covered(self):
        f = unprotected(ps('$s = git --no-optional-locks status --short\n'))
        self.assertEqual(f, [])

    def test_ps_string_not_invocation(self):
        f = unprotected(ps("$x = 'error=git status command failed'\n"))
        self.assertEqual(f, [])

    def test_ps_comment_ignored(self):
        f = unprotected(ps('# run git status --short here\n'))
        self.assertEqual(f, [])

    def test_ps_file_env_covered(self):
        src = "$env:GIT_OPTIONAL_LOCKS = '0'\n$s = git status --short\n"
        f = unprotected(ps(src))
        self.assertEqual(f, [])

    def test_ps_diff_cached(self):
        f = unprotected(ps('    $paths = @(& git diff --cached --name-only)\n'))
        self.assertEqual(len(f), 1)


class BaselineTest(unittest.TestCase):
    def _write_tree(self, root: Path, name: str, body: str):
        p = root / "scripts" / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")

    def test_baseline_only_new_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            bad = 'subprocess.run(["git", "-C", r, "status"])\n'
            self._write_tree(root, "a.py", bad)
            rep = sc.scan(root)
            base = root / "base.json"
            base.write_text(json.dumps(rep), encoding="utf-8")
            # same findings vs baseline -> exit 0
            self.assertEqual(sc.main(["--root", str(root),
                                      "--baseline", str(base)]), 0)
            # add a NEW unprotected call elsewhere -> exit 1
            self._write_tree(root, "b.py",
                             'subprocess.run(["git", "diff", "HEAD"])\n')
            self.assertEqual(sc.main(["--root", str(root),
                                      "--baseline", str(base)]), 1)

    def test_test_files_excluded(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_tree(root, "test_x.py",
                             'subprocess.run(["git", "status"])\n')
            rep = sc.scan(root)
            self.assertEqual(rep["unprotectedCount"], 0)


if __name__ == "__main__":
    unittest.main()
