#!/usr/bin/env python3
"""test_zdr_guard_vocab.py - fixture tests for `zdr_guard.py --vocab`.

jev-vocab: allow-file — this file's own docstring and fixture strings quote the
retired names as scan test data; they are not Jev reason/config usage.

Contract DEMO1-DEVIN-JEV-VOCAB-ALIGN-ASSIST-20260929 (task A): the vocab guard
flags the retired Jev reason/config names (key_invalid_or_expired, "forbidden",
demo.jev.zero-data-retention) inside Jev-scoped files only, reads the correct
vocabulary from docs/API_ROUTING_SPEC.md section 6 (parse failure = exit 2),
and honors `jev-vocab: legacy-alias` line markers during the transition.
All fixtures are synthetic tmpdir trees; the real repo is never touched.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GUARD = ROOT / "scripts" / "zdr_guard.py"

SPEC_OK = """### 6) Vercel AI Gateway — Jev evaluation (ZDR rule SSOT)

- **ZDR default OFF.** Enable only on Pro+ plans via explicit config
  (`jev.gateway.zero-data-retention`, default `false`).
- Reason vocabulary (smoke + product code): `401`→`auth_invalid`;
  `403` + plan/Pro/ZDR wording→`plan_gate`; other `403`→`permission_denied`;
  `402`→`billing-blocked`; `429`→`rate_limited`; `5xx`→`upstream_error`.
  Do not collapse 401/403 into `auth-blocked`.

---
"""

SPEC_NO_SECTION = "# spec\n\nno jev section here\n"
SPEC_NO_VOCAB_LINE = """### 6) Vercel AI Gateway — Jev evaluation

- config (`jev.gateway.zero-data-retention`, default `false`).

---
"""

JAVA_CLEAN = (
    'class JevGatewayClient{\n'
    '  String r401="auth_invalid";\n'
    '  String r403=plan?"plan_gate":"permission_denied";\n'
    '  String key=env.getProperty("jev.gateway.zero-data-retention");\n'
    '}\n'
)
JAVA_DIRTY = (
    'class JevGatewayClient{\n'
    '  String r401="key_invalid_or_expired";\n'
    '  String r403=plan?"plan_gate":"forbidden";\n'
    '  String key=env.getProperty("demo.jev.zero-data-retention");\n'
    '}\n'
)
JAVA_ALIAS_MARKED = (
    'class Holder{\n'
    '  // transition compatibility only — jev-vocab: legacy-alias\n'
    '  String old403="forbidden"; // jev-vocab: legacy-alias\n'
    '}\n'
)
YML_OLD_PATH = (
    "demo:\n"
    "  jev:\n"
    "    mode: off\n"
    "    zero-data-retention: ${DEMO_JEV_ZDR:false}\n"
)
YML_NEW_PATH = (
    "demo:\n"
    "  jev:\n"
    "    mode: off\n"
    "jev:\n"
    "  gateway:\n"
    "    zero-data-retention: ${DEMO_JEV_ZDR:false}\n"
)
NONJEV_FORBIDDEN = (
    "const s={};\n"
    'if(x)s.stopReason="forbidden";\n'
    'if(y)s.code="key_invalid_or_expired";\n'
)


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _make_root(spec=SPEC_OK, files=None):
    td = tempfile.TemporaryDirectory()
    root = Path(td.name)
    _write(root, "docs/API_ROUTING_SPEC.md", spec)
    for rel, text in (files or {}).items():
        _write(root, rel, text)
    return td, root


def _run(root: Path, *extra):
    proc = subprocess.run(
        [sys.executable, "-B", str(GUARD), "--vocab", *extra,
         "--root", str(root)],
        capture_output=True, text=True, timeout=60)
    return proc


class VocabGuard(unittest.TestCase):
    def test_clean_jev_files_pass_strict(self):
        td, root = _make_root(files={
            "main/java/JevGatewayClient.java": JAVA_CLEAN,
            "main/resources/application-meta-display.yml": YML_NEW_PATH,
        })
        self.addCleanup(td.cleanup)
        proc = _run(root, "--strict", "--json")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        payload = json.loads(proc.stdout)
        self.assertEqual(payload["vocab"]["hits"], [])
        self.assertIn("auth_invalid", payload["vocab"]["expectedReasons"])
        self.assertEqual(payload["vocab"]["configKey"],
                         "jev.gateway.zero-data-retention")

    def test_old_names_hit_report_vs_strict(self):
        td, root = _make_root(files={
            "main/java/JevGatewayClient.java": JAVA_DIRTY,
        })
        self.addCleanup(td.cleanup)
        report = _run(root, "--json")
        self.assertEqual(report.returncode, 0,
                         report.stdout + report.stderr)  # report mode
        hits = json.loads(report.stdout)["vocab"]["hits"]
        self.assertGreaterEqual(len(hits), 3)
        strict = _run(root, "--strict")
        self.assertEqual(strict.returncode, 1, strict.stdout + strict.stderr)

    def test_legacy_alias_marker_allows_line(self):
        td, root = _make_root(files={
            "main/java/JevGatewayClient.java": JAVA_CLEAN + JAVA_ALIAS_MARKED,
        })
        self.addCleanup(td.cleanup)
        proc = _run(root, "--strict")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_spec_parse_failure_is_exit_2(self):
        for spec in (SPEC_NO_SECTION, SPEC_NO_VOCAB_LINE):
            td, root = _make_root(spec=spec, files={})
            self.addCleanup(td.cleanup)
            proc = _run(root, "--strict")
            self.assertEqual(proc.returncode, 2,
                             "spec=%r out=%s err=%s" % (
                                 spec[:30], proc.stdout, proc.stderr))

    def test_non_jev_files_not_scanned(self):
        td, root = _make_root(files={
            "main/resources/static/js/chat.js": NONJEV_FORBIDDEN,
            "scripts/unrelated_tool.py": NONJEV_FORBIDDEN,
        })
        self.addCleanup(td.cleanup)
        proc = _run(root, "--strict")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_yaml_nested_old_key_path_hits(self):
        td, root = _make_root(files={
            "main/resources/application-meta-display.yml": YML_OLD_PATH,
        })
        self.addCleanup(td.cleanup)
        proc = _run(root, "--strict")
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertIn("zero-data-retention", proc.stdout)


if __name__ == "__main__":
    unittest.main()
