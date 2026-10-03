#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for p6r2dbg_owner_route_matrix.py — run:
    python -B scripts/test_p6r2dbg_owner_route_matrix.py
Exit 0 on success. Pure stdlib; offline; writes only to a temp dir."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import p6r2dbg_owner_route_matrix as m


ROOT = Path(__file__).resolve().parent.parent


class AntMatcherTest(unittest.TestCase):
    def test_exact_and_glob(self):
        self.assertTrue(m.ant_match("/api/chat/**", "/api/chat/state"))
        self.assertTrue(m.ant_match("/api/chat/**", "/api/chat/a/b/c"))
        self.assertTrue(m.ant_match("/**", "/anything/at/all"))
        self.assertTrue(m.ant_match("/api/chat/state", "/api/chat/state"))
        self.assertFalse(m.ant_match("/api/chat/**", "/api/rag/query"))
        self.assertFalse(m.ant_match("/api/chat/state", "/api/chat/statex"))
        self.assertTrue(m.ant_match("/api/chat/sessions/{id}",
                                    "/api/chat/sessions/42"))


class VerdictTest(unittest.TestCase):
    def test_kept404(self):
        self.assertEqual(m.verdict_for("404", "404"), "MATCH")
        self.assertEqual(m.verdict_for("404", "ALLOW"), "MISMATCH")
        self.assertEqual(m.verdict_for("404", "DENY"), "MATCH")

    def test_deny_satisfied_by_any_denial(self):
        self.assertEqual(m.verdict_for("DENY", "404"), "MATCH")
        self.assertEqual(m.verdict_for("DENY", "403"), "MATCH")
        self.assertEqual(m.verdict_for("DENY", "ALLOW"), "MISMATCH")

    def test_allow(self):
        self.assertEqual(m.verdict_for("ALLOW", "ALLOW"), "MATCH")
        self.assertEqual(m.verdict_for("ALLOW(own-list)", "404"), "MISMATCH")
        self.assertEqual(m.verdict_for("ALLOW-if-needed", "404"), "NEEDS-TRACE")
        self.assertEqual(m.verdict_for("ALLOW-if-needed", "ALLOW"), "MATCH")


class CounterexampleTest(unittest.TestCase):
    """Injected bugs must be caught — a matrix that never flags is useless."""

    def test_open_kept404_route_flags_mismatch(self):
        def buggy_observed(method, path, client):
            if path == "/api/chat/transcribe":
                return "ALLOW"  # bug: kept-404 route became reachable
            return m.observed_static(method, path)
        rows = m.build_matrix(buggy_observed, demo_on=True)
        bad = [r for r in rows if r["route"] == "transcribe"]
        self.assertTrue(bad)
        self.assertTrue(all(r["verdict"] == "MISMATCH" for r in bad))

    def test_other_client_allowed_flags_mismatch(self):
        def buggy_observed(method, path, client):
            # bug: ownership ignored — everyone allowed
            return "ALLOW"
        rows = m.build_matrix(buggy_observed, demo_on=True)
        other = [r for r in rows if "other" in r["route"]
                 or r["route"] == "session-own" and r["client"] == "anonB"]
        self.assertTrue(other)
        self.assertTrue(any(r["verdict"] == "MISMATCH" for r in other))


class StaticExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.static = m.extract_filters_and_chains(ROOT)
        cls.static["ownerCheckCandidates"] = m.owner_check_candidates(ROOT)

    def test_interview_demo_filter_first(self):
        names = [f["filterClass"] for f in self.static["servletFilters"]]
        self.assertIn("InterviewDemoFilter", names)
        # HIGHEST_PRECEDENCE rank: it must sort no later than OwnerKeyBootstrap
        idf = next(f for f in self.static["servletFilters"]
                   if f["filterClass"] == "InterviewDemoFilter")
        self.assertEqual("demo.interview.enabled=true",
                         idf["conditionalOnProperty"])

    def test_chat_open_chain_present(self):
        beans = [c["bean"] for c in self.static["securityFilterChains"]]
        self.assertIn("chatOpenChain", beans)
        ch = next(c for c in self.static["securityFilterChains"]
                  if c["bean"] == "chatOpenChain")
        self.assertTrue(any("chat" in str(r["matcher"])
                            for r in ch["authorizeRules"]))

    def test_owner_candidates_found(self):
        missing = [c["method"] for c in self.static["ownerCheckCandidates"]
                   if not c["file"]]
        self.assertEqual([], missing)


class EndToEndTest(unittest.TestCase):
    def test_cli_writes_matrix(self):
        with tempfile.TemporaryDirectory() as td:
            md = Path(td) / "m.md"
            js = Path(td) / "m.json"
            rc = m.main(["--root", str(ROOT), "--out-md", str(md),
                         "--out-json", str(js)])
            self.assertEqual(rc, 0)
            payload = json.loads(js.read_text(encoding="utf-8"))
            self.assertEqual(payload["schemaVersion"], m.SCHEMA)
            self.assertTrue(payload["matrix_on"])
            self.assertTrue(payload["matrix_off"])
            # demo=off: every watched route expected+observed ALLOW(existing)
            self.assertTrue(all(r["verdict"] == "MATCH"
                                for r in payload["matrix_off"]))
            # current state: own-run routes are 404 under demo=on -> MISMATCH
            self.assertTrue(any(r["verdict"] == "MISMATCH"
                                for r in payload["matrix_on"]))
            self.assertEqual(payload["live"]["status"], "NOT_RUN")
            text = md.read_text(encoding="utf-8")
            self.assertIn("first matching rule wins", text.lower())


if __name__ == "__main__":
    unittest.main(verbosity=1)
