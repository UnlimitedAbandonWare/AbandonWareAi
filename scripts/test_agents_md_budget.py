#!/usr/bin/env python3
"""Fixture tests for scripts/agents_md_budget.py (stdlib unittest).

Run: python -B scripts/test_agents_md_budget.py
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("agents_md_budget", ROOT / "scripts" / "agents_md_budget.py")
amb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(amb)


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


FIXTURE = """# Fixture Rules
<!-- BEGIN DEMO1-PROJECT-ROOT -->
## Project Root
- keep inline always.
<!-- END DEMO1-PROJECT-ROOT -->
<!-- BEGIN DEMO1-PRIMARY-SURFACE -->
## Primary surface
- keep inline always.
<!-- END DEMO1-PRIMARY-SURFACE -->
<!-- BEGIN DEMO1-CORE-AUTO -->
## Core auto rules
- AUTO default; ASK_ONCE irreversible only.
<!-- END DEMO1-CORE-AUTO -->
<!-- BEGIN DEMO1-GIT-REMOTE-SOLE -->
## Git remote (sole valid)
- one remote only.
<!-- END DEMO1-GIT-REMOTE-SOLE -->
## Desktop / Mac Mini / Notebook Workspaces
- workspace rules body.
## Runtime Boundary And Active Runtime Map
- runtime boundary body.
## Safe Patch Rules
- `dev.langchain4j` stays `1.0.1` on the active sourceSet.
## Prompt, Search, And Provider Hygiene
- prompt boundary rules.
## Skill And Prompt Routing
<!-- BEGIN DEMO1-VIBE-SKILL-ROUTER -->
- resolve one primary skill first.
- second bullet of router detail.
<!-- END DEMO1-VIBE-SKILL-ROUTER -->
## PatchDrop Bundle Rules
- patchdrop rules body.
## Redaction
- redaction rules body.
## Evidence And Verification
### Automatic completion and cleanup
- cleanup rules body inside deeper heading block marker below.
<!-- BEGIN DEMO1-COMPLETION-CLEANUP -->
### Automatic completion and cleanup
- completion cleanup detail one.
- completion cleanup detail two.
<!-- END DEMO1-COMPLETION-CLEANUP -->
- evidence tail bullet with blocker lane-local wording.
## Codex Computer And Environment Autostart
- computer autostart body to be moved.
<!-- DEMO1-TAIL-BLOCK:BEGIN -->
- colon style markers work too.
<!-- DEMO1-TAIL-BLOCK:END -->
"""


def write_fixture(tmp: Path, text: str = FIXTURE) -> Path:
    p = tmp / "AGENTS.md"
    p.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
    return p


class AgentsMdBudgetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="amb-fixture-")).resolve()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def plan(self, src: Path) -> tuple[dict, Path]:
        plan_path = self.tmp / "plan.json"
        return amb.run_plan(src, plan_path), plan_path

    def test_parse_blocks_counts_and_pairs(self):
        src = write_fixture(self.tmp)
        lines = amb.load_lines(src)
        blocks, errors = amb.parse_blocks(lines)
        self.assertEqual([], errors)
        ids = [b["id"] for b in blocks]
        self.assertEqual(7, len(blocks))
        self.assertIn("DEMO1-TAIL-BLOCK", ids)

    def test_marker_mismatch_detected(self):
        src = write_fixture(self.tmp, FIXTURE.replace("<!-- END DEMO1-GIT-REMOTE-SOLE -->", "", 1))
        _, errors = amb.parse_blocks(amb.load_lines(src))
        self.assertTrue(any("no END" in e or "mismatch" in e for e in errors), errors)

    def test_split_apply_preserves_content_and_anchors(self):
        src = write_fixture(self.tmp)
        plan, plan_path = self.plan(src)
        moves = [e for e in plan["blocks"] if e["decision"] == "MOVE_TO_DOC"]
        self.assertEqual(4, len(moves), [e["id"] for e in moves])
        docs = self.tmp / "rules"
        ledger = self.tmp / "ledger"
        result = amb.run_apply(src, plan_path, docs, ledger)
        self.assertTrue(result["allShaMatch"])
        text = src.read_text(encoding="utf-8")
        for heading in amb.REQUIRED_HEADINGS:
            self.assertIn(f"## {heading}", text)
        # stub keeps BEGIN/END, title, pointer
        self.assertIn("<!-- BEGIN DEMO1-VIBE-SKILL-ROUTER -->", text)
        self.assertIn("docs/agents-rules/DEMO1-VIBE-SKILL-ROUTER.md", text)
        self.assertIn("<!-- DEMO1-TAIL-BLOCK:BEGIN -->", text)
        # moved doc preserves original body bytes verbatim after header line
        for m in result["moved"]:
            body = (ROOT / m["doc"] if not Path(m["doc"]).is_absolute() else Path(m["doc"]))
            doc = self.tmp / "rules" / f"{m['id']}.md"
            self.assertTrue(doc.is_file(), m["id"])
            doc_body = doc.read_bytes().split(b"\n", 1)[1]
            self.assertEqual(m["sourceSha256"], hashlib.sha256(doc_body).hexdigest())
        # ### depth preserved in stub so Evidence section is not truncated early
        self.assertIn("### Automatic completion and cleanup", text)
        record = json.loads((ledger / "moved-blocks.json").read_text(encoding="utf-8"))
        self.assertTrue(all(m["shaMatch"] for m in record["moved"]))

    def test_section_move_keeps_heading_and_pointer(self):
        src = write_fixture(self.tmp)
        plan, plan_path = self.plan(src)
        amb.run_apply(src, plan_path, self.tmp / "rules", self.tmp / "ledger")
        text = src.read_text(encoding="utf-8")
        self.assertIn("## Codex Computer And Environment Autostart", text)
        self.assertIn("docs/agents-rules/SECTION-codex-computer-and-environment-autostart.md", text)
        self.assertNotIn("computer autostart body to be moved.", text)

    def test_check_pass_then_fail_on_oversize(self):
        src = write_fixture(self.tmp)
        code, failures = amb.check_file(src)
        self.assertEqual(0, code, failures)
        big = self.tmp / "AGENTS_big.md"
        big.write_bytes(src.read_bytes() + b"x" * (amb.HARD_LIMIT + 10))
        code, failures = amb.check_file(big)
        self.assertEqual(1, code)
        self.assertTrue(any(f.startswith("SIZE_OVER") for f in failures), failures)

    def test_check_missing_heading_fails(self):
        src = write_fixture(self.tmp, FIXTURE.replace("## Redaction\n", "", 1))
        code, failures = amb.check_file(src)
        self.assertEqual(1, code)
        self.assertTrue(any("HEADING_MISSING Redaction" in f for f in failures), failures)

    def test_required_small_stub_fits_but_byte_ceiling_still_blocks(self):
        src = write_fixture(self.tmp)
        original = src.read_bytes()
        src.write_bytes(original + b" " * (30_213 - len(original)))
        code, failures = amb.check_file(src)
        self.assertEqual(0, code, failures)
        src.write_bytes(original + b" " * (30_301 - len(original)))
        code, failures = amb.check_file(src)
        self.assertEqual(1, code)
        self.assertTrue(any(f.startswith("SIZE_OVER") for f in failures), failures)
        self.assertEqual(24_000, amb.CRITICAL_LIMIT)
        self.assertLess(amb.HARD_LIMIT, 32_768)

    def test_critical_after_limit_fails(self):
        filler = "<!-- BEGIN PAD -->\n" + ("pad\n" * 9000) + "<!-- END PAD -->\n"
        src = write_fixture(self.tmp, "# T\n" + filler + FIXTURE.split("\n", 1)[1])
        code, failures = amb.check_file(src)
        self.assertEqual(1, code)
        self.assertTrue(any(f.startswith("CRITICAL_BEYOND_24K") for f in failures), failures)

    def test_apply_refuses_changed_source(self):
        src = write_fixture(self.tmp)
        plan, plan_path = self.plan(src)
        src.write_bytes(src.read_bytes() + b"\nforeign\n")
        with self.assertRaises(SystemExit):
            amb.run_apply(src, plan_path, self.tmp / "rules", self.tmp / "ledger")

    def test_restore_roundtrip(self):
        src = write_fixture(self.tmp)
        backup = self.tmp / "backup.md"
        shutil.copy(src, backup)
        before = sha(src)
        _, plan_path = self.plan(src)
        amb.run_apply(src, plan_path, self.tmp / "rules", self.tmp / "ledger")
        self.assertNotEqual(before, sha(src))
        self.assertEqual(0, amb.run_restore(src, backup))
        self.assertEqual(before, sha(src))

    def test_report_budget_fields(self):
        src = write_fixture(self.tmp)
        rep = amb.build_report(src)
        self.assertEqual(3, len(rep["budgets"]))
        self.assertTrue(all(b["fits"] for b in rep["budgets"]))
        self.assertEqual([], rep["missingRequiredHeadings"])
        self.assertEqual([], rep["markerErrors"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
