"""Fixture tests for scripts/agy_rules_overflow_index.py."""
import os
import tempfile
import unittest

from scripts import agy_rules_overflow_index as O

DOC = (
    b"# Title\n\n## Alpha\nalpha body\n\n## Beta\nbeta says see docs/agents-rules/DEMO1-BETA.md ok\n\n"
    b"## Gamma straddler\nfirst body line here\n" + b"x" * 200 + b"\n\n## Delta\n- pointer doc: `docs/agents-rules/DEMO1-DELTA.md`.\n\n"
    b"## Tail\nplain summary line for tail\n"
)


def make_doc():
    return DOC


class BuildTest(unittest.TestCase):
    def test_sections_at_or_straddling_limit(self):
        data = make_doc()
        limit = data.find(b"## Gamma straddler") + 10
        sections = O.find_overflow_sections(data, limit)
        titles = [t for _s, _l, t, _b in sections]
        self.assertEqual(titles[0], "## Gamma straddler")
        self.assertEqual(len(sections), 3)
        self.assertNotIn("## Alpha", titles)
        self.assertNotIn("## Beta", titles)

    def test_entries_link_and_summary(self):
        data = make_doc()
        limit = data.find(b"## Gamma straddler") + 10
        text, sections = O.build(data, limit)
        self.assertIn("docs/agents-rules/DEMO1-DELTA.md", text)
        self.assertIn("first body line here", text)
        self.assertIn("plain summary line for tail", text)
        self.assertTrue(text.startswith("---\ntrigger: always_on\n---\n"))
        self.assertNotIn("beta body", text)
        self.assertNotIn("alpha body", text)
        self.assertIn("L", text)

    def test_under_max_bytes(self):
        data = make_doc()
        text, _ = O.build(data, 1)
        self.assertLessEqual(len(text.encode("utf-8")), O.MAX_BYTES)

    def test_check_exit_codes(self):
        with tempfile.TemporaryDirectory() as td:
            agents = os.path.join(td, "AGENTS.md")
            out = os.path.join(td, "idx.md")
            with open(agents, "wb") as fh:
                fh.write(make_doc())
            limit = make_doc().find(b"## Gamma straddler") + 10
            missing = O.main(["--file", agents, "--out", out, "--limit", str(limit), "--check"])
            self.assertEqual(missing, 3)
            wrote = O.main(["--file", agents, "--out", out, "--limit", str(limit), "--quiet"])
            self.assertEqual(wrote, 0)
            fresh = O.main(["--file", agents, "--out", out, "--limit", str(limit), "--check"])
            self.assertEqual(fresh, 0)
            with open(agents, "ab") as fh:
                fh.write(b"\n## Extra\nmore\n")
            stale = O.main(["--file", agents, "--out", out, "--limit", str(limit), "--check"])
            self.assertEqual(stale, 3)


if __name__ == "__main__":
    unittest.main()
