#!/usr/bin/env python3
"""Synthetic tests for settings_defaults_static_check.py - a small fake
tree under tempdir stands in for the live checkout; git is never run."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "settings_defaults_static_check.py"
spec = importlib.util.spec_from_file_location("sd_static_check", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def write(root: Path, rel: str, text: str):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


class StaticCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    # ------------------------------------------------------------- S1
    def test_s1_clean_tree_passes(self):
        write(self.root, "main/resources/application-llm.yaml",
              "chat:\n  defaults:\n    temperature: 0.2\n")
        write(self.root,
              "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
              "public class M { }\n")
        self.assertEqual("PASS", mod.s1(str(self.root))["status"])

    def test_s1_literal_in_merger_fails(self):
        write(self.root,
              "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
              "double temperature = 0.3;\n")
        write(self.root,
              "main/resources/static/js/model-strategy.js",
              'const fallback = "qwen3.5";\n')
        out = mod.s1(str(self.root))
        self.assertEqual("FAIL", out["status"])
        kinds = {h["pattern"] for h in out["hits"]}
        self.assertIn("temp0.2/0.3", kinds)
        self.assertIn("localModelDefault", kinds)

    # ------------------------------------------------------------- S2
    def test_s2_defaults_removed_and_explicit_kept(self):
        write(self.root,
              "main/java/com/example/lms/dto/ChatRequestDto.java",
              "class D { Double topP; boolean isSearchModeExplicit; }\n")
        self.assertEqual("PASS", mod.s2(str(self.root))["status"])

    def test_s2_predefault_fails(self):
        write(self.root,
              "main/java/com/example/lms/dto/ChatRequestDto.java",
              "class D { Double topP = 1.0; boolean isSearchModeExplicit; }\n")
        self.assertEqual("FAIL", mod.s2(str(self.root))["status"])

    def test_s2_missing_file_pending(self):
        self.assertEqual("PENDING", mod.s2(str(self.root))["status"])

    # ------------------------------------------------------------- S3
    def test_s3_save_call_fails(self):
        write(self.root,
              "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
              "repo.save(entity);\n")
        self.assertEqual("FAIL", mod.s3(str(self.root))["status"])

    def test_s3_no_save_passes(self):
        write(self.root,
              "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
              "merge(a, b); // save is documented but never called\n")
        self.assertEqual("PASS", mod.s3(str(self.root))["status"])

    # ------------------------------------------------------------- S4
    def test_s4_absent_endpoint_pending(self):
        self.assertEqual("PENDING", mod.s4(str(self.root))["status"])

    def test_s4_principal_derived_passes(self):
        write(self.root, "main/java/com/example/lms/api/XController.java",
              '@PatchMapping("/api/settings/preferences")\n'
              "public R patch(Principal p, @RequestBody Body b){}\n")
        self.assertEqual("PASS", mod.s4(str(self.root))["status"])

    def test_s4_body_owner_fails(self):
        write(self.root, "main/java/com/example/lms/api/XController.java",
              '@PatchMapping("/api/settings/preferences")\n'
              "public R patch(@RequestBody OwnerBody owner){}\n")
        self.assertEqual("FAIL", mod.s4(str(self.root))["status"])

    # ------------------------------------------------------------- S5/S6
    def test_s5_new_ddl_fails_against_baseline(self):
        baseline = {"ddlFiles": {}, "ddlAutoLines": []}
        write(self.root, "main/resources/db/migration/V2__x.sql", "x;\n")
        out = mod.s5(str(self.root), baseline)
        self.assertEqual("FAIL", out["status"])
        self.assertIn("main/resources/db/migration/V2__x.sql", out["added"])

    def test_s5_unchanged_passes(self):
        sha = "A" * 64
        baseline = {"ddlFiles": {"main/resources/db/migration/V1.sql": sha},
                    "ddlAutoLines": []}
        write(self.root, "main/resources/db/migration/V1.sql", "")
        # different content -> changed
        out = mod.s5(str(self.root), baseline)
        self.assertEqual("FAIL", out["status"])
        self.assertEqual(["main/resources/db/migration/V1.sql"], out["changed"])

    def test_s6_chatjs_hash_compare(self):
        import hashlib
        body = b"chat js bytes"
        write(self.root, mod.CHAT_JS, body.decode("latin1"))
        # latin1 round-trip keeps bytes equal only for ascii; use binary write
        (self.root / mod.CHAT_JS).write_bytes(body)
        expected = hashlib.sha256(body).hexdigest().upper()
        base = {"chatJs": {"sha256": expected}}
        self.assertEqual("PASS", mod.s6(str(self.root), base)["status"])
        base2 = {"chatJs": {"sha256": "0" * 64}}
        self.assertEqual("FAIL", mod.s6(str(self.root), base2)["status"])

    # ------------------------------------------------------------- S9/S10
    def test_s9_single_derivation_passes(self):
        write(self.root, "main/resources/static/js/model-strategy.js",
              "const useWebSearch = searchMode !== 'OFF';\n")
        out = mod.s9(str(self.root))
        self.assertEqual("PASS", out["status"])

    def test_s9_two_sites_fail(self):
        write(self.root, "main/resources/static/js/model-strategy.js",
              "const useWebSearch = searchMode !== 'OFF';\n")
        write(self.root, "main/resources/static/js/settings-page.js",
              "useWebSearch = (searchMode !== 'OFF');\n")
        self.assertEqual("FAIL", mod.s9(str(self.root))["status"])

    def test_s10_explicit_false_passes(self):
        write(self.root, "scripts/start_rag_stack.ps1",
              "$env:AWX_OPTIONAL_HTTPS_ENABLED = 'false'\n")
        self.assertEqual("PASS", mod.s10(str(self.root))["status"])

    def test_s10_read_order_only_fails(self):
        write(self.root, "scripts/start_rag_stack.ps1",
              '$x = $env:AWX_OPTIONAL_HTTPS_ENABLED; '
              '$y = [Environment]::GetEnvironmentVariable('
              "'AWX_OPTIONAL_HTTPS_ENABLED','User')\n")
        self.assertEqual("FAIL", mod.s10(str(self.root))["status"])

    # ------------------------------------------------------------- S11
    def test_s11_foreign_change_marked(self):
        baseline = {"gitStatus": {"scripts/db_agent.py": "M"}}
        # live git unavailable in test env: stub _git_status
        orig = mod._git_status
        mod._git_status = lambda root: {"scripts/db_agent.py": "M",
                                        "docs/random.txt": "??"}
        try:
            out = mod.s11(str(self.root), baseline)
        finally:
            mod._git_status = orig
        self.assertEqual("FOREIGN", out["status"])
        self.assertEqual("docs/random.txt", out["foreign"][0]["path"])

    def test_s11_allowed_change_not_foreign(self):
        baseline = {"gitStatus": {}}
        orig = mod._git_status
        mod._git_status = lambda root: {
            "main/java/com/example/lms/dto/ChatRequestDto.java": "M"}
        try:
            out = mod.s11(str(self.root), baseline)
        finally:
            mod._git_status = orig
        self.assertEqual("PASS", out["status"])
        self.assertEqual(1, len(out["allowedChanges"]))


if __name__ == "__main__":
    unittest.main()
