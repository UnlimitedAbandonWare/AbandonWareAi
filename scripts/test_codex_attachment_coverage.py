"""Tests for scripts/codex_attachment_coverage.py — synthetic fixtures only.

No real session rollouts are copied in. Secret-shaped strings are composed at
runtime (joined fragments) so commit hooks do not see literal secrets.
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_attachment_coverage as cov  # noqa: E402

FAKE_KEY = "sk" + "-" + "synthetic" + "0" * 12
FAKE_BEARER = "Bea" + "rer " + "zz" * 16


def write_rollout(root: Path, records: list[dict]) -> Path:
    p = root / "rollout-synthetic.jsonl"
    with p.open("w", encoding="utf-8") as fh:
        for rec in records:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return p


def user_msg(text: str) -> dict:
    return {"type": "response_item",
            "payload": {"type": "message", "role": "user",
                        "content": [{"type": "input_text", "text": text}]}}


def tool_call(text: str, name: str = "exec") -> dict:
    return {"type": "response_item",
            "payload": {"type": "custom_tool_call", "name": name,
                        "input": text}}


def event(kind: str) -> dict:
    return {"type": "event_msg", "payload": {"type": kind}}


def run_main(argv: list[str]) -> tuple[int, str]:
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = cov.main(argv)
    return code, buf.getvalue()


class ParseTest(unittest.TestCase):
    def test_names_with_parens_spaces_korean_and_separators(self):
        text = "\n".join([
            "## EVIDENCE (5).md: C:/Users/x/Downloads/EVIDENCE (5).md",
            "## 첨부 계획.txt: C:\\Users\\x\\Downloads\\첨부 계획.txt",
            "## a\\b.json: C:/Users/x/Downloads/a\\b.json",
            "## Heading without colon-path",
            "## word: this is prose not a path",
            "## EVIDENCE (5).md: C:/dup/EVIDENCE (5).md",  # dedup by name
            "plain line",
        ])
        pairs = cov.parse_attachment_lines(text)
        self.assertEqual([n for n, _ in pairs],
                         ["EVIDENCE (5).md", "첨부 계획.txt", "a\\b.json"])
        self.assertEqual(pairs[0][1], "C:/Users/x/Downloads/EVIDENCE (5).md")


class ProbeTest(unittest.TestCase):
    def test_missing_file(self):
        e = cov.probe_attachment("gone.txt", "Z:/no/such/gone.txt")
        self.assertFalse(e["exists"])
        self.assertNotIn("sha12", e)

    def test_md_headings_json_keys_zip_listing(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            md = root / "doc.md"
            md.write_text("# Title A\nbody\n## H2\n# " + FAKE_KEY + "\n",
                          encoding="utf-8")
            e = cov.probe_attachment("doc.md", str(md))
            self.assertEqual(e["kind"], "md")
            self.assertEqual(e["exists"], True)
            self.assertIn("Title A", e["detail"]["headings"])
            self.assertNotIn(FAKE_KEY, "".join(e["detail"]["headings"]))

            js = root / "checks.json"
            js.write_text(json.dumps({"alpha": 1, "beta": [1, 2],
                                      FAKE_BEARER: "x"}), encoding="utf-8")
            e = cov.probe_attachment("checks.json", str(js))
            self.assertIn("alpha", e["detail"]["topKeys"])
            self.assertNotIn(FAKE_BEARER, "".join(e["detail"]["topKeys"]))

            zp = root / "pack.zip"
            with zipfile.ZipFile(zp, "w") as zf:
                zf.writestr("README.md", "hi")
                zf.writestr("dir/file.txt", "x" * 100)
            e = cov.probe_attachment("pack.zip", str(zp))
            self.assertEqual(e["detail"]["entryCount"], 2)
            self.assertIn("README.md", e["detail"]["topEntryNames"])
            self.assertEqual(e["detail"]["uncompressedBytes"], 102)

    def test_manifest_writes_ledger(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            att = root / "note.txt"
            att.write_text("hello", encoding="utf-8")
            goal = root / "goal.md"
            goal.write_text("## note.txt: %s\n" % str(att).replace("\\", "/"),
                            encoding="utf-8")
            code, _ = run_main(["manifest", "--goal-text", str(goal),
                                "--out", str(root / "out"), "--json"])
            self.assertEqual(code, 0)
            doc = json.loads((root / "out" / "manifest.json")
                             .read_text(encoding="utf-8"))
            self.assertEqual(doc["count"], 1)
            self.assertTrue(doc["attachments"][0]["exists"])
            self.assertTrue(doc["attachments"][0]["sha12"])
            ledger = (root / "out" / "ATTACHMENT_LEDGER.md") \
                .read_text(encoding="utf-8")
            self.assertIn("note.txt", ledger)
            self.assertIn("| verdict |", ledger)


class CoverageTest(unittest.TestCase):
    def _rollout(self, root: Path, dls: Path) -> Path:
        names = ["full.md", "partial.md", "late.md", "never.md"]
        user = user_msg("\n".join(
            "## %s: %s" % (n, str(dls / n).replace("\\", "/"))
            for n in names))
        return write_rollout(root, [
            {"type": "session_meta", "payload": {"id": "s"}},
            user,
            tool_call('Get-Content "%s" -Raw' % str(dls / "full.md")),
            tool_call('Get-Content "%s" -TotalCount 40'
                      % str(dls / "partial.md")),
            event("task_complete"),
            tool_call('type "%s"' % str(dls / "late.md")),
        ])

    def test_open_partial_never_before_after(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dls = root / "Downloads"
            dls.mkdir()
            for n in ("full.md", "partial.md", "late.md", "never.md"):
                (dls / n).write_text("x", encoding="utf-8")
            rp = self._rollout(root, dls)
            code, out = run_main(["coverage", "--rollout", str(rp), "--json"])
            self.assertEqual(code, 3)
            doc = json.loads(out)
            s = doc["summary"]
            self.assertEqual(s["total"], 4)
            self.assertEqual(s["openedBeforeFirstCompletion"], 2)
            self.assertEqual(s["openedAfterFirstCompletion"], 1)
            self.assertEqual(s["neverOpened"], 1)
            by_name = {r["name"]: r for r in doc["attachments"]}
            self.assertEqual(by_name["full.md"]["status"], "OPENED")
            self.assertEqual(by_name["full.md"]["timing"], "before")
            self.assertEqual(by_name["partial.md"]["status"], "OPENED_PARTIAL")
            self.assertEqual(by_name["late.md"]["timing"], "after")
            self.assertEqual(by_name["never.md"]["status"], "NOT_OPENED")

    def test_all_opened_exit_zero(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dls = root / "d"
            dls.mkdir()
            (dls / "a.txt").write_text("x", encoding="utf-8")
            rp = write_rollout(root, [
                user_msg("## a.txt: %s" % str(dls / "a.txt").replace("\\", "/")),
                tool_call("Get-Content a.txt"),
                event("task_complete"),
            ])
            code, out = run_main(["coverage", "--rollout", str(rp), "--json"])
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(out)["summary"]["neverOpened"], 0)

    def test_name_boundary_no_substring_false_positive(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dls = root / "d"
            dls.mkdir()
            rp = write_rollout(root, [
                user_msg("## artifact_checks.json: %s\n"
                         "## artifact_checks (1).json: %s"
                         % (str(dls / "artifact_checks.json").replace("\\", "/"),
                            str(dls / "artifact_checks (1).json")
                            .replace("\\", "/"))),
                tool_call('Get-Content "%s"' % str(dls / "artifact_checks (1).json")),
                event("task_complete"),
            ])
            code, out = run_main(["coverage", "--rollout", str(rp), "--json"])
            doc = json.loads(out)
            by_name = {r["name"]: r for r in doc["attachments"]}
            self.assertEqual(by_name["artifact_checks (1).json"]["status"],
                             "OPENED")
            self.assertEqual(by_name["artifact_checks.json"]["status"],
                             "NOT_OPENED")
            self.assertEqual(code, 3)


class GateTest(unittest.TestCase):
    def test_missing_and_reason_rules(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            ledger = root / "ATTACHMENT_LEDGER.md"
            ledger.write_text(
                "# L\n\n| # | name | kind | size | sha12 | verdict | reason |\n"
                "|---|------|------|------|-------|---------|--------|\n"
                "| 1 | a.md | md | 5 | ab12 | APPLIED | in diff |\n"
                "| 2 | b.md | md | 5 | cd34 |  |  |\n"
                "| 3 | c.md | md | 5 | ef56 | OUT_OF_SCOPE->NEXT |  |\n",
                encoding="utf-8")
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps(
                {"attachments": [{"name": n} for n in ("a.md", "b.md", "c.md")]}),
                encoding="utf-8")
            code, out = run_main(["gate", "--ledger", str(ledger),
                                  "--manifest", str(manifest)])
            self.assertEqual(code, 2)
            self.assertIn("b.md", out)
            self.assertIn("c.md", out)  # OUT_OF_SCOPE without reason

            ledger.write_text(
                "# L\n\n| # | name | kind | size | sha12 | verdict | reason |\n"
                "|---|------|------|------|-------|---------|--------|\n"
                "| 1 | a.md | md | 5 | ab12 | APPLIED | diff |\n"
                "| 2 | b.md | md | 5 | cd34 | REFERENCE | read-only |\n"
                "| 3 | c.md | md | 5 | ef56 | OUT_OF_SCOPE->NEXT | next brief |\n",
                encoding="utf-8")
            code, out = run_main(["gate", "--ledger", str(ledger),
                                  "--manifest", str(manifest)])
            self.assertEqual(code, 0)
            self.assertIn("3/3", out)


if __name__ == "__main__":
    unittest.main()
